package com.vicente.scheduler;

import com.vicente.entity.FileWatchLog;
import com.vicente.mapper.FileWatchLogMapper;
import com.vicente.service.FileScannerService;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author sws
 * @description  WatchService 实时监听功能，在文件系统发生变化时自动触发增量扫描，无需定时全量扫描
 * @date 2026/06/26
 */
public class DirectoryWatcher implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryWatcher.class);

    private final WatchService watchService;
    private final Map<WatchKey, Path> keyPathMap = new ConcurrentHashMap<>();
    private final Map<Path, Long> pendingEvents = new ConcurrentHashMap<>(); // 去重延迟处理
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final FileScannerService scannerService;
    private volatile boolean running = true;
    private final SqlSessionFactory sqlSessionFactory;

    public DirectoryWatcher(Path rootDir, FileScannerService scannerService,SqlSessionFactory sqlSessionFactory) throws IOException {
        this.scannerService = scannerService;
        this.sqlSessionFactory = sqlSessionFactory;
        this.watchService = FileSystems.getDefault().newWatchService();
        // 递归注册所有子目录
        registerAll(rootDir);
    }

    /**
     * 递归注册目录及其所有子目录
     */
    private void registerAll(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 注册单个目录到 WatchService
     */
    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyPathMap.put(key, dir);
        logger.debug("已监听目录: {}", dir);
    }

    /**
     * 主循环：监听事件
     */
    @Override
    public void run() {
        logger.info("目录监听服务已启动");
        while (running) {
            WatchKey key;
            try {
                key = watchService.take(); // 阻塞等待事件
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = keyPathMap.get(key);
            if (dir == null) {
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // 处理溢出事件（可能丢失了一些事件）
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    logger.warn("WatchService 溢出，部分事件可能丢失，建议全量扫描");
                    continue;
                }

                // 获取相对路径
                Path relativePath = (Path) event.context();
                Path fullPath = dir.resolve(relativePath);

                // 处理事件
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    // 如果是目录，递归注册
                    if (Files.isDirectory(fullPath)) {
                        try {
                            registerAll(fullPath);
                        } catch (IOException e) {
                            logger.error("注册新目录失败: {}", fullPath, e);
                        }
                    }
                    handleFileChange(fullPath, "CREATE");
                } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    handleFileChange(fullPath, "MODIFY");
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    handleFileDelete(fullPath);
                }
            }

            // 重置 key，以便继续接收事件
            boolean valid = key.reset();
            if (!valid) {
                keyPathMap.remove(key);
                if (keyPathMap.isEmpty()) {
                    logger.warn("所有 WatchKey 已失效，监听停止");
                    break;
                }
            }
        }
        logger.info("目录监听服务已停止");
    }

    /**
     * 处理文件变化（创建或修改）
     * 使用延迟合并去重，避免短时间内重复触发
     */
    private void handleFileChange(Path file, String eventType) {
        // 只处理常规文件（忽略目录）
        if (Files.isDirectory(file)) {
            return;
        }

        // 1. 记录日志
        logEvent(file.toString(), eventType);

        // 延迟处理，去重
        pendingEvents.put(file, System.currentTimeMillis());
        scheduler.schedule(() -> {
            Long lastTime = pendingEvents.remove(file);
            if (lastTime == null) {
                return;
            }
            // 检查文件是否还存在
            if (!Files.exists(file)) {
                return;
            }
            // 提交到扫描服务处理
            logger.info("检测到文件变化: {} ({}), 提交提取任务", file, eventType);
            scannerService.submitFileForExtraction(file);
            // 注意：这里可以标记日志为已处理，但提取过程可能失败，可考虑在提取成功后回调标记，或简单标记为已提交
            // 简化方案：提交后标记为已处理（假设提交即成功）
            markLogProcessed(file.toString(), eventType);
        }, 500, TimeUnit.MILLISECONDS); // 500ms 延迟合并
    }

    /**
     * 处理文件删除
     */
    private void handleFileDelete(Path file) {
        logger.info("检测到文件删除: {}", file);
        logEvent(file.toString(), "DELETE");
        // 调用 scannerService 的删除方法（标记删除或物理删除）
        scannerService.deleteFileRecord(file);
        // 删除事件可直接标记已处理
        markLogProcessed(file.toString(), "DELETE");
    }

    /**
     * 插入日志记录
     */
    private void logEvent(String filePath, String eventType) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            FileWatchLogMapper mapper = session.getMapper(FileWatchLogMapper.class);
            FileWatchLog log = new FileWatchLog();
            log.setFilePath(filePath);
            log.setEventType(eventType);
            log.setEventTime(LocalDateTime.now());
            mapper.insertLog(log);
        } catch (Exception e) {
            logger.error("记录监听日志失败: {}", filePath, e);
        }
    }

    /**
     * 标记日志为已处理
     */
    private void markLogProcessed(String filePath, String eventType) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            FileWatchLogMapper mapper = session.getMapper(FileWatchLogMapper.class);
            // 注意：由于可能有多个事件，需要根据具体事件时间定位，这里简化：只标记最近的一条未处理记录
            // 更好方式是在插入时返回ID，或者用更精确的查询条件
            // 为简化，可采用 UPDATE ... WHERE file_path = #{filePath} AND is_processed = 0 ORDER BY event_time DESC LIMIT 1
            // 但为了演示，我们增加一个根据路径和事件类型更新最近一条未处理的
            // 可在Mapper中增加方法：updateLatestUnprocessed(@Param("filePath") String filePath, @Param("eventType") String eventType)
            // 此处略，直接调用下面的方法：
            // 我们实现一个专门的更新方法
            // 这里为了快速演示，我们使用 UPDATE ... SET is_processed=1 WHERE file_path=#{filePath} AND event_type=#{eventType} AND is_processed=0
            // 但若同一路径同一类型可能有多个未处理，则全部标记，实际可接受
            /*session.update("com.vicente.mapper.FileWatchLogMapper.markAllProcessedByPathAndType",
                    new Object[]{filePath, eventType});*/
            mapper.markAllProcessedByPathAndType(filePath, eventType);
        } catch (Exception e) {
            logger.error("标记日志为已处理失败: {}", filePath, e);
        }
    }


    /**
     * 停止监听
     */
    public void stop() throws IOException {
        running = false;
        scheduler.shutdown();
        watchService.close();
    }
}

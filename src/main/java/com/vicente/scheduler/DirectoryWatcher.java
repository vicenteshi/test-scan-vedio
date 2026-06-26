package com.vicente.scheduler;

import com.vicente.service.FileScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

    public DirectoryWatcher(Path rootDir, FileScannerService scannerService) throws IOException {
        this.scannerService = scannerService;
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
        }, 500, TimeUnit.MILLISECONDS); // 500ms 延迟合并
    }

    /**
     * 处理文件删除
     */
    private void handleFileDelete(Path file) {
        logger.info("检测到文件删除: {}", file);
        // 调用 scannerService 的删除方法（标记删除或物理删除）
        scannerService.deleteFileRecord(file);
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

package com.vicente.service;

import com.vicente.entity.VideoFile;
import com.vicente.mapper.VideoFileMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author sws
 * @description TODO
 * @date 2026/05/26
 */
public class FileScannerService {
    private static final Logger logger = LoggerFactory.getLogger(FileScannerService.class);
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp"
    ));
    private final ExecutorService executor;
    private final SqlSessionFactory sqlSessionFactory;   // 保留成员
    private final Queue<VideoFile> resultQueue = new ConcurrentLinkedQueue<>();
    // 是否使用数据库
    private boolean useDatabase;

    public FileScannerService(int threadPoolSize, SqlSessionFactory sqlSessionFactory, boolean useDatabase) {
        this.executor = Executors.newFixedThreadPool(threadPoolSize);
        this.sqlSessionFactory = sqlSessionFactory;
        this.useDatabase = useDatabase;
    }

    public void scanDirectory(Path startDir) throws IOException {
        logger.info("开始扫描目录: {}", startDir);
        // 显式写 <Path>
        Files.walkFileTree(startDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && isVideoFile(file)) {
                    executor.submit(new FileProcessor(file, resultQueue));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("无法访问文件: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        logger.info("扫描完成，已提交所有文件任务");
    }

    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            String ext = fileName.substring(dot + 1).toLowerCase();
            return VIDEO_EXTENSIONS.contains(ext);
        }
        return false;
    }

    public void shutdownAndWait(long timeoutSeconds) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            logger.warn("强制终止未完成的任务");
        }
        // 保存数据（根据标志选择数据库或文件）
        if (useDatabase) {
            batchSaveToDatabase();
        } else {
            saveToCsvFile();
        }
    }

    private void batchSaveToDatabase() {
        int batchSize = 100;
        List<VideoFile> batch = new ArrayList<>(batchSize);
        int total = 0;
        logger.info("开始批量保存，共收集到 {} 个文件", resultQueue.size());

        try (SqlSession session = sqlSessionFactory.openSession(false)) { // 不自动提交
            VideoFileMapper mapper = session.getMapper(VideoFileMapper.class);
            while (!resultQueue.isEmpty()) {
                VideoFile vf = resultQueue.poll();
                if (vf != null) {
                    batch.add(vf);
                    total++;
                }
                if (batch.size() >= batchSize || resultQueue.isEmpty()) {
                    if (!batch.isEmpty()) {
                        try {
                            mapper.batchInsertOrUpdate(batch);
                            session.commit();
                            logger.info("已批量保存 {} 条记录", batch.size());
                        } catch (Exception e) {
                            session.rollback();
                            logger.error("批量保存失败，回滚批次", e);
                            // 可考虑逐条重试等策略，这里简单打印
                        } finally {
                            batch.clear();
                        }
                    }
                }
            }
        }
        logger.info("批量保存完成，共处理 {} 个文件", total);
    }

    // CSV 文件保存
    private void saveToCsvFile() {
        if (resultQueue.isEmpty()) {
            logger.info("没有视频文件需要保存");
            return;
        }
        String csvFile = "video_files.csv";
        boolean fileExists = Files.exists(Paths.get(csvFile));

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, true))) {
            // 如果文件不存在或为空，写入表头
            if (!fileExists || Files.size(Paths.get(csvFile)) == 0) {
                writer.println("文件名,创建时间,修改时间,上次访问时间,格式,大小(字节),大小(可读),MD5,完整路径,主演,视频编号,分数,保存时间");
            }
            // 写入所有数据
            for (VideoFile vf : resultQueue) {
                writer.println(toCsvRow(vf));
            }
            logger.info("数据已保存到文件：{}，共 {} 条记录", csvFile, resultQueue.size());
        } catch (Exception e) {
            logger.error("写入 CSV 文件失败", e);
        }
    }

    // 将 VideoFile 对象转换为 CSV 行（字段用逗号分隔，内容中若有逗号则加引号）
    private String toCsvRow(VideoFile vf) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"",
                escapeCsv(vf.getFileName()),
                vf.getCreationDate() != null ? vf.getCreationDate().format(formatter) : "",
                vf.getModificationDate() != null ? vf.getModificationDate().format(formatter) : "",
                vf.getLastAccessTime() != null ? vf.getLastAccessTime().format(formatter) : "",
                escapeCsv(vf.getFileFormat()),
                vf.getFileSize(),
                escapeCsv(String.valueOf(vf.getFileSize())),
                escapeCsv(vf.getFileMd5()),
                escapeCsv(vf.getFilePath()),
                escapeCsv(vf.getActor()),
                escapeCsv(vf.getVideoCode()),
                escapeCsv(String.valueOf(vf.getFileScore())),
                LocalDateTime.now().format(formatter)
        );
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // 如果字段中包含双引号，将双引号替换为两个双引号
        return value.replace("\"", "\"\"");
    }


}

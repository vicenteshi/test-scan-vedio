package com.vicente.service;

import com.vicente.entity.ImageFile;
import com.vicente.entity.VideoFile;
import com.vicente.enums.FileType;
import com.vicente.extractor.ImageMetadataExtractor;
import com.vicente.extractor.VideoMetadataExtractor;
import com.vicente.mapper.ImageFileMapper;
import com.vicente.mapper.VideoFileMapper;
import com.vicente.util.FileTypeDetector;
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
import java.util.function.Consumer;

/**
 * @author sws
 * @description 使用两个队列分别存放视频和图片实体，并维护两个 Mapper。
 * @date 2026/05/26
 */
public class FileScannerService {
    private static final Logger logger = LoggerFactory.getLogger(FileScannerService.class);
    // 原有成员
    private final ExecutorService executor;
    private final Queue<VideoFile> videoQueue = new ConcurrentLinkedQueue<>();
    private final Queue<ImageFile> imageQueue = new ConcurrentLinkedQueue<>();
    private final boolean useDatabase;
    private final SqlSessionFactory sqlSessionFactory;

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
                if (attrs.isRegularFile()) {
                    FileType type = FileTypeDetector.detect(file);
                    if (type == FileType.VIDEO) {
                        executor.submit(() -> {
                            try {
                                VideoFile vf = new VideoMetadataExtractor().extract(file);
                                videoQueue.offer(vf);
                            } catch (Exception e) {
                                logger.error("提取视频信息失败: {}", file, e);
                            }
                        });
                    } else if (type == FileType.IMAGE) {
                        executor.submit(() -> {
                            try {
                                ImageFile img = new ImageMetadataExtractor().extract(file);
                                imageQueue.offer(img);
                            } catch (Exception e) {
                                logger.error("提取图片信息失败: {}", file, e);
                            }
                        });
                    }
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
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            VideoFileMapper videoMapper = session.getMapper(VideoFileMapper.class);
            ImageFileMapper imageMapper = session.getMapper(ImageFileMapper.class);

            // 处理视频
            batchProcess(session, videoQueue, videoMapper::batchInsertOrUpdate, "video");
            // 处理图片
            batchProcess(session, imageQueue, imageMapper::batchInsertOrUpdate, "image");
        }
    }

    private <T> void batchProcess(SqlSession session, Queue<T> queue, Consumer<List<T>> batcher, String type) {
        List<T> batch = new ArrayList<>(100);
        int total = 0;
        while (!queue.isEmpty()) {
            T item = queue.poll();
            if (item != null) {
                batch.add(item);
                total++;
            }
            if (batch.size() >= 100 || queue.isEmpty()) {
                if (!batch.isEmpty()) {
                    batcher.accept(batch);
                    session.commit();
                    logger.info("已批量保存 {} 条 {} 记录", batch.size(), type);
                    batch.clear();
                }
            }
        }
        logger.info("批量保存 {} 完成，共 {} 个文件", type, total);
    }

    // CSV 文件保存
    private void saveToCsvFile() {
        if (videoQueue.isEmpty()) {
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
            for (VideoFile vf : videoQueue) {
                writer.println(toCsvRow(vf));
            }
            logger.info("数据已保存到文件：{}，共 {} 条记录", csvFile, videoQueue.size());
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

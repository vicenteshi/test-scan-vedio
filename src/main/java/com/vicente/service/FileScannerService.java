package com.vicente.service;

import com.vicente.entity.FileSnapshot;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author sws
 * @description 使用两个队列分别存放视频和图片实体，并维护两个 Mapper。
 * @date 2026/05/26
 */
public class FileScannerService {
    private static final Logger logger = LoggerFactory.getLogger(FileScannerService.class);
    private final ExecutorService extractorPool;          // 负责提取元数据的线程池
    private final ExecutorService videoSaver;             // 单线程的后台保存线程
    private final ExecutorService imageSaver;
    private final BlockingQueue<VideoFile> videoQueue;     // 改用阻塞队列，可限制容量
    private final BlockingQueue<ImageFile> imageQueue;
    private final AtomicInteger scannedCount = new AtomicInteger(0);    // 已扫描文件总数
    private final AtomicInteger videoProcessed = new AtomicInteger(0);  // 已完成提取的视频数
    private final AtomicInteger imageProcessed = new AtomicInteger(0);  // 已完成提取的图片数
    private final AtomicInteger totalVideo = new AtomicInteger(0);      // 扫描到的视频总数（最终会等于videoProcessed）
    private final AtomicInteger totalImage = new AtomicInteger(0);      // 扫描到的图片总数
    private volatile boolean scanningDone = false;          // 标记扫描是否已完成
    private final boolean useDatabase;
    private final SqlSessionFactory sqlSessionFactory;
    private final AtomicInteger pendingTasks = new AtomicInteger(0);   // 未完成的提取任务数（包括视频和图片）

    // 批量保存配置
    private static final int BATCH_SIZE = 100;
    private static final int SAVE_INTERVAL_MS = 3000;

    // 内存快照缓存
    private Map<String, FileSnapshot> snapshotCache;

    public FileScannerService(int threadPoolSize, SqlSessionFactory sqlSessionFactory, boolean useDatabase) {
        this.extractorPool = Executors.newFixedThreadPool(threadPoolSize);
        // 限制队列最大容量为 BATCH_SIZE * 2，避免无限堆积（可选）
        this.videoQueue = new LinkedBlockingQueue<>(BATCH_SIZE * 2);
        this.imageQueue = new LinkedBlockingQueue<>(BATCH_SIZE * 2);
        this.sqlSessionFactory = sqlSessionFactory;
        this.useDatabase = useDatabase;
        // 启动后台保存线程
        this.videoSaver = Executors.newSingleThreadExecutor(r -> new Thread(r, "video-saver"));
        this.imageSaver = Executors.newSingleThreadExecutor(r -> new Thread(r, "image-saver"));
    }

    public void startBatchSaver() {
        startVideoSaver();
        startImageSaver();
    }

    public void startVideoSaver() {
        videoSaver.submit(() -> {
            logger.info("pendingTasks:{},videoQueue.size:{}",pendingTasks.get(),videoQueue.size());
            while (pendingTasks.get() > 0 || !videoQueue.isEmpty() ) {
                try {
                    // 收集视频和图片，凑够 BATCH_SIZE 或超时
                    List<VideoFile> videoBatch = new ArrayList<>(BATCH_SIZE);
                    long startTime = System.currentTimeMillis();
                    boolean shouldSave = false;

                    // 非阻塞获取一个视频（如果有）
                    VideoFile vf = videoQueue.poll(SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (vf != null) {
                        videoBatch.add(vf);
                        // 继续获取直到满批或超时
                        while (videoBatch.size() < BATCH_SIZE && (vf = videoQueue.poll(SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS)) != null) {
                            videoBatch.add(vf);
                        }
                    }
                    if (!videoBatch.isEmpty()) {
                        saveVideoBatch(videoBatch);
                        videoBatch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 最后的清理
            List<VideoFile> remaining = new ArrayList<>();
            videoQueue.drainTo(remaining);
            if (!remaining.isEmpty()) {
                saveVideoBatch(remaining);
            }
        });
    }

    public void startImageSaver() {
        imageSaver.submit(() -> {
            logger.info("pendingTasks:{},imageQueue.size:{}",pendingTasks.get(),imageQueue.size());
            while (pendingTasks.get() > 0 || !imageQueue.isEmpty() ) {
                try {
                    // 收集视频和图片，凑够 BATCH_SIZE 或超时
                    List<ImageFile> imageBatch = new ArrayList<>(BATCH_SIZE);
                    long startTime = System.currentTimeMillis();
                    boolean shouldSave = false;

                    // 非阻塞获取一个视频（如果有）
                    ImageFile img = imageQueue.poll(SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (img != null) {
                        imageBatch.add(img);
                        // 继续获取直到满批或超时
                        while (imageBatch.size() < BATCH_SIZE && (img = imageQueue.poll(SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS)) != null) {
                            imageBatch.add(img);
                        }
                    }
                    if (!imageBatch.isEmpty()) {
                        saveImageBatch(imageBatch);
                        imageBatch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 最后的清理
            List<ImageFile> remaining = new ArrayList<>();
            imageQueue.drainTo(remaining);
            if (!remaining.isEmpty()) {
                saveImageBatch(remaining);
            }
        });
    }


    public void scanDirectory(Path startDir) throws IOException {
        logger.info("开始扫描目录: {}", startDir);

        // 显式写 <Path>
        Files.walkFileTree(startDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                int currentCount = scannedCount.incrementAndGet();
                String filePath = file.toString();
                // 判断是否需要提取
                boolean needExtract = false;
                FileSnapshot snapshot = snapshotCache.get(filePath);
                if (snapshot == null) {
                    // 新文件
                    needExtract = true;
                } else {
                    // 比对修改时间和大小
                    long currentSize = attrs.size();
                    LocalDateTime currentModTime = attrs.lastModifiedTime()
                            .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                    if (snapshot.getSize() != currentSize || !snapshot.getModificationTime().equals(currentModTime)) {
                        // 文件已变化
                        needExtract = true;
                    }
                }
                if (needExtract) {
                    // 每扫描 100 个文件打印一次进度（仅提交数）
                    if (currentCount % 100 == 0) {
                        logger.info("已扫描文件数: {}, 已提交到提取队列", currentCount);
                    }
                    FileType type = FileTypeDetector.detect(file);
                    if (type == FileType.VIDEO) {
                        totalVideo.incrementAndGet();
                        pendingTasks.incrementAndGet();
                        extractorPool.submit(() -> extractVideo(file));
                    } else if (type == FileType.IMAGE) {
                        totalImage.incrementAndGet();
                        pendingTasks.incrementAndGet();
                        extractorPool.submit(() -> extractImage(file));
                    }
                } else {
                    // 文件未变化，可以仅更新 last_scan_time（可选），但我们不在此处处理，由保存时统一更新
                    logger.info("文件未变化，跳过: {}", filePath);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("无法访问文件: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        // 标记扫描完成，但保存线程还会继续处理队列中的剩余数据
        scanningDone = true;
        logger.info("扫描目录完成，共提交 {} 个文件", scannedCount.get());
    }

    private void extractVideo(Path file) {
        try {
            //生成保存的视频信息
            VideoFile vf = new VideoMetadataExtractor().extract(file);
            videoQueue.put(vf);
            // 阻塞放入，保证不丢失
            logger.info("videoQueue:{}",videoQueue.size());
            int processed = videoProcessed.incrementAndGet();
            // 每完成 50 个视频打印一次进度
            if (processed % 50 == 0 || processed == totalVideo.get()) {
                logger.info("已提取视频: {} / {}, 图片: {} / {}",
                        processed, totalVideo.get(), imageProcessed.get(), totalImage.get());
            }
        } catch (Exception e) {
            logger.error("提取视频信息失败: {}", file, e);
        } finally {
            pendingTasks.decrementAndGet();
        }
    }

    private void extractImage(Path file) {
        try {
            //生成保存的图片信息
            ImageFile img = new ImageMetadataExtractor().extract(file);
            imageQueue.put(img);
            logger.info("imageQueue:{}",imageQueue.size());
            int processed = imageProcessed.incrementAndGet();
            if (processed % 50 == 0 || processed == totalImage.get()) {
                logger.info("已提取图片: {} / {}, 视频: {} / {}",
                        processed, totalImage.get(), videoProcessed.get(), totalVideo.get());
            }
        } catch (Exception e) {
            logger.error("提取图片信息失败: {}", file, e);
        } finally {
            pendingTasks.decrementAndGet();
        }
    }

    /**
     * 关闭服务，等待所有任务完成并最后保存
     */
    public void shutdownAndWait(long timeoutSeconds) throws InterruptedException {
        extractorPool.shutdown();
        if (!extractorPool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            extractorPool.shutdownNow();
            logger.warn("强制终止未完成的提取任务");
        }
        // 等待保存线程处理完所有队列数据（队列为空且没有新数据进来）
        videoSaver.shutdown();
        if (!videoSaver.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            videoSaver.shutdownNow();
            logger.warn("强制终止视频保存线程");
        }
        imageSaver.shutdown();
        if (!imageSaver.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            imageSaver.shutdownNow();
            logger.warn("强制终止图片保存线程");
        }
        logger.info("所有任务完成，视频{}个，图片{}个", videoProcessed.get(), imageProcessed.get());
    }

    /**
     * 批量保存视频和图片到数据库
     */
    private void saveVideoBatch(List<VideoFile> videos) {
        if (useDatabase) {
            try (SqlSession session = sqlSessionFactory.openSession(false)) {
                VideoFileMapper videoMapper = session.getMapper(VideoFileMapper.class);
                if (!videos.isEmpty()) {
                    videoMapper.batchInsertOrUpdate(videos);
                    logger.info("批量保存视频 {} 条", videos.size());
                    session.commit();
                }
            }catch (Exception e) {
                logger.error("批量保存失败", e);
            }
        } else {
            // CSV 追加
            saveToCsvFile(videos);
        }
    }

    private void saveImageBatch(List<ImageFile> images) {
        if (useDatabase) {
            try (SqlSession session = sqlSessionFactory.openSession(false)) {
                ImageFileMapper imageMapper = session.getMapper(ImageFileMapper.class);
                if (!images.isEmpty()) {
                    imageMapper.batchInsertOrUpdate(images);
                    logger.info("批量保存图片 {} 条", images.size());
                    session.commit();
                }
            }catch (Exception e) {
                logger.error("批量保存失败", e);
            }
        } else {
            // CSV 追加
            saveImageCsvFile(images);
        }
    }

    // CSV 保存需要支持追加模式，这里简化为每个批次追加写入
    private synchronized void saveToCsvFile(List<VideoFile> videos) {
        String csvFile = "video_files.csv";
        boolean fileExists = Files.exists(Paths.get(csvFile));
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, true))) {
            if (!fileExists || Files.size(Paths.get(csvFile)) == 0) {
                writer.println("文件名,创建时间,修改时间,上次访问时间,格式,大小(字节),大小(可读),MD5,完整路径,主演,视频编号,分数,保存时间");
            }
            for (VideoFile vf : videos) {
                writer.println(toCsvRow(vf));
            }
            // 图片也可以类似写入另一个CSV
            logger.info("追加写入CSV {} 条视频记录", videos.size());
        } catch (Exception e) {
            logger.error("写入CSV文件失败", e);
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

    // CSV 保存需要支持追加模式，这里简化为每个批次追加写入
    private synchronized void saveImageCsvFile(List<ImageFile> images) {
        String csvFile = "image_files.csv";
        boolean fileExists = Files.exists(Paths.get(csvFile));
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile, true))) {
            if (!fileExists || Files.size(Paths.get(csvFile)) == 0) {
                writer.println("文件名,创建时间,修改时间,上次访问时间,格式,大小(字节),大小(可读),MD5,完整路径,主演,文件名称,分数,保存时间");
            }
            for (ImageFile vf : images) {
                writer.println(toImageCsvRow(vf));
            }
            // 图片也可以类似写入另一个CSV
            logger.info("追加写入CSV {} 条视频记录", images.size());
        } catch (Exception e) {
            logger.error("写入CSV文件失败", e);
        }
    }

    // 将 ImageFile 对象转换为 CSV 行
    private String toImageCsvRow(ImageFile vf) {
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
                escapeCsv(vf.getFolderName()),
                escapeCsv(String.valueOf(vf.getScore())),
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

    /**
     * 加载数据库快照到内存
     */
    public void loadSnapshotCache() {
        if (!useDatabase) {
            snapshotCache = new HashMap<>();
            logger.info("未启用数据库，快照缓存为空");
            return;
        }

        logger.info("开始加载数据库快照到内存...");
        long start = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            VideoFileMapper videoMapper = session.getMapper(VideoFileMapper.class);
            ImageFileMapper imageMapper = session.getMapper(ImageFileMapper.class);
            List<VideoFile> videoList = videoMapper.selectAllSnapshots();
            List<ImageFile> imageList = imageMapper.selectAllSnapshots();
            snapshotCache = new HashMap<>(videoList.size() + imageList.size());
            for (VideoFile vf : videoList) {
                snapshotCache.put(vf.getFilePath(),
                        new FileSnapshot(vf.getFileSize(), vf.getModificationDate(), vf.getLastScanTime()));
            }
            for (ImageFile img : imageList) {
                snapshotCache.put(img.getFilePath(),
                        new FileSnapshot(img.getFileSize(), img.getModificationDate(), img.getLastScanTime()));
            }
            long elapsed = System.currentTimeMillis() - start;
            logger.info("快照加载完成，共 {} 条记录，耗时 {} ms", snapshotCache.size(), elapsed);
        } catch (Exception e) {
            logger.error("加载快照失败，将退化为全量扫描", e);
            snapshotCache = new HashMap<>(); // 降级
        }
    }



}

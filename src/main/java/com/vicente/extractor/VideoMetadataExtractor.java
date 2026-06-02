package com.vicente.extractor;

import com.vicente.entity.VideoFile;
import com.vicente.util.MD5Util;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author sws
 * @description 视频提取器，将原 FileProcessor.buildVideoFile 迁移过来，并实现接口。
 * @date 2026/05/28
 */
public class VideoMetadataExtractor implements MetadataExtractor<VideoFile> {
    private static final Logger logger = LoggerFactory.getLogger(VideoMetadataExtractor.class);

    @Override
    public VideoFile extract(Path path) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        VideoFile vf = new VideoFile();
        vf.setFileName(path.getFileName().toString());
        vf.setCreationDate(toLocalDateTime(attrs.creationTime().toMillis()));
        vf.setModificationDate(toLocalDateTime(attrs.lastModifiedTime().toMillis()));
        vf.setLastAccessTime(toLocalDateTime(attrs.lastAccessTime().toMillis()));
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        vf.setFileFormat(dotIndex > 0 ? fileName.substring(dotIndex + 1).toLowerCase() : "");
        vf.setFileSize(attrs.size());
        vf.setFilePath(path.toAbsolutePath().toString());
        // 这里可以添加从文件名解析主演、视频编号的逻辑，或者留空手动填充
        // 示例：根据文件名规则填充，比如 "S01E02.mp4" -> videoCode = "S01E02"
        // 您可以根据实际需求实现
        vf.setVideoCode(extractVideoCode(fileName));
        vf.setActor(extractActor(fileName));
        vf.setFileSizeFormat(getFileSizeFormatted(attrs.size()));
        vf.setFileScore(extractScore(fileName));

        // 计算MD5（可能耗时）
        vf.setFileMd5(MD5Util.calculateMD5(path));
        Double duration = getVideoDurationWithJavacv(path);
        int round = duration == null ? 0: (int)Math.round(duration);
        vf.setVideoDuration(round);
        return vf;
    }

    @Override
    public String getFileType() {
        return "VIDEO";
    }

    private LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    // 提取视频编号：查找第一个由字母数字组合-字母数字组合的字符串（至少一个连字符）
    private String extractVideoCode(String fileName) {
        Pattern pattern = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+");
        Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    // 提取主演：查找【...】中的内容，并保留括号，如【彤彤】
    private String extractActor(String fileName) {
        Pattern pattern = Pattern.compile("【([^】]+)】");
        Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            // 返回带括号的完整匹配
            return matcher.group(0);
        }
        return "";
    }

    // 示例： [6.8]zhizun-003【彤彤】至尊洗衣视频.mov，文件名开头 [数字]，如 [6.8] → 6.8
    private Float extractScore(String fileName) {
        String score = "";
        Pattern pattern = Pattern.compile("^\\[([\\d.]+)\\]");
        Matcher matcher = pattern.matcher(fileName.trim());
        if (matcher.find()) {
            score = matcher.group(1);
        }
        return StringUtils.isEmpty(score) ? 0 : Float.valueOf(score);
    }

    /**
     * 获取格式化后的文件大小（自动选择 GB/MB/KB）
     */
    public String getFileSizeFormatted(Long fileSize) {
        if (fileSize == null) {
            return "0 B";
        }
        long size = fileSize;
        if (size >= 1024 * 1024 * 1024) {
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        } else if (size >= 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024));
        } else if (size >= 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return size + " B";
        }
    }

    /**
     * 使用系统安装的 ffprobe 命令获取视频文件的时长（秒）
     * @param path 视频文件的 Path 对象
     * @return 时长（秒，浮点数），如果获取失败则返回 null
     */
    private Double getVideoDuration(Path path) {
        // 构建 ffprobe 命令
        // -v error: 只显示错误信息，隐藏不必要的日志
        // -show_entries format=duration: 只输出时长信息
        // -of default=noprint_wrappers=1:nokey=1: 以纯文本形式输出，不包含额外的格式和键名[reference:7][reference:8]
        // %s: 替换为文件路径
        String[] cmd = {
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                path.toString()
        };
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        // 将错误流合并到输入流，简化处理
        processBuilder.redirectErrorStream(true);
        try (InputStream inputStream = processBuilder.start().getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                // 将读取到的字符串（如 "123.456"）解析为 Double 类型并返回
                return Double.parseDouble(line.trim());
            } else {
                logger.warn("未从 ffprobe 获取到时长的输出，文件: {}", path);
                return null;
            }
        } catch (Exception e) {
            // 捕获异常，避免单个文件处理失败影响整体扫描
            logger.warn("调用 ffprobe 获取视频时长失败: {} - {}", path, e.getMessage());
            return null;
        }
    }

    //pom引入javacv-platformjar包后可调用
    private Double getVideoDurationWithJavacv(Path path) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile())) {
            grabber.start();
            // 微秒
            long durationMicroseconds = grabber.getLengthInTime();
            double durationSeconds = durationMicroseconds / 1_000_000.0;
            grabber.stop();
            return durationSeconds;
        } catch (Exception e) {
            logger.warn("获取视频时长失败: {}", path, e);
            return null;
        }
    }


}

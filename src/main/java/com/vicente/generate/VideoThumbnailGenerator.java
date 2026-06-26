package com.vicente.generate;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * @author sws
 * @description 视频缩略图生成器
 * @date 2026/06/26
 */
public class VideoThumbnailGenerator {

    /**
     * 生成视频缩略图
     * @param videoPath 视频文件路径
     * @param outputPath 缩略图输出路径（建议使用 .jpg 或 .png）
     * @param timeSeconds 截取视频的第几秒
     * @return 成功返回 true
     */
    public static boolean generateThumbnail(Path videoPath, Path outputPath, int timeSeconds) {
        // 使用 try-with-resources 确保资源自动释放[reference:10]
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.start(); // 1. 打开视频文件[reference:11]

            // 2. 定位到指定时间点（单位：微秒）[reference:12]
            grabber.setTimestamp(timeSeconds * 1_000_000L);

            // 3. 抓取一帧图像[reference:13]
            Frame frame = grabber.grabImage();
            if (frame == null) {
                System.err.println("警告：无法在 " + timeSeconds + " 秒处抓取帧，将尝试抓取第一帧。");
                frame = grabber.grabImage(); // 尝试抓取第一帧作为备选
                if (frame == null) {
                    return false;
                }
            }

            // 4. 将帧转换为 BufferedImage[reference:14]
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage image = converter.getBufferedImage(frame);

            // 5. 保存为图片文件[reference:15]
            ImageIO.write(image, "jpg", outputPath.toFile());
            return true;

        } catch (IOException e) {
            System.err.println("生成视频缩略图失败: " + e.getMessage());
            return false;
        }
    }

}

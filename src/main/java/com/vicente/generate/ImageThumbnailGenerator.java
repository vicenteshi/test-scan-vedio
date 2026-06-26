package com.vicente.generate;

import net.coobird.thumbnailator.Thumbnails;

import java.io.IOException;
import java.nio.file.Path;

/**
 * @author sws
 * @description 2. 图片缩略图生成器
 * @date 2026/06/26
 */
public class ImageThumbnailGenerator {

    /**
     * 生成图片缩略图
     * @param imagePath 原始图片路径
     * @param outputPath 缩略图输出路径
     * @param width 目标宽度
     * @return 成功返回 true
     */
    public static boolean generateThumbnail(Path imagePath, Path outputPath, int width) {
        try {
            // 使用 Thumbnailator 流畅的 API 生成缩略图[reference:16][reference:17]
            // 指定源文件[reference:18]
            Thumbnails.of(imagePath.toFile())
                    .width(width)         // 设定目标尺寸size(width, height)
                    .outputFormat("jpg")         // 指定输出格式[reference:20]
                    .toFile(outputPath.toFile());// 输出到文件[reference:21]
            return true;
        } catch (IOException e) {
            System.err.println("生成图片缩略图失败: " + e.getMessage());
            return false;
        }
    }

}

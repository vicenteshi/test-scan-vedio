package com.vicente.extractor;

import com.vicente.entity.ImageFile;
import com.vicente.util.MD5Util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * @author sws
 * @description 图片提取器,使用 javax.imageio.ImageIO 读取图片尺寸（无需FFmpeg，减少依赖）。
 * @date 2026/05/28
 */
public class ImageMetadataExtractor implements MetadataExtractor<ImageFile> {
    @Override
    public ImageFile extract(Path path) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        ImageFile img = new ImageFile();
        img.setFileName(path.getFileName().toString());
        img.setCreationDate(LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault()));
        img.setModificationDate(LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()));
        img.setLastAccessTime(LocalDateTime.ofInstant(attrs.lastAccessTime().toInstant(), ZoneId.systemDefault()));

        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        img.setFileFormat(dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "");
        img.setFileSize(attrs.size());
        img.setFilePath(path.toAbsolutePath().toString());
        img.setFileMd5(MD5Util.calculateMD5(path));
        img.setCreateTime(LocalDateTime.now());

        // 读取图片尺寸
        try (InputStream is = Files.newInputStream(path)) {
            BufferedImage bi = ImageIO.read(is);
            if (bi != null) {
                img.setWidth(bi.getWidth());
                img.setHeight(bi.getHeight());
                // 色彩空间可通过 bi.getColorModel() 获取
                img.setColorSpace(bi.getColorModel().getColorSpace().toString());
            }
        } catch (Exception e) {
            // 非图片格式或读取失败，忽略
        }
        return img;
    }

    @Override
    public String getFileType() { return "IMAGE"; }
}

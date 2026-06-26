package com.vicente.extractor;

import com.vicente.entity.ImageFile;
import com.vicente.generate.ImageThumbnailGenerator;
import com.vicente.generate.VideoThumbnailGenerator;
import com.vicente.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;

/**
 * @author sws
 * @description 图片提取器,使用 javax.imageio.ImageIO 读取图片尺寸（无需FFmpeg，减少依赖）。
 * @date 2026/05/28
 */

public class ImageMetadataExtractor implements MetadataExtractor<ImageFile> {
    private static final Logger logger = LoggerFactory.getLogger(ImageMetadataExtractor.class);

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
        img.setFolderName(getParentFolderName(path));
        img.setFileMd5(MD5Util.calculateMD5(path));
        img.setCreateTime(LocalDateTime.now());
        //只解析文件头部，不解码像素数据，性能远优于 ImageIO.read()
        try (ImageInputStream iis = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                reader.setInput(iis);
                img.setWidth(reader.getWidth(0));
                img.setHeight(reader.getHeight(0));
                reader.dispose();
            }
        } catch (Exception e) {
            logger.warn("读取图片尺寸失败: {}", path, e);
        }
        img.setLastScanTime(LocalDateTime.now());
        // 3. 获取 fileKey (跨平台的 inode + device ID)
        String fileKey = VideoMetadataExtractor.getFileKey(path);
        img.setFileInode(fileKey);
        // 2. 生成缩略图，指定宽按原图比例计算出对应尺寸
        Path thumbnailPath = path.resolveSibling("/data/bbb/thumbnails/" + path.getFileName().toString() + ".jpg");
        Files.createDirectories(thumbnailPath.getParent());
        boolean success = ImageThumbnailGenerator.generateThumbnail(path, thumbnailPath, 200);
        if (success) {
            //img.setThumbnailPath(thumbnailPath.toString());
        }
        // 读取图片尺寸
       /* try (InputStream is = Files.newInputStream(path)) {
            BufferedImage bi = ImageIO.read(is);
            if (bi != null) {
                img.setWidth(bi.getWidth());
                img.setHeight(bi.getHeight());
                // 色彩空间可通过 bi.getColorModel() 获取
                img.setColorSpace(bi.getColorModel().getColorSpace().toString());
            }
        } catch (Exception e) {
        }*/
        return img;
    }

    @Override
    public String getFileType() { return "IMAGE"; }

    public static String getParentFolderName(Path path) {
        //Path path = Paths.get(filePath);
        Path parent = path.getParent();
        if (parent == null) {
            return "";   // 或返回 null，根据业务决定
        }
        return parent.getFileName().toString();
    }

}

package com.vicente.util;

import com.vicente.enums.FileType;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author sws
 * @description 文件类型检测器
 * @date 2026/05/28
 */
public class FileTypeDetector {
    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp"
    ));
    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            "jpg","jpeg","png","gif","bmp","webp"
    ));

    public static FileType detect(Path path) {
        String ext = getExtension(path).toLowerCase();
        if (VIDEO_EXTS.contains(ext)) {
            return FileType.VIDEO;
        }
        if (IMAGE_EXTS.contains(ext)) {
            return FileType.IMAGE;
        }
        return FileType.UNKNOWN;
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }
}

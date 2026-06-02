package com.vicente.extractor;
import java.nio.file.Path;

/**
 * @author sws
 * @description 元数据提取器接口
 * @date 2026/05/28
 */
public interface MetadataExtractor<T> {
    T extract(Path filePath) throws Exception;
    // 用于日志或判断
    String getFileType();
}

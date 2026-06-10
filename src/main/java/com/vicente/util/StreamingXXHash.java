package com.vicente.util;

import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author sws
 * @description TODO
 * @date 2026/06/10
 */
public class StreamingXXHash {

    // 8KB的缓冲区，这是一个比较通用的选择（为了减少IO次数，改为8MB)
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;

    public static long xxHash64(Path filePath) throws IOException {
        // 获取一个可用的最快实例
        XXHashFactory factory = XXHashFactory.fastestInstance();

        // 种子值，用于初始化哈希值，相同内容使用相同种子会产生一致的结果。
        // 在去重场景下保持固定即可，例如 0L
        long seed = 0L;

        // ★★★ 核心修正点：通过 factory 创建流式实例 ★★★
        try (StreamingXXHash64 streamingHash = factory.newStreamingHash64(seed)) {
            // 使用 try-with-resources 确保文件流和哈希流式实例都能正确关闭
            try (InputStream in = Files.newInputStream(filePath);
                 BufferedInputStream bis = new BufferedInputStream(in)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = bis.read(buffer)) != -1) {
                    // 分块更新哈希计算器
                    streamingHash.update(buffer, 0, len);
                }
            }
            // 返回最终的64位哈希值
            return streamingHash.getValue();
        }
    }
}

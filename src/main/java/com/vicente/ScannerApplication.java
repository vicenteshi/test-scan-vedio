package com.vicente;

import com.vicente.config.MyBatisConfig;
import com.vicente.service.FileScannerService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地运行时：
 * 菜单栏选择 Run → Edit Configurations。
 * 在 Program arguments 输入框中填入要扫描的目录路径，例如：/data/
 */
public class ScannerApplication {
    private static final Logger logger = LoggerFactory.getLogger(ScannerApplication.class);
    private static final int THREAD_POOL_SIZE = 10;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 120;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: java -jar video-scanner.jar <扫描目录路径>");
            System.exit(1);
        }
        Path scanPath = Paths.get(args[0]);
        if (!scanPath.toFile().exists() || !scanPath.toFile().isDirectory()) {
            System.err.println("错误: 目录不存在或不是一个目录 - " + scanPath);
            System.exit(1);
        }

        long startTime = System.currentTimeMillis();
        logger.info("=== 视频文件扫描器启动 ===");

        boolean useDatabase = true;
        SqlSessionFactory sqlSessionFactory = null;
        try {
            // 尝试初始化数据库
            sqlSessionFactory = MyBatisConfig.getSqlSessionFactory();
        } catch (Exception e) {
            useDatabase = false;
            logger.warn("数据库连接失败，将保存到本地 CSV 文件", e);
        }
        if (useDatabase && sqlSessionFactory == null) {
            useDatabase = false;
        }

        try {
            // 扫描服务
            FileScannerService scanner = new FileScannerService(THREAD_POOL_SIZE, sqlSessionFactory, useDatabase);
            scanner.scanDirectory(scanPath);
            scanner.shutdownAndWait(SHUTDOWN_TIMEOUT_SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("扫描完成，耗时 {} 秒", elapsed / 1000.0);
        } catch (Exception e) {
            logger.error("程序运行失败", e);
            System.exit(1);
        }
        logger.info("=== 程序正常退出 ===");
        System.exit(0);
    }
}

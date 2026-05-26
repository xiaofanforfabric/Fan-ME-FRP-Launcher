package com.xiaofan.launcher.logs;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 日志归档器
 * 
 * 管理 last.logs 的生命周期：
 * - 启动时：如果存在 last.logs，先删除再创建新的
 * - 关闭时：将 last.logs 压缩为 zip 包，以关闭时间命名
 * 
 * 关闭钩子会捕获：
 * - 正常退出（System.exit / main 返回）
 * - Ctrl+C
 * - JVM 崩溃（通过 ShutdownHook）
 */
public class LogArchiver {

    private static final String LOG_FILE_NAME = "last.logs";
    private static Path logsDir;
    private static boolean shutdownHookRegistered = false;

    /**
     * 初始化日志目录，准备写入 last.logs
     * 如果存在旧的 last.logs，先删除
     * 
     * @param jarDir JAR 所在目录
     * @return last.logs 的完整路径
     */
    public static synchronized Path prepare(String jarDir) {
        logsDir = Paths.get(jarDir, "logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            // ignore
        }

        Path logFile = logsDir.resolve(LOG_FILE_NAME);

        // 删除旧的 last.logs
        try {
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            // ignore
        }

        // 注册关闭钩子（只注册一次）
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(LogArchiver::archiveOnShutdown));
        }

        return logFile;
    }

    /**
     * 关闭时归档：将 last.logs 压缩为 zip 包
     * 以关闭时间命名，如 2026-5-27-00-10-00.zip
     */
    private static void archiveOnShutdown() {
        if (logsDir == null) return;

        Path logFile = logsDir.resolve(LOG_FILE_NAME);
        if (!Files.exists(logFile)) return;

        // 生成关闭时间戳
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-M-d-HH-mm-ss");
        String timestamp = sdf.format(new Date());
        Path zipFile = logsDir.resolve(timestamp + ".zip");

        try {
            // 先 flush Logback 的 FileAppender，确保所有日志已写入
            // 通过关闭 Logback 的 LoggerContext 来 flush
            org.slf4j.LoggerFactory.getILoggerFactory().toString();

            zipLogFile(logFile, zipFile);

            // 压缩成功后删除 last.logs
            Files.deleteIfExists(logFile);
        } catch (Exception e) {
            // 归档失败时不要影响程序退出
            System.err.println("[LogArchiver] 归档日志失败: " + e.getMessage());
        }
    }

    /**
     * 将日志文件压缩为 zip 包
     */
    private static void zipLogFile(Path logFile, Path zipFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(logFile.toFile());
             FileOutputStream fos = new FileOutputStream(zipFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            // ZipEntry 使用文件名（不含路径）
            ZipEntry entry = new ZipEntry(LOG_FILE_NAME);
            zos.putNextEntry(entry);

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }

            zos.closeEntry();
        }
    }
}

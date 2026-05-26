package com.xiaofan.launcher.logs;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 错误报告管理器
 * 
 * 记录所有捕获或未捕获的异常，生成错误报告
 * 与 CrashReporter 不同，ErrorReporter 只记录堆栈调用和异常信息
 * 不包含系统信息、内存信息等
 * 
 * 保存到 error/error-2026-6-1-3-04-21.log 格式
 */
public class ErrorReporter {

    private static final String ERROR_DIR_NAME = "error";
    private static final String ERROR_FILE_PREFIX = "error-";
    private static final String ERROR_FILE_SUFFIX = ".log";
    private static final String LINE_SEPARATOR = "================================================================";
    private static final String SUB_SEPARATOR = "----------------------------------------------------------------";

    private final String jarDir;

    public ErrorReporter(String jarDir) {
        this.jarDir = jarDir;
    }

    /**
     * 记录一个异常，生成错误报告
     * 
     * @param throwable 要记录的异常
     * @param context 上下文描述（可选）
     * @return 错误报告文件的路径
     */
    public Path reportError(Throwable throwable, String context) {
        try {
            String report = generateErrorReport(throwable, context);
            return saveErrorReport(report);
        } catch (Exception e) {
            System.err.println("错误报告生成失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 记录一个异常，生成错误报告（无上下文）
     */
    public Path reportError(Throwable throwable) {
        return reportError(throwable, null);
    }

    /**
     * 生成错误报告
     * 只包含堆栈调用和异常信息，不包含系统信息
     */
    private String generateErrorReport(Throwable throwable, String context) {
        StringBuilder sb = new StringBuilder(2048);

        // 头部
        sb.append(LINE_SEPARATOR).append("\n");
        sb.append("  Fan-ME-FRP-Launcher 错误报告\n");
        sb.append(LINE_SEPARATOR).append("\n\n");

        // 时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sb.append("时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("时间戳: ").append(System.currentTimeMillis()).append("\n\n");

        // 上下文（如果有）
        if (context != null && !context.isEmpty()) {
            sb.append(SUB_SEPARATOR).append("\n");
            sb.append("-- 上下文 --\n");
            sb.append(SUB_SEPARATOR).append("\n");
            sb.append("  ").append(context).append("\n\n");
        }

        // 异常信息
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 异常信息 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("异常类型: ").append(throwable.getClass().getName()).append("\n");
        sb.append("异常消息: ").append(throwable.getMessage() != null ? throwable.getMessage() : "null").append("\n\n");

        // 堆栈跟踪
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 堆栈跟踪 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        // 打印 cause 链
        Throwable cause = throwable.getCause();
        while (cause != null) {
            cause.printStackTrace(pw);
            cause = cause.getCause();
        }
        pw.flush();
        sb.append(sw.toString()).append("\n");

        // 尾部
        sb.append(LINE_SEPARATOR).append("\n");
        sb.append("  -- 错误报告结束 --\n");
        sb.append(LINE_SEPARATOR).append("\n");

        return sb.toString();
    }

    /**
     * 保存错误报告到文件
     * 格式: error/error-2026-6-1-3-04-21.log
     */
    private Path saveErrorReport(String report) {
        try {
            // 创建 error 目录
            Path errorDir = Paths.get(jarDir, ERROR_DIR_NAME);
            Files.createDirectories(errorDir);

            // 生成文件名: error-2026-6-1-3-04-21.log
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-M-d-H-mm-ss", Locale.US);
            String timestamp = sdf.format(new Date());
            String fileName = ERROR_FILE_PREFIX + timestamp + ERROR_FILE_SUFFIX;

            Path errorFile = errorDir.resolve(fileName);

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(errorFile.toFile())) {
                fos.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            return errorFile;

        } catch (Exception e) {
            System.err.println("无法保存错误报告: " + e.getMessage());
            return null;
        }
    }
}

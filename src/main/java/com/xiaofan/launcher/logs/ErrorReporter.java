package com.xiaofan.launcher.logs;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
            Path errorFile = saveErrorReport(report);
            // 保存成功后，检查 EULA 并上传
            if (errorFile != null) {
                uploadErrorIfEulaAgreed(errorFile);
            }
            return errorFile;
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

    // ==================== EULA 检查与错误报告上传 ====================

    /**
     * 检查 EULA 是否同意，如果同意则将错误报告打包上传到服务器
     * 与 CrashReporter 的上传逻辑相同
     */
    private void uploadErrorIfEulaAgreed(Path errorFile) {
        // 1. 检查 EULA
        Path eulaFile = Paths.get(jarDir, "res", "eula.txt");
        if (!Files.exists(eulaFile)) {
            System.err.println("[ErrorReporter] eula.txt 不存在，跳过错误报告上传");
            return;
        }
        try {
            String content = new String(Files.readAllBytes(eulaFile), StandardCharsets.UTF_8).trim();
            boolean eulaAgreed = false;
            for (String line : content.split("\\n")) {
                line = line.trim().toLowerCase();
                if (line.equals("eula=true")) {
                    eulaAgreed = true;
                    break;
                }
            }
            if (!eulaAgreed) {
                System.err.println("[ErrorReporter] EULA 未同意，跳过错误报告上传");
                return;
            }
        } catch (Exception e) {
            System.err.println("[ErrorReporter] 读取 eula.txt 失败: " + e.getMessage());
            return;
        }

        System.err.println("[ErrorReporter] EULA 已同意，正在打包错误报告...");

        // 2. 打包 error 文件和 last.logs 为 zip
        Path tmpDir = Paths.get(jarDir, "tmp");
        Path logsDir = Paths.get(jarDir, "logs");
        Path lastLogs = logsDir.resolve("last.logs");

        String errorFileName = errorFile.getFileName().toString();
        String zipName = errorFileName.replace(ERROR_FILE_SUFFIX, ".zip");
        Path zipFile = tmpDir.resolve(zipName);

        try {
            Files.createDirectories(tmpDir);

            try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // 添加错误报告文件
                try (FileInputStream fis = new FileInputStream(errorFile.toFile())) {
                    zos.putNextEntry(new ZipEntry(errorFileName));
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                }

                // 添加 last.logs（如果存在）
                if (Files.exists(lastLogs)) {
                    try (FileInputStream fis = new FileInputStream(lastLogs.toFile())) {
                        zos.putNextEntry(new ZipEntry("last.logs"));
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                        zos.closeEntry();
                    }
                }
            }

            System.err.println("[ErrorReporter] 打包完成: " + zipFile.toAbsolutePath());

            // 3. 读取 zip 文件并 base64 编码
            byte[] zipBytes = Files.readAllBytes(zipFile);
            String base64Data = Base64.getEncoder().encodeToString(zipBytes);

            // 4. 检查 base64 编码后数据是否超过 10MB
            int base64SizeBytes = base64Data.getBytes(StandardCharsets.UTF_8).length;
            int maxSize = 10 * 1024 * 1024;

            if (base64SizeBytes > maxSize) {
                System.err.println("[ErrorReporter] 错误报告过大（base64: " + formatBytes(base64SizeBytes) + "），超过 10MB 限制，跳过上传");
                return;
            }

            // 5. 收集 CPU/GPU 信息并 POST 上传
            System.err.println("[ErrorReporter] 正在上传错误报告（" + formatBytes(base64SizeBytes) + "）...");

            String cpuInfo = collectCpuInfo();
            String gpuInfo = collectGpuInfo();

            String jsonBody = "{\"cpu\":\"" + escapeJson(cpuInfo) + "\",\"gpu\":\"" + escapeJson(gpuInfo) + "\",\"data\":\"" + base64Data + "\"}";

            URL url = new URL("http://192.238.232.239:4102/api/inputlog");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher-ErrorReporter/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            byte[] jsonBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            System.err.println("[ErrorReporter] 上传完成，服务器响应: HTTP " + responseCode);
            conn.disconnect();

        } catch (Exception e) {
            System.err.println("[ErrorReporter] 上传错误报告失败: " + e.getMessage());
        }
    }

    /**
     * 收集 CPU 信息
     */
    private static String collectCpuInfo() {
        String cpuInfo = System.getProperty("os.arch", "unknown");
        try {
            String cpuModel = System.getenv("PROCESSOR_IDENTIFIER");
            if (cpuModel != null && !cpuModel.isEmpty()) {
                cpuInfo = cpuModel;
            } else {
                Path cpuinfoPath = Paths.get("/proc/cpuinfo");
                if (Files.exists(cpuinfoPath)) {
                    String cpuinfo = new String(Files.readAllBytes(cpuinfoPath), StandardCharsets.UTF_8);
                    for (String line : cpuinfo.split("\\n")) {
                        if (line.startsWith("model name")) {
                            cpuInfo = line.substring(line.indexOf(':') + 1).trim();
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return cpuInfo;
    }

    /**
     * 收集 GPU 信息
     */
    private static String collectGpuInfo() {
        String gpuInfo = "unknown";
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("windows")) {
                String gpuEnv = System.getenv("GPU_DEVICE_NAME");
                if (gpuEnv != null && !gpuEnv.isEmpty()) {
                    gpuInfo = gpuEnv;
                }
            } else if (osName.contains("linux")) {
                Path nvidiaPath = Paths.get("/proc/driver/nvidia/version");
                if (Files.exists(nvidiaPath)) {
                    gpuInfo = "NVIDIA GPU";
                }
            }
        } catch (Exception ignored) {}
        return gpuInfo;
    }

    /**
     * 格式化字节数为可读字符串
     */
    private static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}

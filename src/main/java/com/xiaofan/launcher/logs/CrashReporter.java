package com.xiaofan.launcher.logs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
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
 * 崩溃报告生成器
 * 
 * 类似 Minecraft 的崩溃报告机制：
 * - 捕获所有未捕获的异常
 * - 生成完整的崩溃报告（系统信息、线程栈、JVM 参数、内存状态等）
 * - 保存到 crash/crash-2026-6-1-2-00-15.log 格式
 * 
 * 用法：
 *   CrashReporter.install(); // 在程序入口处调用一次
 */
public class CrashReporter implements Thread.UncaughtExceptionHandler {

    private static final String CRASH_DIR_NAME = "crash";
    private static final String CRASH_FILE_PREFIX = "crash-";
    private static final String CRASH_FILE_SUFFIX = ".log";
    private static final String LINE_SEPARATOR = "================================================================";
    private static final String SUB_SEPARATOR = "----------------------------------------------------------------";

    private final String jarDir;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashReporter(String jarDir) {
        this.jarDir = jarDir;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    /**
     * 安装崩溃报告器
     * 应该在程序入口处尽早调用
     * 
     * @param jarDir JAR 所在目录
     */
    public static synchronized void install(String jarDir) {
        // 设置默认未捕获异常处理器
        CrashReporter reporter = new CrashReporter(jarDir);
        Thread.setDefaultUncaughtExceptionHandler(reporter);

        // 也为当前线程设置
        Thread.currentThread().setUncaughtExceptionHandler(reporter);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        Path crashFile = null;
        try {
            // 生成崩溃报告
            String crashReport = generateCrashReport(t, e);

            // 保存到文件
            crashFile = saveCrashReport(crashReport);

            // 输出到控制台
            System.err.println("\n" + LINE_SEPARATOR);
            System.err.println("  Fan-ME-FRP-Launcher 发生崩溃!");
            System.err.println("  崩溃报告已保存至: " + crashFile.toAbsolutePath());
            System.err.println("  请将此文件发送给开发者以帮助排查问题。");
            System.err.println(LINE_SEPARATOR + "\n");
            System.err.println(crashReport);

        } catch (Exception ex) {
            // 崩溃报告生成失败时，至少输出原始异常
            System.err.println("\n严重错误: 程序发生未捕获异常，且崩溃报告生成失败!");
            System.err.println("原始异常:");
            e.printStackTrace(System.err);
            System.err.println("报告生成异常:");
            ex.printStackTrace(System.err);
        }

        // ====== 检查 EULA 并上传崩溃报告 ======
        if (crashFile != null) {
            try {
                uploadCrashReportIfEulaAgreed(crashFile);
            } catch (Exception ignored) {
                // 上传失败不影响程序退出
            }
        }

        // 调用默认处理器（如果有）
        if (defaultHandler != null && defaultHandler != this) {
            defaultHandler.uncaughtException(t, e);
        }

        // 确保程序退出
        System.exit(1);
    }

    /**
     * 生成完整的崩溃报告
     */
    private String generateCrashReport(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder(4096);

        // 头部信息
        sb.append(LINE_SEPARATOR).append("\n");
        sb.append("  Fan-ME-FRP-Launcher 崩溃报告\n");
        sb.append("  // 此报告由 CrashReporter 自动生成\n");
        sb.append(LINE_SEPARATOR).append("\n\n");

        // 1. 时间信息
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sb.append("时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("时间戳: ").append(System.currentTimeMillis()).append("\n\n");

        // 2. 异常信息
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 异常信息 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("异常类型: ").append(throwable.getClass().getName()).append("\n");
        sb.append("异常消息: ").append(throwable.getMessage() != null ? throwable.getMessage() : "null").append("\n");
        sb.append("发生线程: ").append(thread.getName()).append(" (ID=").append(thread.getId()).append(", 优先级=").append(thread.getPriority()).append(")\n");
        sb.append("线程组: ").append(thread.getThreadGroup() != null ? thread.getThreadGroup().getName() : "null").append("\n\n");

        // 3. 完整堆栈跟踪
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

        // 4. 线程转储（所有线程的堆栈）
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 线程转储 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        try {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
            for (ThreadInfo info : threadInfos) {
                sb.append("\"").append(info.getThreadName()).append("\"");
                sb.append(" ID=").append(info.getThreadId());
                sb.append(" 状态=").append(info.getThreadState());
                if (info.getLockName() != null) {
                    sb.append(" 锁=").append(info.getLockName());
                }
                if (info.getLockOwnerName() != null) {
                    sb.append(" 被线程=").append(info.getLockOwnerName()).append(" 持有");
                }
                sb.append("\n");

                StackTraceElement[] stack = info.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 20); i++) {
                    sb.append("\t").append(stack[i]).append("\n");
                }
                if (stack.length > 20) {
                    sb.append("\t... 还有 ").append(stack.length - 20).append(" 帧\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("  获取线程转储失败: ").append(e.getMessage()).append("\n\n");
        }

        // 5. 系统信息
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 系统信息 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            sb.append("操作系统: ").append(osBean.getName()).append(" ").append(osBean.getVersion()).append("\n");
            sb.append("系统架构: ").append(osBean.getArch()).append("\n");
            sb.append("可用处理器: ").append(osBean.getAvailableProcessors()).append("\n");
            sb.append("系统平均负载: ").append(String.format("%.2f", osBean.getSystemLoadAverage())).append("\n");

            // 尝试获取更多系统信息（通过反射，因为不是所有实现都支持）
            try {
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsBean =
                        (com.sun.management.OperatingSystemMXBean) osBean;
                    sb.append("总物理内存: ").append(formatBytes(sunOsBean.getTotalPhysicalMemorySize())).append("\n");
                    sb.append("可用物理内存: ").append(formatBytes(sunOsBean.getFreePhysicalMemorySize())).append("\n");
                    sb.append("总交换空间: ").append(formatBytes(sunOsBean.getTotalSwapSpaceSize())).append("\n");
                    sb.append("可用交换空间: ").append(formatBytes(sunOsBean.getFreeSwapSpaceSize())).append("\n");
                }
            } catch (Exception ignored) {}

            // Java 系统属性
            sb.append("\nJava 信息:\n");
            sb.append("  Java 版本: ").append(System.getProperty("java.version", "未知")).append("\n");
            sb.append("  Java 供应商: ").append(System.getProperty("java.vendor", "未知")).append("\n");
            sb.append("  Java VM 名称: ").append(System.getProperty("java.vm.name", "未知")).append("\n");
            sb.append("  Java VM 版本: ").append(System.getProperty("java.vm.version", "未知")).append("\n");
            sb.append("  Java VM 供应商: ").append(System.getProperty("java.vm.vendor", "未知")).append("\n");
            sb.append("  Java 运行时名称: ").append(System.getProperty("java.runtime.name", "未知")).append("\n");
            sb.append("  Java 类路径: ").append(System.getProperty("java.class.path", "未知")).append("\n");
            sb.append("  Java 库路径: ").append(System.getProperty("java.library.path", "未知")).append("\n");
            sb.append("  文件编码: ").append(System.getProperty("file.encoding", "未知")).append("\n");
            sb.append("  用户目录: ").append(System.getProperty("user.dir", "未知")).append("\n");
            sb.append("  用户语言: ").append(System.getProperty("user.language", "未知")).append("\n");
            sb.append("  用户国家: ").append(System.getProperty("user.country", "未知")).append("\n");
            sb.append("  时区: ").append(System.getProperty("user.timezone", "未知")).append("\n");

        } catch (Exception e) {
            sb.append("  获取系统信息失败: ").append(e.getMessage()).append("\n");
        }
        sb.append("\n");

        // 6. JVM 参数
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- JVM 参数 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        try {
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            sb.append("JVM 启动参数:\n");
            for (String arg : runtimeBean.getInputArguments()) {
                sb.append("  ").append(arg).append("\n");
            }
            sb.append("\n");
            sb.append("JVM 正常运行时间: ").append(formatUptime(runtimeBean.getUptime())).append("\n");
            sb.append("JVM 名称: ").append(runtimeBean.getVmName()).append("\n");
            sb.append("JVM 供应商: ").append(runtimeBean.getVmVendor()).append("\n");
            sb.append("JVM 规范版本: ").append(runtimeBean.getSpecVersion()).append("\n");
        } catch (Exception e) {
            sb.append("  获取 JVM 参数失败: ").append(e.getMessage()).append("\n");
        }
        sb.append("\n");

        // 7. 内存信息
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 内存信息 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            sb.append("堆内存:\n");
            sb.append("  已用: ").append(formatBytes(memoryBean.getHeapMemoryUsage().getUsed())).append("\n");
            sb.append("  已提交: ").append(formatBytes(memoryBean.getHeapMemoryUsage().getCommitted())).append("\n");
            sb.append("  最大值: ").append(formatBytes(memoryBean.getHeapMemoryUsage().getMax())).append("\n");
            sb.append("  初始值: ").append(formatBytes(memoryBean.getHeapMemoryUsage().getInit())).append("\n\n");

            sb.append("非堆内存:\n");
            sb.append("  已用: ").append(formatBytes(memoryBean.getNonHeapMemoryUsage().getUsed())).append("\n");
            sb.append("  已提交: ").append(formatBytes(memoryBean.getNonHeapMemoryUsage().getCommitted())).append("\n");
            sb.append("  最大值: ").append(formatBytes(memoryBean.getNonHeapMemoryUsage().getMax())).append("\n");
            sb.append("  初始值: ").append(formatBytes(memoryBean.getNonHeapMemoryUsage().getInit())).append("\n\n");

            // Runtime 内存
            Runtime rt = Runtime.getRuntime();
            sb.append("Runtime 内存:\n");
            sb.append("  总内存: ").append(formatBytes(rt.totalMemory())).append("\n");
            sb.append("  空闲内存: ").append(formatBytes(rt.freeMemory())).append("\n");
            sb.append("  已用内存: ").append(formatBytes(rt.totalMemory() - rt.freeMemory())).append("\n");
            sb.append("  最大内存: ").append(formatBytes(rt.maxMemory())).append("\n");

            // 计算内存使用率
            long usedMem = rt.totalMemory() - rt.freeMemory();
            long maxMem = rt.maxMemory();
            if (maxMem > 0) {
                double usagePercent = (double) usedMem / maxMem * 100;
                sb.append("  内存使用率: ").append(String.format("%.1f%%", usagePercent)).append("\n");
            }
        } catch (Exception e) {
            sb.append("  获取内存信息失败: ").append(e.getMessage()).append("\n");
        }
        sb.append("\n");

        // 8. 系统属性（筛选重要项）
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 系统属性 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        String[] importantProps = {
            "os.name", "os.version", "os.arch",
            "java.version", "java.vendor", "java.vm.name", "java.vm.version",
            "java.runtime.version", "java.class.version",
            "file.separator", "path.separator", "line.separator",
            "user.name", "user.home", "user.dir",
            "java.io.tmpdir",
            "sun.arch.data.model",
            "java.library.path"
        };
        for (String prop : importantProps) {
            String value = System.getProperty(prop);
            if (value != null) {
                sb.append("  ").append(prop).append(" = ").append(value).append("\n");
            }
        }
        sb.append("\n");

        // 9. 环境变量（筛选 PATH、JAVA_HOME 等）
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 环境变量 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        String[] importantEnv = {
            "PATH", "JAVA_HOME", "JRE_HOME", "CLASSPATH",
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "OS", "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER",
            "NUMBER_OF_PROCESSORS", "COMPUTERNAME"
        };
        for (String env : importantEnv) {
            String value = System.getenv(env);
            if (value != null) {
                sb.append("  ").append(env).append(" = ").append(value).append("\n");
            }
        }
        sb.append("\n");

        // 10. 应用程序信息
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("-- 应用程序信息 --\n");
        sb.append(SUB_SEPARATOR).append("\n");
        sb.append("  应用名称: Fan-ME-FRP-Launcher\n");
        sb.append("  JAR 目录: ").append(jarDir).append("\n");
        sb.append("  崩溃报告版本: 1.0\n");
        sb.append("\n");

        // 尾部
        sb.append(LINE_SEPARATOR).append("\n");
        sb.append("  -- 崩溃报告结束 --\n");
        sb.append(LINE_SEPARATOR).append("\n");

        return sb.toString();
    }

    /**
     * 保存崩溃报告到文件
     * 格式: crash/crash-2026-6-1-2-00-15.log
     */
    private Path saveCrashReport(String report) {
        try {
            // 创建 crash 目录
            Path crashDir = Paths.get(jarDir, CRASH_DIR_NAME);
            Files.createDirectories(crashDir);

            // 生成文件名: crash-2026-6-1-2-00-15.log
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-M-d-H-mm-ss", Locale.US);
            String timestamp = sdf.format(new Date());
            String fileName = CRASH_FILE_PREFIX + timestamp + CRASH_FILE_SUFFIX;

            Path crashFile = crashDir.resolve(fileName);

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(crashFile.toFile())) {
                fos.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            return crashFile;

        } catch (Exception e) {
            System.err.println("无法保存崩溃报告: " + e.getMessage());
            // 尝试在用户目录下保存
            try {
                Path fallbackPath = Paths.get(System.getProperty("user.home", "."), 
                    CRASH_DIR_NAME + "-" + CRASH_FILE_PREFIX + System.currentTimeMillis() + CRASH_FILE_SUFFIX);
                Files.createDirectories(fallbackPath.getParent());
                Files.write(fallbackPath, report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return fallbackPath;
            } catch (Exception e2) {
                System.err.println("备用保存也失败: " + e2.getMessage());
                return null;
            }
        }
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
     * 格式化运行时间
     */
    private static String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        return String.format("%d 天 %d 小时 %d 分钟 %d 秒",
            days, hours % 24, minutes % 60, seconds % 60);
    }

    // ==================== EULA 检查与崩溃报告上传 ====================

    /**
     * 检查 EULA 是否同意，如果同意则将崩溃报告和 last.logs 打包上传
     * 
     * 流程：
     * 1. 检查 res/eula.txt 是否存在且内容为 eula=true
     * 2. 如果同意，将 crash 文件和 logs/last.logs 打包成 zip
     * 3. 放在 JAR 同目录的 tmp 文件夹
     * 4. 将 zip 文件 base64 编码
     * 5. 检查编码后数据是否超过 10MB
     * 6. 如果没超过，POST 到 https://frpc.xiaofanshop.cn/api/inputlog
     */
    private void uploadCrashReportIfEulaAgreed(Path crashFile) {
        // 1. 检查 EULA
        Path eulaFile = Paths.get(jarDir, "res", "eula.txt");
        if (!Files.exists(eulaFile)) {
            System.err.println("[CrashReporter] eula.txt 不存在，跳过崩溃报告上传");
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
                System.err.println("[CrashReporter] EULA 未同意，跳过崩溃报告上传");
                return;
            }
        } catch (Exception e) {
            System.err.println("[CrashReporter] 读取 eula.txt 失败: " + e.getMessage());
            return;
        }

        System.err.println("[CrashReporter] EULA 已同意，正在打包崩溃报告...");

        // 2. 打包 crash 文件和 last.logs 为 zip
        Path tmpDir = Paths.get(jarDir, "tmp");
        Path logsDir = Paths.get(jarDir, "logs");
        Path lastLogs = logsDir.resolve("last.logs");

        // 生成 zip 文件名（使用 crash 文件名的时间戳）
        String crashFileName = crashFile.getFileName().toString();
        String zipName = crashFileName.replace(CRASH_FILE_SUFFIX, ".zip");
        Path zipFile = tmpDir.resolve(zipName);

        try {
            Files.createDirectories(tmpDir);

            try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {

                // 添加崩溃报告文件
                try (FileInputStream fis = new FileInputStream(crashFile.toFile())) {
                    zos.putNextEntry(new ZipEntry(crashFileName));
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

            System.err.println("[CrashReporter] 打包完成: " + zipFile.toAbsolutePath());

            // 3. 读取 zip 文件并 base64 编码
            byte[] zipBytes = Files.readAllBytes(zipFile);
            String base64Data = Base64.getEncoder().encodeToString(zipBytes);

            // 4. 检查 base64 编码后数据是否超过 10MB
            // base64 编码后大小约为原始大小的 4/3，所以检查 base64 字符串的字节数
            int base64SizeBytes = base64Data.getBytes(StandardCharsets.UTF_8).length;
            int maxSize = 10 * 1024 * 1024; // 10MB

            if (base64SizeBytes > maxSize) {
                System.err.println("[CrashReporter] 崩溃报告过大（base64: " + formatBytes(base64SizeBytes) + "），超过 10MB 限制，跳过上传");
                return;
            }

            // 5. POST 到 https://frpc.xiaofanshop.cn/api/inputlog
            System.err.println("[CrashReporter] 正在上传崩溃报告（" + formatBytes(base64SizeBytes) + "）...");

            // 收集 CPU 信息
            String cpuInfo = System.getProperty("os.arch", "unknown");
            try {
                String cpuModel = System.getenv("PROCESSOR_IDENTIFIER");
                if (cpuModel != null && !cpuModel.isEmpty()) {
                    cpuInfo = cpuModel;
                } else {
                    // Linux: 读取 /proc/cpuinfo
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

            // 收集 GPU 信息
            String gpuInfo = "unknown";
            try {
                // Windows: 尝试读取注册表或使用 wmic
                String osName = System.getProperty("os.name", "").toLowerCase();
                if (osName.contains("windows")) {
                    // 尝试读取环境变量（某些系统可能有）
                    String gpuEnv = System.getenv("GPU_DEVICE_NAME");
                    if (gpuEnv != null && !gpuEnv.isEmpty()) {
                        gpuInfo = gpuEnv;
                    }
                } else if (osName.contains("linux")) {
                    // Linux: 尝试读取 /proc/driver/nvidia/version 或 lspci
                    Path nvidiaPath = Paths.get("/proc/driver/nvidia/version");
                    if (Files.exists(nvidiaPath)) {
                        gpuInfo = "NVIDIA GPU";
                    }
                }
            } catch (Exception ignored) {}

            String jsonBody = "{\"cpu\":\"" + escapeJson(cpuInfo) + "\",\"gpu\":\"" + escapeJson(gpuInfo) + "\",\"data\":\"" + base64Data + "\"}";

            URL url = new URL("http://192.238.232.239:4102/api/inputlog");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher-CrashReporter/1.0");
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
            System.err.println("[CrashReporter] 上传完成，服务器响应: HTTP " + responseCode);
            conn.disconnect();

        } catch (Exception e) {
            System.err.println("[CrashReporter] 上传崩溃报告失败: " + e.getMessage());
        }
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

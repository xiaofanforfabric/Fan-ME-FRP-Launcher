package com.xiaofan.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xiaofan.launcher.api.GuiApiServer;
import com.xiaofan.launcher.frpc.EasyStartup;
import com.xiaofan.launcher.frpc.FrpcManager;
import com.xiaofan.launcher.frpc.JarUpdater;
import com.xiaofan.launcher.logs.CrashReporter;
import com.xiaofan.launcher.logs.LogArchiver;
import com.xiaofan.launcher.logs.SystemOutRedirector;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;

/**
 * Fan-ME-FRP-Launcher 主入口
 * 
 * 命令行用法:
 *   java -jar Fan-ME-FRP-Launcher.jar -c config.ini    以 FRPC 客户端模式启动（支持 .ini/.toml/.yaml/.yml/.json）
 *   java -jar Fan-ME-FRP-Launcher.jar -t <runId> -p <proxyId>  快捷启动模式（通过 API 获取配置）
 *   java -jar Fan-ME-FRP-Launcher.jar --no-gui          无头模式，仅启动 HTTP API 服务器（适合 Termux/无 JavaFX 环境）
 *   java -jar Fan-ME-FRP-Launcher.jar                   以 GUI 模式启动（需要 JavaFX）
 * 
 * 平台支持:
 *   - Windows: 使用 JNA 加载动态库 frpc_jna.dll
 *   - Linux:   使用 JNA 加载动态库 linux_*.so
 *   - Android: 使用 ProcessBuilder 启动 frpc 二进制可执行文件（功能可能不受完全支持）
 * 
 * 示例:
 *   java -jar Fan-ME-FRP-Launcher.jar -c frpc.ini
 *   java -jar Fan-ME-FRP-Launcher.jar -c frpc.toml
 *   java -jar Fan-ME-FRP-Launcher.jar -t 890bb47ac5eb4f72bb43a7c240036661 -p 138425
 *   java -jar Fan-ME-FRP-Launcher.jar --no-gui
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** JAR 所在目录，在 main 开头初始化 */
    private static String jarDir = ".";

    public static void main(String[] args) {
        // ====== 第〇步：获取 JAR 目录 ======
        jarDir = getJarDir();

        // ====== 第一步：安装崩溃报告器 ======
        // 必须在最前面安装，确保能捕获所有未捕获异常
        CrashReporter.install(jarDir);

        // ====== 第二步：准备日志文件 ======
        // 删除旧 last.logs，注册关闭归档钩子
        Path logFilePath = LogArchiver.prepare(jarDir);

        // 动态设置 Logback FileAppender 的文件路径为 last.logs
        try {
            Files.createDirectories(logFilePath.getParent());
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            FileAppender<?> fileAppender = (FileAppender<?>) loggerContext.getLogger("ROOT").getAppender("FILE");
            if (fileAppender != null) {
                fileAppender.setFile(logFilePath.toAbsolutePath().toString());
                fileAppender.start();
            }
        } catch (Exception e) {
            // 如果 Logback 配置失败，静默处理
        }

        // ====== 第三步：安装 System.out/err 重定向器 ======
        // 将 System.out.println() 和 System.err.println() 同时输出到控制台和日志文件
        SystemOutRedirector.install();

        log.info("Fan-ME-FRP-Launcher 启动中...");

        // ====== 第四步：检查 JAR 自身版本更新 ======
        Path resDir = Paths.get(jarDir, "res");
        boolean updated = JarUpdater.checkAndUpdate(jarDir, resDir);
        if (updated) {
            log.info("JAR 已更新，请重新启动新版本");
            System.exit(0);
        }

        // ====== 第五步：解析命令行参数 ======
        boolean noGui = false;
        boolean debugMode = false;
        if (args.length > 0) {
            String configPath = null;
            String runId = null;
            int proxyId = -1;

            for (int i = 0; i < args.length; i++) {
                if ("-c".equals(args[i]) && i + 1 < args.length) {
                    configPath = args[i + 1];
                    break;
                }
                if ("-t".equals(args[i]) && i + 1 < args.length) {
                    runId = args[i + 1];
                }
                if ("-p".equals(args[i]) && i + 1 < args.length) {
                    try {
                        proxyId = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException e) {
                        log.error("-p 参数必须是数字");
                        System.exit(1);
                    }
                }
                if ("--no-gui".equals(args[i])) {
                    noGui = true;
                }
                if ("--debug".equals(args[i])) {
                    debugMode = true;
                }
            }

            // 如果启用了调试模式，设置日志级别为 DEBUG
            if (debugMode) {
                enableDebugMode();
            }

            if (configPath != null) {
                runFrpcClient(configPath);
                return;
            }

            if (runId != null && proxyId > 0) {
                runEasyStartup(runId, proxyId);
                return;
            }

            if (runId != null || proxyId > 0) {
                log.error("快捷启动需要同时指定 -t <runId> 和 -p <proxyId>");
                System.exit(1);
            }
        }

        if (noGui) {
            runHeadlessServer(debugMode);
            return;
        }

        // GUI 模式
        try {
            Class<?> guiClass = Class.forName("com.xiaofan.launcher.GuiMain");
            guiClass.getMethod("launchGui", String[].class).invoke(null, (Object) args);
        } catch (ClassNotFoundException e) {
            log.error("GUI 模式需要 JavaFX 运行时组件");
            System.exit(1);
        } catch (Exception e) {
            log.error("启动 GUI 失败", e);
            System.exit(1);
        }
    }

    /**
     * 启用调试模式
     * 将日志级别从 INFO 切换为 DEBUG，输出更详细的运行信息
     */
    private static void enableDebugMode() {
        try {
            // 通过 Logback 的 LoggerContext 动态修改根日志级别为 DEBUG
            ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);

            // 同时将第三方库的日志级别也调整为 DEBUG
            rootLogger.getLoggerContext().getLoggerList().forEach(logger -> {
                if (logger.getName().startsWith("com.xiaofan")) {
                    ((ch.qos.logback.classic.Logger) logger).setLevel(ch.qos.logback.classic.Level.DEBUG);
                }
            });

            log.debug("调试模式已启用 - 日志级别已切换为 DEBUG");
            log.debug("JAR 目录: {}", jarDir);
            log.debug("Java 版本: {}", System.getProperty("java.version"));
            log.debug("操作系统: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
        } catch (Exception e) {
            System.err.println("[Main] 启用调试模式失败: " + e.getMessage());
        }
    }

    private static String getJarDir() {
        try {
            String path = Main.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File jarFile = new File(path);
            if (jarFile.isFile()) {
                return jarFile.getParentFile().getAbsolutePath();
            }
        } catch (Exception e) {
            // ignore
        }
        return ".";
    }

    private static void runEasyStartup(String runId, int proxyId) {
        log.info("Fan-ME-FRP Launcher - 快捷启动模式");
        log.info("RunId: {}, ProxyId: {}", runId, proxyId);

        FrpcManager manager = FrpcManager.getInstance();
        if (!manager.init()) {
            log.error("FRPC 初始化失败");
            System.exit(1);
        }

        EasyStartup easyStartup = new EasyStartup();
        easyStartup.registerCleanupHook();

        Path configFile = easyStartup.execute(runId, proxyId);
        if (configFile == null) {
            log.error("获取隧道配置失败");
            System.exit(1);
        }

        log.info("frpc 版本: {}", manager.getVersion());
        log.info("启动方式: {}", manager.isExecMode() ? "exec（二进制可执行文件）" : "JNA（动态库）");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭 FRPC 客户端...");
            if (manager.stop()) {
                log.info("FRPC 客户端已关闭");
            }
        }));

        if (manager.start(configFile.toAbsolutePath().toString())) {
            log.info("FRPC 客户端已启动，按 Ctrl+C 停止");
            try {
                while (manager.isRunning()) {
                    Thread.sleep(1000);
                }
                log.info("FRPC 客户端已停止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            log.error("FRPC 客户端启动失败");
            System.exit(1);
        }
    }

    private static void runHeadlessServer(boolean debugMode) {
        log.info("Fan-ME-FRP Launcher - 无头模式（仅 HTTP API）");

        log.info("正在初始化 frpc 运行环境...");
        FrpcManager frpcManager = FrpcManager.getInstance();
        if (!frpcManager.init(true)) {
            log.warn("frpc 依赖初始化失败，功能可能受限");
        } else {
            log.info("frpc 运行环境就绪");
        }

        Path resDir = Paths.get(jarDir, "res");
        Path indexDir = resDir.resolve("index");
        try {
            Files.createDirectories(indexDir);
            Path loginHtml = indexDir.resolve("login.html");
            if (!Files.exists(loginHtml)) {
                Path existingLogin = Paths.get(jarDir, "res", "index", "login.html");
                if (Files.exists(existingLogin)) {
                    Files.copy(existingLogin, loginHtml, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("已复制前端资源");
                } else {
                    log.warn("未找到前端资源，API 服务仍可正常使用");
                }
            }
        } catch (IOException e) {
            log.warn("准备前端资源失败", e);
        }

        GuiApiServer apiServer = new GuiApiServer();
        apiServer.setStaticRoot(indexDir);
        apiServer.setDebugMode(debugMode);
        apiServer.start();

        log.info("HTTP API 服务已启动: http://127.0.0.1:1025");
        log.info("按 Ctrl+C 停止服务");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭服务...");
            apiServer.stop();
            log.info("服务已关闭");
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runFrpcClient(String configPath) {
        File configFile = new File(configPath);
        log.info("Fan-ME-FRP Launcher - FRPC 客户端模式");
        log.info("配置文件: {}", configFile.getAbsolutePath());

        if (!configFile.exists()) {
            log.error("配置文件不存在: {}", configFile.getAbsolutePath());
            System.exit(1);
        }

        String name = configFile.getName().toLowerCase();
        if (!name.endsWith(".ini") && !name.endsWith(".toml") 
            && !name.endsWith(".yaml") && !name.endsWith(".yml") 
            && !name.endsWith(".json")) {
            log.warn("不支持的配置文件格式，支持的格式: .ini .toml .yaml .yml .json");
            log.warn("frp 将尝试自动检测格式...");
        }

        FrpcManager manager = FrpcManager.getInstance();
        if (!manager.init()) {
            log.error("FRPC 初始化失败");
            System.exit(1);
        }

        log.info("frpc 版本: {}", manager.getVersion());
        log.info("启动方式: {}", manager.isExecMode() ? "exec（二进制可执行文件）" : "JNA（动态库）");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭 FRPC 客户端...");
            if (manager.stop()) {
                log.info("FRPC 客户端已关闭");
            }
        }));

        if (manager.start(configFile.getAbsolutePath())) {
            log.info("FRPC 客户端已启动，按 Ctrl+C 停止");
            try {
                while (manager.isRunning()) {
                    Thread.sleep(1000);
                }
                log.info("FRPC 客户端已停止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            log.error("FRPC 客户端启动失败");
            System.exit(1);
        }
    }
}

package com.xiaofan.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.xiaofan.launcher.api.GuiApiServer;
import com.xiaofan.launcher.errors.JavaScriptErrorException;
import com.xiaofan.launcher.errors.JavaScriptWarnException;
import com.xiaofan.launcher.frpc.FrpcManager;
import com.xiaofan.launcher.logs.ErrorReporter;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.util.logging.Logger;

/**
 * GUI 主入口（需要 JavaFX）
 * 通过 Main 类的反射调用
 * 
 * 启动流程:
 * 1. 初始化 frpc 依赖（下载 frpc 动态库/二进制文件）
 * 2. 从 OSS 下载 index.zip 前端资源
 * 3. 解压到 res/index/ 目录
 * 4. 启动 GUI API 服务 (127.0.0.1:1023)
 * 5. 加载 http://127.0.0.1:1023/login.html
 */
public class GuiMain {

    private GuiApiServer apiServer;
    private static final String INDEX_ZIP_URL = "http://oss.xiaofanshop.cn/index.zip";
    private static final String INDEX_ZIP_URL_CF = "https://oss.cf.xiaofanshop.cn/index.zip";
    private static final String INDEX_ZIP_URL_DONATE = "https://oss.xiaoli.top/index.zip";

    public static void launchGui(String[] args) {
        // 从 args 中解析 --debug 参数
        boolean debugMode = false;
        if (args != null) {
            for (String arg : args) {
                if ("--debug".equals(arg)) {
                    debugMode = true;
                    break;
                }
            }
        }
        final boolean finalDebugMode = debugMode;
        // 使用 JFXPanel 初始化 JavaFX 工具包，避免继承 Application 导致的 GTK 问题
        new JFXPanel();
        Platform.runLater(() -> {
            try {
                new GuiMain().start(finalDebugMode);
            } catch (Exception e) {
                System.err.println("GUI 启动失败: " + e.getMessage());
                e.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
    }

    public void start() {
        start(false);
    }

    public void start(boolean debugMode) {
        try {
            // ====== 第〇步：先初始化 frpc 依赖（下载动态库/二进制），再搞其他 ======
            System.out.println("正在初始化 frpc 运行环境...");
            FrpcManager frpcManager = FrpcManager.getInstance();
            if (!frpcManager.init(true)) {
                System.err.println("错误: frpc 依赖初始化失败，功能可能受限");
                // 不退出，让用户至少能看界面
            } else {
                System.out.println("frpc 运行环境就绪");
            }

            // 1. 准备前端资源
            Path resDir = getResDir();
            Path indexDir = resDir.resolve("index");
            ensureFrontendResources(indexDir);

            // 2. 启动 GUI API 服务（静态资源映射到 res/index/）
            apiServer = new GuiApiServer();
            apiServer.setStaticRoot(indexDir);
            apiServer.setDebugMode(debugMode);
            apiServer.start();

            // 3. 在 JavaFX 线程中创建窗口
            Platform.runLater(() -> {
                try {
                    WebView webView = new WebView();
                    webView.getEngine().setJavaScriptEnabled(true);
                    webView.getEngine().setUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36 " +
                        "Fan-ME-FRP-Launcher/1.0"
                    );

                    // 设置 JavaScript 控制台日志捕获
                    final Logger jsLogger = Logger.getLogger("BrowserJS");
                    // 创建错误报告器，用于生成 JS 错误报告
                    final ErrorReporter errorReporter = new ErrorReporter(getJarDir());
                    // 使用 confirmHandler 捕获日志（比 setOnAlert 更可靠）
                    webView.getEngine().setConfirmHandler(message -> {
                        if (message.startsWith("[JS_LOG]")) {
                            jsLogger.info(message.substring(8));
                        } else if (message.startsWith("[JS_WARN]")) {
                            jsLogger.warning(message.substring(9));
                            // 生成警告错误报告
                            String jsMsg = message.substring(9);
                            JavaScriptWarnException warnEx = new JavaScriptWarnException(jsMsg);
                            Path reportPath = errorReporter.reportError(warnEx, "JavaScript 警告: " + jsMsg);
                            if (reportPath != null) {
                                System.err.println("[GuiMain] JavaScript 警告报告已保存至: " + reportPath.toAbsolutePath());
                            }
                        } else if (message.startsWith("[JS_ERROR]")) {
                            jsLogger.severe(message.substring(10));
                            // 生成错误报告
                            String jsMsg = message.substring(10);
                            JavaScriptErrorException errorEx = new JavaScriptErrorException(jsMsg);
                            Path reportPath = errorReporter.reportError(errorEx, "JavaScript 错误: " + jsMsg);
                            if (reportPath != null) {
                                System.err.println("[GuiMain] JavaScript 错误报告已保存至: " + reportPath.toAbsolutePath());
                            }
                        } else {
                            jsLogger.info(message);
                        }
                        return true; // 模拟用户点击确认
                    });
                    // 同时捕获 alert（作为备用）
                    webView.getEngine().setOnAlert(event -> {
                        String msg = event.getData();
                        if (msg.startsWith("[JS_LOG]")) {
                            jsLogger.info(msg.substring(8));
                        } else if (msg.startsWith("[JS_WARN]")) {
                            jsLogger.warning(msg.substring(9));
                            // 生成警告错误报告
                            String jsMsg = msg.substring(9);
                            JavaScriptWarnException warnEx = new JavaScriptWarnException(jsMsg);
                            Path reportPath = errorReporter.reportError(warnEx, "JavaScript 警告: " + jsMsg);
                            if (reportPath != null) {
                                System.err.println("[GuiMain] JavaScript 警告报告已保存至: " + reportPath.toAbsolutePath());
                            }
                        } else if (msg.startsWith("[JS_ERROR]")) {
                            jsLogger.severe(msg.substring(10));
                            // 生成错误报告
                            String jsMsg = msg.substring(10);
                            JavaScriptErrorException errorEx = new JavaScriptErrorException(jsMsg);
                            Path reportPath = errorReporter.reportError(errorEx, "JavaScript 错误: " + jsMsg);
                            if (reportPath != null) {
                                System.err.println("[GuiMain] JavaScript 错误报告已保存至: " + reportPath.toAbsolutePath());
                            }
                        } else {
                            jsLogger.info(msg);
                        }
                    });

                    // 监听页面加载状态（包括失败和 CSS 解析错误）
                    webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                        String url = webView.getEngine().getLocation();
                        switch (newState) {
                            case RUNNING:
                                System.out.println("[WebView] 开始加载: " + url);
                                break;
                            case SUCCEEDED:
                                System.out.println("[WebView] 加载成功: " + url);
                                try {
                                    // 注入 console 日志捕获脚本（使用 confirm() 传递到 Java 端）
                                    // 注意：不要使用 // 注释，Java 字符串中的 // 在某些编译器下会被当作行注释导致截断
                                    String script =
                                        "(function() {" +
                                        "var logger = window.console;" +
                                        "if (!logger) return;" +
                                        "var originalLog = logger.log;" +
                                        "var originalWarn = logger.warn;" +
                                        "var originalError = logger.error;" +
                                        "var originalInfo = logger.info;" +
                                        "function formatArgs(args) {" +
                                        "var parts = [];" +
                                        "for (var i = 0; i < args.length; i++) {" +
                                        "try {" +
                                        "if (typeof args[i] === 'object') {" +
                                        "parts.push(JSON.stringify(args[i]));" +
                                        "} else {" +
                                        "parts.push(String(args[i]));" +
                                        "}" +
                                        "} catch(e) {" +
                                        "parts.push('[object]');" +
                                        "}" +
                                        "}" +
                                        "return parts.join(' ');" +
                                        "}" +
                                        "logger.log = function() {" +
                                        "confirm('[JS_LOG]' + formatArgs(arguments));" +
                                        "if (originalLog) originalLog.apply(logger, arguments);" +
                                        "};" +
                                        "logger.info = function() {" +
                                        "confirm('[JS_LOG]' + formatArgs(arguments));" +
                                        "if (originalInfo) originalInfo.apply(logger, arguments);" +
                                        "};" +
                                        "logger.warn = function() {" +
                                        "confirm('[JS_WARN]' + formatArgs(arguments));" +
                                        "if (originalWarn) originalWarn.apply(logger, arguments);" +
                                        "};" +
                                        "logger.error = function() {" +
                                        "confirm('[JS_ERROR]' + formatArgs(arguments));" +
                                        "if (originalError) originalError.apply(logger, arguments);" +
                                        "};" +
                                        "window.onerror = function(msg, source, line, col, error) {" +
                                        "confirm('[JS_ERROR]Uncaught: ' + msg + ' at ' + source + ':' + line);" +
                                        "return true;" +
                                        "};" +
                                        "confirm('[JS_LOG]JavaScript console capture enabled');" +
                                        "})();";
                                    webView.getEngine().executeScript(script);
                                    System.out.println("[GuiMain] JavaScript 控制台日志捕获已注入");
                                } catch (Exception e) {
                                    System.err.println("[GuiMain] 注入控制台日志捕获脚本失败: " + e.getMessage());
                                }

                                break;
                            case FAILED:
                                // 捕获页面加载失败的错误（包括 CSS 资源加载失败、404 等）
                                Throwable exception = webView.getEngine().getLoadWorker().getException();
                                String errorMsg = (exception != null) ? exception.getMessage() : "未知错误";
                                System.err.println("[WebView] 加载失败: " + url + " - " + errorMsg);
                                if (exception != null) {
                                    exception.printStackTrace(System.err);
                                }
                                break;
                            default:
                                break;
                        }
                    });


                    webView.getEngine().load("http://127.0.0.1:1025/login.html");

                    Scene scene = new Scene(webView, 1200, 800);

                    Stage primaryStage = new Stage();
                    primaryStage.setTitle("Fan-ME-FRP Launcher");
                    primaryStage.setScene(scene);
                    primaryStage.setMinWidth(900);
                    primaryStage.setMinHeight(600);

                    primaryStage.setOnCloseRequest(e -> {
                        if (apiServer != null) {
                            apiServer.stop();
                        }
                        Platform.exit();
                        System.exit(0);
                    });

                    primaryStage.show();
                } catch (Exception e) {
                    System.err.println("创建窗口失败: " + e.getMessage());
                    e.printStackTrace();
                    Platform.exit();
                    System.exit(1);
                }
            });

        } catch (Exception e) {
            System.err.println("GUI 启动失败: " + e.getMessage());
            e.printStackTrace();
            Platform.exit();
            System.exit(1);
        }
    }


    /**
     * 确保前端资源就绪
     * 如果 res/index/ 下没有 login.html，则从 OSS 下载 index.zip 并解压
     */
    private void ensureFrontendResources(Path indexDir) throws IOException {
        // 检查是否已有 login.html
        Path loginHtml = indexDir.resolve("login.html");
        if (Files.exists(loginHtml)) {
            System.out.println("前端资源已存在: " + indexDir.toAbsolutePath());
            return;
        }

        // 确保目录存在
        Files.createDirectories(indexDir);

        // 从 OSS 下载 index.zip
        System.out.println("正在下载前端资源包...");
        Path zipFile = indexDir.resolve("index.zip");
        boolean downloaded = downloadIndexZip(zipFile);

        if (!downloaded || !Files.exists(zipFile)) {
            System.err.println("警告: 前端资源下载失败，使用内置 login.html");
            // 如果下载失败，尝试从 classpath 复制内置的 login.html
            extractBuiltinLogin(indexDir);
            return;
        }

        // 解压
        System.out.println("正在解压前端资源...");
        try {
            unzip(zipFile, indexDir);
            // 删除 zip 包
            Files.deleteIfExists(zipFile);
            System.out.println("前端资源解压完成: " + indexDir.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("解压失败: " + e.getMessage());
            // 解压失败时尝试使用内置页面
            extractBuiltinLogin(indexDir);
        }
    }

    /**
     * 从 OSS 下载 index.zip
     * 依次尝试多个节点
     */
    private boolean downloadIndexZip(Path targetPath) {
        String[] urls = {
            INDEX_ZIP_URL_CF,       // CF R2 OSS 存储节点（稳定）
            INDEX_ZIP_URL_DONATE,   // xiaoli 捐赠节点（速度快）
            INDEX_ZIP_URL           // 官方 CF 穿透节点
        };

        for (String url : urls) {
            System.out.print("  尝试下载: " + url + " ... ");
            try {
                if (downloadFile(url, targetPath)) {
                    System.out.println("OK");
                    return true;
                }
            } catch (Exception e) {
                System.out.println("失败: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * 下载文件
     */
    private boolean downloadFile(String fileUrl, Path targetPath) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            return false;
        }

        try (InputStream inputStream = conn.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(targetPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long contentLength = conn.getContentLengthLong();
            int lastPercent = -1;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (contentLength > 0) {
                    int percent = (int) (totalRead * 100 / contentLength);
                    if (percent != lastPercent) {
                        System.out.printf("\r    下载进度: %d%%", percent);
                        lastPercent = percent;
                    }
                }
            }
            System.out.println();
        } finally {
            conn.disconnect();
        }

        return Files.exists(targetPath) && Files.size(targetPath) > 0;
    }

    /**
     * 解压 ZIP 文件
     * 自动检测并剥离 zip 包内的顶层目录（如 index/xxx → xxx）
     */
    private void unzip(Path zipPath, Path destDir) throws IOException {
        // 先扫描所有条目，检测是否有公共顶层目录
        String topDir = detectTopDir(zipPath);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // 剥离顶层目录
                String relativeName;
                if (topDir != null && entryName.startsWith(topDir)) {
                    relativeName = entryName.substring(topDir.length());
                } else {
                    relativeName = entryName;
                }

                // 跳过空路径（顶层目录本身）
                if (relativeName.isEmpty() || relativeName.equals("/")) {
                    zis.closeEntry();
                    continue;
                }

                Path entryPath = destDir.resolve(relativeName).normalize();

                // 防止路径穿越
                if (!entryPath.startsWith(destDir.normalize())) {
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 检测 zip 包内是否有公共顶层目录
     * 例如所有文件都以 "index/" 开头，则返回 "index/"
     */
    private String detectTopDir(Path zipPath) throws IOException {
        String commonPrefix = null;
        int fileCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                zis.closeEntry();

                // 跳过顶层目录条目本身
                if (entry.isDirectory() && !name.contains("/")) {
                    continue;
                }

                fileCount++;
                int slashIdx = name.indexOf('/');
                if (slashIdx > 0) {
                    String prefix = name.substring(0, slashIdx + 1); // 包含 "/"
                    if (commonPrefix == null) {
                        commonPrefix = prefix;
                    } else if (!commonPrefix.equals(prefix)) {
                        // 发现不同前缀，说明没有公共顶层目录
                        return null;
                    }
                } else {
                    // 文件在根目录，没有顶层目录
                    return null;
                }
            }
        }

        // 只有所有文件都在同一个子目录下才返回该前缀
        return commonPrefix;
    }


    /**
     * 从 classpath 提取内置的 login.html（兜底方案）
     */
    private void extractBuiltinLogin(Path indexDir) {
        try {
            // 尝试从 res/index/ 复制已有的 login.html
            Path existingLogin = Paths.get(getJarDir(), "res", "index", "login.html");
            if (Files.exists(existingLogin)) {
                Files.createDirectories(indexDir);
                Files.copy(existingLogin, indexDir.resolve("login.html"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("已复制内置 login.html");
            }
        } catch (Exception e) {
            System.err.println("提取内置 login.html 失败: " + e.getMessage());
        }
    }

    /**
     * 获取 JAR 所在目录
     */
    private Path getResDir() {
        return Paths.get(getJarDir(), "res");
    }

    private static String getJarDir() {
        try {
            String path = GuiMain.class
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
}

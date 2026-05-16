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
import com.xiaofan.launcher.frpc.FrpcManager;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

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
        // 使用 JFXPanel 初始化 JavaFX 工具包，避免继承 Application 导致的 GTK 问题
        new JFXPanel();
        Platform.runLater(() -> {
            try {
                new GuiMain().start();
            } catch (Exception e) {
                System.err.println("GUI 启动失败: " + e.getMessage());
                e.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
        });
    }

    public void start() {
        try {
            // ====== 第〇步：先初始化 frpc 依赖（下载动态库/二进制），再搞其他 ======
            System.out.println("正在初始化 frpc 运行环境...");
            FrpcManager frpcManager = FrpcManager.getInstance();
            if (!frpcManager.init()) {
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

                    webView.getEngine().load("http://127.0.0.1:1023/login.html");

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

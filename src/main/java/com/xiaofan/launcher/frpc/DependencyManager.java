package com.xiaofan.launcher.frpc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * 依赖管理器 - 负责检测平台、选择节点、下载 frpc 可执行文件/动态库
 * 
 * 流程:
 * 1. 检查 JAR 同目录的 res/ 文件夹
 * 2. 测试 OSS 节点可用性（自动或用户选择）
 * 3. 检测系统类型和 CPU 架构
 * 4. 下载对应平台的 frpc 到 res/
 *    - Android: frpc 二进制可执行文件（通过 exec 启动）
 *    - Windows/Linux: frpc 动态库（通过 JNA 加载）
 */
public class DependencyManager {

    private static final String OSS_BASE_URL = "http://oss.xiaofanshop.cn/";
    private static final String OSS_CF_R2_URL = "https://oss.cf.xiaofanshop.cn/";
    private static final String OSS_DONATE_URL = "https://oss.xiaoli.top/";
    private static final String RES_DIR_NAME = "res";
    private static final int MAX_RETRY = 3;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;

    // 系统信息
    public static class SystemInfo {
        public final String os;
        public final String arch;
        public final String libName;   // frpc 文件名（如 linux_arm64.so / frpc_Android_arm64-v8a）
        public final boolean isWindows;
        public final boolean isAndroid; // 是否是 Android（Termux）

        public SystemInfo(String os, String arch, String libName, boolean isWindows, boolean isAndroid) {
            this.os = os;
            this.arch = arch;
            this.libName = libName;
            this.isWindows = isWindows;
            this.isAndroid = isAndroid;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("OS: ").append(os);
            if (isAndroid) sb.append(" (Android/Termux)");
            sb.append(", Arch: ").append(arch).append(", Library: ").append(libName);
            return sb.toString();
        }
    }

    /** 节点信息（URL + 描述） */
    public static class NodeInfo {
        public final String url;
        public final String description;

        public NodeInfo(String url, String description) {
            this.url = url;
            this.description = description;
        }
    }

    private final String jarDir;
    private final Path resDir;
    private SystemInfo systemInfo;
    private String selectedNode;

    public DependencyManager() {
        this.jarDir = getJarDir();
        this.resDir = Paths.get(jarDir, RES_DIR_NAME);
    }

    // ==================== 公开入口 ====================

    /**
     * 运行依赖管理器完整流程
     * 
     * 首次启动（无 res/）:
     *   1. 创建 res/ 目录
     *   2. 检测系统信息
     *   3. 测试节点 → 用户选择
     *   4. 下载 v.txt
     *   5. 下载 frpc 二进制/动态库
     * 
     * 已有 res/:
     *   1. 检测系统信息
     *   2. 读取本地 v.txt
     *   3. 测试节点 → 用户选择
     *   4. 获取云端 v.txt
     *   5. 对比版本号决定是否更新
     *      - 版本一致 → 直接启动
     *      - 正式版更新 → 自动下载
     *      - 开发/测试版 → 询问用户
     *   6. 如需更新 → 下载 frpc 和 v.txt
     * 
     * @return true 如果准备就绪
     */
    public boolean run() {
        try {
            // 1. 检测系统信息（需要在任何流程之前，Android 要显示警告）
            detectSystem();
            System.out.println("系统信息: " + systemInfo);

            // 2. Android 特殊处理
            if (systemInfo.isAndroid) {
                System.out.println("\n===== 此设备运行于 Android/Termux 环境 =====");
                System.out.println("===== 警告: 功能可能不受完全支持 =====");
                System.out.println("===== 将使用 frpc 二进制文件直接启动 =====");
            }

            // 3. 确保 res 目录存在
            boolean isFirstRun = !Files.exists(resDir);
            ensureResDir();

            // 4. 测试 OSS 节点并选择
            System.out.println("测试下载节点...");
            List<NodeInfo> nodes = testNodes();
            if (nodes.isEmpty()) {
                System.err.println("错误: 所有节点均不可用");
                return false;
            }
            NodeInfo selected = selectNode(nodes);
            if (selected == null) {
                System.err.println("错误: 未选择节点");
                return false;
            }
            selectedNode = selected.url;

            // 5. 处理版本检查
            Path frpcLib = resDir.resolve(systemInfo.libName);
            boolean needFrpcDownload = false;

            if (isFirstRun) {
                // 首次启动：没有 res/ 目录，直接下载 v.txt + frpc
                System.out.println("首次启动，正在初始化运行环境...");
                needFrpcDownload = true;

                // 先下载 v.txt
                if (!VersionChecker.downloadVtxt(selectedNode, resDir)) {
                    System.err.println("警告: 无法下载 v.txt，将仅下载 frpc 文件");
                }

            } else {
                // 已有 res/ 目录，检查版本
                VersionChecker.VersionInfo localVer = VersionChecker.readLocalVersion(resDir);
                VersionChecker.VersionInfo remoteVer = VersionChecker.fetchRemoteVersion(selectedNode);

                if (localVer != null && remoteVer != null) {
                    VersionChecker.UpdateDecision decision = VersionChecker.checkAndDecide(
                        localVer, remoteVer, resDir, selectedNode);

                    if (!decision.shouldLaunch) {
                        return false;
                    }

                    if (decision.shouldUpdate) {
                        needFrpcDownload = true;
                    }
                } else {
                    // 无法获取版本信息，回退到文件存在性检查
                    needFrpcDownload = !Files.exists(frpcLib);
                    if (!needFrpcDownload) {
                        System.out.println("本地已存在: " + frpcLib.toAbsolutePath());
                    }
                }
            }

            // 6. 需要下载 frpc
            if (needFrpcDownload) {
                if (!downloadLibrary(selectedNode, systemInfo.libName, frpcLib)) {
                    return false;
                }
                // 下载 frpc 后，确保 v.txt 是最新的
                if (!Files.exists(resDir.resolve("v.txt"))) {
                    VersionChecker.downloadVtxt(selectedNode, resDir);
                }
            }

            // 7. 启动前依赖完整性检查：检查所有必需文件，缺失自动补下
            if (!verifyDependencies(selectedNode)) {
                return false;
            }

            return true;
        } catch (Exception e) {
            System.err.println("依赖管理器运行失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 启动前依赖完整性检查
     * 逐个检查 res/ 下所有必需文件，缺失则自动重下
     * 避免运行到一半才报错
     */
    private boolean verifyDependencies(String nodeUrl) {
        List<String> missingFiles = new ArrayList<>();

        // 检查 1: v.txt 版本文件
        Path vtxt = resDir.resolve("v.txt");
        if (!Files.exists(vtxt)) {
            missingFiles.add("v.txt");
        }

        // 检查 2: 当前平台的 frpc 文件
        Path frpcLib = resDir.resolve(systemInfo.libName);
        if (!Files.exists(frpcLib)) {
            missingFiles.add(systemInfo.libName);
        }

        if (missingFiles.isEmpty()) {
            return true;
        }

        // 有文件缺失，打印警告并尝试自动补下
        System.out.println("\n发现 " + missingFiles.size() + " 个依赖缺失:");
        for (String f : missingFiles) {
            System.out.println("  - " + f);
        }
        System.out.println("正在自动补充缺失的依赖...");

        boolean allOk = true;

        // 补下 v.txt
        if (missingFiles.contains("v.txt")) {
            System.out.println("  下载 v.txt...");
            if (!VersionChecker.downloadVtxt(nodeUrl, resDir)) {
                System.err.println("  警告: v.txt 下载失败，不影响运行但版本检查会跳过");
                // v.txt 不是关键依赖，不影响运行
            } else {
                System.out.println("  v.txt 已下载");
            }
        }

        // 补下 frpc 文件
        if (missingFiles.contains(systemInfo.libName)) {
            System.out.println("  下载 " + systemInfo.libName + "...");
            if (!downloadLibrary(nodeUrl, systemInfo.libName, frpcLib)) {
                System.err.println("  错误: " + systemInfo.libName + " 下载失败！");
                allOk = false;
            } else {
                System.out.println("  " + systemInfo.libName + " 已下载");
            }
        }

        if (allOk) {
            System.out.println("依赖完整性检查通过\n");
        } else {
            System.err.println("依赖完整性检查失败，部分关键文件无法下载");
        }

        return allOk;
    }

    /**
     * 获取 res 目录路径
     */
    public Path getResDir() {
        return resDir;
    }

    /**
     * 获取系统信息
     */
    public SystemInfo getSystemInfo() {
        return systemInfo;
    }

    /**
     * 获取本地 frpc 文件路径
     */
    public Path getLibraryPath() {
        if (systemInfo == null) return null;
        return resDir.resolve(systemInfo.libName);
    }

    // ==================== 1. 目录管理 ====================

    private void ensureResDir() throws IOException {
        if (!Files.exists(resDir)) {
            Files.createDirectories(resDir);
            System.out.println("已创建目录: " + resDir.toAbsolutePath());
        } else {
            System.out.println("目录已存在: " + resDir.toAbsolutePath());
        }
    }

    // ==================== 2. 系统检测 ====================

    private void detectSystem() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String rawOs = os;
        String rawArch = arch;

        // 检测是否为 Android/Termux
        boolean isAndroid = false;
        String javaVendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String javaVmVendor = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
        if (javaVendor.contains("android") || javaVmVendor.contains("android")) {
            isAndroid = true;
        }
        // Termux 路径特征
        if (!isAndroid && jarDir.contains("/data/data/com.termux")) {
            isAndroid = true;
        }

        // 规范化架构名称
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            arch = "x64";
        } else if (arch.contains("x86") || arch.contains("i386") || arch.contains("i686")) {
            arch = "x86";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            arch = "arm64";
        } else if (arch.contains("arm") && !arch.contains("arm64")) {
            arch = "arm";
        } else {
            System.err.println("不支持的 CPU 架构: " + rawArch);
            System.exit(0);
        }

        String libName;

        if (isAndroid) {
            // Android: 使用二进制可执行文件
            // 架构名用 Android ABI 格式（arm64-v8a, armeabi-v7a, x86_64, x86）
            String androidAbi;
            switch (arch) {
                case "arm64": androidAbi = "arm64-v8a";    break;
                case "arm":   androidAbi = "armeabi-v7a";  break;
                case "x64":   androidAbi = "x86_64";       break;
                case "x86":   androidAbi = "x86";          break;
                default:
                    System.err.println("不支持的 Android 架构: " + rawArch);
                    System.exit(0);
                    return;
            }
            libName = "frpc_Android_" + androidAbi;
        } else if (os.contains("windows")) {
            switch (arch) {
                case "x64":
                    libName = "windows_x64.dll";
                    break;
                case "x86":
                    libName = "windows_x86.dll";
                    break;
                case "arm64":
                    libName = "windows_arm64.dll";
                    break;
                default:
                    System.err.println("不支持的 Windows 架构: " + rawArch);
                    System.exit(0);
                    return;
            }
        } else if (os.contains("linux")) {
            switch (arch) {
                case "x64":
                    libName = "linux_amd64.so";
                    break;
                case "x86":
                    libName = "linux_386.so";
                    break;
                case "arm64":
                    libName = "linux_arm64.so";
                    break;
                case "arm":
                    libName = "linux_arm.so";
                    break;
                default:
                    System.err.println("不支持的 Linux 架构: " + rawArch);
                    System.exit(0);
                    return;
            }
        } else {
            System.err.println("不支持的操作系统: " + rawOs);
            System.exit(0);
            return;
        }

        this.systemInfo = new SystemInfo(rawOs, rawArch, libName, os.contains("windows"), isAndroid);
    }

    // ==================== 3. 节点测试 ====================

    private List<NodeInfo> testNodes() {
        List<NodeInfo> availableNodes = new ArrayList<>();

        // 测试官方 CF 穿透节点（xiaofanshop，速度慢，更新最快）
        System.out.print("  测试节点 1 [官方 CF 穿透]: " + OSS_BASE_URL + " ... ");
        if (testNode(OSS_BASE_URL, false)) {
            System.out.println("OK");
            availableNodes.add(new NodeInfo(OSS_BASE_URL, "官方 CF 穿透节点（xiaofanshop，速度慢，更新最快）"));
        } else {
            System.out.println("不可用");
        }

        // 测试捐赠节点（xiaoli，速度快）— 启用内容检测，防攻击时 JS 验证劫持
        System.out.print("  测试节点 2 [xiaoli 捐赠]: " + OSS_DONATE_URL + " ... ");
        if (testNode(OSS_DONATE_URL, true)) {
            System.out.println("OK");
            availableNodes.add(new NodeInfo(OSS_DONATE_URL, "xiaoli 捐赠节点（速度快）"));
        } else {
            System.out.println("不可用");
        }

        // 测试 CF R2 OSS 存储节点（稳定）
        System.out.print("  测试节点 3 [CF R2 OSS]: " + OSS_CF_R2_URL + " ... ");
        if (testNode(OSS_CF_R2_URL, false)) {
            System.out.println("OK");
            availableNodes.add(new NodeInfo(OSS_CF_R2_URL, "CF R2 OSS 存储节点（稳定）"));
        } else {
            System.out.println("不可用");
        }


        return availableNodes;
    }


    /**
     * 测试节点可用性
     * @param nodeUrl 节点地址
     * @param checkContent 是否检测响应内容（仅 xiaoli 捐赠节点启用，该 CDN 被攻击时会返回 JS 验证页但状态码 200）
     */
    private boolean testNode(String nodeUrl, boolean checkContent) {
        try {
            URL url = new URL(nodeUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();

            // 5xx 直接不可用
            if (String.valueOf(responseCode).charAt(0) == '5') {
                conn.disconnect();
                return false;
            }

            // 仅对 xiaoli 捐赠节点启用内容检测（该 CDN 被攻击时会返回 JS 验证页但状态码 200）
            if (checkContent) {
                boolean isHtmlResponse = false;
                try (InputStream is = conn.getInputStream()) {
                    byte[] header = new byte[256];
                    int bytesRead = is.read(header);
                    if (bytesRead > 0) {
                        String headStr = new String(header, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
                        if (headStr.startsWith("<!doctype") || headStr.startsWith("<html") 
                            || headStr.startsWith("<script") || headStr.contains("function(")
                            || headStr.contains("location.href") || headStr.contains("document.cookie")) {
                            isHtmlResponse = true;
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                if (isHtmlResponse) {
                    System.out.println("（节点返回了 JS/HTML 验证页，可能遭受攻击）");
                    return false;
                }
            } else {
                conn.disconnect();
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }


    // ==================== 4. 节点选择 ====================

    private NodeInfo selectNode(List<NodeInfo> nodes) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) {
            System.out.println("已自动选择节点: " + nodes.get(0).url);
            System.out.println("  " + nodes.get(0).description);
            return nodes.get(0);
        }

        System.out.println("\n请选择下载节点:");
        Scanner scanner = new Scanner(System.in);
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo n = nodes.get(i);
            System.out.printf("  %d. %s (%s)%n", i + 1, n.description, n.url);
        }
        System.out.print("请输入编号 (1-" + nodes.size() + "): ");

        try {
            int choice = Integer.parseInt(scanner.nextLine().trim());
            if (choice >= 1 && choice <= nodes.size()) {
                System.out.println("已选择: " + nodes.get(choice - 1).description);
                return nodes.get(choice - 1);
            }
        } catch (NumberFormatException ignored) {
        }

        System.out.println("输入无效，默认选择: " + nodes.get(0).description);
        return nodes.get(0);
    }

    // ==================== 5. 下载 ====================

    private boolean downloadLibrary(String nodeUrl, String libName, Path targetPath) {
        String downloadUrl = nodeUrl.endsWith("/") ? nodeUrl + libName : nodeUrl + "/" + libName;

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            System.out.printf("\n下载尝试 %d/%d: %s%n", attempt, MAX_RETRY, downloadUrl);
            try {
                if (downloadWithProgress(downloadUrl, targetPath)) {
                    System.out.println("\n下载完成: " + targetPath.toAbsolutePath());
                    System.out.printf("文件大小: %.2f MB%n", Files.size(targetPath) / (1024.0 * 1024.0));
                    return true;
                }
            } catch (Exception e) {
                System.err.println("\n下载失败: " + e.getMessage());
            }

            if (attempt < MAX_RETRY) {
                System.out.println("准备重试...");
            }
        }

        System.err.println("错误: 下载失败 " + MAX_RETRY + " 次，退出程序");
        System.exit(1);
        return false;
    }

    /**
     * 带进度显示的下载
     * 加 User-Agent 避免 CDN 防盗链返回 HTML 验证页
     * 下载后校验文件是否为有效的二进制（非 HTML）
     */
    private boolean downloadWithProgress(String fileUrl, Path targetPath) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + responseCode + ": " + conn.getResponseMessage());
        }

        long contentLength = conn.getContentLengthLong();
        System.out.println("  文件大小: " + (contentLength > 0 ? String.format("%.2f MB", contentLength / (1024.0 * 1024.0)) : "未知"));

        try (InputStream inputStream = conn.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(targetPath.toFile())) {

            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int bytesRead;
            int lastPercent = -1;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (contentLength > 0) {
                    int percent = (int) (totalRead * 100 / contentLength);
                    if (percent != lastPercent) {
                        printProgress(percent, totalRead, contentLength);
                        lastPercent = percent;
                    }
                } else {
                    if (totalRead / (1024 * 1024) > lastPercent / 100) {
                        lastPercent = (int) (totalRead / (1024 * 1024) * 100);
                        System.out.printf("\r  已下载: %.2f MB", totalRead / (1024.0 * 1024.0));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        // 校验下载内容：如果是 HTML 验证页，文件太小或开头含 <!DOCTYPE 则报错
        long fileSize = Files.size(targetPath);
        if (fileSize < 1024) {
            // 读前几个字节判断是否为 HTML
            byte[] header = new byte[Math.min((int) fileSize, 128)];
            try (InputStream checkIs = Files.newInputStream(targetPath)) {
                checkIs.read(header);
            }
            String headStr = new String(header).trim().toLowerCase();
            if (headStr.startsWith("<!doctype") || headStr.startsWith("<html") || headStr.startsWith("<script")) {
                Files.deleteIfExists(targetPath);
                throw new IOException("服务器返回了 HTML 验证页，非有效二进制文件 (size=" + fileSize + " bytes)");
            }
        }

        return true;
    }

    private void printProgress(int percent, long downloaded, long total) {
        StringBuilder sb = new StringBuilder("\r  [");
        int barWidth = 50;
        int filled = percent * barWidth / 100;
        for (int i = 0; i < barWidth; i++) {
            sb.append(i < filled ? '=' : (i == filled ? '>' : ' '));
        }
        sb.append("] ");
        sb.append(String.format("%3d%%  %.2f/%.2f MB",
                percent,
                downloaded / (1024.0 * 1024.0),
                total / (1024.0 * 1024.0)));
        System.out.print(sb.toString());
        if (percent == 100) {
            System.out.println();
        }
    }

    // ==================== 工具方法 ====================

    private static String getJarDir() {
        try {
            String path = DependencyManager.class
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

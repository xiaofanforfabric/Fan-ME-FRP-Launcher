package com.xiaofan.launcher.frpc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String OSS_ALLIANCE_URL = "https://alist.yealqp.cn/download/Fan-ME-FRP-Launcher/";
    private static final String RES_DIR_NAME = "res";
    private static final int MAX_RETRY = 3;
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;

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
     * 运行依赖管理器完整流程（非交互模式）
     * 自动选择第一个可用节点，不询问用户
     * 适用于 GUI 模式下的后台自动初始化
     * 
     * @return true 如果准备就绪
     */
    public boolean runNonInteractive() {
        try {
            detectSystem();
            System.out.println("系统信息: " + systemInfo);

            if (systemInfo.isAndroid) {
                System.out.println("\n===== 此设备运行于 Android/Termux 环境 =====");
                System.out.println("===== 警告: 功能可能不受完全支持 =====");
                System.out.println("===== 将使用 frpc 二进制文件直接启动 =====");
            }

            boolean isFirstRun = !Files.exists(resDir);
            ensureResDir();

            System.out.println("测试下载节点...");
            List<NodeInfo> nodes = testNodes();
            if (nodes.isEmpty()) {
                System.err.println("错误: 所有节点均不可用");
                return false;
            }
            // 非交互模式：加权随机选择（联盟60%，官方40%）
            NodeInfo selected = autoSelectNode(nodes);
            System.out.println("已自动选择节点: " + selected.url);
            System.out.println("  " + selected.description);
            selectedNode = selected.url;

            if (isFirstRun) {
                // ===== 情况1: res 目录不存在 → 完整下载所有依赖 =====
                System.out.println("首次启动，正在初始化运行环境...");
                return fullDownload(selectedNode);
            }

            // ===== 情况2: res 目录已存在 → 版本检查 + 完整性校验 =====
            return handleExistingRes(selected, false);

        } catch (Exception e) {
            System.err.println("依赖管理器运行失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 运行依赖管理器完整流程（交互模式）
     * 
     * @return true 如果准备就绪
     */
    public boolean run() {
        try {
            // 1. 检测系统信息
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

            if (isFirstRun) {
                // ===== 情况1: res 目录不存在 → 完整下载所有依赖 =====
                System.out.println("首次启动，正在初始化运行环境...");
                return fullDownload(selectedNode);
            }

            // ===== 情况2: res 目录已存在 → 版本检查 + 完整性校验 =====
            return handleExistingRes(selected, true);

        } catch (Exception e) {
            System.err.println("依赖管理器运行失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 处理 res 目录已存在的情况
     * 1. 版本检查 → 有新正式版或用户选测试版 → 覆盖下载
     * 2. 无更新 → 完整性校验（缺文件/文件损坏 → 覆盖下载）
     */
    private boolean handleExistingRes(NodeInfo selected, boolean interactive) {
        VersionChecker.VersionInfo localVer = VersionChecker.readLocalVersion(resDir);
        VersionChecker.VersionInfo remoteVer = VersionChecker.fetchRemoteVersion(selectedNode);

        boolean needFullDownload = false;

        if (localVer != null && remoteVer != null) {
            int cmp = remoteVer.compareTo(localVer);

            if (cmp > 0) {
                // 云端有新版
                System.out.println("\n发现新版本: " + remoteVer.raw + " (当前: " + localVer.raw + ")");

                if (remoteVer.isRelease) {
                    // 正式版 → 自动覆盖下载
                    System.out.println("正式版更新，自动下载中...");
                    needFullDownload = true;
                } else {
                    // dev 或 beta → 询问用户
                    String typeName = remoteVer.isDev ? "开发版" : "测试版";
                    if (interactive) {
                        System.out.print("新" + typeName + "可用 (" + remoteVer.raw + ")，是否更新？(y/n): ");
                        @SuppressWarnings("resource")
                        Scanner scanner = new Scanner(System.in);
                        String input = scanner.nextLine().trim().toLowerCase();
                        if ("y".equals(input) || "yes".equals(input)) {
                            System.out.println("用户确认更新");
                            needFullDownload = true;
                        } else {
                            System.out.println("用户跳过更新，使用现有版本");
                        }
                    } else {
                        // 非交互模式：dev/beta 不自动更新
                        System.out.println("新" + typeName + "可用 (" + remoteVer.raw + ")，非交互模式跳过更新");
                    }
                }
            } else if (cmp == 0) {
                System.out.println("版本已是最新: " + localVer.raw);
            } else {
                System.out.println("本地版本 (" + localVer.raw + ") 比云端 (" + remoteVer.raw + ") 更新，跳过检查");
            }
        } else {
            System.out.println("无法获取版本信息（本地或云端 v.txt 缺失），将进行完整性校验");
        }

        if (needFullDownload) {
            System.out.println(">>> 开始覆盖下载所有依赖...");
            return fullDownload(selectedNode);
        }

        // ===== 无版本更新 → 完整性校验 =====
        System.out.println("\n>>> 正在进行文件完整性校验...");
        IntegrityResult result = checkIntegrity();

        if (result.corruptedFiles > 0) {
            System.out.println("完整性校验发现问题:");
            System.out.println("  - 损坏文件数: " + result.corruptedFiles);
            System.out.println(">>> 开始完整下载所有依赖覆盖...");
            return fullDownload(selectedNode);
        }

        System.out.println("完整性校验通过，所有文件完好");
        return true;
    }

    // ==================== 完整性校验 ====================

    /** 完整性校验结果 */
    private static class IntegrityResult {
        final int corruptedFiles;

        IntegrityResult(int corruptedFiles) {
            this.corruptedFiles = corruptedFiles;
        }
    }

    /**
     * 校验已存在文件的完整性
     * 读取 md5.txt 作为对照表，只校验本地已存在的文件是否被篡改/损坏
     * md5.txt 中列出的文件如果本地不存在，则跳过（不视为缺失）
     * 文件是否缺失由 checkRequiredFiles() 判断
     */
    private IntegrityResult checkIntegrity() {
        Path md5File = resDir.resolve("md5.txt");
        if (!Files.exists(md5File)) {
            System.out.println("  md5.txt 不存在，无法校验完整性");
            return new IntegrityResult(0);
        }

        // 读取 md5.txt 并解析
        Map<String, String> expectedMd5Map = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(md5File, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int colonIdx = line.lastIndexOf(':');
                if (colonIdx <= 0 || colonIdx >= line.length() - 1) continue;
                String fileName = line.substring(0, colonIdx);
                String md5 = line.substring(colonIdx + 1);
                expectedMd5Map.put(fileName, md5);
            }
        } catch (IOException e) {
            System.err.println("  读取 md5.txt 失败: " + e.getMessage());
            return new IntegrityResult(0);
        }

        if (expectedMd5Map.isEmpty()) {
            System.out.println("  md5.txt 为空，无法校验");
            return new IntegrityResult(0);
        }

        int corruptedCount = 0;

        for (Map.Entry<String, String> entry : expectedMd5Map.entrySet()) {
            String fileName = entry.getKey();
            String expectedMd5 = entry.getValue();

            // 跳过 md5.txt 自身的校验
            if ("md5.txt".equals(fileName)) continue;

            // 查找文件：先在 res 根目录找，再在 res/index/ 下找
            Path filePath = resDir.resolve(fileName);
            if (!Files.exists(filePath)) {
                // 尝试在 index 子目录下找
                filePath = resDir.resolve("index").resolve(fileName);
            }

            // 文件不存在则跳过（md5.txt 只是对照表，不是文件清单）
            if (!Files.exists(filePath)) {
                continue;
            }

            // 计算实际 MD5
            try {
                String actualMd5 = computeMd5(filePath);
                if (!expectedMd5.equalsIgnoreCase(actualMd5)) {
                    System.out.println("  [损坏] " + fileName + " (期望: " + expectedMd5 + ", 实际: " + actualMd5 + ")");
                    corruptedCount++;
                }
            } catch (Exception e) {
                System.out.println("  [校验失败] " + fileName + ": " + e.getMessage());
                corruptedCount++;
            }
        }

        return new IntegrityResult(corruptedCount);
    }

    /**
     * 计算文件的 MD5 值
     */
    private String computeMd5(Path filePath) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buffer = new byte[8192];
        try (InputStream is = Files.newInputStream(filePath)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ==================== 完整下载 ====================

    /**
     * 完整下载当前平台所需的依赖
     * 只下载当前平台需要的文件，不下载其他平台的 frpc 文件
     * md5.txt 仅作为完整性校验对照，不按它下载所有文件
     */
    private boolean fullDownload(String nodeUrl) {
        System.out.println(">>> 开始下载当前平台所需依赖...");

        // 1. 下载 md5.txt（校验对照文件）
        System.out.println("\n[1/5] 下载 md5.txt...");
        if (!downloadFile(nodeUrl, "md5.txt", resDir.resolve("md5.txt"))) {
            System.err.println("错误: md5.txt 下载失败");
            return false;
        }

        // 2. 下载 v.txt（版本文件）
        System.out.println("\n[2/5] 下载 v.txt...");
        if (!VersionChecker.downloadVtxt(nodeUrl, resDir)) {
            System.err.println("警告: v.txt 下载失败，不影响运行但版本检查会跳过");
        }

        // 3. 下载 index.zip（前端资源包）
        System.out.println("\n[3/5] 下载 index.zip...");
        Path indexZip = resDir.resolve("index.zip");
        if (!downloadFile(nodeUrl, "index.zip", indexZip)) {
            System.err.println("警告: index.zip 下载失败，前端资源可能缺失");
        }

        // 4. 下载当前平台的 frpc 文件
        System.out.println("\n[4/5] 下载 " + systemInfo.libName + "...");
        Path frpcLib = resDir.resolve(systemInfo.libName);
        if (!downloadLibrary(nodeUrl, systemInfo.libName, frpcLib)) {
            return false;
        }

        // 5. 下载前端页面文件（index.html, 404.html）
        System.out.println("\n[5/5] 下载前端页面文件...");
        downloadFrontendFiles(nodeUrl);

        // 6. 解压 index.zip → index/ 目录
        if (Files.exists(indexZip)) {
            System.out.println("\n>>> 解压 index.zip 到 index/...");
            if (!unzipIndex(indexZip)) {
                System.err.println("警告: index.zip 解压失败");
            }
        }

        System.out.println("\n>>> 当前平台依赖下载完成");
        return true;
    }

    /**
     * 下载前端页面文件（index.html, 404.html）
     * 这些是通用文件，所有平台都需要
     */
    private void downloadFrontendFiles(String nodeUrl) {
        // 需要下载的前端文件列表
        String[] frontendFiles = {"index.html", "404.html"};
        for (String fileName : frontendFiles) {
            Path target = resDir.resolve(fileName);
            if (Files.exists(target)) {
                System.out.println("  [已存在] " + fileName);
                continue;
            }
            System.out.println("  下载 " + fileName + "...");
            if (!downloadFile(nodeUrl, fileName, target)) {
                System.err.println("  警告: " + fileName + " 下载失败");
            }
        }
    }

    /**
     * 解压 index.zip 到 index/ 目录
     * 使用 Java 内置的 ZipInputStream
     */
    private boolean unzipIndex(Path zipPath) {
        Path indexDir = resDir.resolve("index");
        try {
            // 删除旧的 index 目录
            if (Files.exists(indexDir)) {
                Files.walk(indexDir)
                    .sorted((a, b) -> b.compareTo(a)) // 先删子文件再删目录
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
            Files.createDirectories(indexDir);

            // 解压
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    Files.newInputStream(zipPath))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // 去掉顶层目录名（index/xxx → xxx）
                    String entryName = entry.getName();
                    int slashIdx = entryName.indexOf('/');
                    String relativeName = (slashIdx >= 0) ? entryName.substring(slashIdx + 1) : entryName;
                    if (relativeName.isEmpty()) continue;

                    Path target = indexDir.resolve(relativeName);
                    Files.createDirectories(target.getParent());
                    try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    zis.closeEntry();
                }
            }

            System.out.println("  解压完成: " + indexDir.toAbsolutePath());
            long fileCount = Files.walk(indexDir).filter(Files::isRegularFile).count();
            System.out.println("  解压文件数: " + fileCount);
            return true;
        } catch (Exception e) {
            System.err.println("  解压失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从节点下载单个文件
     */
    private boolean downloadFile(String nodeUrl, String fileName, Path targetPath) {
        String downloadUrl = nodeUrl.endsWith("/") ? nodeUrl + fileName : nodeUrl + "/" + fileName;

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            System.out.printf("  下载尝试 %d/%d: %s%n", attempt, MAX_RETRY, downloadUrl);
            try {
                if (downloadWithProgress(downloadUrl, targetPath)) {
                    System.out.println("    完成: " + targetPath.toAbsolutePath());
                    return true;
                }
            } catch (Exception e) {
                System.err.println("    下载失败: " + e.getMessage());
            }

            if (attempt < MAX_RETRY) {
                System.out.println("    准备重试...");
            }
        }

        return false;
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

        // 测试第三方客户端联盟节点（yealqp）
        System.out.print("  测试节点 4 [第三方客户端联盟]: " + OSS_ALLIANCE_URL + " ... ");
        if (testNode(OSS_ALLIANCE_URL, false)) {
            System.out.println("OK");
            availableNodes.add(new NodeInfo(OSS_ALLIANCE_URL, "第三方客户端联盟节点（yealqp）"));
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

            // 仅对 xiaoli 捐赠节点启用内容检测
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

    /**
     * 加权随机选择节点
     * 联盟节点（OSS_ALLIANCE_URL）权重 60%，官方 CF 穿透节点（OSS_BASE_URL）权重 40%
     * 如果其中一个不可用，自动选健康的那个
     * 如果两个都不可用或都不在列表中，从所有可用节点中随机选
     */
    private NodeInfo autoSelectNode(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);

        // 查找联盟节点和官方节点在列表中的位置
        int allianceIdx = -1;
        int officialIdx = -1;
        for (int i = 0; i < nodes.size(); i++) {
            String url = nodes.get(i).url;
            if (url.equals(OSS_ALLIANCE_URL)) {
                allianceIdx = i;
            } else if (url.equals(OSS_BASE_URL)) {
                officialIdx = i;
            }
        }

        boolean allianceAvailable = allianceIdx >= 0;
        boolean officialAvailable = officialIdx >= 0;

        if (allianceAvailable && officialAvailable) {
            // 两个都健康：联盟60%，官方40%
            int roll = (int)(Math.random() * 100);
            if (roll < 60) {
                System.out.println("加权随机选择 → 第三方客户端联盟节点（60%权重）");
                return nodes.get(allianceIdx);
            } else {
                System.out.println("加权随机选择 → 官方 CF 穿透节点（40%权重）");
                return nodes.get(officialIdx);
            }
        } else if (allianceAvailable) {
            // 仅联盟可用
            System.out.println("官方节点不可用，自动选择第三方客户端联盟节点");
            return nodes.get(allianceIdx);
        } else if (officialAvailable) {
            // 仅官方可用
            System.out.println("联盟节点不可用，自动选择官方 CF 穿透节点");
            return nodes.get(officialIdx);
        }

        // 都不在列表中，随机选一个
        int randomIdx = (int)(Math.random() * nodes.size());
        System.out.println("随机选择节点: " + nodes.get(randomIdx).description);
        return nodes.get(randomIdx);
    }

    private NodeInfo selectNode(List<NodeInfo> nodes) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) {
            System.out.println("已自动选择节点: " + nodes.get(0).url);
            System.out.println("  " + nodes.get(0).description);
            return nodes.get(0);
        }

        System.out.println("\n请选择下载节点 (10秒内输入编号，超时将自动选择):");
        Scanner scanner = new Scanner(System.in);
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo n = nodes.get(i);
            System.out.printf("  %d. %s (%s)%n", i + 1, n.description, n.url);
        }
        System.out.print("请输入编号 (1-" + nodes.size() + "): ");
        System.out.flush();

        // 使用带超时的输入读取（10秒）
        String input = readLineWithTimeout(scanner, 10000);

        if (input != null) {
            try {
                int choice = Integer.parseInt(input.trim());
                if (choice >= 1 && choice <= nodes.size()) {
                    System.out.println("已选择: " + nodes.get(choice - 1).description);
                    return nodes.get(choice - 1);
                }
            } catch (NumberFormatException ignored) {
            }
            System.out.println("输入无效，自动选择...");
        } else {
            System.out.println("\n等待超时，自动选择...");
        }

        // 超时或输入无效 → 加权随机选择
        NodeInfo auto = autoSelectNode(nodes);
        System.out.println("已自动选择节点: " + auto.url);
        System.out.println("  " + auto.description);
        return auto;
    }

    /**
     * 带超时的控制台输入读取
     * @param scanner Scanner 对象
     * @param timeoutMs 超时时间（毫秒）
     * @return 用户输入，超时返回 null
     */
    private String readLineWithTimeout(Scanner scanner, long timeoutMs) {
        try {
            // 使用 System.in.available() 检测是否有输入可用
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (System.in.available() > 0) {
                    return scanner.nextLine();
                }
                Thread.sleep(100);
            }
        } catch (Exception e) {
            // 超时或异常，返回 null
        }
        return null;
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

        // 校验下载内容：如果是 HTML 验证页则报错
        long fileSize = Files.size(targetPath);
        if (fileSize < 1024) {
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

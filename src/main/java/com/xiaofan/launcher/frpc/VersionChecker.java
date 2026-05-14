package com.xiaofan.launcher.frpc;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * 版本检查器
 * 
 * v.txt 格式: "0.0.1_dev" / "0.0.1_beta" / "0.0.1_r"
 *   - _dev:   开发版，有更新时仅提示
 *   - _beta:  测试版，有更新时仅提示
 *   - _r:     正式版，有更新时自动下载
 * 
 * 版本对比比较前缀数值部分（如 "0.0.1"），按三段数字逐段比较。
 */
public class VersionChecker {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 5000;

    /** 本地版本信息 */
    public static class VersionInfo {
        public final String raw;              // 原始字符串如 "0.0.1_dev"
        public final int[] parts;             // 数值部分 [0, 0, 1]
        public final String suffix;           // 后缀 "dev" / "beta" / "r"
        public final boolean isDev;
        public final boolean isBeta;
        public final boolean isRelease;

        public VersionInfo(String raw) {
            this.raw = raw.trim();
            String[] split = this.raw.split("_", 2);
            String verPart = split[0];
            String suffixPart = split.length > 1 ? split[1] : "r";

            // 解析数值部分
            String[] numStrs = verPart.split("\\.");
            this.parts = new int[numStrs.length];
            for (int i = 0; i < numStrs.length; i++) {
                try {
                    this.parts[i] = Integer.parseInt(numStrs[i]);
                } catch (NumberFormatException e) {
                    this.parts[i] = 0;
                }
            }

            this.suffix = suffixPart;
            this.isDev = "dev".equals(suffixPart);
            this.isBeta = "beta".equals(suffixPart);
            this.isRelease = "r".equals(suffixPart);
        }

        /** 比较两个版本的数值部分：正数=比v大，0=相等，负数=比v小 */
        public int compareTo(VersionInfo other) {
            int maxLen = Math.max(this.parts.length, other.parts.length);
            for (int i = 0; i < maxLen; i++) {
                int a = i < this.parts.length ? this.parts[i] : 0;
                int b = i < other.parts.length ? other.parts[i] : 0;
                if (a != b) return a - b;
            }
            return 0;
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    /**
     * 从 res/v.txt 读取本地版本
     */
    public static VersionInfo readLocalVersion(Path resDir) {
        Path vFile = resDir.resolve("v.txt");
        if (!Files.exists(vFile)) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(vFile), StandardCharsets.UTF_8).trim();
            return new VersionInfo(content);
        } catch (IOException e) {
            System.err.println("读取本地 v.txt 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 OSS 获取云端最新版本号
     */
    public static VersionInfo fetchRemoteVersion(String nodeUrl) {
        String urlStr = nodeUrl.endsWith("/") ? nodeUrl + "v.txt" : nodeUrl + "/v.txt";
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                System.err.println("获取云端版本失败: HTTP " + responseCode);
                return null;
            }

            String content;
            try (InputStream is = conn.getInputStream()) {
                Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
                content = s.hasNext() ? s.next().trim() : "";
            }
            conn.disconnect();


            if (content.isEmpty()) {
                System.err.println("云端 v.txt 为空");
                return null;
            }
            return new VersionInfo(content);

        } catch (IOException e) {
            System.err.println("获取云端版本失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 执行版本检查与更新策略
     * 
     * @param localVer 本地版本
     * @param remoteVer 云端版本
     * @param resDir res/ 目录路径
     * @param nodeUrl OSS 节点地址
     * @return true 如果需要继续启动，false 表示应退出
     */
    public static UpdateDecision checkAndDecide(VersionInfo localVer, VersionInfo remoteVer,
                                                 Path resDir, String nodeUrl) {
        if (localVer == null || remoteVer == null) {
            // 无法获取版本信息，跳过检查直接启动
            return new UpdateDecision(true, false, null);
        }

        int cmp = remoteVer.compareTo(localVer);
        if (cmp == 0) {
            // 版本相同，直接启动
            System.out.println("版本已是最新: " + localVer.raw);
            return new UpdateDecision(true, false, null);
        }

        if (cmp < 0) {
            // 本地版本比云端还新（本地开发调试）
            System.out.println("本地版本 (" + localVer.raw + ") 比云端 (" + remoteVer.raw + ") 更新，跳过检查");
            return new UpdateDecision(true, false, null);
        }

        // 云端有新版
        System.out.println("\n发现新版本: " + remoteVer.raw + " (当前: " + localVer.raw + ")");

        if (remoteVer.isRelease) {
            // 正式版 → 自动更新
            System.out.println("正式版更新，自动下载中...");
            return new UpdateDecision(true, true, remoteVer);
        }

        // dev 或 beta → 询问用户
        String typeName = remoteVer.isDev ? "开发版" : "测试版";
        System.out.print("新" + typeName + "可用 (" + remoteVer.raw + ")，是否更新？(y/n): ");

        @SuppressWarnings("resource")
        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine().trim().toLowerCase();
        if ("y".equals(input) || "yes".equals(input)) {
            System.out.println("用户确认更新");
            return new UpdateDecision(true, true, remoteVer);
        } else {
            System.out.println("用户跳过更新，使用现有版本启动");
            return new UpdateDecision(true, false, null);
        }
    }

    /**
     * 更新决策结果
     */
    public static class UpdateDecision {
        public final boolean shouldLaunch;   // 是否继续启动
        public final boolean shouldUpdate;   // 是否需要更新文件
        public final VersionInfo targetVersion; // 目标版本（下载后写入 v.txt）

        public UpdateDecision(boolean shouldLaunch, boolean shouldUpdate, VersionInfo targetVersion) {
            this.shouldLaunch = shouldLaunch;
            this.shouldUpdate = shouldUpdate;
            this.targetVersion = targetVersion;
        }
    }

    /**
     * 下载 v.txt 到本地
     */
    public static boolean downloadVtxt(String nodeUrl, Path resDir) {
        String urlStr = nodeUrl.endsWith("/") ? nodeUrl + "v.txt" : nodeUrl + "/v.txt";
        Path target = resDir.resolve("v.txt");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {

                conn.disconnect();
                System.err.println("下载 v.txt 失败: HTTP " + conn.getResponseCode());
                return false;
            }

            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            conn.disconnect();

            System.out.println("v.txt 已下载: " + target.toAbsolutePath());
            return true;
        } catch (IOException e) {
            System.err.println("下载 v.txt 失败: " + e.getMessage());
            return false;
        }
    }
}

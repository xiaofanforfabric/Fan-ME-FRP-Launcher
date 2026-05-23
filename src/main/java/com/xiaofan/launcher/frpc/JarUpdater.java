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
import java.util.Scanner;

/**
 * JAR 自身版本更新器
 * 
 * 流程:
 * 1. 调用 API https://frpc.xiaofanshop.cn/version/last 获取最新版本信息
 * 2. 对比 res/v.txt 中的版本号
 * 3. 有新版本时下载新 JAR
 * 4. 删除 res 文件夹和自身 JAR 文件
 * 5. Windows 用 PowerShell Remove-Item，Linux 用 rm -rf
 */
public class JarUpdater {

    private static final String VERSION_API = "https://frpc.xiaofanshop.cn/version/last";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    /**
     * 检查并执行 JAR 自身更新
     * @param jarDir JAR 所在目录
     * @param resDir res 目录路径
     * @return true 如果有更新并已执行删除（调用者应退出），false 表示无需更新
     */
    public static boolean checkAndUpdate(String jarDir, Path resDir) {
        try {
            // 1. 读取本地版本
            VersionChecker.VersionInfo localVer = VersionChecker.readLocalVersion(resDir);
            if (localVer == null) {
                System.out.println("[JAR更新] 无法读取本地版本 (res/v.txt 不存在)");
                return false;
            }
            System.out.println("[JAR更新] 本地版本: " + localVer.raw);

            // 2. 调用 API 获取最新版本
            System.out.println("[JAR更新] 检查最新版本...");
            String apiResponse = callVersionApi();
            if (apiResponse == null) {
                System.out.println("[JAR更新] 获取最新版本失败");
                return false;
            }

            // 3. 解析 API 响应
            String remoteVersion = extractJsonString(apiResponse, "version");
            String downloadUrl = extractJsonString(apiResponse, "download");

            if (remoteVersion == null || remoteVersion.isEmpty()) {
                System.out.println("[JAR更新] API 返回的版本号为空");
                return false;
            }
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                System.out.println("[JAR更新] API 返回的下载地址为空");
                return false;
            }

            System.out.println("[JAR更新] 最新版本: " + remoteVersion);

            // 4. 对比版本
            VersionChecker.VersionInfo remoteVer = new VersionChecker.VersionInfo(remoteVersion);
            int cmp = remoteVer.compareTo(localVer);

            if (cmp <= 0) {
                System.out.println("[JAR更新] 已是最新版本，无需更新");
                return false;
            }

            // 5. 有新版本，下载新 JAR
            System.out.println("[JAR更新] 发现新版本: " + remoteVersion + "，开始下载...");

            // 新 JAR 文件名：Fan-ME-FRP-Launcher-{version}.jar
            String newJarName = "Fan-ME-FRP-Launcher-" + remoteVersion + ".jar";
            Path newJarPath = Paths.get(jarDir, newJarName);

            boolean downloaded = downloadJar(downloadUrl, newJarPath);
            if (!downloaded) {
                System.err.println("[JAR更新] 下载新版本失败");
                return false;
            }

            System.out.println("[JAR更新] 新版本已下载: " + newJarPath.toAbsolutePath());

            // 6. 获取当前 JAR 路径
            String currentJarPath = getCurrentJarPath();
            if (currentJarPath == null) {
                System.err.println("[JAR更新] 无法获取当前 JAR 路径");
                return false;
            }
            System.out.println("[JAR更新] 当前 JAR: " + currentJarPath);

            // 7. 执行清理：删除 res 文件夹和自身 JAR
            System.out.println("[JAR更新] 正在清理旧文件...");
            cleanup(jarDir, resDir, currentJarPath);

            return true;

        } catch (Exception e) {
            System.err.println("[JAR更新] 检查更新异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 调用版本 API
     */
    private static String callVersionApi() {
        try {
            URL url = new URL(VERSION_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                System.err.println("[JAR更新] API 请求失败: HTTP " + responseCode);
                return null;
            }

            String content;
            try (InputStream is = conn.getInputStream()) {
                Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
                content = s.hasNext() ? s.next().trim() : "";
            }
            conn.disconnect();

            return content.isEmpty() ? null : content;

        } catch (IOException e) {
            System.err.println("[JAR更新] API 请求异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 下载新 JAR 文件
     */
    private static boolean downloadJar(String downloadUrl, Path targetPath) {
        try {
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                System.err.println("[JAR更新] 下载失败: HTTP " + responseCode);
                return false;
            }

            long contentLength = conn.getContentLengthLong();
            System.out.println("[JAR更新] 文件大小: " + (contentLength > 0 ?
                String.format("%.2f MB", contentLength / (1024.0 * 1024.0)) : "未知"));

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
                            System.out.printf("\r[JAR更新] 下载进度: %d%%", percent);
                            lastPercent = percent;
                        }
                    }
                }
                System.out.println();
            } finally {
                conn.disconnect();
            }

            return Files.exists(targetPath) && Files.size(targetPath) > 0;

        } catch (IOException e) {
            System.err.println("[JAR更新] 下载异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 清理旧文件：删除 res 文件夹和自身 JAR
     * Windows 用 PowerShell Remove-Item，Linux 用 rm -rf
     */
    private static void cleanup(String jarDir, Path resDir, String currentJarPath) {
        String os = System.getProperty("os.name").toLowerCase();

        // 构建删除命令
        String deleteCommand;
        if (os.contains("windows")) {
            // Windows: 用 PowerShell 删除
            String resPath = resDir.toAbsolutePath().toString().replace("/", "\\");
            String jarPath = currentJarPath.replace("/", "\\");
            deleteCommand = String.format(
                "powershell -Command \"Remove-Item -Recurse -Force '%s'; Remove-Item -Force '%s'\"",
                resPath, jarPath
            );
        } else {
            // Linux: 用 rm -rf
            String resPath = resDir.toAbsolutePath().toString();
            String jarPath = currentJarPath;
            deleteCommand = String.format("rm -rf '%s' '%s'", resPath, jarPath);
        }

        try {
            System.out.println("[JAR更新] 执行清理命令: " + deleteCommand);
            Process process = Runtime.getRuntime().exec(deleteCommand);
            int exitCode = process.waitFor();
            System.out.println("[JAR更新] 清理命令退出码: " + exitCode);
        } catch (Exception e) {
            System.err.println("[JAR更新] 清理失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前 JAR 文件的绝对路径
     */
    private static String getCurrentJarPath() {
        try {
            String path = JarUpdater.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File jarFile = new File(path);
            if (jarFile.isFile()) {
                return jarFile.getAbsolutePath();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值
     */
    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": \"";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return "";
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }
}

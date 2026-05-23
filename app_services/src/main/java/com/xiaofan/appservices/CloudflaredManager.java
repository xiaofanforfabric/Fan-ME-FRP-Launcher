package com.xiaofan.appservices;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Cloudflared 隧道管理器
 *
 * 从 JAR 中提取 cloudflared-linux-amd64 二进制文件，
 * 通过 exec 启动 Cloudflare Tunnel。
 *
 * 启动命令: ./cloudflared tunnel run --token <token>
 */
public class CloudflaredManager {

    private static final String CLOUDFLARED_RESOURCE = "/cloudflared-linux-amd64";
    private static final String CLOUDFLARED_FILENAME = "cloudflared-linux-amd64";

    private Process tunnelProcess;
    private Thread outputReader;
    private Path binaryPath;

    /**
     * 初始化 cloudflared：从 JAR 中提取二进制文件到 JAR 同级目录
     * @return true 如果提取成功
     */
    public boolean init() {
        try {
            // 目标路径：JAR 同级目录
            String jarDir = getJarDir();
            binaryPath = Paths.get(jarDir, CLOUDFLARED_FILENAME);

            // 如果已存在且可执行，跳过提取
            if (Files.exists(binaryPath) && Files.isExecutable(binaryPath)) {
                System.out.println("cloudflared 已就绪: " + binaryPath.toAbsolutePath());
                return true;
            }

            // 从 JAR 中提取
            System.out.println("正在提取 cloudflared...");
            try (InputStream is = getClass().getResourceAsStream(CLOUDFLARED_RESOURCE)) {
                if (is == null) {
                    System.err.println("错误: JAR 中未找到 " + CLOUDFLARED_RESOURCE);
                    return false;
                }
                Files.copy(is, binaryPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 设置可执行权限
            File binFile = binaryPath.toFile();
            if (!binFile.setExecutable(true)) {
                System.out.println("警告: setExecutable 失败，尝试 chmod...");
                try {
                    Runtime.getRuntime().exec(new String[]{"chmod", "+x", binFile.getAbsolutePath()});
                } catch (IOException e) {
                    System.err.println("chmod 失败: " + e.getMessage());
                }
            }

            System.out.println("cloudflared 已提取到: " + binaryPath.toAbsolutePath());
            return true;

        } catch (Exception e) {
            System.err.println("cloudflared 初始化失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 启动 Cloudflare Tunnel
     * @param token Cloudflare Tunnel Token
     * @return true 如果启动成功
     */
    public boolean startTunnel(String token) {
        if (token == null || token.trim().isEmpty()) {
            System.err.println("错误: Tunnel Token 不能为空");
            return false;
        }

        // 如果已有隧道在运行，先停止
        stopTunnel();

        if (binaryPath == null || !Files.exists(binaryPath)) {
            System.err.println("错误: cloudflared 二进制文件不存在，请先调用 init()");
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toAbsolutePath().toString(),
                "tunnel", "run",
                "--token", token.trim()
            );
            pb.redirectErrorStream(true);
            pb.directory(new File("."));

            tunnelProcess = pb.start();

            // 启动线程读取进程输出
            outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(tunnelProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[cloudflared] " + line);
                    }
                } catch (IOException e) {
                    // 进程结束时的正常异常
                }
            }, "cloudflared-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            // 短暂等待确认进程是否启动成功
            Thread.sleep(1000);
            if (tunnelProcess.isAlive()) {
                System.out.println("Cloudflare Tunnel 已启动，PID: " + tunnelProcess.pid());
                return true;
            } else {
                int exitCode = tunnelProcess.exitValue();
                System.err.println("Cloudflare Tunnel 启动后立即退出，退出码: " + exitCode);
                return false;
            }

        } catch (IOException e) {
            System.err.println("启动 Cloudflare Tunnel 失败: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 停止 Cloudflare Tunnel
     * @return true 如果停止成功
     */
    public boolean stopTunnel() {
        if (tunnelProcess == null) {
            return true;
        }

        try {
            // 先尝试优雅终止
            tunnelProcess.destroy();
            if (!tunnelProcess.waitFor(5, TimeUnit.SECONDS)) {
                System.out.println("cloudflared 未在 5 秒内退出，强制终止");
                tunnelProcess.destroyForcibly();
                tunnelProcess.waitFor(3, TimeUnit.SECONDS);
            }
            System.out.println("Cloudflare Tunnel 已停止");
            return true;
        } catch (InterruptedException e) {
            System.out.println("等待 cloudflared 退出被中断");
            tunnelProcess.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        } finally {
            tunnelProcess = null;
            outputReader = null;
        }
    }

    /**
     * 检查 Cloudflare Tunnel 是否在运行
     */
    public boolean isRunning() {
        return tunnelProcess != null && tunnelProcess.isAlive();
    }

    /**
     * 获取 JAR 所在目录
     */
    private static String getJarDir() {
        try {
            String path = CloudflaredManager.class
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

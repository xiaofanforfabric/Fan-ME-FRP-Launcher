package com.xiaofan.launcher.frpc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * FRPC 进程管理器
 * 
 * 统一管理 frpc 客户端生命周期，自动选择执行方式：
 * - Android/Termux: 使用 ProcessBuilder 启动 frpc 二进制可执行文件
 * - Windows/Linux: 使用 JNA 加载动态库
 * 
 * 职责:
 * 1. 调用 DependencyManager 确保依赖已就绪
 * 2. 根据平台选择 JNA 或 exec 方式
 * 3. 管理 frpc 进程的生命周期
 */
public class FrpcManager {

    private static final Logger LOG = Logger.getLogger(FrpcManager.class.getName());

    private static FrpcManager instance;
    private DependencyManager dependencyManager;
    private FrpcJnaBridge jnaBridge;
    private Process frpcProcess;
    private Thread outputReader;
    private boolean running = false;

    private FrpcManager() {}

    public static synchronized FrpcManager getInstance() {
        if (instance == null) {
            instance = new FrpcManager();
        }
        return instance;
    }

    /**
     * 初始化 frpc 执行环境
     * @return true 如果初始化成功
     */
    public boolean init() {
        dependencyManager = new DependencyManager();
        if (!dependencyManager.run()) {
            LOG.severe("依赖管理器初始化失败");
            return false;
        }

        DependencyManager.SystemInfo info = dependencyManager.getSystemInfo();
        if (info == null) {
            LOG.severe("无法获取系统信息");
            return false;
        }

        // Android/Termux: 使用 exec 启动二进制
        if (info.isAndroid) {
            LOG.info("===== 此设备运行于 Android/Termux 环境 =====");
            LOG.info("===== 警告: 功能可能不受完全支持 =====");
            LOG.info("===== 将使用 frpc 二进制文件启动 =====");
            return prepareExecBinary();
        }

        // Windows/Linux: 使用 JNA 加载动态库
        LOG.info("初始化 JNA 桥接层...");
        Path libPath = dependencyManager.getLibraryPath();
        if (libPath == null) {
            LOG.severe("无法获取动态库路径");
            return false;
        }
        return FrpcJnaBridge.init(libPath.toAbsolutePath().toString());

    }

    /**
     * 准备 exec 模式：确保二进制文件可执行
     */
    private boolean prepareExecBinary() {
        Path binPath = dependencyManager.getLibraryPath();
        if (binPath == null || !Files.exists(binPath)) {
            LOG.severe("frpc 二进制文件不存在: " + binPath);
            return false;
        }

        File binFile = binPath.toFile();
        // 设置可执行权限
        if (!binFile.canExecute()) {
            if (!binFile.setExecutable(true)) {
                LOG.warning("无法设置 frpc 可执行权限，尝试 chmod...");
                try {
                    Runtime.getRuntime().exec(new String[]{"chmod", "+x", binFile.getAbsolutePath()});
                } catch (IOException e) {
                    LOG.warning("chmod 失败: " + e.getMessage());
                }
            }
        }

        LOG.info("frpc 二进制文件就绪: " + binPath.toAbsolutePath());
        return true;
    }

    /**
     * 启动 frpc 客户端
     * @param configPath 配置文件路径
     * @return true 如果启动成功
     */
    public boolean start(String configPath) {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return startExec(configPath);
        } else {
            return FrpcJnaBridge.start(configPath);
        }
    }

    /**
     * 通过 exec 启动 frpc 进程
     */
    private boolean startExec(String configPath) {
        if (running) {
            LOG.warning("frpc 已在运行中");
            return false;
        }

        Path binPath = dependencyManager.getLibraryPath();
        if (binPath == null || !Files.exists(binPath)) {
            LOG.severe("frpc 二进制文件不存在");
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                binPath.toAbsolutePath().toString(),
                "-c", configPath
            );
            pb.redirectErrorStream(true);
            pb.directory(new File("."));

            frpcProcess = pb.start();
            running = true;

            // 启动线程读取进程输出
            outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(frpcProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[frpc] " + line);
                    }
                } catch (IOException e) {
                    if (running) {
                        LOG.warning("读取 frpc 输出时出错: " + e.getMessage());
                    }
                }
            }, "frpc-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            // 短暂等待确认进程是否启动成功
            Thread.sleep(500);
            if (frpcProcess.isAlive()) {
                LOG.info("frpc 进程已启动，PID: " + frpcProcess.pid());
                return true;
            } else {
                int exitCode = frpcProcess.exitValue();
                LOG.severe("frpc 进程启动后立即退出，退出码: " + exitCode);
                running = false;
                return false;
            }

        } catch (IOException e) {
            LOG.severe("启动 frpc 进程失败: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 停止 frpc 客户端
     * @return true 如果停止成功
     */
    public boolean stop() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return stopExec();
        } else {
            return FrpcJnaBridge.stop();
        }
    }

    /**
     * 停止 frpc 进程
     */
    private boolean stopExec() {
        if (!running || frpcProcess == null) {
            return true;
        }

        try {
            // 先尝试优雅终止
            frpcProcess.destroy();
            if (!frpcProcess.waitFor(5, TimeUnit.SECONDS)) {
                LOG.warning("frpc 进程未在 5 秒内退出，强制终止");
                frpcProcess.destroyForcibly();
                frpcProcess.waitFor(3, TimeUnit.SECONDS);
            }
            LOG.info("frpc 进程已停止");
            return true;
        } catch (InterruptedException e) {
            LOG.warning("等待 frpc 进程退出被中断");
            frpcProcess.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        } finally {
            running = false;
            frpcProcess = null;
        }
    }

    /**
     * 检查 frpc 是否在运行
     */
    public boolean isRunning() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return running && frpcProcess != null && frpcProcess.isAlive();
        } else {
            return FrpcJnaBridge.isRunning();
        }
    }

    /**
     * 获取 frpc 版本号
     */
    public String getVersion() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return getExecVersion();
        } else {
            return FrpcJnaBridge.getVersion();
        }
    }

    /**
     * 通过 exec version 命令获取版本号
     */
    private String getExecVersion() {
        Path binPath = dependencyManager.getLibraryPath();
        if (binPath == null || !Files.exists(binPath)) {
            return "unknown";
        }

        try {
            Process proc = new ProcessBuilder(
                binPath.toAbsolutePath().toString(), "--version"
            ).start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                proc.waitFor(3, TimeUnit.SECONDS);
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取依赖管理器
     */
    public DependencyManager getDependencyManager() {
        return dependencyManager;
    }

    /**
     * 获取系统信息
     */
    public DependencyManager.SystemInfo getSystemInfo() {
        return dependencyManager != null ? dependencyManager.getSystemInfo() : null;
    }

    /**
     * 是否是 Android/Termux exec 模式
     */
    public boolean isExecMode() {
        DependencyManager.SystemInfo info = getSystemInfo();
        return info != null && info.isAndroid;
    }
}

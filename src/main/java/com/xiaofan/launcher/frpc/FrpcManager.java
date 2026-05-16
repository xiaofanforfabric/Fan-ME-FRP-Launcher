package com.xiaofan.launcher.frpc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * FRPC 进程管理器
 * 
 * 统一管理 frpc 客户端生命周期，自动选择执行方式：
 * - Android/Termux: 使用 ProcessBuilder 启动 frpc 二进制可执行文件
 * - Windows/Linux: 使用 JNA 加载动态库
 * 
 * 支持多实例：每个 proxyId 对应一个独立的 frpc 实例
 */
public class FrpcManager {

    private static final Logger LOG = Logger.getLogger(FrpcManager.class.getName());

    private static FrpcManager instance;
    private DependencyManager dependencyManager;
    private boolean jnaInitialized = false;

    // 多实例管理: proxyId -> instanceId (Go 端分配的 ID)
    private final Map<Integer, Integer> proxyInstanceMap = new ConcurrentHashMap<>();
    // exec 模式下的进程管理: proxyId -> Process
    private final Map<Integer, Process> proxyProcessMap = new ConcurrentHashMap<>();
    // exec 模式下的输出线程: proxyId -> Thread
    private final Map<Integer, Thread> proxyOutputThreadMap = new ConcurrentHashMap<>();

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
        return init(false);
    }

    /**
     * 初始化 frpc 执行环境
     * @param interactive 是否使用交互模式（命令行选择节点）
     * @return true 如果初始化成功
     */
    public boolean init(boolean interactive) {
        dependencyManager = new DependencyManager();
        boolean ok = interactive ? dependencyManager.run() : dependencyManager.runNonInteractive();
        if (!ok) {
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
        jnaInitialized = FrpcJnaBridge.init(libPath.toAbsolutePath().toString());
        return jnaInitialized;
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
     * 启动 frpc 客户端（单实例模式，兼容旧接口）
     * @param configPath 配置文件路径
     * @return true 如果启动成功
     */
    public boolean start(String configPath) {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return startExec(0, configPath);
        } else {
            return FrpcJnaBridge.start(configPath);
        }
    }

    /**
     * 启动指定 proxyId 的 frpc 实例（多实例模式）
     * @param proxyId 隧道 ID
     * @param configPath 配置文件路径
     * @return true 如果启动成功
     */
    public boolean startProxy(int proxyId, String configPath) {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        // 如果该 proxyId 已有实例在运行，先停止
        stopProxy(proxyId);

        boolean ok;
        if (info != null && info.isAndroid) {
            ok = startExec(proxyId, configPath);
        } else {
            ok = startJnaProxy(proxyId, configPath);
        }

        if (ok) {
            LOG.info("frpc 实例启动成功, proxyId=" + proxyId);
        } else {
            LOG.severe("frpc 实例启动失败, proxyId=" + proxyId);
        }
        return ok;
    }

    /**
     * 通过 JNA 启动指定 proxyId 的实例
     */
    private boolean startJnaProxy(int proxyId, String configPath) {
        if (!jnaInitialized) {
            LOG.severe("JNA 未初始化，无法启动 frpc");
            return false;
        }

        int instanceId = FrpcJnaBridge.startWithId(configPath);
        if (instanceId >= 0) {
            proxyInstanceMap.put(proxyId, instanceId);
            return true;
        }
        return false;
    }

    /**
     * 通过 exec 启动 frpc 进程
     */
    private boolean startExec(int proxyId, String configPath) {
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

            Process process = pb.start();

            // 启动线程读取进程输出
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[frpc:" + proxyId + "] " + line);
                    }
                } catch (IOException e) {
                    // 进程结束时的正常异常
                }
            }, "frpc-output-reader-" + proxyId);
            outputReader.setDaemon(true);
            outputReader.start();

            // 短暂等待确认进程是否启动成功
            Thread.sleep(500);
            if (process.isAlive()) {
                LOG.info("frpc 进程已启动，PID: " + process.pid() + ", proxyId=" + proxyId);
                proxyProcessMap.put(proxyId, process);
                proxyOutputThreadMap.put(proxyId, outputReader);
                return true;
            } else {
                int exitCode = process.exitValue();
                LOG.severe("frpc 进程启动后立即退出，退出码: " + exitCode + ", proxyId=" + proxyId);
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
     * 停止 frpc 客户端（单实例模式，兼容旧接口）
     * @return true 如果停止成功
     */
    public boolean stop() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return stopExec(0);
        } else {
            return FrpcJnaBridge.stop();
        }
    }

    /**
     * 停止指定 proxyId 的 frpc 实例
     * @param proxyId 隧道 ID
     * @return true 如果停止成功
     */
    public boolean stopProxy(int proxyId) {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        boolean ok;
        if (info != null && info.isAndroid) {
            ok = stopExec(proxyId);
        } else {
            ok = stopJnaProxy(proxyId);
        }

        if (ok) {
            LOG.info("frpc 实例已停止, proxyId=" + proxyId);
        }
        return ok;
    }

    /**
     * 通过 JNA 停止指定 proxyId 的实例
     */
    private boolean stopJnaProxy(int proxyId) {
        Integer instanceId = proxyInstanceMap.remove(proxyId);
        if (instanceId == null) {
            LOG.info("proxyId=" + proxyId + " 没有运行中的实例");
            return true;
        }
        return FrpcJnaBridge.stopWithId(instanceId);
    }

    /**
     * 停止 frpc 进程
     */
    private boolean stopExec(int proxyId) {
        Process process = proxyProcessMap.remove(proxyId);
        Thread outputReader = proxyOutputThreadMap.remove(proxyId);

        if (process == null) {
            return true;
        }

        try {
            // 先尝试优雅终止
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                LOG.warning("frpc 进程未在 5 秒内退出，强制终止");
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            LOG.info("frpc 进程已停止, proxyId=" + proxyId);
            return true;
        } catch (InterruptedException e) {
            LOG.warning("等待 frpc 进程退出被中断");
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 停止所有 frpc 实例
     * @return true 如果全部停止成功
     */
    public boolean stopAll() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            boolean allOk = true;
            for (Integer proxyId : proxyProcessMap.keySet()) {
                allOk = stopExec(proxyId) && allOk;
            }
            return allOk;
        } else {
            proxyInstanceMap.clear();
            return FrpcJnaBridge.stopAll();
        }
    }

    /**
     * 检查 frpc 是否在运行（单实例模式）
     */
    public boolean isRunning() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            return !proxyProcessMap.isEmpty() && 
                   proxyProcessMap.values().stream().anyMatch(Process::isAlive);
        } else {
            return FrpcJnaBridge.isRunning();
        }
    }

    /**
     * 获取所有正在运行的 proxyId 列表
     * @return 包含所有正在运行的 proxyId 的数组
     */
    public int[] getAllRunningProxyIds() {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            // exec 模式：遍历 proxyProcessMap
            return proxyProcessMap.keySet().stream()
                .filter(id -> {
                    Process p = proxyProcessMap.get(id);
                    return p != null && p.isAlive();
                })
                .mapToInt(Integer::intValue)
                .toArray();
        } else {
            // JNA 模式：遍历 proxyInstanceMap
            return proxyInstanceMap.keySet().stream()
                .filter(id -> {
                    Integer instanceId = proxyInstanceMap.get(id);
                    return instanceId != null && FrpcJnaBridge.isRunningWithId(instanceId);
                })
                .mapToInt(Integer::intValue)
                .toArray();
        }
    }

    /**
     * 检查指定 proxyId 的 frpc 实例是否在运行
     */
    public boolean isProxyRunning(int proxyId) {
        DependencyManager.SystemInfo info = dependencyManager != null ?
            dependencyManager.getSystemInfo() : null;

        if (info != null && info.isAndroid) {
            Process process = proxyProcessMap.get(proxyId);
            return process != null && process.isAlive();
        } else {
            Integer instanceId = proxyInstanceMap.get(proxyId);
            if (instanceId == null) return false;
            return FrpcJnaBridge.isRunningWithId(instanceId);
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

package com.xiaofan.launcher;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.xiaofan.launcher.frpc.EasyStartup;
import com.xiaofan.launcher.frpc.FrpcManager;
import com.xiaofan.launcher.frpc.JarUpdater;

/**
 * Fan-ME-FRP-Launcher 主入口
 * 
 * 命令行用法:
 *   java -jar Fan-ME-FRP-Launcher.jar -c config.ini    以 FRPC 客户端模式启动（支持 .ini/.toml/.yaml/.yml/.json）
 *   java -jar Fan-ME-FRP-Launcher.jar -t <runId> -p <proxyId>  快捷启动模式（通过 API 获取配置）
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
 */
public class Main {

    public static void main(String[] args) {
        // ====== 第〇步：检查 JAR 自身版本更新 ======
        String jarDir = getJarDir();
        Path resDir = Paths.get(jarDir, "res");
        boolean updated = JarUpdater.checkAndUpdate(jarDir, resDir);
        if (updated) {
            // 已下载新版本并删除旧文件，退出
            System.out.println("JAR 已更新，请重新启动新版本");
            System.exit(0);
        }

        // 检查命令行参数
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
                        System.err.println("错误: -p 参数必须是数字");
                        System.exit(1);
                    }
                }
            }

            if (configPath != null) {
                // FRPC 命令行模式
                runFrpcClient(configPath);
                return;
            }

            if (runId != null && proxyId > 0) {
                // 快捷启动模式
                runEasyStartup(runId, proxyId);
                return;
            }

            if (runId != null || proxyId > 0) {
                System.err.println("错误: 快捷启动需要同时指定 -t <runId> 和 -p <proxyId>");
                System.err.println("用法: java -jar Fan-ME-FRP-Launcher.jar -t <runId> -p <proxyId>");
                System.exit(1);
            }
        }

        // GUI 模式 - 通过反射启动 JavaFX
        try {
            Class<?> guiClass = Class.forName("com.xiaofan.launcher.GuiMain");
            guiClass.getMethod("launchGui", String[].class).invoke(null, (Object) args);
        } catch (ClassNotFoundException e) {
            System.err.println("错误: GUI 模式需要 JavaFX 运行时组件");
            System.err.println("用法: java -jar Fan-ME-FRP-Launcher.jar -c <配置文件>");
            System.err.println("       java -jar Fan-ME-FRP-Launcher.jar -t <runId> -p <proxyId>");
            System.err.println("支持的配置文件格式: .ini .toml .yaml .yml .json");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("启动 GUI 失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 获取 JAR 所在目录
     */
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

    /**
     * 快捷启动模式 - 通过 API 获取隧道配置并启动 frpc
     */
    private static void runEasyStartup(String runId, int proxyId) {
        System.out.println("Fan-ME-FRP Launcher - 快捷启动模式");
        System.out.println("RunId: " + runId);
        System.out.println("ProxyId: " + proxyId);
        System.out.println();

        // 1. 初始化 FRPC 管理器（下载依赖）
        FrpcManager manager = FrpcManager.getInstance();
        if (!manager.init()) {
            System.err.println("错误: FRPC 初始化失败");
            System.exit(1);
        }

        // 2. 通过 API 获取配置并生成临时 TOML
        EasyStartup easyStartup = new EasyStartup();
        easyStartup.registerCleanupHook();

        Path configFile = easyStartup.execute(runId, proxyId);
        if (configFile == null) {
            System.err.println("错误: 获取隧道配置失败");
            System.exit(1);
        }

        // 3. 启动 frpc
        System.out.println("frpc 版本: " + manager.getVersion());
        if (manager.isExecMode()) {
            System.out.println("启动方式: exec（二进制可执行文件）");
        } else {
            System.out.println("启动方式: JNA（动态库）");
        }
        System.out.println();

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭 FRPC 客户端...");
            if (manager.stop()) {
                System.out.println("FRPC 客户端已关闭");
            }
        }));

        // 启动 frpc
        if (manager.start(configFile.toAbsolutePath().toString())) {
            System.out.println("\nFRPC 客户端已启动，按 Ctrl+C 停止");

            try {
                while (manager.isRunning()) {
                    Thread.sleep(1000);
                }
                System.out.println("FRPC 客户端已停止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            System.err.println("FRPC 客户端启动失败");
            System.exit(1);
        }
    }


    /**
     * 以 FRPC 客户端模式运行（命令行）
     * 自动检测平台并选择合适的启动方式：
     * - Windows/Linux: 通过 JNA 加载动态库
     * - Android/Termux: 通过 exec 启动 frpc 二进制文件
     */
    private static void runFrpcClient(String configPath) {
        File configFile = new File(configPath);
        System.out.println("Fan-ME-FRP Launcher - FRPC 客户端模式");
        System.out.println("配置文件: " + configFile.getAbsolutePath());
        System.out.println();

        // 检查配置文件是否存在
        if (!configFile.exists()) {
            System.err.println("错误: 配置文件不存在: " + configFile.getAbsolutePath());
            System.exit(1);
        }

        // 检查文件扩展名
        String name = configFile.getName().toLowerCase();
        if (!name.endsWith(".ini") && !name.endsWith(".toml") 
            && !name.endsWith(".yaml") && !name.endsWith(".yml") 
            && !name.endsWith(".json")) {
            System.err.println("警告: 不支持的配置文件格式，支持的格式: .ini .toml .yaml .yml .json");
            System.err.println("frp 将尝试自动检测格式...");
        }

        // 初始化 FRPC 管理器
        FrpcManager manager = FrpcManager.getInstance();
        if (!manager.init()) {
            System.err.println("错误: FRPC 初始化失败");
            System.exit(1);
        }

        System.out.println("frpc 版本: " + manager.getVersion());
        if (manager.isExecMode()) {
            System.out.println("启动方式: exec（二进制可执行文件）");
        } else {
            System.out.println("启动方式: JNA（动态库）");
        }
        System.out.println();

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭 FRPC 客户端...");
            if (manager.stop()) {
                System.out.println("FRPC 客户端已关闭");
            }
        }));

        // 启动 frpc
        if (manager.start(configFile.getAbsolutePath())) {
            System.out.println("\nFRPC 客户端已启动，按 Ctrl+C 停止");

            // 保持主线程运行，定期检查状态
            try {
                while (manager.isRunning()) {
                    Thread.sleep(1000);
                }
                System.out.println("FRPC 客户端已停止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            System.err.println("FRPC 客户端启动失败");
            System.exit(1);
        }
    }
}

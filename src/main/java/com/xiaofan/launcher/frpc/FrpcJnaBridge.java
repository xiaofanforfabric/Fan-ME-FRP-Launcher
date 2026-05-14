package com.xiaofan.launcher.frpc;

import java.io.File;
import java.util.logging.Logger;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * JNA 桥接层 - 调用 Go 编译的 frpc_jna 动态库
 * 
 * 不再管理下载，由外部（FrpcManager）统一处理依赖后传入路径。
 */
public class FrpcJnaBridge {

    private static final Logger LOG = Logger.getLogger(FrpcJnaBridge.class.getName());

    private static FrpcJNA jna = null;
    private static boolean loaded = false;

    /**
     * JNA 接口定义 - 对应 Go DLL 导出的 C 函数
     */
    public interface FrpcJNA extends Library {
        int FrpcStart(String configPath);
        int FrpcStop();
        int FrpcIsRunning();
        String FrpcGetVersion();
        String FrpcGetLastError();
        void FrpcFreeString(String str);
        void FrpcSetLogLevel(String level);
    }

    /**
     * 初始化 JNA 桥接层
     * 
     * @param libraryPath frpc 动态库的完整路径
     * @return true 如果加载成功
     */
    public static synchronized boolean init(String libraryPath) {
        if (loaded) {
            return true;
        }

        File libFile = new File(libraryPath);
        if (!libFile.exists()) {
            LOG.severe("动态库不存在: " + libraryPath);
            return false;
        }

        try {
            LOG.info("加载动态库: " + libraryPath);

            // 设置 JNA 库路径并加载
            System.setProperty("jna.library.path", libFile.getParentFile().getAbsolutePath());

            // 去掉扩展名作为库名
            String libName = libFile.getName();
            int dotIndex = libName.lastIndexOf('.');
            if (dotIndex > 0) {
                libName = libName.substring(0, dotIndex);
            }

            jna = Native.load(libName, FrpcJNA.class);
            loaded = true;
            LOG.info("JNA 桥接层初始化成功，frpc 版本: " + getVersion());
            return true;

        } catch (UnsatisfiedLinkError e) {
            LOG.severe("JNA 桥接层初始化失败: " + e.getMessage());
            LOG.severe("请确保动态库文件完整且与系统架构匹配");
            return false;
        }
    }

    /**
     * 启动 frpc 客户端
     */
    public static boolean start(String configPath) {
        if (!loaded || jna == null) {
            LOG.severe("JNA 未初始化，无法启动 frpc");
            return false;
        }

        try {
            int result = jna.FrpcStart(configPath);
            if (result == 0) {
                LOG.info("frpc 启动成功");
                return true;
            } else {
                String error = jna.FrpcGetLastError();
                LOG.severe("frpc 启动失败: " + (error != null ? error : "未知错误"));
                return false;
            }
        } catch (Exception e) {
            LOG.severe("frpc 启动异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 停止 frpc 客户端
     */
    public static boolean stop() {
        if (!loaded || jna == null) {
            return true;
        }

        try {
            int result = jna.FrpcStop();
            LOG.info("frpc 停止" + (result == 0 ? "成功" : "失败"));
            return result == 0;
        } catch (Exception e) {
            LOG.warning("frpc 停止异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 frpc 是否在运行
     */
    public static boolean isRunning() {
        if (!loaded || jna == null) {
            return false;
        }

        try {
            return jna.FrpcIsRunning() == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 frpc 版本号
     */
    public static String getVersion() {
        if (!loaded || jna == null) {
            return "unknown";
        }

        try {
            String version = jna.FrpcGetVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 设置日志级别
     */
    public static void setLogLevel(String level) {
        if (!loaded || jna == null) {
            return;
        }

        try {
            jna.FrpcSetLogLevel(level);
        } catch (Exception e) {
            LOG.warning("设置日志级别失败: " + e.getMessage());
        }
    }
}

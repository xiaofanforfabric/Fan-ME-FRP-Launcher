package com.xiaofan.appservices;

import java.io.IOException;

/**
 * 更新服务器主入口
 *
 * 启动两个 HTTP 服务：
 *   - 管理页面：端口 4101（默认）— 对 tpca.json 和 app.json 进行 CRUD 操作
 *   - API 服务：端口 4102（默认）— 对外提供查询接口
 *
 * 数据文件（在 JAR 同级目录创建）：
 *   - tpca.json — 完整更新日志
 *   - app.json  — 仅存版本号
 *
 * 命令行参数：
 *   --admin-port=4101   管理页面端口
 *   --api-port=4102     API 端口
 *   --tpca-file=tpca.json  tpca.json 路径
 *   --app-file=app.json     app.json 路径
 */
public class AppServicesMain {

    public static void main(String[] args) {
        // 解析命令行参数
        AppConfig.parseArgs(args);

        System.out.println("========================================");
        System.out.println("  Fan-ME-FRP 更新服务器 v2.0");
        System.out.println("========================================");
        System.out.println("  管理页面: http://localhost:" + AppConfig.ADMIN_PORT);
        System.out.println("  API 服务: http://localhost:" + AppConfig.API_PORT);
        System.out.println("  tpca.json: " + AppConfig.TPCA_FILE);
        System.out.println("  app.json:  " + AppConfig.APP_FILE);
        System.out.println("========================================");

        // 加载数据
        ChangelogData changelogData = ChangelogData.load(AppConfig.TPCA_FILE);
        AppData appData = AppData.load(AppConfig.APP_FILE);
        System.out.println("  已加载 " + changelogData.getData().size() + " 个版本记录");
        System.out.println("  当前版本: " + (appData.getVersion().isEmpty() ? "未设置" : appData.getVersion()));

        // 启动 API 服务（HTTP）
        ApiServer apiServer = new ApiServer(
            AppConfig.API_PORT,
            changelogData,
            appData,
            AppConfig.TPCA_FILE,
            AppConfig.APP_FILE
        );
        try {
            apiServer.start();
            System.out.println("  ✅ API 服务已启动 (端口 " + AppConfig.API_PORT + ")");
        } catch (IOException e) {
            System.err.println("  ❌ API 服务启动失败: " + e.getMessage());
            System.exit(1);
        }

        // 启动管理页面服务
        AdminServer adminServer = new AdminServer(AppConfig.ADMIN_PORT);
        adminServer.setData(changelogData, appData, AppConfig.TPCA_FILE, AppConfig.APP_FILE);
        try {
            adminServer.start();
            System.out.println("  ✅ 管理页面已启动 (端口 " + AppConfig.ADMIN_PORT + ")");
        } catch (IOException e) {
            System.err.println("  ❌ 管理页面启动失败: " + e.getMessage());
            System.exit(1);
        }

        // ====== 初始化并启动 Cloudflare Tunnel ======
        CloudflaredManager cloudflaredManager = new CloudflaredManager();
        adminServer.setCloudflaredManager(cloudflaredManager);

        if (cloudflaredManager.init()) {
            System.out.println("  ✅ cloudflared 已就绪");
            if (!AppConfig.CLOUDFLARED_TOKEN.isEmpty()) {
                System.out.println("  正在启动 Cloudflare Tunnel...");
                if (cloudflaredManager.startTunnel(AppConfig.CLOUDFLARED_TOKEN)) {
                    System.out.println("  ✅ Cloudflare Tunnel 已启动");
                } else {
                    System.err.println("  ❌ Cloudflare Tunnel 启动失败");
                }
            } else {
                System.out.println("  ⚠️ 未设置 --cf-token，Cloudflare Tunnel 未启动");
                System.out.println("  可通过管理页面手动启动或添加 --cf-token=xxx 参数");
            }
        } else {
            System.err.println("  ❌ cloudflared 初始化失败");
        }

        System.out.println("========================================");
        System.out.println("  服务器运行中...");
        System.out.println("  管理页面: http://localhost:" + AppConfig.ADMIN_PORT);
        System.out.println("  tpca.json: http://localhost:" + AppConfig.API_PORT + "/tpca.json");
        System.out.println("  最新版本: http://localhost:" + AppConfig.API_PORT + "/version/last");
        System.out.println("  全部版本: http://localhost:" + AppConfig.API_PORT + "/version/all");
        System.out.println("========================================");

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭服务器...");
            cloudflaredManager.stopTunnel();
            apiServer.stop();
            adminServer.stop();
            System.out.println("服务器已关闭");
        }));

        // 保持主线程运行，等待 Ctrl+C 关闭
        System.out.println("  按 Ctrl+C 停止服务器");
        System.out.println("========================================");
        Object waitLock = new Object();
        synchronized (waitLock) {
            try {
                waitLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

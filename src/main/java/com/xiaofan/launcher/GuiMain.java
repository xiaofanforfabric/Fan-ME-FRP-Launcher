package com.xiaofan.launcher;

import com.xiaofan.launcher.api.GuiApiServer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * GUI 主入口（需要 JavaFX）
 * 通过 Main 类的反射调用
 * 启动时自动开启 GUI API 服务 (127.0.0.1:1023)
 */
public class GuiMain extends Application {

    private GuiApiServer apiServer;

    public static void launchGui(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // 启动 GUI API 服务
        apiServer = new GuiApiServer();
        apiServer.start();

        // 创建主界面
        LauncherUI launcherUI = new LauncherUI();
        Scene scene = new Scene(launcherUI, 1200, 800);

        // 设置窗口
        primaryStage.setTitle("Fan-ME-FRP Launcher");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // 窗口关闭时清理资源
        primaryStage.setOnCloseRequest(e -> {
            launcherUI.shutdown();
            if (apiServer != null) {
                apiServer.stop();
            }
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
    }
}



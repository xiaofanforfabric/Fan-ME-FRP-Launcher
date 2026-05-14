package com.xiaofan.launcher;

import com.xiaofan.launcher.browser.BrowserEngine;
import com.xiaofan.launcher.browser.BrowserTab;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * 启动器主界面
 * 包含浏览器标签页、导航栏和侧边栏
 */
public class LauncherUI extends BorderPane {

    private final TabPane tabPane;
    private final BrowserEngine browserEngine;
    private TextField urlBar;
    private Button backBtn;
    private Button forwardBtn;
    private Button refreshBtn;

    public LauncherUI() {
        this.browserEngine = new BrowserEngine();

        // 顶部导航栏
        VBox topBar = createTopBar();
        this.setTop(topBar);

        // 中间标签页区域
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        this.setCenter(tabPane);

        // 底部状态栏
        HBox statusBar = createStatusBar();
        this.setBottom(statusBar);

        // 创建默认标签页
        createNewTab("https://www.mefrp.com");

        // 应用样式
        applyStyles();
    }

    /**
     * 创建顶部导航栏
     */
    private VBox createTopBar() {
        VBox topBar = new VBox();
        topBar.setStyle("-fx-background-color: #2c3e50;");

        // 标题栏
        HBox titleBar = new HBox(10);
        titleBar.setPadding(new Insets(8, 15, 8, 15));
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Fan-ME-FRP Launcher");
        title.setFont(Font.font("Microsoft YaHei", 16));
        title.setTextFill(Color.WHITE);
        HBox.setHgrow(title, Priority.ALWAYS);

        // 窗口控制按钮
        Button minBtn = createWindowBtn("-");
        Button closeBtn = createWindowBtn("×");
        closeBtn.setOnAction(e -> {
            shutdown();
            javafx.application.Platform.exit();
            System.exit(0);
        });

        titleBar.getChildren().addAll(title, minBtn, closeBtn);

        // 导航栏
        HBox navBar = new HBox(5);
        navBar.setPadding(new Insets(5, 15, 10, 15));
        navBar.setAlignment(Pos.CENTER_LEFT);

        // 导航按钮
        backBtn = createNavBtn("◀");
        forwardBtn = createNavBtn("▶");
        refreshBtn = createNavBtn("⟳");

        backBtn.setOnAction(e -> navigateBack());
        forwardBtn.setOnAction(e -> navigateForward());
        refreshBtn.setOnAction(e -> refreshCurrentTab());

        // 地址栏
        urlBar = new TextField();
        urlBar.setPromptText("输入网址...");
        urlBar.setStyle(
                "-fx-background-color: white;" +
                "-fx-background-radius: 15;" +
                "-fx-padding: 5 15;" +
                "-fx-font-size: 13px;"
        );
        urlBar.setOnAction(e -> navigateTo(urlBar.getText()));
        HBox.setHgrow(urlBar, Priority.ALWAYS);

        // 新标签页按钮
        Button newTabBtn = new Button("+");
        newTabBtn.setStyle(
                "-fx-background-color: #3498db;" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 15;" +
                "-fx-font-size: 16px;" +
                "-fx-font-weight: bold;" +
                "-fx-min-width: 30;" +
                "-fx-min-height: 30;"
        );
        newTabBtn.setOnAction(e -> createNewTab("https://www.mefrp.com"));

        navBar.getChildren().addAll(backBtn, forwardBtn, refreshBtn, urlBar, newTabBtn);

        topBar.getChildren().addAll(titleBar, navBar);
        return topBar;
    }

    /**
     * 创建窗口控制按钮
     */
    private Button createWindowBtn(String text) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 18px;" +
                "-fx-cursor: hand;"
        );
        return btn;
    }

    /**
     * 创建导航按钮
     */
    private Button createNavBtn(String text) {
        Button btn = new Button(text);
        btn.setStyle(
                "-fx-background-color: #34495e;" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 5;" +
                "-fx-font-size: 14px;" +
                "-fx-min-width: 32;" +
                "-fx-min-height: 32;" +
                "-fx-cursor: hand;"
        );
        return btn;
    }

    /**
     * 创建底部状态栏
     */
    private HBox createStatusBar() {
        HBox statusBar = new HBox();
        statusBar.setPadding(new Insets(3, 15, 3, 15));
        statusBar.setStyle("-fx-background-color: #ecf0f1;");

        Label statusLabel = new Label("就绪");
        statusLabel.setFont(Font.font("Microsoft YaHei", 11));
        statusLabel.setTextFill(Color.web("#7f8c8d"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label versionLabel = new Label("v1.0.0 | 纯Java浏览器内核");
        versionLabel.setFont(Font.font("Microsoft YaHei", 11));
        versionLabel.setTextFill(Color.web("#95a5a6"));

        statusBar.getChildren().addAll(statusLabel, spacer, versionLabel);
        return statusBar;
    }

    /**
     * 创建新的浏览器标签页
     */
    public void createNewTab(String url) {
        BrowserTab browserTab = new BrowserTab(browserEngine, url);
        Tab tab = new Tab();
        tab.setText(getTabTitle(url));
        tab.setContent(browserTab);
        tab.setOnClosed(e -> browserTab.dispose());
        tab.setOnSelectionChanged(e -> {
            if (tab.isSelected()) {
                urlBar.setText(browserTab.getCurrentUrl());
            }
        });

        // 监听页面标题变化
        browserTab.titleProperty().addListener((obs, old, title) -> {
            if (title != null && !title.isEmpty()) {
                tab.setText(title.length() > 20 ? title.substring(0, 20) + "..." : title);
            }
        });

        // 监听URL变化
        browserTab.urlProperty().addListener((obs, old, newUrl) -> {
            if (tab.isSelected() && newUrl != null) {
                urlBar.setText(newUrl);
            }
        });

        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        urlBar.setText(url);
    }

    /**
     * 导航到指定URL
     */
    public void navigateTo(String url) {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            BrowserTab browserTab = (BrowserTab) selectedTab.getContent();
            browserTab.loadUrl(url);
        }
    }

    /**
     * 后退
     */
    public void navigateBack() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            BrowserTab browserTab = (BrowserTab) selectedTab.getContent();
            browserTab.goBack();
        }
    }

    /**
     * 前进
     */
    public void navigateForward() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            BrowserTab browserTab = (BrowserTab) selectedTab.getContent();
            browserTab.goForward();
        }
    }

    /**
     * 刷新当前标签页
     */
    public void refreshCurrentTab() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            BrowserTab browserTab = (BrowserTab) selectedTab.getContent();
            browserTab.refresh();
        }
    }

    /**
     * 获取标签页标题
     */
    private String getTabTitle(String url) {
        if (url == null || url.isEmpty()) return "新标签页";
        try {
            String domain = url.replace("https://", "").replace("http://", "");
            int slashIndex = domain.indexOf('/');
            return slashIndex > 0 ? domain.substring(0, slashIndex) : domain;
        } catch (Exception e) {
            return "新标签页";
        }
    }

    /**
     * 应用样式
     */
    private void applyStyles() {
        this.setStyle("-fx-background-color: #f5f6fa;");
    }

    /**
     * 关闭清理
     */
    public void shutdown() {
        browserEngine.shutdown();
    }
}

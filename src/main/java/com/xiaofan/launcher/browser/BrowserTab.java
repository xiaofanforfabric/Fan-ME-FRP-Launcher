package com.xiaofan.launcher.browser;

import com.xiaofan.launcher.browser.BrowserEngine.HttpResponse;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;

import java.io.IOException;

/**
 * 浏览器标签页
 * 使用纯 Java 实现的浏览器引擎 + HtmlParser 获取网页内容
 * 通过 JavaFX WebView 渲染（如果可用）或纯文本显示
 */
public class BrowserTab extends StackPane {

    private final BrowserEngine browserEngine;
    private final StringProperty titleProperty;
    private final StringProperty urlProperty;
    private String currentUrl;
    private boolean isLoading;

    // 渲染组件
    private WebView webView;
    private ScrollPane textScrollPane;

    public BrowserTab(BrowserEngine browserEngine, String url) {
        this.browserEngine = browserEngine;
        this.titleProperty = new SimpleStringProperty("");
        this.urlProperty = new SimpleStringProperty("");

        // 尝试创建 WebView
        try {
            this.webView = new WebView();
            configureWebView();
            this.getChildren().add(webView);
        } catch (Exception e) {
            // WebView 不可用，使用纯文本显示
            this.textScrollPane = new ScrollPane();
            this.textScrollPane.setFitToWidth(true);
            this.textScrollPane.setFitToHeight(true);
            this.getChildren().add(textScrollPane);
        }

        // 加载初始页面
        if (url != null && !url.isEmpty()) {
            loadUrl(url);
        }
    }

    /**
     * 配置 WebView
     */
    private void configureWebView() {
        if (webView == null) return;
        try {
            webView.getEngine().setJavaScriptEnabled(true);
            webView.getEngine().setUserAgent(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36 " +
                    "Fan-ME-FRP-Launcher/1.0"
            );
            webView.setPrefWidth(1200);
            webView.setPrefHeight(800);
            webView.setContextMenuEnabled(true);
            webView.setZoom(1.0);

            // 监听加载状态
            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                switch (newState) {
                    case RUNNING:
                        isLoading = true;
                        break;
                    case SUCCEEDED:
                        isLoading = false;
                        updateTitleAndUrl();
                        break;
                    case FAILED:
                        isLoading = false;
                        break;
                    default:
                        break;
                }
            });

            webView.getEngine().titleProperty().addListener((obs, old, title) -> {
                if (title != null && !title.isEmpty()) {
                    Platform.runLater(() -> titleProperty.set(title));
                }
            });

            webView.getEngine().locationProperty().addListener((obs, old, location) -> {
                if (location != null && !location.isEmpty()) {
                    currentUrl = location;
                    Platform.runLater(() -> urlProperty.set(location));
                }
            });
        } catch (Exception e) {
            System.err.println("WebView 配置失败: " + e.getMessage());
        }
    }

    private void updateTitleAndUrl() {
        if (webView == null) return;
        try {
            String title = webView.getEngine().getTitle();
            if (title != null && !title.isEmpty()) {
                Platform.runLater(() -> titleProperty.set(title));
            }
            String location = webView.getEngine().getLocation();
            if (location != null && !location.isEmpty()) {
                currentUrl = location;
                Platform.runLater(() -> urlProperty.set(location));
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 加载 URL
     */
    public void loadUrl(String url) {
        if (url == null || url.isEmpty()) return;

        String processedUrl = url;
        if (!processedUrl.startsWith("http://") && !processedUrl.startsWith("https://")) {
            processedUrl = "https://" + processedUrl;
        }

        final String targetUrl = processedUrl;
        this.currentUrl = targetUrl;
        Platform.runLater(() -> {
            urlProperty.set(targetUrl);
            if (webView != null) {
                try {
                    webView.getEngine().load(targetUrl);
                } catch (Exception e) {
                    loadWithEngine(targetUrl);
                }
            } else {
                loadWithEngine(targetUrl);
            }
        });
    }

    /**
     * 使用纯 Java 引擎加载页面
     */
    private void loadWithEngine(String url) {
        new Thread(() -> {
            try {
                HttpResponse response = browserEngine.sendGet(url);
                String html = response.getBody();
                HtmlParser parser = new HtmlParser(html, url);

                String title = parser.getTitle();
                if (title != null && !title.isEmpty()) {
                    Platform.runLater(() -> titleProperty.set(title));
                }

                // 显示为格式化文本
                String displayContent = formatAsHtml(parser, url);
                Platform.runLater(() -> {
                    if (webView != null) {
                        webView.getEngine().loadContent(displayContent);
                    }
                });

            } catch (IOException e) {
                Platform.runLater(() -> {
                    String errorHtml = createErrorHtml("加载失败: " + e.getMessage(), url);
                    if (webView != null) {
                        webView.getEngine().loadContent(errorHtml);
                    }
                });
            }
        }).start();
    }

    /**
     * 将解析的 HTML 格式化为可读内容
     */
    private String formatAsHtml(HtmlParser parser, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<style>");
        sb.append("body { font-family: 'Microsoft YaHei', sans-serif; padding: 20px; background: #f5f6fa; color: #2c3e50; }");
        sb.append(".header { background: #2c3e50; color: white; padding: 15px 20px; border-radius: 8px; margin-bottom: 20px; }");
        sb.append(".header h1 { margin: 0; font-size: 18px; }");
        sb.append(".header .url { font-size: 12px; color: #bdc3c7; margin-top: 5px; word-break: break-all; }");
        sb.append(".content { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }");
        sb.append(".content p { line-height: 1.8; font-size: 14px; }");
        sb.append(".links { margin-top: 20px; }");
        sb.append(".links h3 { color: #3498db; font-size: 14px; }");
        sb.append(".links a { display: block; padding: 5px 0; color: #2980b9; font-size: 13px; text-decoration: none; }");
        sb.append(".links a:hover { color: #e74c3c; }");
        sb.append(".meta { font-size: 12px; color: #95a5a6; margin-top: 20px; padding-top: 10px; border-top: 1px solid #ecf0f1; }");
        sb.append("</style></head><body>");

        // 头部
        sb.append("<div class='header'>");
        sb.append("<h1>").append(escapeHtml(parser.getTitle())).append("</h1>");
        sb.append("<div class='url'>").append(escapeHtml(url)).append("</div>");
        sb.append("</div>");

        // 内容
        sb.append("<div class='content'>");
        String text = parser.getTextContent();
        if (text.length() > 5000) {
            text = text.substring(0, 5000) + "...";
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                sb.append("<p>").append(escapeHtml(trimmed)).append("</p>");
            }
        }
        sb.append("</div>");

        // 链接
        if (!parser.getLinks().isEmpty()) {
            sb.append("<div class='links'>");
            sb.append("<h3>页面链接 (").append(parser.getLinks().size()).append(" 个)</h3>");
            int count = 0;
            for (HtmlParser.Link link : parser.getLinks()) {
                if (count++ >= 20) break;
                sb.append("<a href='").append(escapeHtml(link.getUrl())).append("'>")
                  .append(escapeHtml(link.getText().isEmpty() ? link.getUrl() : link.getText()))
                  .append("</a>");
            }
            sb.append("</div>");
        }

        // Meta 信息
        sb.append("<div class='meta'>");
        sb.append("由 Fan-ME-FRP Launcher 纯 Java 浏览器引擎渲染");
        sb.append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 创建错误页面
     */
    private String createErrorHtml(String message, String url) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<style>body{font-family:'Microsoft YaHei',sans-serif;padding:40px;background:#f5f6fa;}" +
               ".error{max-width:600px;margin:auto;text-align:center;}" +
               "h2{color:#e74c3c;}p{color:#7f8c8d;font-size:14px;}" +
               ".url{color:#95a5a6;font-size:12px;word-break:break-all;}" +
               "</style></head><body>" +
               "<div class='error'>" +
               "<h2>\u26A0 页面加载失败</h2>" +
               "<p>" + escapeHtml(message) + "</p>" +
               "<p class='url'>" + escapeHtml(url) + "</p>" +
               "</div></body></html>";
    }

    /**
     * HTML 转义
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&"); break;
                case '<': sb.append("<"); break;
                case '>': sb.append(">"); break;
                case '"': sb.append("&#34;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 加载 HTML 内容
     */
    public void loadContent(String content) {
        Platform.runLater(() -> {
            if (webView != null) {
                try {
                    webView.getEngine().loadContent(content, "text/html");
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    /**
     * 后退
     */
    public void goBack() {
        Platform.runLater(() -> {
            if (webView != null) {
                try {
                    if (webView.getEngine().getHistory().getCurrentIndex() > 0) {
                        webView.getEngine().getHistory().go(-1);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    /**
     * 前进
     */
    public void goForward() {
        Platform.runLater(() -> {
            if (webView != null) {
                try {
                    if (webView.getEngine().getHistory().getCurrentIndex() < 
                        webView.getEngine().getHistory().getEntries().size() - 1) {
                        webView.getEngine().getHistory().go(1);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }

    /**
     * 刷新
     */
    public void refresh() {
        if (currentUrl != null) {
            loadUrl(currentUrl);
        }
    }

    /**
     * 停止加载
     */
    public void stop() {
        isLoading = false;
    }

    /**
     * 获取当前 URL
     */
    public String getCurrentUrl() {
        return currentUrl;
    }

    /**
     * 标题属性
     */
    public ReadOnlyStringProperty titleProperty() {
        return titleProperty;
    }

    /**
     * URL 属性
     */
    public ReadOnlyStringProperty urlProperty() {
        return urlProperty;
    }

    /**
     * 是否正在加载
     */
    public boolean isLoading() {
        return isLoading;
    }

    /**
     * 清理资源
     */
    public void dispose() {
        try {
            stop();
            if (webView != null) {
                webView.getEngine().load(null);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}

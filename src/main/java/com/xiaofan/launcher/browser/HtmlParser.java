package com.xiaofan.launcher.browser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 实现的 HTML 解析器
 * 
 * 功能：
 * - 解析 HTML 文档结构
 * - 提取标题、文本内容
 * - 提取链接、图片等资源
 * - 解析 CSS 和 JavaScript
 * - 构建 DOM 树
 */
public class HtmlParser {

    private final String rawHtml;
    private final String baseUrl;
    private Document document;

    // 正则模式
    private static final Pattern TITLE_PATTERN = 
            Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_PATTERN = 
            Pattern.compile("<a\\s+[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>([^<]*)</a>", 
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG_PATTERN = 
            Pattern.compile("<img\\s+[^>]*src\\s*=\\s*\"([^\"]*)\"[^>]*>", 
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_PATTERN = 
            Pattern.compile("<meta\\s+[^>]*name\\s*=\\s*\"([^\"]*)\"[^>]*content\\s*=\\s*\"([^\"]*)\"[^>]*>", 
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_PATTERN = 
            Pattern.compile("<script[^>]*>([^<]*)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CSS_PATTERN = 
            Pattern.compile("<link\\s+[^>]*href\\s*=\\s*\"([^\"]*\\.css[^\"]*)\"[^>]*>", 
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_PATTERN = 
            Pattern.compile("<body[^>]*>([\\s\\S]*)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TEXT_CONTENT_PATTERN = 
            Pattern.compile(">([^<]+)<", Pattern.DOTALL);

    public HtmlParser(String rawHtml, String baseUrl) {
        this.rawHtml = rawHtml;
        this.baseUrl = baseUrl;
        parse();
    }

    /**
     * 解析 HTML 文档
     */
    private void parse() {
        this.document = new Document();
        document.title = extractTitle();
        document.bodyContent = extractBody();
        document.links = extractLinks();
        document.images = extractImages();
        document.metaTags = extractMetaTags();
        document.scripts = extractScripts();
        document.cssFiles = extractCssFiles();
        document.textContent = extractTextContent();
    }

    /**
     * 提取页面标题
     */
    public String extractTitle() {
        Matcher matcher = TITLE_PATTERN.matcher(rawHtml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * 提取 body 内容
     */
    public String extractBody() {
        Matcher matcher = BODY_PATTERN.matcher(rawHtml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return rawHtml;
    }

    /**
     * 提取所有链接
     */
    public List<Link> extractLinks() {
        List<Link> links = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            String href = matcher.group(1).trim();
            String text = matcher.group(2).trim();
            if (!href.isEmpty() && !href.startsWith("#") && !href.startsWith("javascript:")) {
                links.add(new Link(resolveUrl(href), text));
            }
        }
        return links;
    }

    /**
     * 提取所有图片
     */
    public List<String> extractImages() {
        List<String> images = new ArrayList<>();
        Matcher matcher = IMG_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            String src = matcher.group(1).trim();
            if (!src.isEmpty()) {
                images.add(resolveUrl(src));
            }
        }
        return images;
    }

    /**
     * 提取 Meta 标签
     */
    public Map<String, String> extractMetaTags() {
        Map<String, String> metaTags = new HashMap<>();
        Matcher matcher = META_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            String name = matcher.group(1).trim().toLowerCase();
            String content = matcher.group(2).trim();
            metaTags.put(name, content);
        }
        return metaTags;
    }

    /**
     * 提取 Script 内容
     */
    public List<String> extractScripts() {
        List<String> scripts = new ArrayList<>();
        Matcher matcher = SCRIPT_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            String script = matcher.group(1).trim();
            if (!script.isEmpty()) {
                scripts.add(script);
            }
        }
        return scripts;
    }

    /**
     * 提取 CSS 文件链接
     */
    public List<String> extractCssFiles() {
        List<String> cssFiles = new ArrayList<>();
        Matcher matcher = CSS_PATTERN.matcher(rawHtml);
        while (matcher.find()) {
            String href = matcher.group(1).trim();
            if (!href.isEmpty()) {
                cssFiles.add(resolveUrl(href));
            }
        }
        return cssFiles;
    }

    /**
     * 提取纯文本内容
     */
    public String extractTextContent() {
        // 移除 script 和 style 标签
        String text = rawHtml.replaceAll("<script[^>]*>[^<]*</script>", " ");
        text = text.replaceAll("<style[^>]*>[^<]*</style>", " ");
        // 移除所有 HTML 标签
        text = text.replaceAll("<[^>]+>", " ");
        // 合并空白
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /**
     * 解析相对 URL 为绝对 URL
     */
    private String resolveUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            try {
                java.net.URI uri = new java.net.URI(baseUrl);
                return uri.getScheme() + "://" + uri.getHost() + 
                       (uri.getPort() > 0 ? ":" + uri.getPort() : "") + url;
            } catch (Exception e) {
                return baseUrl + url;
            }
        }
        // 相对路径
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
        return base + url;
    }

    /**
     * 获取解析后的文档
     */
    public Document getDocument() {
        return document;
    }

    /**
     * 获取页面标题
     */
    public String getTitle() {
        return document.title;
    }

    /**
     * 获取纯文本内容
     */
    public String getTextContent() {
        return document.textContent;
    }

    /**
     * 获取所有链接
     */
    public List<Link> getLinks() {
        return document.links;
    }

    /**
     * 获取所有图片
     */
    public List<String> getImages() {
        return document.images;
    }

    /**
     * 检查页面是否包含指定文本
     */
    public boolean containsText(String text) {
        return document.textContent.toLowerCase().contains(text.toLowerCase());
    }

    /**
     * 文档模型
     */
    public static class Document {
        private String title;
        private String bodyContent;
        private List<Link> links;
        private List<String> images;
        private Map<String, String> metaTags;
        private List<String> scripts;
        private List<String> cssFiles;
        private String textContent;

        public String getTitle() { return title; }
        public String getBodyContent() { return bodyContent; }
        public List<Link> getLinks() { return links; }
        public List<String> getImages() { return images; }
        public Map<String, String> getMetaTags() { return metaTags; }
        public List<String> getScripts() { return scripts; }
        public List<String> getCssFiles() { return cssFiles; }
        public String getTextContent() { return textContent; }
    }

    /**
     * 链接模型
     */
    public static class Link {
        private final String url;
        private final String text;

        public Link(String url, String text) {
            this.url = url;
            this.text = text;
        }

        public String getUrl() { return url; }
        public String getText() { return text; }

        @Override
        public String toString() {
            return text + " -> " + url;
        }
    }
}

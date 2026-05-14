package com.xiaofan.launcher.browser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 实现的浏览器引擎
 * 
 * 核心功能：
 * - HTTP/HTTPS 请求处理
 * - HTML 内容获取
 * - Cookie 管理
 * - 会话管理
 * - 请求/响应头处理
 * - 重定向处理
 * - 缓存控制
 */
public class BrowserEngine {

    private final CookieManager cookieManager;
    private final Map<String, String> sessionStorage;
    private final ExecutorService executorService;
    private final String userAgent;
    private final Map<String, List<String>> defaultHeaders;

    public BrowserEngine() {
        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);

        this.sessionStorage = new ConcurrentHashMap<>();
        this.executorService = Executors.newFixedThreadPool(4);
        this.userAgent = "Fan-ME-FRP-Launcher/1.0 (compatible; Java Browser Engine)";

        this.defaultHeaders = new LinkedHashMap<>();
        defaultHeaders.put("User-Agent", List.of(userAgent));
        defaultHeaders.put("Accept", List.of(
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        ));
        defaultHeaders.put("Accept-Language", List.of("zh-CN,zh;q=0.9,en;q=0.8"));
        defaultHeaders.put("Accept-Encoding", List.of("gzip, deflate"));
        defaultHeaders.put("Connection", List.of("keep-alive"));
        defaultHeaders.put("Cache-Control", List.of("max-age=0"));
    }

    /**
     * 发送 HTTP GET 请求
     */
    public HttpResponse sendGet(String url) throws IOException {
        return sendRequest(url, "GET", null, null);
    }

    /**
     * 发送 HTTP GET 请求（带自定义头）
     */
    public HttpResponse sendGet(String url, Map<String, String> headers) throws IOException {
        return sendRequest(url, "GET", headers, null);
    }

    /**
     * 发送 HTTP POST 请求
     */
    public HttpResponse sendPost(String url, Map<String, String> headers, String body) throws IOException {
        return sendRequest(url, "POST", headers, body);
    }

    /**
     * 发送 HTTP 请求（核心方法）
     */
    public HttpResponse sendRequest(String url, String method,
                                     Map<String, String> customHeaders,
                                     String body) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();

            // 设置请求方法
            connection.setRequestMethod(method.toUpperCase());
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            // 设置默认请求头
            for (Map.Entry<String, List<String>> entry : defaultHeaders.entrySet()) {
                for (String value : entry.getValue()) {
                    connection.setRequestProperty(entry.getKey(), value);
                }
            }

            // 设置自定义请求头
            if (customHeaders != null) {
                for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            // 设置请求体（POST 等）
            if (body != null && !body.isEmpty()) {
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            // 发送请求并获取响应
            int responseCode = connection.getResponseCode();

            // 处理重定向
            if (responseCode >= 300 && responseCode < 400) {
                String redirectUrl = connection.getHeaderField("Location");
                if (redirectUrl != null) {
                    if (!redirectUrl.startsWith("http")) {
                        try {
                            URI baseUri = requestUrl.toURI();
                            redirectUrl = baseUri.resolve(redirectUrl).toString();
                        } catch (URISyntaxException e) {
                            // 如果 URI 解析失败，尝试简单拼接
                            String base = url.substring(0, url.lastIndexOf('/') + 1);
                            redirectUrl = base + (redirectUrl.startsWith("/") ? redirectUrl.substring(1) : redirectUrl);
                        }
                    }
                    return sendRequest(redirectUrl, method, customHeaders, body);
                }
            }

            // 读取响应
            String responseBody = readResponse(connection);
            Map<String, List<String>> responseHeaders = connection.getHeaderFields();

            return new HttpResponse(responseCode, responseBody, responseHeaders, url);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 读取响应内容（支持 GZIP 解压）
     */
    private String readResponse(HttpURLConnection connection) throws IOException {
        String contentEncoding = connection.getContentEncoding();
        InputStream inputStream;

        try {
            if (responseCodeIsError(connection.getResponseCode())) {
                inputStream = connection.getErrorStream();
            } else {
                inputStream = connection.getInputStream();
            }
        } catch (IOException e) {
            inputStream = connection.getErrorStream();
        }

        if (inputStream == null) {
            return "";
        }

        try {
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                try (GZIPInputStream gzipStream = new GZIPInputStream(inputStream);
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(gzipStream, StandardCharsets.UTF_8))) {
                    return readAll(reader);
                }
            } else {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    return readAll(reader);
                }
            }
        } finally {
            inputStream.close();
        }
    }

    private boolean responseCodeIsError(int code) {
        return code >= 400;
    }

    private String readAll(BufferedReader reader) throws IOException {
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[8192];
        int bytesRead;
        while ((bytesRead = reader.read(buffer, 0, buffer.length)) != -1) {
            content.append(buffer, 0, bytesRead);
        }
        return content.toString();
    }

    /**
     * 异步发送请求
     */
    public CompletableFuture<HttpResponse> sendGetAsync(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendGet(url);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }

    /**
     * 获取 Cookie
     */
    public String getCookies(String url) {
        try {
            URI uri = new URI(url);
            Map<String, List<String>> cookieHeaders = new HashMap<>();
            cookieHeaders.put("Cookie", List.of());
            Map<String, List<String>> cookies = cookieManager.get(uri, cookieHeaders);
            if (cookies != null && cookies.containsKey("Cookie")) {
                return String.join("; ", cookies.get("Cookie"));
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 设置 Cookie
     */
    public void setCookie(String url, String cookie) {
        try {
            URI uri = new URI(url);
            Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Set-Cookie", List.of(cookie));
            cookieManager.put(uri, responseHeaders);
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 存储会话数据
     */
    public void setSessionItem(String key, String value) {
        sessionStorage.put(key, value);
    }

    /**
     * 获取会话数据
     */
    public String getSessionItem(String key) {
        return sessionStorage.get(key);
    }

    /**
     * 清除所有 Cookie
     */
    public void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    /**
     * 关闭引擎
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * HTTP 响应封装
     */
    public static class HttpResponse {
        private final int statusCode;
        private final String body;
        private final Map<String, List<String>> headers;
        private final String url;

        public HttpResponse(int statusCode, String body,
                            Map<String, List<String>> headers, String url) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
            this.url = url;
        }

        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }
        public Map<String, List<String>> getHeaders() { return headers; }
        public String getUrl() { return url; }

        public String getHeader(String name) {
            List<String> values = headers.get(name);
            if (values != null && !values.isEmpty()) {
                return values.get(0);
            }
            // 尝试不区分大小写查找
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> vals = entry.getValue();
                    return vals != null && !vals.isEmpty() ? vals.get(0) : null;
                }
            }
            return null;
        }

        public String getContentType() {
            return getHeader("Content-Type");
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        @Override
        public String toString() {
            return "HttpResponse{" +
                    "statusCode=" + statusCode +
                    ", url='" + url + '\'' +
                    ", bodyLength=" + (body != null ? body.length() : 0) +
                    '}';
        }
    }
}

package com.xiaofan.launcher.api;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.xiaofan.launcher.frpc.FrpcManager;
import com.xiaofan.launcher.logs.DebugCrashException;
import com.xiaofan.launcher.logs.DebugErrorException;
import com.xiaofan.launcher.logs.ErrorReporter;

/**
 * GUI API 服务 - 轻量 HTTP 服务器
 * 
 * 绑定 127.0.0.1:1023，仅允许本地调用
 * 提供 RESTful API 和前端静态资源服务
 * 
 * API:
 *   POST /api/login
 *     Body: {"accesstoken": "xxx"}
 *     先调用 ME Frp API 验证 token，通过后启动 frpc 并保存 token
 * 
 *   GET /api/status
 *     获取当前登录状态（检查 config.json 中的 token 是否有效）
 * 
 * 静态资源:
 *   GET /login.html          → res/index/login.html
 *   GET /js/xxx.js           → res/index/js/xxx.js
 *   GET /css/xxx.css         → res/index/css/xxx.css
 *   GET /                    → 重定向到 /login.html
 */
public class GuiApiServer {

    private static final Logger LOG = Logger.getLogger(GuiApiServer.class.getName());
    private static final String BIND_HOST = "127.0.0.1";
    private static final int BIND_PORT = 1025;

    private static final int THREAD_POOL_SIZE = 4;
    private static final String ME_FRP_API = "https://api.mefrp.com/api";
    private static final String CONFIG_FILE_NAME = "config.json";

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;
    private volatile boolean debugMode = false;
    private Path staticRoot;
    private Path resDir;

    public GuiApiServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        String jarDir = getJarDir();
        this.resDir = Paths.get(jarDir, "res");
        this.staticRoot = resDir.resolve("index");
    }

    /**
     * 设置静态资源根目录
     */
    public void setStaticRoot(Path staticRoot) {
        this.staticRoot = staticRoot;
    }

    /**
     * 设置调试模式
     * 仅在调试模式下，/debug/crash API 才可用
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /**
     * 启动 API 服务器
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(BIND_PORT, 50, InetAddress.getByName(BIND_HOST));
            running = true;
            System.out.println("GUI API 服务已启动: http://" + BIND_HOST + ":" + BIND_PORT);
            System.out.println("静态资源目录: " + staticRoot.toAbsolutePath());

            Thread acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        threadPool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (running) {
                            LOG.warning("接受客户端连接失败: " + e.getMessage());
                        }
                    }
                }
            }, "gui-api-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            LOG.severe("启动 GUI API 服务失败: " + e.getMessage());
        }
    }

    /**
     * 停止 API 服务器
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.warning("关闭服务器套接字失败: " + e.getMessage());
        }
        threadPool.shutdown();
    }

    /**
     * 处理客户端请求
     */
    private void handleClient(Socket client) {
        try (Socket s = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String path = parts[1];

            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = reader.read(buf, 0, contentLength);
                if (read > 0) {
                    body = new String(buf, 0, read);
                }
            }

            // 路由分发
            if ("POST".equalsIgnoreCase(method) && "/api/login".equals(path)) {
                String response = handleLogin(body);
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/status".equals(path)) {
                String response = handleStatus();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/userdata".equals(path)) {
                String response = handleUserData();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/proxylist".equals(path)) {
                String response = handleProxyList();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/nodelist".equals(path)) {
                String response = handleNodeList();
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/newproxy".equals(path)) {
                String response = handleNewProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/freeport".equals(path)) {
                String response = handleFreePort(body);
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/getserver".equals(path)) {
                String response = handleGetServer();
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/updateproxy".equals(path)) {
                String response = handleUpdateProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/kickproxy".equals(path)) {
                String response = handleKickProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/banunproxy".equals(path)) {
                String response = handleBanUnProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/delproxy".equals(path)) {
                String response = handleDelProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/startproxy".equals(path)) {
                String response = handleStartProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/stopproxy".equals(path)) {
                String response = handleStopProxy(body);
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/getserverstatus".equals(path)) {
                String response = handleGetServerStatus();
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/logout".equals(path)) {
                String response = handleLogout();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/serverstatus".equals(path)) {
                String response = handleServerStatus();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/server_info".equals(path)) {
                String response = handleServerInfo();
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/api/server_important_info".equals(path)) {
                String response = handleServerImportantInfo();
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/sign".equals(path)) {
                String response = handleSign(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/openurl".equals(path)) {
                String response = handleOpenUrl(body);
                sendJsonResponse(out, 200, response);
            } else if ("POST".equalsIgnoreCase(method) && "/api/passlogin".equals(path)) {
                String response = handlePassLogin(body);
                sendJsonResponse(out, 200, response);
            } else if ("GET".equalsIgnoreCase(method) && "/debug/crash".equals(path)) {
                // 仅在调试模式下可用
                if (!debugMode) {
                    sendError(out, 403, "{\"code\":403,\"message\":\"仅在 --debug 模式下可用\"}");
                    return;
                }
                // 先返回响应，再触发崩溃
                String crashMsg = "{\"code\":200,\"message\":\"正在生成崩溃报告...\"}";
                sendJsonResponse(out, 200, crashMsg);
                // 在新线程中触发崩溃，确保响应已发送
                new Thread(() -> {
                    try {
                        Thread.sleep(500); // 等待响应发送完成
                    } catch (InterruptedException ignored) {}
                    handleDebugCrash();
                }, "debug-crash-trigger").start();
                return;
            } else if ("GET".equalsIgnoreCase(method) && "/debug/error".equals(path)) {
                // 仅在调试模式下可用
                if (!debugMode) {
                    sendError(out, 403, "{\"code\":403,\"message\":\"仅在 --debug 模式下可用\"}");
                    return;
                }
                String result = handleDebugError();
                sendJsonResponse(out, 200, result);
                return;
            } else {



                serveStaticFile(out, method, path);
            }


        } catch (Exception e) {
            LOG.warning("处理请求异常: " + e.getMessage());
        }
    }

    // ==================== API 处理 ====================

    /**
     * 调用 ME Frp API 验证 accesstoken
     */
    private int verifyToken(String accesstoken) {
        try {
            URL url = new URL(ME_FRP_API + "/auth/user/info");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode;
        } catch (Exception e) {
            LOG.warning("验证 token 失败: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 保存 accesstoken 到 config.json（Base64 编码）
     */
    private void saveToken(String accesstoken) throws IOException {
        Files.createDirectories(resDir);
        String encoded = Base64.getEncoder().encodeToString(accesstoken.getBytes(StandardCharsets.UTF_8));
        String json = "{\"accesstoken\":\"" + encoded + "\"}";
        Path configFile = resDir.resolve(CONFIG_FILE_NAME);
        Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("Token 已保存到: " + configFile.toAbsolutePath());
    }

    /**
     * 处理 /api/login 请求
     * 用 accesstoken 请求 ME Frp API /auth/user/info 验证是否有效
     * 有效则保存 token，无效则返回 401
     */
    private String handleLogin(String body) {
        try {
            String accesstoken = extractJsonString(body, "accesstoken");
            if (accesstoken == null || accesstoken.isEmpty()) {
                LOG.warning("[handleLogin] 缺少 accesstoken 参数");
                return "{\"code\":400,\"message\":\"缺少 accesstoken 参数\"}";
            }

            LOG.info("[handleLogin] 收到登录请求, 正在验证 token...");

            // 1. 用 accesstoken 请求 ME Frp API /auth/user/info 验证
            String apiUrl = ME_FRP_API + "/auth/user/info";
            LOG.info("[handleLogin] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int apiCode = conn.getResponseCode();
            LOG.info("[handleLogin] <<< 上游返回: HTTP " + apiCode);
            conn.disconnect();

            if (apiCode == 401) {
                LOG.warning("[handleLogin] Token 无效, 返回 401");
                return "{\"code\":401,\"message\":\"访问密钥错误，请检查后重试\"}";
            } else if (apiCode != 200) {
                LOG.warning("[handleLogin] 上游返回非200: " + apiCode);
                return "{\"code\":500,\"message\":\"验证服务暂时不可用，请稍后重试 (\" + apiCode + \")\"}";
            }

            LOG.info("[handleLogin] Token 验证通过");

            // 2. 保存 token（Base64 编码）
            try {
                saveToken(accesstoken);
                LOG.info("[handleLogin] Token 已保存到 config.json");
            } catch (IOException e) {
                LOG.warning("[handleLogin] 保存 token 失败: " + e.getMessage());
            }

            return "{\"code\":200,\"message\":\"登录成功\"}";

        } catch (Exception e) {
            LOG.severe("[handleLogin] 登录处理异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"服务器内部错误: " + e.getMessage() + "\"}";
        }
    }




    /**
     * 处理 /api/status - 检查是否有已保存的 token
     */
    private String handleStatus() {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                return "{\"code\":404,\"message\":\"未找到已保存的访问密钥\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                return "{\"code\":404,\"message\":\"访问密钥格式错误\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            // 验证 token 是否仍然有效
            int apiCode = verifyToken(accesstoken);
            if (apiCode == 200) {
                // 返回 accesstoken 供前端自动登录使用
                return "{\"code\":200,\"message\":\"Token 有效\",\"accesstoken\":\"" + escapeJsonString(accesstoken) + "\"}";
            } else {
                // token 失效，删除配置文件
                Files.deleteIfExists(configFile);
                return "{\"code\":401,\"message\":\"已保存的访问密钥已失效，请重新登录\"}";
            }
        } catch (Exception e) {
            return "{\"code\":500,\"message\":\"读取配置失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理 GET /api/userdata - 获取用户信息
     * 从已保存的 config.json 读取 accesstoken，自动请求 ME Frp API
     * 返回 ME Frp API 的用户信息
     */
    private String handleUserData() {
        try {
            // 从 config.json 读取已保存的 token
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleUserData] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleUserData] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/user/info";
            LOG.info("[handleUserData] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleUserData] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                LOG.warning("[handleUserData] 上游返回非200: " + responseCode);
                return "{\"code\":401,\"message\":\"获取用户信息失败\"}";
            }

            // 读取 API 返回的 JSON
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleUserData] <<< 上游响应体: " + respStr.substring(0, Math.min(respStr.length(), 200)));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleUserData] 获取用户信息异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取用户信息失败: " + e.getMessage() + "\"}";
        }
    }



    /**
     * 处理 GET /api/proxylist - 获取隧道列表
     * 从已保存的 config.json 读取 accesstoken，请求 ME Frp API /auth/proxy/list
     * 返回包含 nodes 和 proxies 的完整数据
     */
    private String handleProxyList() {
        try {
            // 从 config.json 读取已保存的 token
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleProxyList] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleProxyList] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/proxy/list";
            LOG.info("[handleProxyList] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleProxyList] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                LOG.warning("[handleProxyList] 上游返回非200: " + responseCode);
                return "{\"code\":401,\"message\":\"获取隧道列表失败\"}";
            }

            // 读取 API 返回的 JSON
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleProxyList] <<< 上游响应体: " + respStr.substring(0, Math.min(respStr.length(), 300)));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleProxyList] 获取隧道列表异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取隧道列表失败: " + e.getMessage() + "\"}";
        }
    }



    /**
     * 处理 GET /api/nodelist - 获取节点列表
     * 从已保存的 config.json 读取 accesstoken，请求 ME Frp API /auth/node/list
     */
    private String handleNodeList() {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleNodeList] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleNodeList] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/node/list";
            LOG.info("[handleNodeList] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleNodeList] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                LOG.warning("[handleNodeList] 上游返回非200: " + responseCode);
                return "{\"code\":401,\"message\":\"获取节点列表失败\"}";
            }

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleNodeList] <<< 上游响应体: " + respStr.substring(0, Math.min(respStr.length(), 300)));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleNodeList] 获取节点列表异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取节点列表失败: " + e.getMessage() + "\"}";
        }
    }



    /**
     * 处理 POST /api/newproxy - 创建隧道
     * 与 GOSDK CreateProxyRequest 结构体保持一致
     * 从 config.json 读取 accesstoken，请求 ME Frp API /auth/proxy/create
     */
    private String handleNewProxy(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleNewProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleNewProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            // 解析前端传入的 JSON，重组为与 GOSDK CreateProxyRequest 一致的格式
            // 确保所有字段都正确传递，特别是 HTTP 类型隧道需要的 domain、locations 等
            String upstreamBody = normalizeCreateProxyBody(body);

            String apiUrl = ME_FRP_API + "/auth/proxy/create";
            LOG.info("[handleNewProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleNewProxy] >>> 原始请求体: " + maskSensitiveBody(body));
            LOG.info("[handleNewProxy] >>> 标准化请求体: " + maskSensitiveBody(upstreamBody));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleNewProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleNewProxy] <<< 上游响应体: " + respStr);
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleNewProxy] 创建隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"创建隧道失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 将前端传入的创建隧道请求体标准化为与 GOSDK CreateProxyRequest 一致的格式
     * 前端 JSON 格式本身是正确的，只需追加 requestHeaders 和 responseHeaders 两个字段
     */
    private String normalizeCreateProxyBody(String body) {
        try {
            if (body == null || body.isEmpty()) return body;
            String trimmed = body.trim();
            // 去掉末尾的 }
            if (trimmed.endsWith("}")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            // 追加 GOSDK 需要的额外字段
            trimmed += ",\"requestHeaders\":{},\"responseHeaders\":{}}";
            return trimmed;
        } catch (Exception e) {
            LOG.warning("[normalizeCreateProxyBody] 标准化失败，使用原始 body: " + e.getMessage());
            return body;
        }
    }


    /**
     * 处理 POST /api/freeport - 获取远程空闲端口
     * 请求 ME Frp API /auth/node/freePort
     * Body: {"nodeId": 43, "protocol": "tcp"}
     */
    private String handleFreePort(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleFreePort] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleFreePort] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/node/freePort";
            LOG.info("[handleFreePort] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleFreePort] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleFreePort] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleFreePort] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleFreePort] 获取空闲端口异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取空闲端口失败: " + e.getMessage() + "\"}";
        }
    }



    /**
     * 处理 GET /api/getserver - 获取节点状态
     * 从 config.json 读取 accesstoken，请求 ME Frp API /auth/node/status
     */
    private String handleGetServer() {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleGetServer] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleGetServer] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/node/status";
            LOG.info("[handleGetServer] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleGetServer] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                LOG.warning("[handleGetServer] 上游返回非200: " + responseCode);
                return "{\"code\":401,\"message\":\"获取节点状态失败\"}";
            }

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleGetServer] <<< 上游响应体: " + respStr.substring(0, Math.min(respStr.length(), 300)));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleGetServer] 获取节点状态异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取节点状态失败: " + e.getMessage() + "\"}";
        }
    }


    /**
     * 处理 POST /api/updateproxy - 更新隧道
     * 请求 ME Frp API /auth/proxy/update
     * Body 需要包含 proxyName, proxyType, localIp, localPort, remotePort, nodeId 等字段
     */
    private String handleUpdateProxy(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleUpdateProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleUpdateProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/proxy/update";
            LOG.info("[handleUpdateProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleUpdateProxy] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleUpdateProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleUpdateProxy] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleUpdateProxy] 更新隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"更新隧道失败: " + e.getMessage() + "\"}";
        }
    }


    /**
     * 处理 POST /api/kickproxy - 强制下线隧道
     * 请求 ME Frp API /auth/proxy/kick
     * Body: {"proxyId": 123}
     */
    private String handleKickProxy(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleKickProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleKickProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/proxy/kick";
            LOG.info("[handleKickProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleKickProxy] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleKickProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleKickProxy] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleKickProxy] 强制下线隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"强制下线隧道失败: " + e.getMessage() + "\"}";
        }
    }


    /**
     * 处理 POST /api/banunproxy - 启用/禁用隧道
     * 请求 ME Frp API /auth/proxy/toggle
     * Body: {"proxyId": 123, "isDisabled": true}
     */
    private String handleBanUnProxy(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleBanUnProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleBanUnProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/proxy/toggle";
            LOG.info("[handleBanUnProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleBanUnProxy] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleBanUnProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleBanUnProxy] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleBanUnProxy] 启用/禁用隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"操作失败: " + e.getMessage() + "\"}";
        }
    }


    /**
     * 处理 POST /api/delproxy - 删除隧道
     * 请求 ME Frp API /auth/proxy/delete
     * Body: {"proxyId": 123}
     */
    private String handleDelProxy(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleDelProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleDelProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/proxy/delete";
            LOG.info("[handleDelProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleDelProxy] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleDelProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleDelProxy] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleDelProxy] 删除隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"删除隧道失败: " + e.getMessage() + "\"}";
        }
    }


    /**
     * 处理 POST /api/startproxy - 在此启动隧道
     * 1. 调用 ME Frp API /auth/proxy/config 获取 TOML 配置
     * 2. 保存到 tmp/ 目录
     * 3. 通过 FrpcManager 启动 frpc
     * Body: {"proxyId": 123}
     */
    private String handleStartProxy(String body) {
        try {
            int proxyId = extractJsonInt(body, "proxyId");
            if (proxyId <= 0) {
                LOG.warning("[handleStartProxy] 缺少 proxyId 参数");
                return "{\"code\":400,\"message\":\"缺少 proxyId 参数\"}";
            }

            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleStartProxy] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleStartProxy] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            // 1. 调用 ME Frp API /auth/proxy/config 获取 TOML 配置
            String apiUrl = ME_FRP_API + "/auth/proxy/config";
            String requestBody = "{\"proxyId\":" + proxyId + ",\"format\":\"toml\"}";
            LOG.info("[handleStartProxy] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleStartProxy] >>> 请求体: " + maskSensitiveBody(requestBody));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            byte[] reqBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(reqBytes.length));
            conn.getOutputStream().write(reqBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleStartProxy] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleStartProxy] <<< 上游响应体: " + maskSensitiveBody(respStr));

            if (responseCode != 200) {
                return respStr;
            }

            // 2. 从响应中提取 config 字段（TOML 内容）
            String tomlConfig = extractJsonString(respStr, "config");
            if (tomlConfig == null || tomlConfig.isEmpty()) {
                LOG.severe("[handleStartProxy] 上游返回的 config 为空");
                return "{\"code\":500,\"message\":\"获取隧道配置失败\"}";
            }
            // JSON 字符串中的 \n 转义序列需要转换成真正的换行符
            tomlConfig = unescapeJsonString(tomlConfig);

            // 3. 保存到 tmp/ 目录
            String jarDir = getJarDir();
            Path tmpDir = Paths.get(jarDir, "tmp");
            if (!Files.exists(tmpDir)) {
                Files.createDirectories(tmpDir);
            }
            Path tmpConfigFile = tmpDir.resolve("proxy_" + proxyId + ".toml");
            Files.write(tmpConfigFile, tomlConfig.getBytes(StandardCharsets.UTF_8));
            LOG.info("[handleStartProxy] 配置文件已保存到: " + tmpConfigFile.toAbsolutePath());

            // 4. 通过 FrpcManager 启动 frpc（多实例模式）
            FrpcManager frpcManager = FrpcManager.getInstance();
            boolean startOk = frpcManager.startProxy(proxyId, tmpConfigFile.toAbsolutePath().toString());
            if (startOk) {
                LOG.info("[handleStartProxy] frpc 启动成功, proxyId=" + proxyId);
                // 注册 JVM 关闭钩子，退出时删除 tmp 目录
                registerTmpCleanupHook(tmpDir);
                return "{\"code\":200,\"message\":\"启动成功\"}";
            } else {
                LOG.severe("[handleStartProxy] frpc 启动失败, proxyId=" + proxyId);
                // 清理临时文件
                Files.deleteIfExists(tmpConfigFile);
                return "{\"code\":500,\"message\":\"frpc 启动失败\"}";
            }

        } catch (Exception e) {
            LOG.severe("[handleStartProxy] 启动隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"启动隧道失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理 POST /api/stopproxy - 关闭隧道
     * 停止指定 proxyId 的 frpc 实例，并通过 ME Frp API 确保服务端感知到隧道关闭
     * 
     * 流程：
     *   1. 本地关闭 frpc
     *   2. 调用 ME Frp API 禁用隧道（确保服务端标记隧道离线）
     *   3. 调用 ME Frp API 强制下线（确保服务端断开连接）
     *   4. 调用 ME Frp API 启用隧道（恢复隧道可用状态）
     *   各操作间隔 100ms
     * 
     * Body: {"proxyId": 123}
     */
    private String handleStopProxy(String body) {
        try {
            int proxyId = extractJsonInt(body, "proxyId");
            if (proxyId <= 0) {
                LOG.warning("[handleStopProxy] 缺少 proxyId 参数");
                return "{\"code\":400,\"message\":\"缺少 proxyId 参数\"}";
            }

            // 从 config.json 读取 accesstoken
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            String accesstoken = null;
            if (Files.exists(configFile)) {
                String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                String encoded = extractJsonString(content, "accesstoken");
                if (encoded != null && !encoded.isEmpty()) {
                    accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                }
            }

            FrpcManager frpcManager = FrpcManager.getInstance();

            // 步骤1: 本地关闭 frpc
            LOG.info("[handleStopProxy] 步骤1: 本地关闭 frpc, proxyId=" + proxyId);
            frpcManager.stopProxy(proxyId);
            // 等待 frpc 完全退出
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.info("[handleStopProxy] frpc 本地已停止, proxyId=" + proxyId);

            if (accesstoken == null) {
                LOG.warning("[handleStopProxy] 未找到 accesstoken，跳过 API 操作");
                return "{\"code\":200,\"message\":\"已停止\"}";
            }

            // 步骤2: 禁用隧道（isDisabled: true）
            try {
                Thread.sleep(100);
                LOG.info("[handleStopProxy] 步骤2: 调用 ME Frp API 禁用隧道, proxyId=" + proxyId);
                String toggleBody = "{\"proxyId\":" + proxyId + ",\"isDisabled\":true}";
                LOG.info("[handleStopProxy] 禁用隧道请求体: " + maskSensitiveBody(toggleBody));
                String toggleResp = callMeFrpApi("/auth/proxy/toggle", toggleBody, accesstoken);
                LOG.info("[handleStopProxy] 禁用隧道响应: " + maskSensitiveBody(toggleResp));
            } catch (Exception e) {
                LOG.warning("[handleStopProxy] 禁用隧道失败: " + e.getMessage());
            }

            // 步骤3: 强制下线
            try {
                Thread.sleep(100);
                LOG.info("[handleStopProxy] 步骤3: 调用 ME Frp API 强制下线, proxyId=" + proxyId);
                String kickBody = "{\"proxyId\":" + proxyId + "}";
                LOG.info("[handleStopProxy] 强制下线请求体: " + maskSensitiveBody(kickBody));
                String kickResp = callMeFrpApi("/auth/proxy/kick", kickBody, accesstoken);
                LOG.info("[handleStopProxy] 强制下线响应: " + maskSensitiveBody(kickResp));
            } catch (Exception e) {
                LOG.warning("[handleStopProxy] 强制下线失败: " + e.getMessage());
            }

            // 步骤4: 启用隧道（恢复可用状态）
            try {
                Thread.sleep(100);
                LOG.info("[handleStopProxy] 步骤4: 调用 ME Frp API 启用隧道, proxyId=" + proxyId);
                String enableBody = "{\"proxyId\":" + proxyId + ",\"isDisabled\":false}";
                LOG.info("[handleStopProxy] 启用隧道请求体: " + maskSensitiveBody(enableBody));
                String enableResp = callMeFrpApi("/auth/proxy/toggle", enableBody, accesstoken);
                LOG.info("[handleStopProxy] 启用隧道响应: " + maskSensitiveBody(enableResp));
            } catch (Exception e) {
                LOG.warning("[handleStopProxy] 启用隧道失败: " + e.getMessage());
            }

            LOG.info("[handleStopProxy] proxyId=" + proxyId + " 关闭流程完成");
            return "{\"code\":200,\"message\":\"已停止\"}";

        } catch (Exception e) {
            LOG.severe("[handleStopProxy] 停止隧道异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"停止隧道失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 调用 ME Frp API
     * @param apiPath API 路径，例如 /auth/proxy/toggle
     * @param body 请求体 JSON
     * @param accesstoken 用户访问令牌
     * @return API 响应字符串
     */
    private String callMeFrpApi(String apiPath, String body, String accesstoken) {
        try {
            String apiUrl = ME_FRP_API + apiPath;
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();
            return apiResponse.toString();
        } catch (Exception e) {
            LOG.warning("[callMeFrpApi] " + apiPath + " 请求失败: " + e.getMessage());
            return "{\"code\":-1,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理 GET /api/getserverstatus - 获取本地隧道运行状态
     * 返回本机各隧道的 frpc 运行状态
     * 返回格式: {"code":200,"data":{"138425":true,"138426":false}}
     * 其中 key 为 proxyId, value 为是否在本机运行
     */
    private String handleGetServerStatus() {
        try {
            FrpcManager frpcManager = FrpcManager.getInstance();
            int[] runningIds = frpcManager.getAllRunningProxyIds();
            
            // 构建 JSON: {"code":200,"data":{"138425":true,"138426":true}}
            StringBuilder sb = new StringBuilder();
            sb.append("{\"code\":200,\"data\":{");
            for (int i = 0; i < runningIds.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(runningIds[i]).append("\":true");
            }
            sb.append("}}");
            return sb.toString();
        } catch (Exception e) {
            LOG.severe("[handleGetServerStatus] 获取本地状态异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取本地状态失败\"}";
        }
    }

    /**
     * 处理 POST /api/logout - 退出登录
     * 直接删除 config.json 文件
     */
    private String handleLogout() {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                Files.delete(configFile);
                LOG.info("[handleLogout] config.json 已删除");
                return "{\"code\":200,\"message\":\"已退出登录\"}";
            } else {
                return "{\"code\":200,\"message\":\"未登录\"}";
            }
        } catch (Exception e) {
            LOG.severe("[handleLogout] 删除 config.json 失败: " + e.getMessage());
            return "{\"code\":500,\"message\":\"退出登录失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理 GET /api/serverstatus - 获取 ME Frp 系统运行状态
     * 调用 ME Frp API /auth/system/status（无需 token）
     * 返回: {"code":200,"data":{"status":0,"remark":"ME Frp 当前一切正常！"},"message":"获取系统状态成功"}
     * status: 0=正常(绿), 1=降级(黄), 2=离线(红)
     */
    private String handleServerStatus() {
        try {
            // 从 config.json 读取 accesstoken
            String accesstoken = null;
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                String encoded = extractJsonString(content, "accesstoken");
                if (encoded != null && !encoded.isEmpty()) {
                    accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                }
            }

            String apiUrl = ME_FRP_API + "/auth/system/status";
            LOG.info("[handleServerStatus] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            if (accesstoken != null && !accesstoken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
                LOG.info("[handleServerStatus] 已携带 Authorization 头");
            } else {
                LOG.warning("[handleServerStatus] 未找到 accesstoken，请求将不带 Authorization 头");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleServerStatus] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                return "{\"code\":500,\"data\":{\"status\":2,\"remark\":\"无法连接 ME Frp 服务\"},\"message\":\"获取系统状态失败\"}";
            }

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleServerStatus] <<< 上游响应体: " + respStr);
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleServerStatus] 获取系统状态异常: " + e.getMessage());
            return "{\"code\":500,\"data\":{\"status\":2,\"remark\":\"请求失败: " + e.getMessage() + "\"},\"message\":\"获取系统状态失败\"}";
        }
    }

    /**
     * 处理 GET /api/server_info - 获取 ME Frp 公告
     * 调用 ME Frp API /auth/notice
     * 从 config.json 读取 accesstoken 并携带 Authorization 头
     */
    private String handleServerInfo() {
        try {
            // 从 config.json 读取 accesstoken
            String accesstoken = null;
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                String encoded = extractJsonString(content, "accesstoken");
                if (encoded != null && !encoded.isEmpty()) {
                    accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                }
            }

            String apiUrl = ME_FRP_API + "/auth/notice";
            LOG.info("[handleServerInfo] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            if (accesstoken != null && !accesstoken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
                LOG.info("[handleServerInfo] 已携带 Authorization 头");
            } else {
                LOG.warning("[handleServerInfo] 未找到 accesstoken，请求将不带 Authorization 头");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleServerInfo] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                return "{\"code\":500,\"message\":\"获取公告失败\"}";
            }

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleServerInfo] <<< 上游响应体: " + respStr);
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleServerInfo] 获取公告异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"获取公告失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理 GET /api/server_important_info - 获取重要公告（弹窗）
     * 调用 ME Frp API /auth/popupNotice
     * 将结果保存到 res/server_info.json，对比内容是否有变化
     * 返回: {"code":200,"data":"公告内容","changed":true/false,"message":"获取弹窗公告成功"}
     */
    private String handleServerImportantInfo() {
        try {
            // 从 config.json 读取 accesstoken
            String accesstoken = null;
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                String encoded = extractJsonString(content, "accesstoken");
                if (encoded != null && !encoded.isEmpty()) {
                    accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                }
            }

            String apiUrl = ME_FRP_API + "/auth/popupNotice";
            LOG.info("[handleServerImportantInfo] >>> 请求上游: GET " + apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            if (accesstoken != null && !accesstoken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
                LOG.info("[handleServerImportantInfo] 已携带 Authorization 头");
            } else {
                LOG.warning("[handleServerImportantInfo] 未找到 accesstoken，请求将不带 Authorization 头");
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleServerImportantInfo] <<< 上游返回: HTTP " + responseCode);
            if (responseCode != 200) {
                conn.disconnect();
                return "{\"code\":500,\"message\":\"获取重要公告失败\"}";
            }

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleServerImportantInfo] <<< 上游响应体: " + respStr);

            // 从上游响应中提取 data 字段（公告内容）
            String upstreamData = extractJsonString(respStr, "data");
            if (upstreamData == null) {
                return "{\"code\":500,\"message\":\"上游返回数据格式异常\"}";
            }

            // 保存到 res/server_info.json
            Path serverInfoFile = resDir.resolve("server_info.json");
            boolean changed = true;
            if (Files.exists(serverInfoFile)) {
                String oldContent = new String(Files.readAllBytes(serverInfoFile), StandardCharsets.UTF_8);
                // 对比内容是否有变化
                if (oldContent.equals(upstreamData)) {
                    changed = false;
                }
            }

            // 写入新内容
            Files.write(serverInfoFile, upstreamData.getBytes(StandardCharsets.UTF_8));
            LOG.info("[handleServerImportantInfo] 已保存到: " + serverInfoFile.toAbsolutePath() + ", changed=" + changed);

            // 返回给前端
            return "{\"code\":200,\"data\":\"" + escapeJsonString(upstreamData) + "\",\"changed\":" + changed + ",\"message\":\"获取弹窗公告成功\"}";

        } catch (Exception e) {
            LOG.severe("[handleServerImportantInfo] 获取重要公告异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"错误，请查看日志并联系开发者获得支持\"}";
        }
    }

    /**
     * 处理 POST /api/sign - 签到
     * 将前端 body 原样转发到 ME Frp API /auth/user/sign
     * 需要携带 Authorization: Bearer token
     */
    private String handleSign(String body) {
        try {
            Path configFile = resDir.resolve(CONFIG_FILE_NAME);
            if (!Files.exists(configFile)) {
                LOG.warning("[handleSign] config.json 不存在");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            String encoded = extractJsonString(content, "accesstoken");
            if (encoded == null || encoded.isEmpty()) {
                LOG.warning("[handleSign] config.json 中 accesstoken 为空");
                return "{\"code\":401,\"message\":\"未登录\"}";
            }

            String accesstoken = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

            String apiUrl = ME_FRP_API + "/auth/user/sign";
            LOG.info("[handleSign] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handleSign] >>> 请求体: " + maskSensitiveBody(body));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accesstoken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handleSign] <<< 上游返回: HTTP " + responseCode);
            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handleSign] <<< 上游响应体: " + maskSensitiveBody(respStr));
            return respStr;

        } catch (Exception e) {
            LOG.severe("[handleSign] 签到异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"签到失败，请稍后重试\"}";
        }
    }

    /**
     * 处理 POST /api/openurl - 用系统默认浏览器打开指定 URL
     * 先检查是否有可用的浏览器（非 IE），有则打开，无则返回错误
     * Body: {"url": "https://..."}
     */
    private String handleOpenUrl(String body) {
        try {
            String url = extractJsonString(body, "url");
            if (url == null || url.isEmpty()) {
                return "{\"code\":400,\"message\":\"缺少 url 参数\"}";
            }

            // 检查 Desktop 是否支持 browse（即是否有默认浏览器）
            if (!Desktop.isDesktopSupported()) {
                return "{\"code\":500,\"message\":\"OMG BRO 没浏览器我也无能为力啊\"}";
            }

            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return "{\"code\":500,\"message\":\"OMG BRO 没浏览器我也无能为力啊\"}";
            }

            desktop.browse(new URI(url));
            LOG.info("[handleOpenUrl] 已打开浏览器: " + url);
            return "{\"code\":200,\"message\":\"已打开浏览器\"}";

        } catch (Exception e) {
            LOG.severe("[handleOpenUrl] 打开浏览器失败: " + e.getMessage());
            return "{\"code\":500,\"message\":\"OMG BRO 没浏览器我也无能为力啊\"}";
        }
    }

    /**
     * 处理 POST /api/passlogin - 密码登录
     * 调用 ME Frp API /public/login 进行密码登录
     * 从返回的 data.token 中提取 token，复用 handleLogin 的保存逻辑
     * Body: {"username": "xxx", "password": "xxx", "captchaToken": "xxx"}
     */
    private String handlePassLogin(String body) {
        try {
            String username = extractJsonString(body, "username");
            String password = extractJsonString(body, "password");
            String captchaToken = extractJsonString(body, "captchaToken");

            if (username == null || username.isEmpty()) {
                return "{\"code\":400,\"message\":\"缺少用户名\"}";
            }
            if (password == null || password.isEmpty()) {
                return "{\"code\":400,\"message\":\"缺少密码\"}";
            }
            if (captchaToken == null || captchaToken.isEmpty()) {
                return "{\"code\":400,\"message\":\"缺少人机验证\"}";
            }

            // 人机验证码是 Base64 编码的 "token||client" 格式
            // 需要解码后提取 token 部分作为 captchaToken 提交
            String decodedCaptcha = decodeCaptchaToken(captchaToken);
            if (decodedCaptcha == null) {
                LOG.warning("[handlePassLogin] 人机验证码 Base64 解码失败: " + captchaToken);
                return "{\"code\":400,\"message\":\"人机验证码格式错误\"}";
            }
            LOG.info("[handlePassLogin] 人机验证码解码结果: " + decodedCaptcha);

            // 用解码后的 captchaToken 替换原 body 中的值
            String upstreamBody = body.replaceAll(
                "\"captchaToken\"\\s*:\\s*\"" + captchaToken + "\"",
                "\"captchaToken\":\"" + decodedCaptcha + "\"");

            LOG.info("[handlePassLogin] 收到密码登录请求, username=" + username);

            // 1. 调用 ME Frp API /public/login
            String apiUrl = ME_FRP_API + "/public/login";
            LOG.info("[handlePassLogin] >>> 请求上游: POST " + apiUrl);
            LOG.info("[handlePassLogin] >>> 请求体: " + maskSensitiveBody(upstreamBody));
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Fan-ME-FRP-Launcher/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = upstreamBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            conn.getOutputStream().write(bodyBytes);

            int responseCode = conn.getResponseCode();
            LOG.info("[handlePassLogin] <<< 上游返回: HTTP " + responseCode);

            StringBuilder apiResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    apiResponse.append(line);
                }
            }
            conn.disconnect();

            String respStr = apiResponse.toString();
            LOG.info("[handlePassLogin] <<< 上游响应体: " + maskSensitiveBody(respStr));

            // 2. 检查上游返回的 code
            int upstreamCode = extractJsonInt(respStr, "code");
            if (upstreamCode != 200) {
                // 直接透传上游的错误消息
                String upstreamMsg = extractJsonString(respStr, "message");
                if (upstreamMsg == null) upstreamMsg = "登录失败";
                return "{\"code\":" + upstreamCode + ",\"message\":\"" + upstreamMsg + "\"}";
            }

            // 3. 从 data.token 中提取 token
            // 上游返回格式: {"code":200,"data":{"group":"sponsor","token":"xxx","username":"xiaofan"},"message":"已成功登录, 欢迎回来"}
            // 需要从 data 字段中提取 token
            String dataStr = extractJsonRaw(respStr, "data");
            if (dataStr == null || dataStr.isEmpty()) {
                LOG.severe("[handlePassLogin] 上游返回的 data 为空");
                return "{\"code\":500,\"message\":\"登录失败：上游返回数据异常\"}";
            }
            String token = extractJsonString(dataStr, "token");
            if (token == null || token.isEmpty()) {
                LOG.severe("[handlePassLogin] 上游返回的 data 中缺少 token");
                return "{\"code\":500,\"message\":\"登录失败：未获取到访问令牌\"}";
            }

            LOG.info("[handlePassLogin] 密码登录成功，获取到 token");

            // 4. 复用 handleLogin 的保存逻辑
            try {
                saveToken(token);
                LOG.info("[handlePassLogin] Token 已保存到 config.json");
            } catch (IOException e) {
                LOG.warning("[handlePassLogin] 保存 token 失败: " + e.getMessage());
            }

            return "{\"code\":200,\"message\":\"登录成功\"}";

        } catch (Exception e) {
            LOG.severe("[handlePassLogin] 密码登录异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"登录失败，请稍后重试\"}";
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJsonString(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);     break;
            }
        }
        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取指定 key 的整数值
     */
    private int extractJsonInt(String json, String key) {
        if (json == null || json.isEmpty()) return 0;
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": ";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return 0;
        start += searchKey.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) return 0;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 注册 JVM 关闭钩子，退出时删除 tmp 目录
     */
    private void registerTmpCleanupHook(Path tmpDir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(tmpDir)) {
                    LOG.info("正在清理临时文件...");
                    Files.walk(tmpDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                    LOG.info("临时文件已清理");
                }
            } catch (Exception e) {
                LOG.warning("清理临时文件失败: " + e.getMessage());
            }
        }));
    }


    // ==================== 静态文件服务 ====================


    private void serveStaticFile(OutputStream out, String method, String path) throws IOException {



        if (!"GET".equalsIgnoreCase(method)) {
            sendError(out, 405, "Method Not Allowed");
            return;
        }

        path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());

        if (path.equals("/") || path.equals("")) {
            sendRedirect(out, "/login.html");
            return;
        }

        String normalizedPath = path.replace('\\', '/');
        if (normalizedPath.contains("../") || normalizedPath.contains("..")) {
            sendError(out, 403, "Forbidden");
            return;
        }

        String relativePath = normalizedPath.startsWith("/") ? normalizedPath.substring(1) : normalizedPath;
        Path filePath = staticRoot.resolve(relativePath).normalize();

        if (!filePath.startsWith(staticRoot.normalize())) {
            sendError(out, 403, "Forbidden");
            return;
        }

        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            sendError(out, 404, "Not Found");
            return;
        }

        byte[] fileBytes = Files.readAllBytes(filePath);
        String contentType = getContentType(filePath.toString());
        sendFileResponse(out, contentType, fileBytes);
    }

    private String getContentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".eot")) return "application/vnd.ms-fontobject";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }

    // ==================== HTTP 响应工具 ====================

    private void sendJsonResponse(OutputStream out, int statusCode, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(getStatusText(statusCode)).append("\r\n");
        response.append("Content-Type: application/json; charset=utf-8\r\n");
        response.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("\r\n");
        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private void sendFileResponse(OutputStream out, String contentType, byte[] data) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: ").append(contentType).append("\r\n");
        response.append("Content-Length: ").append(data.length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("Cache-Control: no-cache\r\n");
        response.append("\r\n");
        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private void sendRedirect(OutputStream out, String location) throws IOException {
        String response = "HTTP/1.1 302 Found\r\n" +
                "Location: " + location + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendError(OutputStream out, int statusCode, String message) throws IOException {
        String body = "{\"code\":" + statusCode + ",\"message\":\"" + message + "\"}";
        sendJsonResponse(out, statusCode, body);
    }

    private String getStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }

    // ==================== 工具方法 ====================

    private String extractJsonString(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": \"";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return null;
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * 从 JSON 字符串中提取指定 key 的原始值（支持嵌套对象）
     * 例如: {"data":{"token":"xxx"}} 提取 "data" 返回 {"token":"xxx"}
     */
    private String extractJsonRaw(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": ";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return null;
        start += searchKey.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        
        char firstChar = json.charAt(start);
        if (firstChar == '{') {
            // 提取嵌套对象
            int depth = 1;
            int end = start + 1;
            while (end < json.length() && depth > 0) {
                char c = json.charAt(end);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth > 0) end++;
            }
            return json.substring(start, end + 1);
        } else if (firstChar == '"') {
            // 提取字符串
            int end = start + 1;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\') {
                    end += 2;
                    continue;
                }
                if (c == '"') break;
                end++;
            }
            return json.substring(start, end + 1);
        } else if (firstChar == '[') {
            // 提取数组
            int depth = 1;
            int end = start + 1;
            while (end < json.length() && depth > 0) {
                char c = json.charAt(end);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                if (depth > 0) end++;
            }
            return json.substring(start, end + 1);
        } else {
            // 提取数字或布尔值
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.' || json.charAt(end) == 'e' || json.charAt(end) == 'E' || json.charAt(end) == '+' || json.charAt(end) == 't' || json.charAt(end) == 'f' || json.charAt(end) == 'n')) {
                end++;
            }
            return json.substring(start, end);
        }
    }

    /**
     * 对日志中的 body 进行脱敏处理，替换敏感字段（如 accesstoken、token）的值
     */
    private String maskSensitiveBody(String body) {
        if (body == null || body.isEmpty()) return body;
        // 替换 "accesstoken":"xxx" 或 "accesstoken": "xxx"
        String masked = body.replaceAll("\"accesstoken\"\\s*:\\s*\"[^\"]*\"", "\"accesstoken\":\"***\"");
        // 替换 "token":"xxx" 或 "token": "xxx"
        masked = masked.replaceAll("\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"***\"");
        // 注意：captchaToken 不脱敏，因为人机验证 token 是一次性的，
        // 能打印出来的肯定都是用过的，泄漏无关紧要
        return masked;
    }

    /**
     * 将 JSON 字符串中的转义序列转换为实际字符
     * 例如: \n → 换行符, \t → 制表符, \\ → 反斜杠, \" → 双引号
     */
    private String unescapeJsonString(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n':  sb.append('\n'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '"':  sb.append('"');  i++; break;
                    case '/':  sb.append('/');  i++; break;
                    default:   sb.append(c);    break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 解码人机验证码
     * 人机验证码是 Base64 编码的 "token||client" 格式
     * 解码后提取 "||" 前面的 token 部分返回
     * @param encoded Base64 编码的验证码
     * @return 解码后的 token，如果解码失败返回 null
     */
    private String decodeCaptchaToken(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encoded);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            // 格式: "token||client"，提取 token 部分
            int splitIndex = decoded.indexOf("||");
            if (splitIndex > 0) {
                return decoded.substring(0, splitIndex);
            }
            // 如果没有 ||，直接返回解码结果
            return decoded;
        } catch (Exception e) {
            LOG.warning("[decodeCaptchaToken] Base64 解码失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 处理 /debug/crash - 触发调试崩溃
     * 仅在 --debug 模式下可用
     * 直接抛出 DebugCrashException，触发 CrashReporter 生成崩溃报告
     * 类似于 Minecraft 的 F3+C 长按 10 秒
     */
    private void handleDebugCrash() {
        System.err.println("\n================================================================");
        System.err.println("  /debug/crash 被调用！正在生成崩溃报告...");
        System.err.println("  类似于 Minecraft 的 F3+C 长按 10 秒");
        System.err.println("================================================================");

        // 收集硬件信息到日志
        LOG.severe("[debug/crash] 用户触发了调试崩溃！");
        LOG.severe("[debug/crash] Java 版本: " + System.getProperty("java.version"));
        LOG.severe("[debug/crash] Java 供应商: " + System.getProperty("java.vendor"));
        LOG.severe("[debug/crash] 操作系统: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        LOG.severe("[debug/crash] CPU 核心数: " + Runtime.getRuntime().availableProcessors());
        LOG.severe("[debug/crash] 最大内存: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        LOG.severe("[debug/crash] 可用内存: " + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + " MB");
        LOG.severe("[debug/crash] 已用内存: " + ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024) + " MB");

        // 直接抛出异常不捕获，让 CrashReporter 处理
        throw new DebugCrashException();
    }

    /**
     * 处理 /debug/error - 触发调试错误
     * 仅在 --debug 模式下可用
     * 使用 ErrorReporter 生成错误报告并保存到 error/ 目录
     * 程序不会崩溃，只记录错误
     */
    private String handleDebugError() {
        System.err.println("\n================================================================");
        System.err.println("  /debug/error 被调用！正在生成错误报告...");
        System.err.println("================================================================");

        LOG.severe("[debug/error] 用户触发了调试错误！");

        // 使用 ErrorReporter 记录错误
        String jarDir = getJarDir();
        ErrorReporter errorReporter = new ErrorReporter(jarDir);
        DebugErrorException exception = new DebugErrorException();
        Path errorFile = errorReporter.reportError(exception, "由 /debug/error API 触发");

        if (errorFile != null) {
            System.err.println("错误报告已保存至: " + errorFile.toAbsolutePath());
            return "{\"code\":200,\"message\":\"错误报告已生成\",\"file\":\"" + errorFile.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}";
        } else {
            return "{\"code\":500,\"message\":\"错误报告生成失败\"}";
        }
    }

    private static String getJarDir() {
        try {
            String path = GuiApiServer.class
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
}

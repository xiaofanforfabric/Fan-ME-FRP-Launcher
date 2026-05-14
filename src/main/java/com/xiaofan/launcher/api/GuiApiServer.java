package com.xiaofan.launcher.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.xiaofan.launcher.frpc.EasyStartup;
import com.xiaofan.launcher.frpc.FrpcManager;

/**
 * GUI API 服务 - 轻量 HTTP 服务器
 * 
 * 绑定 127.0.0.1:1023，仅允许本地调用
 * 提供 RESTful API 供 GUI 前端调用
 * 
 * API:
 *   POST /api/login
 *     Body: {"accesstoken": "CB92FABF19DE772C267F3531ABFE40BA1C3A865A"}
 *     验证 token 并启动 frpc
 */
public class GuiApiServer {

    private static final Logger LOG = Logger.getLogger(GuiApiServer.class.getName());
    private static final String BIND_HOST = "127.0.0.1";
    private static final int BIND_PORT = 1023;
    private static final int THREAD_POOL_SIZE = 4;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;

    public GuiApiServer() {
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    /**
     * 启动 API 服务器
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(BIND_PORT, 50, InetAddress.getByName(BIND_HOST));
            running = true;
            System.out.println("GUI API 服务已启动: http://" + BIND_HOST + ":" + BIND_PORT);

            // 在独立线程中接受连接
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

            // 解析请求行
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String path = parts[1];

            // 读取请求头
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // 读取请求体
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = reader.read(buf, 0, contentLength);
                if (read > 0) {
                    body = new String(buf, 0, read);
                }
            }

            // 路由分发
            String response;
            int statusCode;

            if ("POST".equalsIgnoreCase(method) && "/api/login".equals(path)) {
                statusCode = 200;
                response = handleLogin(body);
            } else {
                statusCode = 404;
                response = "{\"code\":404,\"message\":\"Not Found\"}";
            }

            // 发送响应
            sendResponse(out, statusCode, response);

        } catch (Exception e) {
            LOG.warning("处理请求异常: " + e.getMessage());
        }
    }

    /**
     * 处理 /api/login 请求
     * 验证 accesstoken 并启动 frpc
     */
    private String handleLogin(String body) {
        try {
            // 解析 accesstoken
            String accesstoken = extractJsonString(body, "accesstoken");
            if (accesstoken == null || accesstoken.isEmpty()) {
                return "{\"code\":400,\"message\":\"缺少 accesstoken 参数\"}";
            }

            System.out.println("收到登录请求，accesstoken: " + accesstoken.substring(0, Math.min(8, accesstoken.length())) + "...");

            // 解析 accesstoken 格式: runId_proxyId
            // 示例: CB92FABF19DE772C267F3531ABFE40BA1C3A865A_138425
            String runId;
            int proxyId;

            int underscoreIndex = accesstoken.lastIndexOf('_');
            if (underscoreIndex > 0 && underscoreIndex < accesstoken.length() - 1) {
                runId = accesstoken.substring(0, underscoreIndex);
                try {
                    proxyId = Integer.parseInt(accesstoken.substring(underscoreIndex + 1));
                } catch (NumberFormatException e) {
                    return "{\"code\":400,\"message\":\"accesstoken 格式错误，末尾需为数字 proxyId\"}";
                }
            } else {
                return "{\"code\":400,\"message\":\"accesstoken 格式错误，应为 runId_proxyId\"}";
            }

            // 初始化 FRPC 管理器
            FrpcManager manager = FrpcManager.getInstance();
            if (!manager.init()) {
                return "{\"code\":500,\"message\":\"FRPC 初始化失败\"}";
            }

            // 通过 API 获取配置并生成临时 TOML
            EasyStartup easyStartup = new EasyStartup();
            easyStartup.registerCleanupHook();

            Path configFile = easyStartup.execute(runId, proxyId);
            if (configFile == null) {
                return "{\"code\":500,\"message\":\"获取隧道配置失败\"}";
            }

            // 启动 frpc
            if (manager.start(configFile.toAbsolutePath().toString())) {
                System.out.println("frpc 启动成功");
                return "{\"code\":200,\"message\":\"登录成功，frpc 已启动\"}";
            } else {
                return "{\"code\":500,\"message\":\"frpc 启动失败\"}";
            }

        } catch (Exception e) {
            LOG.severe("登录处理异常: " + e.getMessage());
            return "{\"code\":500,\"message\":\"服务器内部错误: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 发送 HTTP 响应
     */
    private void sendResponse(OutputStream out, int statusCode, String body) throws IOException {
        String statusText;
        switch (statusCode) {
            case 200: statusText = "OK"; break;
            case 400: statusText = "Bad Request"; break;
            case 404: statusText = "Not Found"; break;
            case 500: statusText = "Internal Server Error"; break;
            default: statusText = "Unknown";
        }

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        response.append("Content-Type: application/json; charset=utf-8\r\n");
        response.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        response.append("Connection: close\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("\r\n");

        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值
     */
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
}

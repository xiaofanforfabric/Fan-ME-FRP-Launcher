package com.xiaofan.appservices;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.iki.elonen.NanoHTTPD;

/**
 * 管理页面服务 - 端口 4101
 * 提供 HTML 管理页面 + 管理 API（写操作）
 *
 * 管理 API：
 *   GET  /api/versions       - 获取全部版本列表
 *   POST /api/save-app       - 保存 app.json（版本号）
 *   POST /api/save-tpca      - 保存 tpca.json（添加/更新版本）
 *   POST /api/delete-tpca    - 删除 tpca.json 中的版本
 *   POST /api/cloudflared/start  - 启动 Cloudflare Tunnel
 *   POST /api/cloudflared/stop   - 停止 Cloudflare Tunnel
 *   GET  /api/cloudflared/status - 查询 Cloudflare Tunnel 状态
 */
public class AdminServer extends NanoHTTPD {

    private static final String HTML;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private ChangelogData changelogData;
    private AppData appData;
    private String tpcaFilePath;
    private String appFilePath;
    private CloudflaredManager cloudflaredManager;

    static {
        String htmlContent;
        try {
            InputStream is = AdminServer.class.getClassLoader().getResourceAsStream("webapp/admin.html");
            if (is != null) {
                byte[] buf = new byte[is.available()];
                is.read(buf);
                htmlContent = new String(buf, StandardCharsets.UTF_8);
            } else {
                htmlContent = getDefaultHtml();
            }
        } catch (Exception e) {
            htmlContent = getDefaultHtml();
        }
        HTML = htmlContent;
    }

    public AdminServer(int port) {
        super(port);
    }

    /**
     * 设置数据引用（由主入口注入）
     */
    public void setData(ChangelogData changelogData, AppData appData,
                        String tpcaFilePath, String appFilePath) {
        this.changelogData = changelogData;
        this.appData = appData;
        this.tpcaFilePath = tpcaFilePath;
        this.appFilePath = appFilePath;
    }

    /**
     * 设置 Cloudflare Tunnel 管理器（由主入口注入）
     */
    public void setCloudflaredManager(CloudflaredManager manager) {
        this.cloudflaredManager = manager;
    }

    /**
     * 检查 Basic Auth 鉴权
     */
    private boolean checkAuth(IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        String base64 = authHeader.substring(6);
        String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        if (colon < 0) return false;
        String user = decoded.substring(0, colon);
        String pass = decoded.substring(colon + 1);
        return AppConfig.ADMIN_USER.equals(user) && AppConfig.ADMIN_PASS.equals(pass);
    }

    /**
     * 返回 401 未授权响应
     */
    private Response unauthorizedResponse() {
        Response resp = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized");
        resp.addHeader("WWW-Authenticate", "Basic realm=\"Fan-ME-FRP Admin\"");
        return resp;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // 所有请求都需要鉴权
        if (!checkAuth(session)) {
            return unauthorizedResponse();
        }

        // 管理页面
        if ("/".equals(uri) || "/admin".equals(uri) || "/admin.html".equals(uri)) {
            Response resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", HTML);
            resp.addHeader("Access-Control-Allow-Origin", "*");
            return resp;
        }

        // 管理 API
        try {
            // 解析请求体
            Map<String, String> body = new HashMap<>();
            if (method == Method.POST) {
                Integer size = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
                if (size > 0) {
                    byte[] buf = new byte[size];
                    session.getInputStream().read(buf);
                    String bodyStr = new String(buf, StandardCharsets.UTF_8);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = gson.fromJson(bodyStr, Map.class);
                        if (parsed != null) {
                            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                                body.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            switch (uri) {
                // ====== 获取全部版本列表 ======
                case "/api/versions":
                    if (method == Method.GET) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (String v : changelogData.getVersions()) {
                            ChangelogData.VersionEntry ve = changelogData.getData().get(v);
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("version", v);
                            item.put("date", ve.getDate() != null ? ve.getDate() : "");
                            item.put("note", ve.getNote() != null ? ve.getNote() : "");
                            item.put("download", ve.getDownload() != null ? ve.getDownload() : "");
                            item.put("changes", ve.getChanges() != null ? ve.getChanges() : new ArrayList<>());
                            list.add(item);
                        }
                        Response versionsResp = newFixedLengthResponse(
                            Response.Status.OK, "application/json; charset=utf-8", gson.toJson(list));
                        versionsResp.addHeader("Access-Control-Allow-Origin", "*");
                        return versionsResp;
                    }
                    return jsonResponse(405, "不支持的请求方法");

                // ====== 保存 app.json ======
                case "/api/save-app":
                    if (method == Method.POST) {
                        String version = body.get("version");
                        if (version == null || version.isEmpty()) {
                            return jsonResponse(400, "版本号不能为空");
                        }
                        appData.setVersion(version);
                        appData.setDownload(body.getOrDefault("download", ""));
                        appData.save(appFilePath);
                        return jsonResponse(200, "app.json 已更新为 " + version);
                    }
                    return jsonResponse(405, "不支持的请求方法");

                // ====== 保存 tpca.json（添加/更新版本） ======
                case "/api/save-tpca":
                    if (method == Method.POST) {
                        String version = body.get("version");
                        String date = body.getOrDefault("date", "");
                        String note = body.getOrDefault("note", "");
                        String changesStr = body.getOrDefault("changes", "");

                        if (version == null || version.isEmpty()) {
                            return jsonResponse(400, "版本号不能为空");
                        }

                        List<String> changes = new ArrayList<>();
                        if (!changesStr.isEmpty()) {
                            String[] lines = changesStr.split("\\n");
                            for (String line : lines) {
                                line = line.trim();
                                if (!line.isEmpty()) {
                                    changes.add(line);
                                }
                            }
                        }

                        String download = body.getOrDefault("download", "");

                        changelogData.putVersion(version, date, note, changes);
                        // 设置 download 字段
                        ChangelogData.VersionEntry ve = changelogData.getData().get(version);
                        if (ve != null) {
                            ve.setDownload(download);
                        }
                        changelogData.save(tpcaFilePath);
                        return jsonResponse(200, "保存成功");
                    }
                    return jsonResponse(405, "不支持的请求方法");

                // ====== 删除 tpca.json 中的版本 ======
                case "/api/delete-tpca":
                    if (method == Method.POST) {
                        String version = body.get("version");
                        if (version == null || version.isEmpty()) {
                            return jsonResponse(400, "版本号不能为空");
                        }
                        boolean removed = changelogData.removeVersion(version);
                        if (removed) {
                            changelogData.save(tpcaFilePath);
                            return jsonResponse(200, "删除成功");
                        } else {
                            return jsonResponse(404, "版本不存在");
                        }
                    }
                    return jsonResponse(405, "不支持的请求方法");

                // ====== Cloudflare Tunnel 管理 ======
                case "/api/cloudflared/start":
                    if (method == Method.POST) {
                        if (cloudflaredManager == null) {
                            return jsonResponse(500, "Cloudflare Tunnel 管理器未初始化");
                        }
                        String token = body.get("token");
                        if (token == null || token.trim().isEmpty()) {
                            return jsonResponse(400, "Token 不能为空");
                        }
                        boolean ok = cloudflaredManager.startTunnel(token.trim());
                        if (ok) {
                            return jsonResponse(200, "Cloudflare Tunnel 已启动");
                        } else {
                            return jsonResponse(500, "Cloudflare Tunnel 启动失败");
                        }
                    }
                    return jsonResponse(405, "不支持的请求方法");

                case "/api/cloudflared/stop":
                    if (method == Method.POST) {
                        if (cloudflaredManager == null) {
                            return jsonResponse(500, "Cloudflare Tunnel 管理器未初始化");
                        }
                        cloudflaredManager.stopTunnel();
                        return jsonResponse(200, "Cloudflare Tunnel 已停止");
                    }
                    return jsonResponse(405, "不支持的请求方法");

                case "/api/cloudflared/status":
                    if (method == Method.GET) {
                        if (cloudflaredManager == null) {
                            return jsonResponse(500, "Cloudflare Tunnel 管理器未初始化");
                        }
                        Map<String, Object> status = new LinkedHashMap<>();
                        status.put("running", cloudflaredManager.isRunning());
                        Response resp = newFixedLengthResponse(
                            Response.Status.OK, "application/json; charset=utf-8", gson.toJson(status));
                        resp.addHeader("Access-Control-Allow-Origin", "*");
                        return resp;
                    }
                    return jsonResponse(405, "不支持的请求方法");

                default:
                    return jsonResponse(404, "接口不存在");
            }
        } catch (Exception e) {
            return jsonResponse(500, "服务器错误: " + e.getMessage());
        }
    }

    private Response jsonResponse(int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("message", message);
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(resp));
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }

    private static String getDefaultHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>更新日志管理</title></head><body><h1>管理页面加载失败</h1></body></html>";
    }
}

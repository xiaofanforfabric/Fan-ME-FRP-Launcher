package com.xiaofan.appservices;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocketFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.iki.elonen.NanoHTTPD;

/**
 * API 服务 - 端口 4102（HTTPS）
 *
 * 接口列表：
 *   GET  /tpca.json       - 获取完整更新日志（原始 tpca.json 内容）
 *   GET  /version/last    - 获取最新版本信息
 *   GET  /version/all     - 获取全部版本列表
 */
public class ApiServer extends NanoHTTPD {

    private final ChangelogData changelogData;
    private final AppData appData;
    private final String tpcaFilePath;
    private final String appFilePath;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ApiServer(int port, ChangelogData changelogData, AppData appData,
                     String tpcaFilePath, String appFilePath) {
        super(port);
        this.changelogData = changelogData;
        this.appData = appData;
        this.tpcaFilePath = tpcaFilePath;
        this.appFilePath = appFilePath;
    }

    /**
     * 启用 HTTPS（在 start() 之前调用）
     */
    public void enableHttps() throws Exception {
        // 从 JAR 中加载证书和私钥
        byte[] certPem;
        byte[] keyPem;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("ssl/cert.pem")) {
            if (is == null) throw new Exception("ssl/cert.pem 未找到");
            certPem = readAllBytes(is);
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("ssl/key_pkcs8.pem")) {
            if (is == null) throw new Exception("ssl/key_pkcs8.pem 未找到");
            keyPem = loadPrivateKeyPem(readAllBytes(is));
        }

        // 解析证书
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(certPem));

        // 解析私钥（PKCS8 格式）
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyPem);
        PrivateKey privateKey = kf.generatePrivate(keySpec);

        // 创建 KeyStore
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry("server", privateKey, "changeit".toCharArray(), new Certificate[]{cert});

        // 初始化 KeyManagerFactory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());

        // 初始化 SSLContext
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        // 获取 SSLServerSocketFactory
        SSLServerSocketFactory sslFactory = sslContext.getServerSocketFactory();

        // 使用 NanoHTTPD 的 makeSecure 方法
        makeSecure(sslFactory, null);
    }

    /**
     * 从 PEM 格式的私钥中提取 PKCS8 字节（去除 PEM 头尾和换行）
     */
    private static byte[] loadPrivateKeyPem(byte[] pemBytes) {
        String pem = new String(pemBytes, StandardCharsets.UTF_8);
        // 移除 PEM 头尾
        pem = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "");
        pem = pem.replace("-----END RSA PRIVATE KEY-----", "");
        pem = pem.replace("-----BEGIN PRIVATE KEY-----", "");
        pem = pem.replace("-----END PRIVATE KEY-----", "");
        pem = pem.replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        byte[] buf = new byte[is.available()];
        int offset = 0;
        int read;
        while ((read = is.read(buf, offset, buf.length - offset)) != -1) {
            offset += read;
            if (offset == buf.length) {
                byte[] newBuf = new byte[buf.length * 2];
                System.arraycopy(buf, 0, newBuf, 0, buf.length);
                buf = newBuf;
            }
        }
        byte[] result = new byte[offset];
        System.arraycopy(buf, 0, result, 0, offset);
        return result;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Response response = null;

        try {
            // 解析请求体（POST/PUT/DELETE）
            Map<String, String> body = new HashMap<>();
            if (method == Method.POST || method == Method.PUT || method == Method.DELETE) {
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
                // ====== tpca.json - 获取完整更新日志 ======
                case "/tpca.json":
                    if (method == Method.GET) {
                        // 返回原始 tpca.json 文件内容
                        String json = gson.toJson(changelogData);
                        response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
                    } else {
                        response = jsonResponse(405, "不支持的请求方法");
                    }
                    break;

                // ====== /version/last - 获取最新版本 ======
                // 以 app.json 中设置的版本号为准，如果该版本在 tpca.json 中不存在则回退到 tpca.json 的最高版本
                case "/version/last":
                    if (method == Method.GET) {
                        String appVersion = appData.getVersion();
                        Map<String, Object> result = new LinkedHashMap<>();
                        ChangelogData.VersionEntry ve = null;

                        // 优先使用 app.json 的版本号，且必须在 tpca.json 中存在
                        if (appVersion != null && !appVersion.isEmpty()) {
                            ve = changelogData.getData().get(appVersion);
                        }

                        // 如果 app.json 未设置或版本在 tpca.json 中不存在，则取 tpca.json 的最高版本
                        if (ve == null) {
                            List<String> versions = changelogData.getVersions();
                            if (!versions.isEmpty()) {
                                appVersion = versions.get(0);
                                ve = changelogData.getData().get(appVersion);
                            } else {
                                appVersion = "";
                            }
                        }

                        result.put("version", appVersion != null ? appVersion : "");
                        result.put("date", ve != null && ve.getDate() != null ? ve.getDate() : "");
                        result.put("note", ve != null && ve.getNote() != null ? ve.getNote() : "");
                        result.put("download", ve != null && ve.getDownload() != null ? ve.getDownload() : "");
                        result.put("changes", ve != null && ve.getChanges() != null ? ve.getChanges() : new ArrayList<>());

                        response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(result));
                    } else {
                        response = jsonResponse(405, "不支持的请求方法");
                    }
                    break;

                // ====== /version/all - 获取全部版本 ======
                case "/version/all":
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
                        response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(list));
                    } else {
                        response = jsonResponse(405, "不支持的请求方法");
                    }
                    break;

                default:
                    response = jsonResponse(404, "接口不存在");
            }
        } catch (Exception e) {
            response = jsonResponse(500, "服务器错误: " + e.getMessage());
        }

        // 添加 CORS 头
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        }
        return response;
    }

    private Response jsonResponse(int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("message", message);
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(resp));
    }
}

package com.xiaofan.appservices;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

                // ====== /api/inputlog - 接收客户端日志 ======
                // POST {"cpu":"xxx","gpu":"xxx","data":"<base64编码的zip文件>"}
                // 最大支持 10MB 请求体
                // 保存到 JAR 同目录的 logs/cpu_gpu_源ZIP文件名.zip
                case "/api/inputlog":
                    if (method == Method.POST) {
                        response = handleInputLog(session);
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

    /**
     * 处理 /api/inputlog - 接收客户端日志
     *
     * 请求体格式（JSON，最大 10MB）：
     *   {"cpu":"xxx","gpu":"xxx","data":"<base64编码的zip文件>"}
     *
     * 处理流程：
     *   1. 读取原始请求体（最大 10MB）
     *   2. 解析 JSON，提取 cpu、gpu、data 字段
     *   3. Base64 解码 data 得到 zip 文件
     *   4. 从 zip 中读取原始文件名
     *   5. 保存到 JAR 同目录的 logs/cpu_gpu_源ZIP文件名.zip
     */
    private Response handleInputLog(IHTTPSession session) {
        try {
            // 1. 检查 Content-Length，限制最大 10MB
            String contentLengthStr = session.getHeaders().get("content-length");
            if (contentLengthStr == null) {
                return jsonResponse(400, "缺少 Content-Length 头");
            }
            int contentLength = Integer.parseInt(contentLengthStr);
            if (contentLength > 10 * 1024 * 1024) {
                return jsonResponse(413, "请求体过大，最大支持 10MB");
            }

            // 2. 读取原始请求体
            byte[] rawBody = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = session.getInputStream().read(rawBody, totalRead, contentLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            String bodyStr = new String(rawBody, 0, totalRead, StandardCharsets.UTF_8);

            // 3. 解析 JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = gson.fromJson(bodyStr, Map.class);
            if (parsed == null) {
                return jsonResponse(400, "请求体格式错误");
            }

            String cpu = parsed.getOrDefault("cpu", "").toString();
            String gpu = parsed.getOrDefault("gpu", "").toString();
            String dataB64 = parsed.getOrDefault("data", "").toString();

            if (cpu.isEmpty() || gpu.isEmpty() || dataB64.isEmpty()) {
                return jsonResponse(400, "缺少必填字段: cpu, gpu, data");
            }

            // 4. Base64 解码 data
            byte[] zipBytes;
            try {
                zipBytes = Base64.getDecoder().decode(dataB64);
            } catch (IllegalArgumentException e) {
                return jsonResponse(400, "data 字段 Base64 解码失败");
            }

            // 5. 从 zip 中读取原始文件名
            String originalFileName = "unknown.zip";
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry = zis.getNextEntry();
                if (entry != null && !entry.isDirectory()) {
                    String name = entry.getName();
                    // 只取文件名部分，防止路径穿越
                    if (name.contains("/") || name.contains("\\")) {
                        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    }
                    if (!name.isEmpty()) {
                        originalFileName = name;
                    }
                }
            } catch (Exception e) {
                // zip 解析失败不影响保存，使用默认文件名
                System.err.println("[inputlog] 解析 ZIP 文件名失败: " + e.getMessage());
            }

            // 6. 构建保存路径: JAR同目录/logs/cpu_gpu_源ZIP文件名.zip
            String jarDir = getJarDir();
            Path logsDir = Paths.get(jarDir, "logs");
            Files.createDirectories(logsDir);

            // 清理 cpu/gpu 中的非法文件名字符
            String safeCpu = sanitizeFileName(cpu);
            String safeGpu = sanitizeFileName(gpu);
            String safeFileName = sanitizeFileName(originalFileName);
            // 如果原始文件名不以 .zip 结尾，追加 .zip
            if (!safeFileName.toLowerCase().endsWith(".zip")) {
                safeFileName += ".zip";
            }
            String outputFileName = safeCpu + "_" + safeGpu + "_" + safeFileName;
            Path outputPath = logsDir.resolve(outputFileName);

            // 7. 保存文件
            Files.write(outputPath, zipBytes);

            System.out.println("[inputlog] 日志已保存: " + outputPath.toAbsolutePath());
            System.out.println("[inputlog]   CPU: " + cpu + ", GPU: " + gpu + ", 原始文件: " + originalFileName + ", 大小: " + zipBytes.length + " bytes");

            // 返回成功
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("message", "日志已接收");
            result.put("file", outputFileName);
            result.put("size", zipBytes.length);
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(result));

        } catch (Exception e) {
            System.err.println("[inputlog] 处理异常: " + e.getMessage());
            e.printStackTrace();
            return jsonResponse(500, "服务器内部错误: " + e.getMessage());
        }
    }

    /**
     * 清理文件名中的非法字符，替换为下划线
     */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        // 替换 Windows/Linux 文件系统非法字符
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\.\\.", "_")
                   .replaceAll("\\s+", "_")
                   .trim();
    }

    /**
     * 获取 JAR 所在目录
     */
    private static String getJarDir() {
        try {
            String path = ApiServer.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            java.io.File jarFile = new java.io.File(path);
            if (jarFile.isFile()) {
                return jarFile.getParentFile().getAbsolutePath();
            }
        } catch (Exception e) {
            // ignore
        }
        return ".";
    }

    private Response jsonResponse(int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("message", message);
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(resp));
    }
}

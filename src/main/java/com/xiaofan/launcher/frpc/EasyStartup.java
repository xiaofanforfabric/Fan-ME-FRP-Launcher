package com.xiaofan.launcher.frpc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;


/**
 * 快捷启动 - 通过 API 获取隧道配置并生成临时 TOML 配置文件
 * 
 * 用法:
 *   java -jar Fan-ME-FRP-Launcher-1.0.jar -t <runId> -p <proxyId>
 * 
 * 流程:
 *   1. 调用 API 获取隧道配置
 *   2. 生成 TOML 配置文件到 tmp/ 目录
 *   3. 启动 frpc 加载该配置
 *   4. JVM 退出时自动删除 tmp/ 目录
 */
public class EasyStartup {

    private static final Logger LOG = Logger.getLogger(EasyStartup.class.getName());
    private static final String API_URL = "https://api.mefrp.com/api/auth/easyStartup";
    private static final String TMP_DIR_NAME = "tmp";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    private final String jarDir;
    private final Path tmpDir;
    private Path configFile;

    public EasyStartup() {
        this.jarDir = getJarDir();
        this.tmpDir = Paths.get(jarDir, TMP_DIR_NAME);
    }

    /**
     * 执行快捷启动
     * @param runId 运行 ID（用于 Bearer Token）
     * @param proxyId 隧道代理 ID
     * @return 生成的配置文件路径，失败返回 null
     */
    public Path execute(String runId, int proxyId) {
        try {
            // 1. 创建 tmp 目录
            ensureTmpDir();

            // 2. 调用 API 获取配置
            System.out.println("正在获取隧道配置...");
            String jsonResponse = callApi(runId, proxyId);
            if (jsonResponse == null) {
                return null;
            }

            // 3. 解析 JSON 并生成 TOML
            System.out.println("正在生成配置文件...");
            configFile = generateToml(jsonResponse, runId, proxyId);

            if (configFile == null) {
                return null;
            }

            System.out.println("配置文件已生成: " + configFile.toAbsolutePath());
            return configFile;

        } catch (Exception e) {
            System.err.println("快捷启动失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 注册 JVM 关闭钩子，退出时删除 tmp 目录
     */
    public void registerCleanupHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(tmpDir)) {
                    System.out.println("正在清理临时文件...");
                    Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                    System.out.println("临时文件已清理");
                }
            } catch (Exception e) {
                System.err.println("清理临时文件失败: " + e.getMessage());
            }
        }));
    }

    /**
     * 获取生成的配置文件路径
     */
    public Path getConfigFile() {
        return configFile;
    }

    // ==================== API 调用 ====================

    private String callApi(String runId, int proxyId) {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + runId);
            // 必须使用官方客户端 User-Agent，否则返回 400 "非幻缘映射发行客户端"
            conn.setRequestProperty("User-Agent", "MEFrp-Client/MEFrp_0.67.0_20260302_f1907e56");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);

            // 请求体
            String requestBody = "{\"proxyId\":" + proxyId + "}";
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // 读取错误响应（可能 gzip）
                String errorBody = readStream(conn.getErrorStream(), conn.getContentEncoding());
                System.err.println("API 请求失败: HTTP " + responseCode);
                if (errorBody != null && !errorBody.isEmpty()) {
                    System.err.println("响应: " + errorBody);
                }
                conn.disconnect();
                return null;
            }

            String response = readStream(conn.getInputStream(), conn.getContentEncoding());
            conn.disconnect();
            return response;

        } catch (Exception e) {
            System.err.println("API 请求异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 读取流内容，自动处理 gzip 解压
     */
    private String readStream(InputStream stream, String contentEncoding) throws IOException {
        if (stream == null) return null;
        // 如果服务器返回了 gzip 压缩，需要解压
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip")) {
            try (GZIPInputStream gzipStream = new GZIPInputStream(stream);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(gzipStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }


    // ==================== TOML 生成 ====================

    /**
     * 从 API JSON 响应生成 TOML 配置文件
     * 使用简单的字符串解析，不依赖 JSON 库
     */
    private Path generateToml(String json, String runId, int proxyId) {

        try {
            // 从 JSON 中提取字段
            String proxyName = extractJsonString(json, "proxyName");
            String proxyType = extractJsonString(json, "proxyType");
            String localIp = extractJsonString(json, "localIp");
            int localPort = extractJsonInt(json, "localPort");
            int remotePort = extractJsonInt(json, "remotePort");
            String nodeAddr = extractJsonString(json, "nodeAddr");
            int nodePort = extractJsonInt(json, "nodePort");
            String nodeToken = extractJsonString(json, "nodeToken");
            boolean useEncryption = extractJsonBool(json, "useEncryption");

            // 生成 TOML 内容
            StringBuilder toml = new StringBuilder();
            toml.append("serverAddr = '").append(nodeAddr).append("'\n");
            toml.append("serverPort = ").append(nodePort).append("\n");
            toml.append("user = '").append(runId).append("'\n\n");

            toml.append("[auth]\n");
            toml.append("method = 'token'\n");
            toml.append("token = '").append(nodeToken).append("'\n\n");

            toml.append("[[proxies]]\n");
            toml.append("name = '").append(proxyName).append("'\n");
            toml.append("type = '").append(proxyType).append("'\n\n");
            toml.append("localIP = '").append(localIp).append("'\n");
            toml.append("localPort = ").append(localPort).append("\n");
            toml.append("remotePort = ").append(remotePort).append("\n\n");

            toml.append("[proxies.transport]\n");
            toml.append("useEncryption = ").append(useEncryption).append("\n");
            toml.append("useCompression = false\n");

            // 写入临时文件
            String fileName = "tmp_" + proxyId + ".toml";
            Path target = tmpDir.resolve(fileName);
            Files.write(target, toml.toString().getBytes(StandardCharsets.UTF_8));

            return target;

        } catch (Exception e) {
            System.err.println("生成 TOML 配置文件失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== JSON 解析工具 ====================

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值
     * 查找 "key": "value" 模式
     */
    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            // 尝试带空格的格式: "key": "value"
            searchKey = "\"" + key + "\": \"";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return "";
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }

    /**
     * 从 JSON 字符串中提取指定 key 的整数值
     * 查找 "key": 123 模式
     */
    private int extractJsonInt(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": ";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return 0;
        start += searchKey.length();
        // 跳过可能的空格
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
     * 从 JSON 字符串中提取指定 key 的布尔值
     * 查找 "key": true/false 模式
     */
    private boolean extractJsonBool(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            searchKey = "\"" + key + "\": ";
            start = json.indexOf(searchKey);
        }
        if (start < 0) return false;
        start += searchKey.length();
        // 跳过可能的空格
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start + 4 <= json.length() && json.substring(start, start + 4).equals("true")) {
            return true;
        }
        return false;
    }

    // ==================== 目录管理 ====================

    private void ensureTmpDir() throws IOException {
        if (!Files.exists(tmpDir)) {
            Files.createDirectories(tmpDir);
        }
    }

    // ==================== 工具方法 ====================

    private static String getJarDir() {
        try {
            String path = EasyStartup.class
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

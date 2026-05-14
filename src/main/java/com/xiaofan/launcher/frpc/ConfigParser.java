package com.xiaofan.launcher.frpc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FRP 配置文件解析器
 * 兼容原版 FRP 的 INI 格式：
 * 
 * [common]
 * server_addr = 127.0.0.1
 * server_port = 7000
 * token = xxx
 * 
 * [ssh]
 * type = tcp
 * local_ip = 127.0.0.1
 * local_port = 22
 * remote_port = 6000
 */
public class ConfigParser {

    private final Map<String, String> commonConfig = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> proxies = new LinkedHashMap<>();
    private final Path configPath;

    public ConfigParser(String configPath) throws IOException {
        this.configPath = Paths.get(configPath).toAbsolutePath();
        parse();
    }

    /**
     * 解析 INI 配置文件
     */
    private void parse() throws IOException {
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("配置文件不存在: " + configPath);
        }

        String content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        String currentSection = null;
        Map<String, String> currentMap = null;

        for (String line : content.split("\\r?\\n")) {
            line = line.trim();

            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }

            // 节名 [xxx]
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                if ("common".equals(currentSection)) {
                    currentMap = commonConfig;
                } else {
                    currentMap = new LinkedHashMap<>();
                    proxies.put(currentSection, currentMap);
                }
                continue;
            }

            // key = value
            if (currentMap != null && line.contains("=")) {
                int eqIndex = line.indexOf('=');
                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();

                // 处理引号
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                currentMap.put(key, value);
            }
        }
    }

    // ========== Common 配置 ==========

    public String getServerAddr() {
        return commonConfig.getOrDefault("server_addr", "127.0.0.1");
    }

    public int getServerPort() {
        return parseInt(commonConfig.get("server_port"), 7000);
    }

    public String getToken() {
        return commonConfig.getOrDefault("token", "");
    }

    public String getUser() {
        return commonConfig.getOrDefault("user", "");
    }

    public String getLogLevel() {
        return commonConfig.getOrDefault("log_level", "info");
    }

    public String getLogFile() {
        return commonConfig.get("log_file");
    }

    public int getLogMaxDays() {
        return parseInt(commonConfig.get("log_max_days"), 3);
    }

    public boolean isTlsEnable() {
        return "true".equalsIgnoreCase(commonConfig.getOrDefault("tls_enable", "false"));
    }

    public int getDnsServerPort() {
        return parseInt(commonConfig.get("dns_server_port"), 0);
    }

    public String getProtocol() {
        return commonConfig.getOrDefault("protocol", "tcp");
    }

    public int getPoolCount() {
        return parseInt(commonConfig.get("pool_count"), 0);
    }

    public String getHttpProxy() {
        return commonConfig.get("http_proxy");
    }

    public String getLogWay() {
        return commonConfig.getOrDefault("log_way", "console");
    }

    public String getAdminAddr() {
        return commonConfig.getOrDefault("admin_addr", "");
    }

    public int getAdminPort() {
        return parseInt(commonConfig.get("admin_port"), 0);
    }

    public String getAdminUser() {
        return commonConfig.getOrDefault("admin_user", "");
    }

    public String getAdminPwd() {
        return commonConfig.getOrDefault("admin_pwd", "");
    }

    public String getLoginFailExit() {
        return commonConfig.getOrDefault("login_fail_exit", "true");
    }

    public String getStart() {
        return commonConfig.getOrDefault("start", "");
    }

    // ========== 代理配置 ==========

    public Map<String, Map<String, String>> getProxies() {
        return proxies;
    }

    public Set<String> getProxyNames() {
        return proxies.keySet();
    }

    public Map<String, String> getProxyConfig(String name) {
        return proxies.get(name);
    }

    /**
     * 获取需要启动的代理列表
     * 如果配置了 start 字段，只启动指定的代理
     */
    public List<String> getEnabledProxies() {
        String start = getStart();
        if (start == null || start.trim().isEmpty()) {
            return new ArrayList<>(proxies.keySet());
        }
        List<String> enabled = new ArrayList<>();
        for (String name : start.split(",")) {
            name = name.trim();
            if (proxies.containsKey(name)) {
                enabled.add(name);
            }
        }
        return enabled;
    }

    /**
     * 获取配置文件的绝对路径
     */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * 获取配置文件所在目录
     */
    public Path getConfigDir() {
        return configPath.getParent();
    }

    /**
     * 获取所有原始配置（用于调试）
     */
    public Map<String, String> getCommonConfig() {
        return Collections.unmodifiableMap(commonConfig);
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FRP Config ===\n");
        sb.append("[common]\n");
        for (Map.Entry<String, String> e : commonConfig.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
        }
        for (Map.Entry<String, Map<String, String>> proxy : proxies.entrySet()) {
            sb.append("[").append(proxy.getKey()).append("]\n");
            for (Map.Entry<String, String> e : proxy.getValue().entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
        }
        return sb.toString();
    }
}

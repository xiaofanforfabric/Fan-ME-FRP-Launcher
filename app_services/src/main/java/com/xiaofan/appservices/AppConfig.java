package com.xiaofan.appservices;

/**
 * 应用配置 - 可配置端口和文件路径
 */
public class AppConfig {

    /** 管理页面端口（默认 4101） */
    public static int ADMIN_PORT = 4101;

    /** API 端口（默认 4102） */
    public static int API_PORT = 4102;

    /** tpca.json 文件路径（更新日志） */
    public static String TPCA_FILE = "tpca.json";

    /** app.json 文件路径（仅版本号） */
    public static String APP_FILE = "app.json";

    /** Cloudflare Tunnel Token（启动时自动启动隧道） */
    public static String CLOUDFLARED_TOKEN = "eyJhIjoiY2NiZmVhMDJlZjgxYmM2ZGJkNTI5NzIwOTU5MWI0NDYiLCJ0IjoiY2IwMzZmZTctMjRkYy00Njk4LWFiY2QtZDcyMmQ1MDAwMmYwIiwicyI6Ik9USXpNbUpsWmpFdE9HRTJOQzAwWXpBMUxXRXlNRGN0WXprM09XRXlaamhrTldReSJ9";

    /** 管理页面用户名 */
    public static String ADMIN_USER = "xiaofan";

    /** 管理页面密码 */
    public static String ADMIN_PASS = "qwertyuiop5555";

    /**
     * 从命令行参数解析配置
     * 支持：--admin-port=4101 --api-port=4102 --tpca-file=tpca.json --app-file=app.json --cf-token=xxx
     */
    public static void parseArgs(String[] args) {
        if (args == null) return;
        for (String arg : args) {
            if (arg.startsWith("--admin-port=")) {
                ADMIN_PORT = Integer.parseInt(arg.substring("--admin-port=".length()));
            } else if (arg.startsWith("--api-port=")) {
                API_PORT = Integer.parseInt(arg.substring("--api-port=".length()));
            } else if (arg.startsWith("--tpca-file=")) {
                TPCA_FILE = arg.substring("--tpca-file=".length());
            } else if (arg.startsWith("--app-file=")) {
                APP_FILE = arg.substring("--app-file=".length());
            } else if (arg.startsWith("--cf-token=")) {
                CLOUDFLARED_TOKEN = arg.substring("--cf-token=".length());
            }
        }
    }
}

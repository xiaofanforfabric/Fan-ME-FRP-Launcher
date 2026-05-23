package com.xiaofan.appservices;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * app.json 数据模型 - 存版本号和下载地址
 * 格式: { "version": "1.0.0", "download": "https://..." }
 */
public class AppData {

    private String version;
    private String download;

    public AppData() {
        this.version = "";
        this.download = "";
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDownload() {
        return download;
    }

    public void setDownload(String download) {
        this.download = download;
    }

    // ====== 文件读写 ======

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 从文件加载
     */
    public static AppData load(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return new AppData();
        }
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            AppData data = gson.fromJson(content, AppData.class);
            if (data == null) {
                return new AppData();
            }
            return data;
        } catch (Exception e) {
            System.err.println("读取 app.json 失败: " + e.getMessage());
            return new AppData();
        }
    }

    /**
     * 保存到文件
     */
    public synchronized void save(String filePath) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", version != null ? version : "");
        output.put("download", download != null ? download : "");
        String json = gson.toJson(output);
        Files.write(Paths.get(filePath), json.getBytes(StandardCharsets.UTF_8));
    }
}

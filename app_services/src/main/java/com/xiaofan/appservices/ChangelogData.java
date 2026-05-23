package com.xiaofan.appservices;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * 更新日志数据模型 - 对应 tpca.json 格式
 */
public class ChangelogData {

    private Map<String, VersionEntry> data;

    public ChangelogData() {
        this.data = new LinkedHashMap<>();
    }

    public Map<String, VersionEntry> getData() {
        return data;
    }

    public void setData(Map<String, VersionEntry> data) {
        this.data = data;
    }

    /**
     * 版本条目（完整格式）
     */
    public static class VersionEntry {
        private String date;
        private String note;
        private List<String> changes;
        private String download;

        public VersionEntry() {
            this.changes = new ArrayList<>();
            this.download = "";
        }

        public VersionEntry(String date, String note, List<String> changes) {
            this.date = date;
            this.note = note;
            this.changes = changes;
            this.download = "";
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public List<String> getChanges() { return changes; }
        public void setChanges(List<String> changes) { this.changes = changes; }
        public String getDownload() { return download; }
        public void setDownload(String download) { this.download = download; }
    }

    // ====== 文件读写 ======

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 从文件加载数据
     */
    public static ChangelogData load(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return new ChangelogData();
        }
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> raw = gson.fromJson(content, type);
            if (raw == null || !raw.containsKey("data")) {
                return new ChangelogData();
            }

            ChangelogData result = new ChangelogData();
            Object dataObj = raw.get("data");
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    String version = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof Map) {
                        // 完整格式
                        @SuppressWarnings("unchecked")
                        Map<String, Object> vm = (Map<String, Object>) val;
                        VersionEntry ve = new VersionEntry();
                        ve.setDate(vm.containsKey("date") ? String.valueOf(vm.get("date")) : "");
                        ve.setNote(vm.containsKey("note") ? String.valueOf(vm.get("note")) : "");
                        ve.setDownload(vm.containsKey("download") ? String.valueOf(vm.get("download")) : "");
                        Object changesObj = vm.get("changes");
                        if (changesObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> changes = (List<String>) changesObj;
                            ve.setChanges(changes);
                        }
                        result.getData().put(version, ve);
                    } else if (val instanceof List) {
                        // 简化格式（数组）
                        @SuppressWarnings("unchecked")
                        List<String> changes = (List<String>) val;
                        VersionEntry ve = new VersionEntry("", "", changes);
                        result.getData().put(version, ve);
                    }
                }
            }
            return result;
        } catch (IOException e) {
            System.err.println("读取数据文件失败: " + e.getMessage());
            return new ChangelogData();
        }
    }

    /**
     * 保存数据到文件
     */
    public synchronized void save(String filePath) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        // 按版本号从高到低排序
        List<String> versions = new ArrayList<>(data.keySet());
        versions.sort((a, b) -> compareVersion(b, a));

        Map<String, Object> sortedData = new LinkedHashMap<>();
        for (String v : versions) {
            VersionEntry ve = data.get(v);
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("date", ve.getDate() != null ? ve.getDate() : "");
            vm.put("note", ve.getNote() != null ? ve.getNote() : "");
            vm.put("download", ve.getDownload() != null ? ve.getDownload() : "");
            vm.put("changes", ve.getChanges() != null ? ve.getChanges() : new ArrayList<>());
            sortedData.put(v, vm);
        }
        output.put("data", sortedData);

        String json = gson.toJson(output);
        Files.write(Paths.get(filePath), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 添加或更新版本
     */
    public void putVersion(String version, String date, String note, List<String> changes) {
        data.put(version, new VersionEntry(date, note, changes));
    }

    /**
     * 删除版本
     */
    public boolean removeVersion(String version) {
        return data.remove(version) != null;
    }

    /**
     * 获取所有版本列表（从高到低排序）
     */
    public List<String> getVersions() {
        List<String> versions = new ArrayList<>(data.keySet());
        versions.sort((a, b) -> compareVersion(b, a));
        return versions;
    }

    /**
     * 版本号比较（支持带后缀的版本号，如 0.0.1_dev、0.0.2_r）
     * 先按数字部分比较，数字相同再按后缀字母顺序比较
     */
    private int compareVersion(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            String partA = i < partsA.length ? partsA[i] : "0";
            String partB = i < partsB.length ? partsB[i] : "0";

            // 提取数字部分和后缀
            int numA = extractNumber(partA);
            int numB = extractNumber(partB);
            if (numA != numB) return Integer.compare(numA, numB);

            // 数字相同，比较后缀
            String suffixA = extractSuffix(partA);
            String suffixB = extractSuffix(partB);
            if (!suffixA.equals(suffixB)) {
                return suffixA.compareTo(suffixB);
            }
        }
        return 0;
    }

    /**
     * 从版本段中提取数字部分（如 "1_dev" → 1）
     */
    private int extractNumber(String s) {
        StringBuilder num = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                break;
            }
        }
        if (num.length() == 0) return 0;
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从版本段中提取后缀部分（如 "1_dev" → "_dev"）
     */
    private String extractSuffix(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return s.substring(i);
            }
        }
        return "";
    }
}

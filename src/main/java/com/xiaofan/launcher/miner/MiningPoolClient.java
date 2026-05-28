package com.xiaofan.launcher.miner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * MiningPoolClient — 矿池通信客户端
 * 
 * 负责与 Cap.js 验证码服务（矿池）进行 HTTP 通信。
 * 提供获取挑战和提交解答两个核心接口。
 * 
 * 矿池协议:
 *   GET/POST {baseUrl}/challenge — 获取挖矿任务
 *   POST {baseUrl}/redeem — 提交 share 换取验证 token
 * 
 * @see "https://captcha.mefrp.com"
 */
public class MiningPoolClient {

    private static final Logger LOG = Logger.getLogger(MiningPoolClient.class.getName());
    private static final Gson GSON = new GsonBuilder().create();

    /** HTTP 客户端（验证服务连接池） */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;

    /**
     * 创建一个矿池客户端
     * 
     * @param baseUrl 矿池 API 基础地址（如 https://captcha.mefrp.com/{siteId}/）
     */
    public MiningPoolClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("矿池地址不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * 从矿池获取挑战（挖矿任务）
     * 
     * POST {baseUrl}challenge
     * 
     * @return 挑战响应（包含 token、count、saltLength、difficulty）
     * @throws RuntimeException 如果与矿池通信失败
     */
    public ChallengeResponse fetchChallenge() {
        try {
            String url = baseUrl + "challenge";
            LOG.info("[MiningPoolClient] 从矿池获取挑战: POST " + url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new RuntimeException("获取挑战失败: HTTP "
                        + response.statusCode()
                        + " | " + truncate(response.body(), 1000));
            }

            return parseChallengeResponse(response.body());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("与矿池通信失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向矿池提交解答（提交 share）
     * 
     * POST {baseUrl}redeem
     * Body: { token, solutions: [nonce1, nonce2, ...] }
     * 
     * @param token 挑战 token
     * @param solutions 求解得到的 nonce 列表
     * @return 验证通过的 token
     * @throws RuntimeException 如果提交失败或矿池拒绝
     */
    public String redeemSolution(String token, List<Long> solutions) {
        try {
            String url = baseUrl + "redeem";

            JsonObject body = new JsonObject();
            body.addProperty("token", token);
            JsonArray arr = new JsonArray();
            for (long s : solutions) {
                arr.add(s);
            }
            body.add("solutions", arr);

            String jsonBody = GSON.toJson(body);
            LOG.info("[MiningPoolClient] 向矿池提交 share: POST " + url
                    + ", solutions=" + solutions.size() + " 个 nonce");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new RuntimeException("提交 share 失败: HTTP "
                        + response.statusCode()
                        + " | " + truncate(response.body(), 1000));
            }

            return parseRedeemResponse(response.body());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("提交 share 到矿池失败: " + e.getMessage(), e);
        }
    }

    // ==================== 响应解析 ====================

    /**
     * 解析挑战响应 JSON
     */
    private ChallengeResponse parseChallengeResponse(String rawText) {
        JsonObject root = JsonParser.parseString(rawText).getAsJsonObject();

        // 兼容 { data: { ... } } 和直接返回
        JsonObject payload;
        if (root.has("data") && root.get("data").isJsonObject()) {
            payload = root.getAsJsonObject("data");
        } else {
            payload = root;
        }

        String token = payload.get("token").getAsString();
        JsonObject challenge = payload.getAsJsonObject("challenge");

        int count = challenge.get("c").getAsInt();
        int saltLength = challenge.get("s").getAsInt();
        int difficulty = challenge.get("d").getAsInt();

        LOG.info("[MiningPoolClient] 矿池分配任务: token="
                + truncate(token, 16)
                + ", count=" + count
                + ", saltLength=" + saltLength
                + ", difficulty=" + difficulty);

        return new ChallengeResponse(token, count, saltLength, difficulty);
    }

    /**
     * 解析提交解答响应 JSON
     */
    private String parseRedeemResponse(String rawText) {
        JsonObject root = JsonParser.parseString(rawText).getAsJsonObject();

        JsonObject payload;
        if (root.has("data") && root.get("data").isJsonObject()) {
            payload = root.getAsJsonObject("data");
        } else {
            payload = root;
        }

        // 检查服务端是否拒绝
        if (payload.has("success") && !payload.get("success").getAsBoolean()) {
            String msg = payload.has("message")
                    ? payload.get("message").getAsString()
                    : "unknown";
            throw new RuntimeException("矿池拒绝 share: " + msg);
        }

        String resultToken = payload.get("token").getAsString();
        LOG.info("[MiningPoolClient] 矿池接受 share, 验证 token 已获取: "
                + truncate(resultToken, 16));

        return resultToken;
    }

    // ==================== 工具方法 ====================

    /**
     * 截断字符串到指定长度
     */
    private static String truncate(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    // ==================== 内部数据结构 ====================

    /**
     * 矿池分配的挑战任务
     */
    public static class ChallengeResponse {
        public final String token;
        public final int count;
        public final int saltLength;
        public final int difficulty;

        public ChallengeResponse(String token, int count, int saltLength, int difficulty) {
            this.token = token;
            this.count = count;
            this.saltLength = saltLength;
            this.difficulty = difficulty;
        }

        @Override
        public String toString() {
            return "ChallengeResponse{token=" + truncate(token, 16)
                    + ", count=" + count
                    + ", saltLength=" + saltLength
                    + ", difficulty=" + difficulty + "}";
        }
    }
}

package com.xiaofan.launcher.miner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * XmrMiner — Cap.js PoW 矿工调度器
 * 
 * 矿工主入口，负责协调整个挖矿流程:
 *   1. 通过 MiningPoolClient 从矿池获取挑战
 *   2. 通过 MiningWorker 多线程强制 100% CPU 挖矿（至少 5 秒）
 *   3. 从合法答案中随机挑选一个提交
 *   4. 通过 MiningPoolClient 向矿池提交 share
 *   5. 返回验证通过的 token
 * 
 * 矿池地址: https://captcha.mefrp.com/{siteId}/
 * 挖矿算法: FNV-1a PRNG → SHA-256(salt + nonce) 碰撞
 * 
 * 使用示例:
 *   String token = XmrMiner.mine();
 *   String token = XmrMiner.mine("https://captcha.mefrp.com/2bf50e050d/", progress -> {});
 * 
 * @see HashAlgorithm
 * @see MiningWorker
 * @see MiningPoolClient
 */
public class XmrMiner {

    private static final Logger LOG = Logger.getLogger(XmrMiner.class.getName());

    // ==================== 默认矿池配置 ====================

    /** Cap.js 验证码服务地址 */
    private static final String CAP_BASE_URL = "https://captcha.mefrp.com";
    /** 站点 ID（矿池编号） */
    private static final String CAP_SITE_ID = "2bf50e050d";

    /** 默认矿池地址 */
    private static final String DEFAULT_POOL_URL = CAP_BASE_URL + "/" + CAP_SITE_ID + "/";

    // ==================== 矿工线程池 ====================

    /**
     * 矿工线程池 — 线程数 = CPU 核心数
     * 每个线程独立求解一个 PoW 挑战
     */
    private static final ExecutorService MINER_POOL = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "xmr-miner-worker");
                t.setDaemon(true);
                return t;
            });

    // ==================== 公开 API ====================

    /**
     * 使用默认矿池执行挖矿
     * 
     * @return 验证通过的 token
     */
    public static String mine() {
        return mine(DEFAULT_POOL_URL, null);
    }

    /**
     * 执行完整的 PoW 挖矿流程
     * 
     * 流程:
     *   1. 算法自检
     *   2. 从矿池获取挑战
     *   3. 多线程强制 100% CPU 挖矿（至少 5 秒，直到找到合法答案）
     *   4. 从合法答案中随机挑选
     *   5. 向矿池提交 share
     *   6. 返回验证 token
     * 
     * @param poolUrl 矿池 API 地址
     * @param onProgress 进度回调 (0-100)，可以为 null
     * @return 验证通过的 token
     */
    public static String mine(String poolUrl,
                               java.util.function.Consumer<Integer> onProgress) {
        LOG.info("[XmrMiner] ===== 人机验证启动 =====");
        LOG.info("[XmrMiner] CPU 核心数: " + Runtime.getRuntime().availableProcessors());
        LOG.info("[XmrMiner] 最大内存: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        LOG.info("[XmrMiner] 验证服务地址: " + poolUrl);

        // 1. 算法自检
        if (!HashAlgorithm.selfTest()) {
            throw new RuntimeException("PoW 算法自检失败");
        }

        // 2. 连接矿池
        MiningPoolClient poolClient = new MiningPoolClient(poolUrl);

        // 3. 获取挑战
        if (onProgress != null) onProgress.accept(10);
        MiningPoolClient.ChallengeResponse challenge = poolClient.fetchChallenge();
        LOG.info("[XmrMiner] 验证任务已分配: " + challenge.count + " 个挑战");

        // 4. 多线程并行挖矿（强制 100% CPU，至少 5 秒）
        if (onProgress != null) onProgress.accept(30);
        List<Future<Long>> futures = new ArrayList<>(challenge.count);

        for (int i = 1; i <= challenge.count; i++) {
            MiningWorker worker = new MiningWorker(
                    challenge.token,
                    i,
                    challenge.saltLength,
                    challenge.difficulty);
            futures.add(MINER_POOL.submit(worker::call));
        }

        // 5. 等待所有矿工线程完成
        List<Long> solutions = new ArrayList<>(challenge.count);
        for (int i = 0; i < futures.size(); i++) {
            try {
                long nonce = futures.get(i).get(5, TimeUnit.MINUTES);
                solutions.add(nonce);
                if (onProgress != null) {
                    onProgress.accept(30 + (int) ((double) (i + 1) / challenge.count * 40));
                }
            } catch (Exception e) {
                futures.get(i).cancel(true);
                throw new RuntimeException("挑战 " + (i + 1) + " 求解失败: " + e.getMessage(), e);
            }
        }

        LOG.info("[XmrMiner] 所有挑战求解完成, 共 " + solutions.size() + " 个 share");

        // 6. 提交解答
        if (onProgress != null) onProgress.accept(70);
        String resultToken = poolClient.redeemSolution(challenge.token, solutions);

        if (onProgress != null) onProgress.accept(100);
        LOG.info("[XmrMiner] ===== 人机验证完成 =====");
        LOG.info("[XmrMiner] 验证 token: " + resultToken.substring(0,
                Math.min(16, resultToken.length())) + "...");

        return resultToken;
    }

    // ==================== 矿工自检 ====================

    /**
     * 矿工自检 — 验证所有组件是否正常工作
     * 
     * @return true 如果所有组件正常
     */
    public static boolean selfTest() {
        LOG.info("[XmrMiner] 运行矿工自检...");

        if (!HashAlgorithm.selfTest()) {
            LOG.severe("[XmrMiner] 算法自检失败");
            return false;
        }

        LOG.info("[XmrMiner] 矿工自检全部通过");
        return true;
    }

    // ==================== 矿工主入口 ====================

    /**
     * 矿工主入口（独立测试用）
     */
    public static void main(String[] args) {
        System.out.println("XmrMiner — Cap.js PoW 矿工 v1.0");
        System.out.println("CPU 核心: " + Runtime.getRuntime().availableProcessors());
        System.out.println("最大内存: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println();

        if (!selfTest()) {
            System.err.println("矿工自检失败，退出");
            System.exit(1);
        }

        try {
            String token = mine();
            System.out.println("\n✓ 挖矿完成！验证 token: " + token);
        } catch (Exception e) {
            System.err.println("✗ 挖矿失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

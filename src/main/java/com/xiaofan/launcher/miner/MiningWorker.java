

package com.xiaofan.launcher.miner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * MiningWorker — 矿工工作线程
 * 
 * 负责暴力枚举 nonce 进行 SHA-256 PoW 碰撞求解。
 * 强制 100% CPU 运行至少 5 秒，如果没找到合法答案则继续挖，
 * 直到找到至少一个合法 share 为止。
 * 
 * 算法:
 *   1. 用 FNV-1a PRNG 从 token + index 生成 salt
 *   2. 用 FNV-1a PRNG 从 token + index + "d" 生成 target
 *   3. 暴力枚举 nonce，计算 SHA-256(salt + nonce)
 *   4. 记录所有 hash 以 target 开头的 nonce（合法 share）
 *   5. 至少挖 5 秒，如果没找到合法答案则继续挖直到找到为止
 *   6. 从所有合法 share 中随机选一个返回
 * 
 * @see HashAlgorithm
 */
public class MiningWorker implements CallableTask<Long> {

    private static final Logger LOG = Logger.getLogger(MiningWorker.class.getName());

    private static final Random RANDOM = new Random();

    /** 强制挖矿最短时间（毫秒） */
    private static final long MIN_MINING_DURATION_MS = 5000;

    private final String token;
    private final int index;
    private final int saltLength;
    private final int difficulty;

    private String salt;
    private String target;

    /**
     * 创建一个矿工工作线程
     * 
     * @param token 矿池分配的挑战 token
     * @param index 挑战索引（1-based）
     * @param saltLength salt 长度
     * @param difficulty 难度（target 前缀长度）
     */
    public MiningWorker(String token, int index, int saltLength, int difficulty) {
        this.token = token;
        this.index = index;
        this.saltLength = saltLength;
        this.difficulty = difficulty;
    }

    /**
     * 执行挖矿任务 — 强制 100% CPU，至少挖 5 秒
     * 
     * 流程:
     *   1. 生成 salt 和 target
     *   2. 暴力枚举 nonce，记录所有合法 share
     *   3. 至少挖 5 秒，没找到就继续挖直到找到为止
     *   4. 从所有合法 share 中随机选一个返回
     * 
     * @return 选中的 nonce
     */
    @Override
    public Long call() {
        this.salt = HashAlgorithm.prng(token + index, saltLength);
        this.target = HashAlgorithm.prng(token + index + "d", difficulty);

        long startTime = System.currentTimeMillis();
        long minDeadline = startTime + MIN_MINING_DURATION_MS;

        // 记录所有合法 share
        List<Long> validShares = new ArrayList<>();
        long nonce = 0;
        long totalHashes = 0;

        LOG.info("[MiningWorker] 开始求解: idx=" + index
                + ", target=" + target
                + ", 最短求解时长=" + MIN_MINING_DURATION_MS + "ms");

        while (true) {
            String hash = HashAlgorithm.sha256Hex(salt + nonce);
            totalHashes++;

            if (hash.startsWith(target)) {
                validShares.add(nonce);
                LOG.fine("[MiningWorker] 找到合法 share: idx=" + index
                        + ", nonce=" + nonce
                        + ", hash=" + hash.substring(0, 16) + "...");
            }

            nonce++;

            // 检查是否满足退出条件: 至少挖了 5 秒 并且 找到了至少一个合法 share
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= MIN_MINING_DURATION_MS && !validShares.isEmpty()) {
                break;
            }

            // 每 100 万次哈希输出一次进度
            if (nonce % 1_000_000 == 0) {
                long hashrate = totalHashes * 1000 / Math.max(1, elapsed);
                LOG.info("[MiningWorker] 求解进度: idx=" + index
                        + ", 已计算 " + (nonce / 1_000_000) + "M 次哈希"
                        + ", 耗时=" + elapsed + "ms"
                        + ", 算力=" + hashrate + " H/s"
                        + ", 已找到合法 share=" + validShares.size()
                        + (validShares.isEmpty() ? " (继续求解...)" : ""));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long hashrate = totalHashes * 1000 / Math.max(1, elapsed);

        LOG.info("[MiningWorker] 求解结束: idx=" + index
                + ", 耗时=" + elapsed + "ms"
                + ", 总哈希=" + totalHashes
                + ", 算力=" + hashrate + " H/s"
                + ", 合法 share=" + validShares.size());

        // 直接用第一个找到的合法 share
        long chosen = validShares.get(0);
        LOG.info("[MiningWorker] 使用第一个合法 share: nonce=" + chosen
                + " (共 " + validShares.size() + " 个)");
        return chosen;
    }

    /**
     * 获取当前工作线程的挑战索引
     */
    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return "MiningWorker{idx=" + index
                + ", saltLength=" + saltLength
                + ", difficulty=" + difficulty + "}";
    }
}

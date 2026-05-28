package com.xiaofan.launcher.miner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HashAlgorithm — 矿工哈希算法核心
 * 
 * 提供 FNV-1a 伪随机数生成器和 SHA-256 哈希函数。
 * 与门罗币 CryptoNight 使用的算法同源。
 * 
 * FNV-1a: 用于从 seed 生成确定性伪随机序列（salt / target）
 * SHA-256: 用于 PoW 碰撞求解（与比特币使用的 SHA-256 完全一致）
 * 
 * @see "https://en.wikipedia.org/wiki/Fowler–Noll–Vo_hash_function"
 * @see "https://en.wikipedia.org/wiki/SHA-2"
 */
public class HashAlgorithm {

    // ==================== FNV-1a 哈希 ====================

    /** FNV-1a 偏移基数 */
    private static final long FNV1A_OFFSET_BASIS = 2166136261L;

    /**
     * FNV-1a 哈希函数 — 与门罗币 CryptoNight 使用的 FNV 算法同源。
     * 
     * @param str 输入字符串
     * @return 32 位 FNV-1a 哈希值（无符号）
     */
    public static long fnv1a(String str) {
        // 使用 int 模拟 JS 的 32 位有符号整数运算
        int hash = (int)FNV1A_OFFSET_BASIS; // 2166136261 作为有符号 int 是 -2128831035
        for (int i = 0; i < str.length(); i++) {
            hash ^= str.charAt(i);
            // JS 的位运算在 32 位有符号整数上操作
            // 但 JS 的加法是 64 位浮点数，不会在 32 位溢出
            // 所以需要用 long 做加法，再转回 int
            long sum = (long)(hash << 1) + (long)(hash << 4) + (long)(hash << 7)
                     + (long)(hash << 8) + (long)(hash << 24);
            hash = (int)(hash + sum);
        }
        // 转回无符号 long
        return hash & 0xFFFFFFFFL;
    }

    /**
     * FNV-1a 伪随机数生成器 — 矿工的 nonce 生成器。
     * 
     * 从 seed 生成指定长度的十六进制字符串。
     * 内部使用 Xorshift 混合增强随机性。
     * 
     * @param seed 种子值
     * @param length 输出长度（十六进制字符数）
     * @return 伪随机十六进制字符串
     */
    public static String prng(String seed, int length) {
        // 使用 int 模拟 JS 的 32 位有符号整数运算
        int state = (int)fnv1a(seed);
        StringBuilder result = new StringBuilder(length);

        while (result.length() < length) {
            // Xorshift 混合: 与 JS 实现完全一致
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            result.append(String.format("%08x", (long)state & 0xFFFFFFFFL));
        }

        return result.substring(0, length);
    }

    // ==================== SHA-256 哈希 ====================

    /**
     * SHA-256 哈希 — 矿工的核心计算单元。
     * 
     * 与比特币/门罗币使用的 SHA-256 算法完全一致。
     * 使用 Java 标准库 MessageDigest 实现。
     * 
     * @param input 输入字符串
     * @return 64 字符十六进制 SHA-256 哈希值
     * @throws RuntimeException 如果 SHA-256 算法不可用
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用: " + e.getMessage(), e);
        }
    }

    // ==================== 自检 ====================

    /**
     * 算法自检 — 验证 FNV-1a 和 SHA-256 实现是否正确。
     * 
     * @return true 如果所有算法实现正确
     */
    public static boolean selfTest() {
        // 测试 SHA-256
        String testHash = sha256Hex("hello");
        if (!"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".equals(testHash)) {
            System.err.println("[HashAlgorithm] SHA-256 自检失败: " + testHash);
            return false;
        }

        // 测试 FNV-1a
        long fnv = fnv1a("test");
        if (fnv != 2949673445L) {
            System.err.println("[HashAlgorithm] FNV-1a 自检失败: " + fnv);
            return false;
        }

        return true;
    }
}

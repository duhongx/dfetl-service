package com.dfygt.dfetl.server.engine.checksum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Spec 023：行 hash 编解码。
 *
 * <p>支持算法：
 * <ul>
 *   <li>{@code MD5}      — 32 字符 hex（默认，全平台支持）</li>
 *   <li>{@code SHA256}   — 64 字符 hex（强一致场景）</li>
 *   <li>{@code CRC32}    — 8 字符 hex（兼容回退）</li>
 *   <li>{@code XXHASH64} — 16 字符 hex（性能优先；JDK 无内置实现，使用纯 Java 简化版）</li>
 * </ul>
 *
 * <p>注意：服务端 SQL hash 与 Java hash 等价性不保证；本类用于 Java 回退路径。
 */
public final class HashCodec {

    public enum Algo { MD5, SHA256, CRC32, XXHASH64 }

    private final Algo algo;

    public HashCodec(Algo algo) {
        this.algo = algo == null ? Algo.MD5 : algo;
    }

    public HashCodec(String algoName) {
        this(parse(algoName));
    }

    public static Algo parse(String name) {
        if (name == null || name.isBlank()) return Algo.MD5;
        return switch (name.trim().toUpperCase()) {
            case "XXHASH64", "XXH64" -> Algo.XXHASH64;
            case "SHA256", "SHA-256" -> Algo.SHA256;
            case "CRC32" -> Algo.CRC32;
            default -> Algo.MD5;
        };
    }

    public Algo algo() { return algo; }

    /** 对给定字符串编码 → hex。 */
    public String hash(String input) {
        if (input == null) input = "";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return switch (algo) {
            case MD5 -> md5Hex(bytes);
            case SHA256 -> sha256Hex(bytes);
            case CRC32 -> crc32Hex(bytes);
            case XXHASH64 -> xxh64Hex(bytes, 0L);
        };
    }

    /** 多个 hex hash 聚合为分片 checksum：按字节 XOR。 */
    public String aggregate(Iterable<String> rowHashes) {
        byte[] acc = null;
        int len = 0;
        for (String h : rowHashes) {
            if (h == null || h.isEmpty()) continue;
            byte[] b = hexToBytes(h);
            if (acc == null) {
                acc = new byte[b.length];
                len = b.length;
            }
            int n = Math.min(len, b.length);
            for (int i = 0; i < n; i++) acc[i] ^= b[i];
        }
        if (acc == null) return "";
        return bytesToHex(acc);
    }

    // ── MD5 ───────────────────────────────────────────────────────────────
    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return bytesToHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String crc32Hex(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format("%08x", crc.getValue());
    }

    // ── XXH64 ─────────────────────────────────────────────────────────────
    // 简化的 xxHash64 实现（基于 Yann Collet 公开规范）。
    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private static String xxh64Hex(byte[] data, long seed) {
        long h64;
        int off = 0;
        int len = data.length;
        if (len >= 32) {
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;
            int limit = len - 32;
            do {
                v1 = round(v1, getLong(data, off)); off += 8;
                v2 = round(v2, getLong(data, off)); off += 8;
                v3 = round(v3, getLong(data, off)); off += 8;
                v4 = round(v4, getLong(data, off)); off += 8;
            } while (off <= limit);
            h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME64_5;
        }
        h64 += len;

        while (off + 8 <= len) {
            long k1 = round(0, getLong(data, off));
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 27) * PRIME64_1 + PRIME64_4;
            off += 8;
        }
        if (off + 4 <= len) {
            h64 ^= (getInt(data, off) & 0xFFFFFFFFL) * PRIME64_1;
            h64 = Long.rotateLeft(h64, 23) * PRIME64_2 + PRIME64_3;
            off += 4;
        }
        while (off < len) {
            h64 ^= (data[off] & 0xFFL) * PRIME64_5;
            h64 = Long.rotateLeft(h64, 11) * PRIME64_1;
            off++;
        }
        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;
        return String.format("%016x", h64);
    }

    private static long round(long acc, long input) {
        acc += input * PRIME64_2;
        acc = Long.rotateLeft(acc, 31);
        acc *= PRIME64_1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        acc = acc * PRIME64_1 + PRIME64_4;
        return acc;
    }

    private static long getLong(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32)
                | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48)
                | ((b[off + 7] & 0xFFL) << 56);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    // ── helpers ───────────────────────────────────────────────────────────
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2]     = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

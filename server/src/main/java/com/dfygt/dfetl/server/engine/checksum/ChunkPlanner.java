package com.dfygt.dfetl.server.engine.checksum;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec 023：分片规划器。
 *
 * <p>当前 v1 仅支持单列、数值型主键的范围分片（{@code [start, end]}）。
 * 字符串/复合主键在后续 spec 处理；视图无主键场景由 view-acceptance-policy.md D 档拒绝。
 */
public final class ChunkPlanner {

    /** 一个分片的边界，闭区间 [start, end]。{@code chunkNo} 从 0 起。 */
    public record Chunk(int chunkNo, BigInteger start, BigInteger end) {
        public String startStr() { return start == null ? null : start.toString(); }
        public String endStr()   { return end   == null ? null : end.toString();   }
    }

    /**
     * 按目标行数计算分片数。
     *
     * @param minPk          源端主键最小值
     * @param maxPk          源端主键最大值
     * @param totalRows      估算总行数（用于决定分片数）
     * @param chunkSizeRows  单分片预期行数
     * @return 分片列表（至少 1 片）
     */
    public List<Chunk> plan(BigInteger minPk, BigInteger maxPk, long totalRows, int chunkSizeRows) {
        if (minPk == null || maxPk == null) {
            // 退化为单分片（Java 端分页拉取所有行）
            return List.of(new Chunk(0, null, null));
        }
        if (chunkSizeRows <= 0) chunkSizeRows = 100_000;
        if (minPk.compareTo(maxPk) > 0) {
            // 异常输入，交换
            BigInteger t = minPk; minPk = maxPk; maxPk = t;
        }
        long estimated = Math.max(1, totalRows);
        long chunkCount = Math.max(1, (estimated + chunkSizeRows - 1) / chunkSizeRows);
        BigInteger range = maxPk.subtract(minPk).add(BigInteger.ONE);
        if (range.signum() <= 0) {
            return List.of(new Chunk(0, minPk, maxPk));
        }
        // 实际分片数不能超过 range
        BigInteger maxChunks = range;
        BigInteger desired = BigInteger.valueOf(chunkCount);
        BigInteger actual = desired.min(maxChunks);
        long n = actual.longValueExact();

        BigInteger step = range.divide(BigInteger.valueOf(n));
        if (step.signum() <= 0) step = BigInteger.ONE;

        List<Chunk> chunks = new ArrayList<>((int) Math.min(n, 100_000));
        BigInteger cursor = minPk;
        int idx = 0;
        while (cursor.compareTo(maxPk) <= 0) {
            BigInteger end = cursor.add(step).subtract(BigInteger.ONE);
            if (end.compareTo(maxPk) > 0 || idx == n - 1) end = maxPk;
            chunks.add(new Chunk(idx, cursor, end));
            cursor = end.add(BigInteger.ONE);
            idx++;
            if (idx >= n) break;
        }
        // 若因取整剩余少量，将最后一片扩到 maxPk
        if (!chunks.isEmpty()) {
            Chunk last = chunks.get(chunks.size() - 1);
            if (last.end.compareTo(maxPk) < 0) {
                chunks.set(chunks.size() - 1, new Chunk(last.chunkNo, last.start, maxPk));
            }
        }
        return chunks;
    }
}

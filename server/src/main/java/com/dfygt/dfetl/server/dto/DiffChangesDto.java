package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * spec validation-workbench-redesign Phase 2：历史比对（diff-of-diffs）响应。
 *
 * <p>对比两次校验 run 的差异行变化：
 * <ul>
 *   <li>{@code newDiffs} — 本次新增的差异（上次无、本次有）</li>
 *   <li>{@code fixedDiffs} — 已修复的差异（上次有、本次无）</li>
 *   <li>{@code unchangedCount} — 两次都有的差异数量</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiffChangesDto {

    /** 本次新增的差异 PK 列表（最多 200 条，超出截断） */
    private List<DiffChangeItem> newDiffs;

    /** 已修复的差异 PK 列表（最多 200 条，超出截断） */
    private List<DiffChangeItem> fixedDiffs;

    /** 同一 PK 仍有差异但 diffType 发生变化的列表（最多 200 条，超出截断） */
    private List<DiffTypeChangeItem> changedTypeDiffs;

    /** 两次都有的差异数量 */
    private long unchangedCount;

    /** 本次 run 总差异数 */
    private long currentTotal;

    /** 对比 run 总差异数 */
    private long compareTotal;

    /** 是否截断（newDiffs 或 fixedDiffs 超过 200 条） */
    private boolean truncated;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffChangeItem {
        private String pkValue;
        private String diffType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffTypeChangeItem {
        private String pkValue;
        private String oldDiffType;
        private String newDiffType;
    }
}

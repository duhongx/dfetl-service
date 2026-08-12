package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * spec validation-workbench-redesign · Task P1-3.1
 *
 * <p>差异分类计数响应。Validates: Requirement 2 (AC 1-3)。
 *
 * <p>语义：把 {@code etl_verify_diff} 表中 5 种 {@code diffType} 折叠为 3 类业务故事：
 * <ul>
 *   <li>{@code INSERT_MISSING_COUNT} = INSERT_MISSING + ROW_AUDIT_MISSING（目标库少了行）</li>
 *   <li>{@code UPDATE_DIFF_COUNT}    = UPDATE_DIFF + ROW_AUDIT_MISMATCH（字段值对不上）</li>
 *   <li>{@code DELETE_MISSING_COUNT} = DELETE_MISSING（目标库多了行）</li>
 * </ul>
 *
 * <p>{@code TOTAL} 等于上述三项之和（加和守恒约束 — Correctness Property 2）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRunDiffSummaryDto {

    /** 目标库少了行（INSERT_MISSING + ROW_AUDIT_MISSING）。 */
    private long insertMissingCount;

    /** 字段值对不上（UPDATE_DIFF + ROW_AUDIT_MISMATCH）。 */
    private long updateDiffCount;

    /** 目标库多了行（DELETE_MISSING）。 */
    private long deleteMissingCount;

    /** 三项加和；必须等于 insertMissingCount + updateDiffCount + deleteMissingCount。 */
    private long total;
}

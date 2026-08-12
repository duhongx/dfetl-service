package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务执行统计（供任务详情页顶部卡片使用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatsDto {

    /** 累计执行次数 */
    private long totalRuns;

    /** 成功次数 */
    private long successCount;

    /** 失败次数 */
    private long failedCount;

    /** 需要人工核对次数（SeaTunnel 终态不确定，未推进水位） */
    private long reconcileRequiredCount;

    /** 需要人工核对总数（包含已处理和未处理）。 */
    private long reconcileRequiredTotal;

    /** 未处理的人工核对数。 */
    private long reconcileRequiredUnhandled;

    /** 已处理的人工核对数。 */
    private long reconcileRequiredHandled;

    /** 成功率（0~100，保留一位小数） */
    private double successRate;
}

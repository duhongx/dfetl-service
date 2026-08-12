package com.dfygt.dfetl.server.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 首页仪表盘聚合统计
 * GET /api/dashboard/stats
 */
@Data
@Builder
public class DashboardStatsDto {

    // ── 数据源 ──────────────────────────────────────────────────────────────
    private long sourceCount;
    private long targetCount;

    // ── 同步任务 ─────────────────────────────────────────────────────────────
    private long taskTotal;
    private long taskEnabled;
    private long taskDisabled;
    private long taskFailed;

    // ── 今日执行 ─────────────────────────────────────────────────────────────
    private long execTodayTotal;
    private long execTodaySuccess;
    private long execTodayFailed;
    private long execTodayReconcileRequired;
    private long execRunning;
    private long execReconcileRequired;
    private long reconcileRequiredTotal;
    private long reconcileRequiredUnhandled;
    private long reconcileRequiredHandled;

    // ── 最近执行记录（最近 8 条）────────────────────────────────────────────
    private List<RecentExecItem> recentExecutions;

    @Data
    @Builder
    public static class RecentExecItem {
        private Long id;
        private Long taskId;
        private String taskName;
        private String status;
        private String batchNo;
        private String startedAt;
        private Long durationMs;
        private Long readRows;
        private Long writeRows;
    }
}

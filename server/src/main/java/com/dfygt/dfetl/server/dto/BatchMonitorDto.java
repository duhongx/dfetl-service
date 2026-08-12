package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量模板监控面板 — 汇总模板下所有任务的执行状态。
 */
@Data
public class BatchMonitorDto {

    /** 模板 ID */
    private Long templateId;

    /** 模板名称 */
    private String templateName;

    /** 总数据源数 */
    private int totalSources;

    /** 已创建任务数 */
    private int createdTasks;

    /** 未创建任务数 */
    private int pendingTasks;

    /** 状态汇总 */
    private StatusSummary statusSummary;

    /** 各任务详情 */
    private List<TaskStatus> tasks;

    @Data
    public static class StatusSummary {
        private int running;
        private int success;
        private int failed;
        private int disabled;
        private int reconcileRequired;
    }

    @Data
    public static class TaskStatus {
        /** 关联数据源 ID */
        private Long sourceId;

        /** 机构名称 */
        private String institutionName;

        /** 机构代码 */
        private String institutionCode;

        /** sync_task ID */
        private Long syncTaskId;

        /** 任务名称 */
        private String taskName;

        /** 任务状态：ENABLED / DISABLED / FAILED */
        private String status;

        /** 最近执行状态：SUCCESS / FAILED / RUNNING / RECONCILE_REQUIRED */
        private String lastRunStatus;

        /** 最近执行时间 */
        private LocalDateTime lastRunTime;

        /** 增量水位 */
        private String incrementalCheckpoint;

        /** 告警状态 */
        private String alertStatus;
    }
}

package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ValidationGoalSummaryDto {

    private String goal;
    private boolean configured;
    private boolean enabled;
    private String status;
    private Long lastRunId;
    private LocalDateTime lastRunAt;
    private Long sourceRows;
    private Long targetRows;
    /** 医共体源窗口总行数：合规行 + 阻断剔除行。 */
    private Long sourceRowsTotal;
    /** 医共体分流后进入 SeaTunnel / Doris 对比口径的合规源行数。 */
    private Long validSourceRows;
    /** 医共体分流阻断、未写入 Doris 的行数。 */
    private Long excludedRows;
    /** 医共体分流告警但仍写入 Doris 的行数。 */
    private Long warningRows;
    private Long diffRows;
    private String message;
    private String nextAction;
    private boolean canRerun;
    private boolean canRepair;
    private boolean canExport;

    private boolean snapshotCaptureEnabled;
    private LocalDateTime lastSnapshotCaptureAt;
    private LocalDateTime lastSnapshotDiffAt;
    private Long deletedKeys;
    private boolean applyEnabled;
    private String lastApplyStatus;
    private String lastApplyError;

    /**
     * 失败兜底错误码（spec validation-workbench-redesign · Requirement 1 AC 7）。
     *
     * <p>典型取值：{@code DIFF_COUNT_FAILED}（diffRows 统计失败：DB 抛异常或查询超时）。
     * 仅在 {@code status == FAILED} 时填充；正常成功响应保持 {@code null} 以保证向后兼容。
     */
    private String errorCode;

    /** P2-1：待修复差异数（repair_status=PENDING 的 diff 行数） */
    private Long pendingDiffRows;
}

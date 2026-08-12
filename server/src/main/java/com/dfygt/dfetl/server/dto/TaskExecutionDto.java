package com.dfygt.dfetl.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Data
public class TaskExecutionDto {

    private Long id;
    private Long taskId;
    private String taskName;
    private String batchNo;
    private String triggeredBy;
    private String workerNode;
    private String status;

    // 快照
    private String snapshotSyncType;
    private String snapshotSyncMode;
    private String snapshotViewNames;

    // 增量窗口
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowEnd;
    private Long windowStartId;
    private Long windowEndId;
    /** FULL | INCREMENT | CUSTOM_WINDOW —— 本次执行的数据范围类型 */
    private String windowType;

    // 统计
    private Long readRows;
    private Long writeRows;
    private Long failedRows;
    /** 医共体源窗口总行数：合规行 + 阻断剔除行。 */
    private Long sourceRowsTotal;
    /** 医共体分流后进入 SeaTunnel 的合规源行数。 */
    private Long validSourceRows;
    /** 医共体分流阻断、未写入 Doris 的行数。 */
    private Long excludedRows;
    /** 医共体分流告警但仍写入 Doris 的行数。 */
    private Long warningRows;
    /** SeaTunnel 累计读取尝试数（含引擎内部重试），仅用于诊断。 */
    private Long engineReadRows;
    /** SeaTunnel 累计写入尝试数（含引擎内部重试），仅用于诊断。 */
    private Long engineWriteRows;
    private Long bytesWritten;
    private BigDecimal speedMbS;
    private Integer channelCount;

    // 时间线
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;

    private String errorMsg;
    private LocalDateTime createdAt;

    // 执行引擎元数据（spec 014 / 015c）
    private String executorType;
    private String engineJobId;

    // RECONCILE_REQUIRED 运维可见性（不新增库字段，基于执行状态/errorMsg/最新校验派生）
    private Boolean reconcileRequired;
    private Boolean reconcileHandled;
    private Instant reconcileHandledAt;
    private String reconcileHandledBy;
    private String reconcileNote;
    private Instant reconcileLastProbedAt;
    private String reconcileLastProbeResult;
    private String reconcileReason;
    private String lastProbeStatus;
    private String stopResult;
    private Boolean watermarkCommitted;
    private String validationStatus;
    private String operationAdvice;
}

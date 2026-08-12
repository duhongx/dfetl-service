package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 任务执行历史（每次触发一条记录）
 */
@Entity
@Table(name = "task_execution")
@Getter
@Setter
@NoArgsConstructor
public class TaskExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 批次号，格式 yyyyMMdd_HHmmss */
    @Column(name = "batch_no", nullable = false, length = 30)
    private String batchNo;

    /** SCHEDULER | MANUAL */
    @Column(name = "triggered_by", nullable = false, length = 50)
    private String triggeredBy = "MANUAL";

    /** 执行节点（Phase 12 多节点时填写） */
    @Column(name = "worker_node", length = 100)
    private String workerNode;

    /** PENDING | RUNNING | SUCCESS | SUCCESS_WITH_DIRTY_ROWS | FAILED | TIMEOUT | CANCELLED | RECONCILE_REQUIRED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    // ── 执行快照 ─────────────────────────────────────────────────────────────

    @Column(name = "snapshot_sync_type", length = 20)
    private String snapshotSyncType;

    @Column(name = "snapshot_sync_mode", length = 20)
    private String snapshotSyncMode;

    @Column(name = "snapshot_view_names", columnDefinition = "TEXT")
    private String snapshotViewNames;

    // ── 增量窗口 ─────────────────────────────────────────────────────────────

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "window_start_id")
    private Long windowStartId;

    @Column(name = "window_end_id")
    private Long windowEndId;

    /** FULL | INCREMENT | CUSTOM_WINDOW */
    @Column(name = "window_type", length = 20)
    private String windowType;

    // ── 运行统计 ─────────────────────────────────────────────────────────────

    @Column(name = "read_rows")
    private Long readRows;

    @Column(name = "write_rows")
    private Long writeRows;

    @Column(name = "failed_rows")
    private Long failedRows;

    /** 医共体源窗口总行数：合规行 + 阻断剔除行。 */
    @Column(name = "source_rows_total")
    private Long sourceRowsTotal;

    /** 医共体分流后进入 SeaTunnel 的合规源行数。 */
    @Column(name = "valid_source_rows")
    private Long validSourceRows;

    /** 医共体分流阻断、未写入 Doris 的行数。 */
    @Column(name = "excluded_rows")
    private Long excludedRows;

    /** 医共体分流告警但仍写入 Doris 的行数。 */
    @Column(name = "warning_rows")
    private Long warningRows;

    /** 医共体执行期分流后 SeaTunnel 实际读取的合规源查询快照。 */
    @Column(name = "medical_valid_source_query", columnDefinition = "TEXT")
    private String medicalValidSourceQuery;

    /** SeaTunnel vertex 累计读取尝试数；重试会累加，不等同业务源行数。 */
    @Column(name = "engine_read_rows")
    private Long engineReadRows;

    /** SeaTunnel vertex 累计写入尝试数；重试会累加，不等同目标已提交行数。 */
    @Column(name = "engine_write_rows")
    private Long engineWriteRows;

    @Column(name = "bytes_written")
    private Long bytesWritten;

    @Column(name = "speed_mb_s", precision = 10, scale = 2)
    private BigDecimal speedMbS;

    @Column(name = "channel_count")
    private Integer channelCount;

    // ── 时间线 ───────────────────────────────────────────────────────────────

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    // ── 日志与错误 ───────────────────────────────────────────────────────────

    @Column(name = "log_path", columnDefinition = "TEXT")
    private String logPath;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 执行引擎类型，冗余记录（快照 task.executor_type）*/
    @Column(name = "executor_type", length = 30)
    private String executorType;

    /** 引擎内部 Job ID（如 SeaTunnel jobId）*/
    @Column(name = "engine_job_id", length = 100)
    private String engineJobId;

    /** RECONCILE_REQUIRED 是否已由人工核对并关闭待办；不代表执行成功。 */
    @Column(name = "reconcile_handled", nullable = false)
    private Boolean reconcileHandled = false;

    @Column(name = "reconcile_handled_at")
    private Instant reconcileHandledAt;

    @Column(name = "reconcile_handled_by", length = 100)
    private String reconcileHandledBy;

    @Column(name = "reconcile_note", columnDefinition = "TEXT")
    private String reconcileNote;

    @Column(name = "reconcile_last_probed_at")
    private Instant reconcileLastProbedAt;

    @Column(name = "reconcile_last_probe_result", columnDefinition = "TEXT")
    private String reconcileLastProbeResult;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

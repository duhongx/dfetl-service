package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Spec 062：校验运行记录。
 *
 * <p>当前阶段仅作为 run 级锚点，保留 legacyExecId 兼容旧链路。
 */
@Entity
@Table(name = "validation_run",
        indexes = {
                @Index(name = "idx_validation_run_task_exec", columnList = "task_id, legacy_exec_id", unique = true),
                @Index(name = "idx_validation_run_task", columnList = "task_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ValidationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "legacy_exec_id", nullable = false)
    private Long legacyExecId;

    @Column(name = "mode", nullable = false, length = 32)
    private String mode;

    @Column(name = "scope", nullable = false, length = 16)
    private String scope;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 本次校验实际执行的源端 SQL（多表以换行分隔） */
    @Column(name = "source_sql", columnDefinition = "TEXT")
    private String sourceSql;

    /** 本次校验实际执行的目标端 SQL（多表以换行分隔） */
    @Column(name = "target_sql", columnDefinition = "TEXT")
    private String targetSql;

    /** 源端 WHERE 条件部分 */
    @Column(name = "source_where", columnDefinition = "TEXT")
    private String sourceWhere;

    /** 目标端 WHERE 条件部分 */
    @Column(name = "target_where", columnDefinition = "TEXT")
    private String targetWhere;

    /**
     * spec validation-workbench-redesign · Task P1-5.1
     * Validates: Requirement 4 (AC 2) + Requirement 5 (AC 1)
     *
     * <p>校验触发来源（5+1 种枚举）：
     * <ul>
     *   <li>{@code AUTO} — AutoValidationTrigger（同步成功后自动）</li>
     *   <li>{@code AUTO_COUNT} — L1 ROW_COUNT 哨兵（SeaTunnelExecutorStrategy）</li>
     *   <li>{@code MANUAL} — 用户在工作台/列表页手动触发</li>
     *   <li>{@code MANUAL_FULL} — 用户手动触发的全量校验（trigger-full 强制 CHECKSUM）</li>
     *   <li>{@code DRIFT} — DriftWatchService（driftCron 定时漂移检测）</li>
     *   <li>{@code GATE} — ValidationGateService（门控校验）</li>
     *   <li>{@code MANUAL_REPAIR_RECHECK} — Repair 闭环 B 异步 ROW_COUNT 复查（P1-12.1）</li>
     * </ul>
     *
     * <p>NULL = 本需求落地前的历史数据，UI 上展示为灰色「未知触发类型」徽章。
     */
    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    // ══════════════════════════════════════════════════════════════════════════
    //   spec validation-table-consolidation · Step 3
    //   合并自 validation_task 的字段（Requirement 10）
    // ══════════════════════════════════════════════════════════════════════════

    /** 校验状态：PENDING / RUNNING / CONSISTENT / DIFF / ERROR */
    @Column(name = "status", length = 20)
    private String status = "PENDING";

    /** 校验方式：ROW_COUNT / CHECKSUM / ROW_COUNT_CHECKSUM */
    @Column(name = "method", length = 20)
    private String method;

    /** 差异行数 */
    @Column(name = "diff_rows")
    private Long diffRows;

    /** 校验耗时（毫秒） */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 源端行数 */
    @Column(name = "source_rows")
    private Long sourceRows;

    /** 目标端行数 */
    @Column(name = "target_rows")
    private Long targetRows;

    /** 错误信息（截断至 2000 字符） */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /**
     * spec 069：非阻塞口径警告（不同于 error_msg 的「执行错误」语义）。
     * <p>多机构共表目标端补 {@code _etl_job_id} 列后存在 NULL 历史行时，按
     * {@code _etl_job_id = {taskId}} 过滤会漏掉这些行，可能假报「目标少行」。
     * 校验链路检测到目标端 {@code _etl_job_id IS NULL} 行数 &gt; 0 时写入本字段，
     * 引导运维清空重采。CONSISTENT/DIFF 终态不清空（与 error_msg 不同）。
     */
    @Column(name = "scope_warning", columnDefinition = "TEXT")
    private String scopeWarning;

    /** 校验任务名称 */
    @Column(name = "name", length = 100)
    private String name;

    /** 触发本次校验的执行批次 ID */
    @Column(name = "execution_id")
    private Long executionId;

    /** 校验表列表（逗号分隔）— 列名用 tables_text 避免 SQL 保留字冲突 */
    @Column(name = "tables_text", columnDefinition = "TEXT")
    private String tablesText;

    /** 最后执行时间 */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** 窗口类型：FULL / INCREMENT / ID_RANGE / TIME_FIELD */
    @Column(name = "window_type", length = 20)
    private String windowType;

    /** ID_RANGE 窗口起点 */
    @Column(name = "window_start_id")
    private Long windowStartId;

    /** ID_RANGE 窗口终点 */
    @Column(name = "window_end_id")
    private Long windowEndId;
}

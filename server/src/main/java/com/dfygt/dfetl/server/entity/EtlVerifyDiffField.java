package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Spec 056：异步预计算的字段级差异。
 *
 * <p>由 {@code DiffFieldPrecomputeService} 在 Checksum 完成后异步生成，作为
 * spec 055 实时回读的缓存层，支持秒开 + 批量导出。
 *
 * <p><b>隐私约束</b>：
 * <ul>
 *   <li>{@code srcValueDisplay} / {@code tgtValueDisplay} 已脱敏 + 已截断</li>
 *   <li>{@code srcValueHash} / {@code tgtValueHash} 是归一化值的 hash，不可还原</li>
 *   <li>命中 mask 模式的列，display 强制 {@code "***"}，hash 为 {@code "***"} 的 hash（确定性）</li>
 * </ul>
 */
@Entity
@Table(name = "etl_verify_diff_field",
        indexes = {
                @Index(name = "idx_verify_diff_field_diff", columnList = "diff_id"),
                @Index(name = "idx_verify_diff_field_task_exec", columnList = "task_id, exec_id"),
                @Index(name = "idx_verify_diff_field_run", columnList = "validation_run_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class EtlVerifyDiffField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 etl_verify_diff.id（PK 级差异行） */
    @Column(name = "diff_id", nullable = false)
    private Long diffId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "exec_id", nullable = false)
    private Long execId;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    /** 源端列名 */
    @Column(name = "column_name", nullable = false, length = 128)
    private String columnName;

    /** 目标端列名（spec 055 Phase A：默认 column.toLowerCase()） */
    @Column(name = "target_column", length = 128)
    private String targetColumn;

    /** VALUE_DIFF | MISSING_IN_TARGET | EXTRA_IN_TARGET | EQUAL */
    @Column(name = "diff_kind", nullable = false, length = 32)
    private String diffKind;

    @Column(name = "src_value_display", columnDefinition = "TEXT")
    private String srcValueDisplay;

    @Column(name = "tgt_value_display", columnDefinition = "TEXT")
    private String tgtValueDisplay;

    @Column(name = "src_value_hash", length = 64)
    private String srcValueHash;

    @Column(name = "tgt_value_hash", length = 64)
    private String tgtValueHash;

    @Column(name = "masked", nullable = false)
    private boolean masked = false;

    @Column(name = "truncated", nullable = false)
    private boolean truncated = false;

    @Column(name = "normalized_differ", nullable = false)
    private boolean normalizedDiffer = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;
}

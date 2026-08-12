package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Spec 023：Checksum 行级差异。spec 024 Repair 引擎消费此表。
 */
@Entity
@Table(name = "etl_verify_diff",
        indexes = {
                @Index(name = "idx_verify_diff_task_exec", columnList = "task_id, exec_id"),
                @Index(name = "idx_verify_diff_run", columnList = "validation_run_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class EtlVerifyDiff {

    /** 差异类型枚举（与 DB 字符串一致）。 */
    public static final String TYPE_INSERT_MISSING = "INSERT_MISSING"; // 源有目标无
    public static final String TYPE_UPDATE_DIFF    = "UPDATE_DIFF";    // 双方都有但 hash 不同
    public static final String TYPE_DELETE_MISSING = "DELETE_MISSING"; // 目标有源无（疑似源端已删）

    /** Spec 057：行级核查（spec 050）写入同一张表时使用的差异类型。 */
    public static final String TYPE_ROW_AUDIT_MISSING  = "ROW_AUDIT_MISSING";  // 行级核查：目标缺失
    public static final String TYPE_ROW_AUDIT_MISMATCH = "ROW_AUDIT_MISMATCH"; // 行级核查：目标存在但 hash 不一致

    public static final String REPAIR_PENDING = "PENDING";
    public static final String REPAIR_DONE    = "DONE";
    public static final String REPAIR_FAILED  = "FAILED";
    public static final String REPAIR_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "exec_id", nullable = false)
    private Long execId;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "chunk_no")
    private Integer chunkNo;

    @Column(name = "pk_value", nullable = false, length = 512)
    private String pkValue;

    @Column(name = "diff_type", nullable = false, length = 32)
    private String diffType;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(name = "target_hash", length = 64)
    private String targetHash;

    @Column(name = "repair_status", nullable = false, length = 32)
    private String repairStatus = REPAIR_PENDING;

    /**
     * spec validation-workbench-redesign · Task P1-6.1
     * Validates: Requirement 6 (AC 1) + Property 7
     *
     * <p>Repair 来源：AUTO=自动修复 / MANUAL=用户主动 / NULL=未修复或本需求落地前历史数据。
     */
    @Column(name = "repair_source", length = 16)
    private String repairSource;

    @Column(name = "repaired_at")
    private OffsetDateTime repairedAt;

    @Column(name = "repair_label", length = 128)
    private String repairLabel;

    @Column(name = "detected_at", nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime detectedAt;
}

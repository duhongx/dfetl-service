package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 任务校验策略配置（task_validation_config）
 * 每个同步任务最多一条，method 决定任务级校验强度；null 表示继承全局策略。
 *
     * method 取值：
     *   ROW_COUNT          — 仅行数比对（最轻量）
     *   CHECKSUM           — 分片 Checksum（中等）
     *   ROW_COUNT_CHECKSUM — 行数 + Checksum（严格）
 */
@Entity
@Table(name = "task_validation_config")
@Getter
@Setter
@NoArgsConstructor
public class TaskValidationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的同步任务，唯一约束保证每任务只有一条配置 */
    @Column(name = "task_id", nullable = false, unique = true)
    private Long taskId;

    /** 是否启用校验 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 校验方式：null=继承全局；ROW_COUNT | CHECKSUM | ROW_COUNT_CHECKSUM */
    @Column(length = 30)
    private String method;

    /** Checksum 算法：XXHASH64 | MD5 | SHA256 | CRC32 */
    @Column(name = "checksum_algo", length = 20)
    private String checksumAlgo = "XXHASH64";

    /** 预留采样率字段；当前 SAMPLE 方法不开放 */
    @Column(name = "sample_rate", precision = 5, scale = 2)
    private BigDecimal sampleRate = BigDecimal.TEN;

    /** 允许的差异绝对行数（0=严格一致） */
    @Column(name = "tolerance_rows")
    private Long toleranceRows = 0L;

    /** 允许的差异百分比（0.01 = 1%，0=严格一致） */
    @Column(name = "tolerance_pct", precision = 8, scale = 6)
    private BigDecimal tolerancePct = BigDecimal.ZERO;

    /** 同步完成后自动触发校验；null 表示继承全局 autoEnabled */
    @Column(name = "auto_trigger")
    private Boolean autoTrigger;

    /** 校验失败是否阻断下次同步；null 表示继承全局 failBlock */
    @Column(name = "block_on_fail")
    private Boolean blockOnFail;
    /** 校验配置模板：LIGHT | STANDARD | STRICT | CUSTOM */
    @Column(name = "validation_template", nullable = false, length = 20)
    private String validationTemplate = "STANDARD";

    /** 失败动作：WARN | FAIL */
    @Column(name = "failure_action", nullable = false, length = 20)
    private String failureAction = "WARN";

    /** Checksum Java 端最大校验行数，超出则降级为 ROW_COUNT */
    @Column(name = "max_check_rows", nullable = false)
    private Long maxCheckRows = 1_000_000L;
    /** 校验表范围（逗号分隔，null = 任务中所有表） */
    @Column(name = "target_tables", columnDefinition = "TEXT")
    private String targetTables;

    /** spec 030：drift-watch 周期 cron（Quartz 6/5 字段均可）；null/空 = 不调度 */
    @Column(name = "drift_cron", length = 32)
    private String driftCron;

    /** Spec 048：Checksum 范围：FULL（全表）| WINDOW（仅 incrementalField 窗口内）。默认 FULL。 */
    @Column(name = "checksum_scope", nullable = false, length = 10)
    private String checksumScope = "FULL";

    /** spec 033：DIFF 自动重试仍失败后是否自动触发 Repair。默认关闭。 */
    @Column(name = "auto_repair", nullable = false)
    private Boolean autoRepair = false;

    /** spec 033：自动 Repair 单次最大行数（保险阀，超过则只 log 不修复）。 */
    @Column(name = "auto_repair_max_rows", nullable = false)
    private Long autoRepairMaxRows = 1000L;

    /** 校验回看窗口（小时），null 表示继承全局设置。0 = 只验本次增量窗口。 */
    @Column(name = "validation_lookback_hours")
    private Integer validationLookbackHours;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}

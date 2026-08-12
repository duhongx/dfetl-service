package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 批量任务模板 — 区域医共体场景：多个医疗机构的相同视图同步到同一张 Doris 目标表。
 */
@Entity
@Table(name = "batch_task_template", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class BatchTaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    // ── 目标配置 ────────────────────────────────────────────────────────────────

    @Column(name = "target_datasource_id", nullable = false)
    private Long targetDatasourceId;

    @Column(name = "target_table", nullable = false, length = 200)
    private String targetTable;

    // ── 源端配置（模板级，各机构共用） ──────────────────────────────────────────

    @Column(name = "view_name", nullable = false, length = 200)
    private String viewName;

    @Column(name = "source_schema", length = 100)
    private String sourceSchema;

    // ── 同步配置 ────────────────────────────────────────────────────────────────

    /** FULL | INCREMENTAL */
    @Column(name = "data_scope", length = 20)
    private String dataScope = "INCREMENTAL";

    /** TIME_FIELD | ID_RANGE */
    @Column(name = "increment_mode", length = 20)
    private String incrementMode = "TIME_FIELD";

    @Column(name = "incremental_field", length = 100)
    private String incrementalField;

    /** TRUNCATE | APPEND | UPSERT */
    @Column(name = "sync_mode", length = 20)
    private String syncMode = "UPSERT";

    /** JSON 数组：UPSERT 主键列 */
    @Column(name = "upsert_keys", columnDefinition = "TEXT")
    private String upsertKeys;

    @Column(nullable = false)
    private Integer parallelism = 1;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    // ── 校验配置 ────────────────────────────────────────────────────────────────

    /** ROW_COUNT | CHECKSUM | ROW_COUNT_CHECKSUM */
    @Column(name = "validation_method", length = 20)
    private String validationMethod = "CHECKSUM";

    @Column(name = "validation_drift_cron", length = 100)
    private String validationDriftCron;

    @Column(name = "validation_lookback_hours")
    private Integer validationLookbackHours = 24;

    @Column(name = "auto_trigger")
    private Boolean autoTrigger = true;

    // ── Doris 配置 ──────────────────────────────────────────────────────────────

    /** DUPLICATE_KEY | UNIQUE_KEY | AGGREGATE_KEY */
    @Column(name = "doris_table_model", length = 20)
    private String dorisTableModel = "UNIQUE_KEY";

    @Column(name = "enable_doris_merge")
    private Boolean enableDorisMerge = false;

    @Column(name = "soft_delete_field", length = 100)
    private String softDeleteField;

    @Column(name = "delete_sign_value", length = 20)
    private String deleteSignValue = "1";

    @Column(name = "sequence_col", length = 100)
    private String sequenceCol;

    // ── 元数据 ──────────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

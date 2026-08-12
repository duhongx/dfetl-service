package com.dfygt.dfetl.server.entity;

import com.dfygt.dfetl.server.common.StringListConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务
 */
@Entity
@Table(name = "sync_task")
@Getter
@Setter
@NoArgsConstructor
public class SyncTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** 关联机构 ID（df_etl.institution.id），可空 */
    @Column(name = "institution_id")
    private Long institutionId;

    /** FULL | INCREMENTAL */
    @Column(name = "sync_type", nullable = false, length = 20)
    private String syncType;

    @Column(name = "source_datasource_id")
    private Long sourceDataSourceId;

    @Column(name = "target_datasource_id")
    private Long targetDataSourceId;

    /** 覆盖数据源默认 schema（任务级别） */
    @Column(name = "source_schema", length = 100)
    private String sourceSchema;

    /** TABLE_VIEW | CUSTOM_SQL */
    @Column(name = "source_mode", nullable = false, length = 20)
    private String sourceMode = "TABLE_VIEW";

    /** 自定义 SQL 模式下的只读 SELECT 语句 */
    @Column(name = "custom_sql", columnDefinition = "TEXT")
    private String customSql;

    /** 自定义 SQL 的逻辑源名，用于目标表映射、任务命名与执行日志 */
    @Column(name = "custom_sql_name", length = 100)
    private String customSqlName;

    /** 视图/表名列表，JSON 字符串存储 */
    @Convert(converter = StringListConverter.class)
    @Column(name = "view_names", columnDefinition = "TEXT")
    private List<String> viewNames = new ArrayList<>();

    /** TRUNCATE | APPEND | UPSERT */
    @Column(name = "sync_mode", nullable = false, length = 20)
    private String syncMode = "TRUNCATE";

    /** FULL | INCREMENTAL */
    @Column(name = "data_scope", nullable = false, length = 20)
    private String dataScope = "FULL";

    /** 增量字段，如 updated_at */
    @Column(name = "incremental_field", length = 200)
    private String incrementalField;

    /** UPSERT 主键列（JSON 数组调用者透明，兼容遗留 CSV 数据） */
    @Convert(converter = com.dfygt.dfetl.server.common.UpsertKeysConverter.class)
    @Column(name = "upsert_keys", columnDefinition = "TEXT")
    private List<String> upsertKeys = new ArrayList<>();

    /** 任务级 JDBC fetch_size 覆盖值；NULL/0 表示继承全局 etl.fetch_size */
    @Column(name = "batch_size")
    private Integer batchSize;

    /** 并发 channel 数 */
    @Column(name = "parallelism", nullable = false)
    private Integer parallelism = 4;

    /** 计划分片数，NULL=自动计算 */
    @Column(name = "shard_count")
    private Integer shardCount;

    /** PRIMARY_KEY_RANGE */
    @Column(name = "shard_strategy", nullable = false, length = 50)
    private String shardStrategy = "PRIMARY_KEY_RANGE";

    /** 速率上限 MB/s，0=不限 */
    @Column(name = "rate_limit", nullable = false)
    private Integer rateLimit = 0;

    @Column(length = 100)
    private String schedule;

    @Column(name = "schedule_label", length = 100)
    private String scheduleLabel;

    /** spec 053 - Quartz Cron 表达式（由 scheduleConfig 后端生成；MANUAL 模式为 null） */
    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    /** spec 053 - 可视化调度配置 JSON（mode + 各模式参数） */
    @Column(name = "schedule_config", columnDefinition = "TEXT")
    private String scheduleConfig;

    /** spec 053 - 中文描述，如 "每天 02:30 执行" */
    @Column(name = "schedule_description", length = 255)
    private String scheduleDescription;

    /** spec 053 - 调度时区，默认 Asia/Shanghai */
    @Column(name = "schedule_timezone", length = 64)
    private String scheduleTimezone;

    /** ENABLED | DISABLED | FAILED */
    @Column(nullable = false, length = 20)
    private String status = "DISABLED";

    /** DRAFT | PUBLISHED | OFFLINE */
    @Column(name = "version_status", length = 20)
    private String versionStatus = "DRAFT";

    @Column(length = 20)
    private String version = "V1";

    @Column(name = "last_run_time")
    private LocalDateTime lastRunTime;

    /** SUCCESS | SUCCESS_WITH_DIRTY_ROWS | FAILED | RUNNING | RECONCILE_REQUIRED */
    @Column(name = "last_run_status", length = 20)
    private String lastRunStatus;

    /** 增量模式：上次窗口结束时间 */
    @Column(name = "incremental_checkpoint")
    private Instant incrementalCheckpoint;

    // ── 增量配置 ────────────────────────────────────────────────────────────────

    /** TIME_FIELD | ID_RANGE */
    @Column(name = "increment_mode", length = 20)
    private String incrementMode;

    /** CURRENT_TIME | DELAY_MINUTES */
    @Column(name = "upper_bound_strategy", nullable = false, length = 20)
    private String upperBoundStrategy = "CURRENT_TIME";

    /** 上界延迟分钟数（upperBoundStrategy=DELAY_MINUTES 时有效）*/
    @Column(name = "upper_bound_delay_minutes", nullable = false)
    private Integer upperBoundDelayMinutes = 5;

    /** 首次增量运行起点（字符串：ISO8601 时间 或 数字 ID）*/
    @Column(name = "initial_watermark", length = 100)
    private String initialWatermark;

    /** 是否启用「首次跑全量，后续转增量」一键模式（仅 dataScope=INCREMENTAL 生效）*/
    @Column(name = "initial_full_sync", nullable = false)
    private Boolean initialFullSync = false;

    /** 首次全量是否已完成（首次成功后由后端置 true，下次起按增量执行）*/
    @Column(name = "initial_full_sync_done", nullable = false)
    private Boolean initialFullSyncDone = false;

    // ── Writer & Doris 模型 ─────────────────────────────────────────────────

    /** STREAM_LOAD | BROKER_LOAD */
    @Column(name = "writer_type", nullable = false, length = 20)
    private String writerType = "STREAM_LOAD";

    /** DUPLICATE_KEY | UNIQUE_KEY | AGGREGATE_KEY */
    @Column(name = "doris_table_model", length = 20)
    private String dorisTableModel;

    // ── 过滤条件 & 自定义窗口 ────────────────────────────────────────────────

    /** 静态 WHERE 条件（不含 WHERE 关键字），用于长期固定的业务过滤 */
    @Column(name = "static_filter", columnDefinition = "TEXT")
    private String staticFilter;

    /** per-table 过滤条件 JSON，格式：{"表名":"where条件"} */
    @Column(name = "filter_condition_map", columnDefinition = "TEXT")
    private String filterConditionMap;

    /** per-table 目标表名映射 JSON，格式：{"源表名":"目标表名"} */
    @Column(name = "target_table_map", columnDefinition = "TEXT")
    private String targetTableMap;

    /** 创建/编辑时的业务问答快照 JSON */
    @Column(name = "data_characteristics", columnDefinition = "TEXT")
    private String dataCharacteristics;

    /** 自定义窗口起点（dataScope=CUSTOM_WINDOW 时生效）*/
    @Column(name = "custom_window_start")
    private Instant customWindowStart;

    /** 自定义窗口终点（dataScope=CUSTOM_WINDOW 时生效）*/
    @Column(name = "custom_window_end")
    private Instant customWindowEnd;

    /** NORMAL | WARNING | ERROR */
    @Column(name = "alert_status", length = 20)
    private String alertStatus = "NORMAL";

    /** 执行引擎类型：当前仅支持 SEATUNNEL_CLUSTER */
    @Column(name = "executor_type", length = 30)
    private String executorType = "SEATUNNEL_CLUSTER";

    /** Reader splitPk 分片字段，用于并行读取加速（TABLE 模式且数据库支持时生效）*/
    @Column(name = "split_pk", length = 200)
    private String splitPk;

    /** 源对象类型：TABLE（默认）| VIEW | MATERIALIZED_VIEW */
    @Column(name = "source_object_type", nullable = false, length = 30)
    private String sourceObjectType = "TABLE";

    /** 软删除标记字段（如 is_deleted），用于业务层逻辑删除同步；为空表示禁用 */
    @Column(name = "soft_delete_field", length = 100)
    private String softDeleteField;

    /** 软删除字段的"未删除"值（默认 '0'），仅作元信息透传，配合下游业务过滤 */
    @Column(name = "soft_delete_active_value", length = 50)
    private String softDeleteActiveValue = "0";

    /** 是否启用 Doris MERGE 模式同步物理删除（需 syncMode=UPSERT + softDeleteField + UNIQUE_KEY 表）*/
    @Column(name = "enable_doris_merge", nullable = false)
    private Boolean enableDorisMerge = false;

    /** 软删除字段中表示"已删除"的值（默认 '1'），仅在 enableDorisMerge=true 时生效 */
    @Column(name = "delete_sign_value", length = 50)
    private String deleteSignValue = "1";

    /** 顺序列 function_column.sequence_col（UNIQUE_KEY 表上同主键冲突时按该列取最新）*/
    @Column(name = "sequence_col", length = 100)
    private String sequenceCol;

    /** 部分列更新开关，仅在 syncMode=UPSERT 时生效 */
    @Column(name = "partial_columns", nullable = false)
    private Boolean partialColumns = false;

    /** TIME_FIELD 增量回看窗口（秒），windowStart = checkpoint - lookbackSeconds，0=不回看 */
    @Column(name = "lookback_seconds", nullable = false)
    private Integer lookbackSeconds = 0;

    /** spec 020：启用快照差集删除检测 */
    @Column(name = "enable_snapshot_delete", nullable = false)
    private Boolean enableSnapshotDelete = false;

    // ── spec 020.2: 快照对账自动调度 ─────────────────────────────────────────

    /** 任务每次执行成功后自动 capture 主键快照（前置 enableSnapshotDelete=true）*/
    @Column(name = "snapshot_auto_capture", nullable = false)
    private Boolean snapshotAutoCapture = false;

    /** 自动 detect 调度 cron（Quartz 兼容；为空则不自动 detect）*/
    @Column(name = "snapshot_auto_detect_cron", length = 64)
    private String snapshotAutoDetectCron;

    /** detect 后差集比例 ≤ 阈值时自动 apply（前置 snapshotAutoCapture=true）*/
    @Column(name = "snapshot_auto_apply", nullable = false)
    private Boolean snapshotAutoApply = false;

    /** 熔断阈值：deletedKeys / prevSnapshotSize > 该比例 → 不自动 apply，转人工。范围 (0,1]，默认 0.05 */
    @Column(name = "snapshot_delete_max_ratio", nullable = false, precision = 5, scale = 4)
    private java.math.BigDecimal snapshotDeleteMaxRatio = new java.math.BigDecimal("0.0500");

    /** spec 036：snapshot capture 频控，上次 capture 距今不足该分钟数则跳过。默认 0【=每次都 capture】*/
    @Column(name = "snapshot_capture_interval_minutes", nullable = false)
    private Integer snapshotCaptureIntervalMinutes = 0;

    /** 任务级自动重试最大次数，NULL=使用全局默认值(0=不重试) */
    @Column(name = "retry_max_attempts")
    private Integer retryMaxAttempts;

    /** 任务级自动重试间隔秒数，NULL=使用全局默认值(30) */
    @Column(name = "retry_interval_seconds")
    private Integer retryIntervalSeconds;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

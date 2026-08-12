package com.dfygt.dfetl.server.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class SyncTaskDto {

    private Long id;

    /** 创建多表任务时，本次请求实际拆分创建出的所有任务 ID；单表时仅包含当前 id。 */
    private List<Long> createdTaskIds;

    /** 创建时原子保存到 task_view_config 的字段映射快照。 */
    private List<TaskViewConfigDto> viewConfigs;

    /** 创建时原子保存的消息发布配置。 */
    private MessagePublishConfigDto messagePublishConfig;

    /** 创建任务时一并保存的任务级校验配置，避免发布后再二次 PUT 产生竞态。 */
    private TaskValidationConfigDto validationConfig;

    // name 由后端自动生成（若前端未填写），此处不强制校验
    private String name;

    /** 关联机构 ID（df_etl.institution.id），可空 */
    private Long institutionId;

    // 扩展同步策略字段
    /** TABLE_VIEW | CUSTOM_SQL */
    private String sourceMode = "TABLE_VIEW";

    private String sourceSchema;

    /** 自定义 SQL 模式下的只读 SELECT 语句 */
    private String customSql;

    /** 自定义 SQL 的逻辑源名，用于目标表映射、任务命名与执行日志 */
    private String customSqlName;

    private String syncMode;
    private String dataScope;
    private String incrementalField;
    private List<String> upsertKeys;
    private Integer batchSize;
    private Integer parallelism = 4;
    private Integer shardCount;
    private String shardStrategy = "PRIMARY_KEY_RANGE";
    private Integer rateLimit = 0;

    /** FULL | INCREMENTAL */
    private String syncType;

    /** 任务最终快照必须显式包含同步类型。 */
    @JsonIgnore
    @AssertTrue(message = "syncType 不能为空")
    public boolean isSyncTypeResolvable() {
        return syncType != null && !syncType.isBlank();
    }

    private Long sourceDataSourceId;

    private Long targetDataSourceId;

    private List<String> viewNames;

    private String schedule;

    private String scheduleLabel;

    /** spec 053 - Quartz cron（后端按 scheduleConfig 重新生成；MANUAL 模式为 null） */
    private String cronExpression;

    /** spec 053 - 可视化调度配置 JSON 字符串 */
    private String scheduleConfig;

    /** spec 053 - 中文调度描述 */
    private String scheduleDescription;

    /** spec 053 - 调度时区，默认 Asia/Shanghai */
    private String scheduleTimezone;

    /** ENABLED | DISABLED | FAILED */
    private String status = "DISABLED";

    /** DRAFT | TESTED | PUBLISHED */
    private String versionStatus = "DRAFT";

    private String version = "V1";

    private LocalDateTime lastRunTime;

    private String lastRunStatus;

    /** NORMAL | ERROR */
    private String alertStatus = "NORMAL";

    // ── 增量配置 ────────────────────────────────────────────────────────────

    /** TIME_FIELD | ID_RANGE */
    private String incrementMode;

    /** CURRENT_TIME | DELAY_MINUTES */
    private String upperBoundStrategy = "CURRENT_TIME";

    private Integer upperBoundDelayMinutes = 5;

    /** 首次增量运行起点 */
    private String initialWatermark;

    /** 一键全量→增量：首次跑全量，成功后自动转增量 */
    private Boolean initialFullSync;

    /** 一键全量→增量：首次全量是否已完成（只读展示）*/
    private Boolean initialFullSyncDone;

    /** STREAM_LOAD | BROKER_LOAD */
    private String writerType = "STREAM_LOAD";

    /** DUPLICATE_KEY | UNIQUE_KEY | AGGREGATE_KEY */
    private String dorisTableModel;

    /** 静态过滤条件（不含 WHERE 关键字）*/
    private String staticFilter;

    /** per-table 过滤条件 JSON，格式：{"表名":"where条件"} */
    private String filterConditionMap;

    /** per-table 目标表名映射 JSON，格式：{"源表名":"目标表名"} */
    private String targetTableMap;

    /** 创建/编辑时的业务问答快照 JSON */
    private String dataCharacteristics;

    private Instant customWindowStart;
    private Instant customWindowEnd;

    /** 增量同步已完成水位（展示用）*/
    private Instant incrementalCheckpoint;

    /** 执行引擎：当前仅支持 SEATUNNEL_CLUSTER */
    private String executorType = "SEATUNNEL_CLUSTER";

    /** Reader splitPk 分片字段 */
    private String splitPk;

    /** TABLE | VIEW | MATERIALIZED_VIEW */
    private String sourceObjectType = "TABLE";

    /** 软删除字段（如 is_deleted），为空禁用 */
    private String softDeleteField;

    /** 软删除字段"未删除"值，默认 '0' */
    private String softDeleteActiveValue = "0";

    /** 是否启用 Doris MERGE 模式同步物理删除 */
    private Boolean enableDorisMerge = false;

    /** 软删除字段"已删除"值，默认 '1' */
    private String deleteSignValue = "1";

    /** TIME_FIELD 增量回看窗口（秒），0=不回看 */
    private Integer lookbackSeconds = 0;

    /** spec 020：启用快照差集删除检测 */
    private Boolean enableSnapshotDelete = false;

    // ── spec 020.2: 快照对账自动调度 ─────────────────────────────────────────

    /** 任务每次执行成功后自动 capture 主键快照（前置 enableSnapshotDelete=true）*/
    private Boolean snapshotAutoCapture = false;

    /** 自动 detect 调度 cron（Quartz 兼容；为空则不自动 detect）*/
    private String snapshotAutoDetectCron;

    /** detect 后差集比例 ≤ 阈值时自动 apply（前置 snapshotAutoCapture=true）*/
    private Boolean snapshotAutoApply = false;

    /** 熔断阈值：deletedKeys / prevSnapshotSize > 该比例 → 不自动 apply。范围 (0,1]，默认 0.05 */
    private java.math.BigDecimal snapshotDeleteMaxRatio = new java.math.BigDecimal("0.0500");

    /** spec 036：snapshot capture 频控，紧跟同步任务后不超过该间隔不重复 capture。默认 0=不限制 */
    private Integer snapshotCaptureIntervalMinutes = 0;

    /** 顺序列（UNIQUE_KEY 表冲突时取最新的依据列）*/
    private String sequenceCol;

    /** 部分列更新开关 */
    private Boolean partialColumns = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

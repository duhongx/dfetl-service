-- =============================================================================
-- dfetl_meta 数据库初始化 DDL
-- 数据库：PostgreSQL 14+
-- 字符集：UTF-8
-- 时区：Asia/Shanghai（应用层统一转换，DB 存 TIMESTAMPTZ）
--
-- 使用说明：
--   初始化：psql -U dfetl -d dfetl_meta -f init.sql
--   所有环境：ddl-auto: none，手动执行本文件及显式 migration_*.sql
--
-- 不使用 Flyway，变更历史通过 git log 追溯。
--
-- 表结构总览（按实现阶段）：
--   Phase 7:  source_datasource, target_datasource, sync_task,
--             task_view_config
--   Phase 8:  task_execution, task_chunk, dirty_record, audit_log
--   Phase 9+: system_setting, webhook_endpoint, alert_rule,
--             notify_record, validation_task
-- =============================================================================


-- =============================================================================
-- ■ Phase 7  核心实体
-- =============================================================================

-- 机构是源数据源、任务和机构数据集路由的共同上游，必须先于这些表建立。
CREATE SCHEMA IF NOT EXISTS df_etl AUTHORIZATION df_etl;

CREATE TABLE IF NOT EXISTS df_etl.institution (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(50)  NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    short_name   VARCHAR(50),
    type         VARCHAR(20),
    level        VARCHAR(20),
    region_code  VARCHAR(20),
    parent_id    BIGINT REFERENCES df_etl.institution(id),
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_institution_parent
    ON df_etl.institution(parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_institution_enabled
    ON df_etl.institution(enabled) WHERE enabled = true;

COMMENT ON TABLE df_etl.institution IS '机构主表 — 医共体场景下的医疗机构一等公民';

-- -----------------------------------------------------------------------------
-- 0. 系统用户（app_user）
--    JWT 登录鉴权使用。当前阶段仅保留 admin 单用户。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(100)    NOT NULL UNIQUE,
    password_hash   TEXT            NOT NULL,
    role            VARCHAR(30)     NOT NULL DEFAULT 'ADMIN',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    refresh_token_version INTEGER   NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_user IS '系统登录用户（JWT 鉴权）';
COMMENT ON COLUMN app_user.refresh_token_version IS 'refresh token 版本号；logout 后自增，旧 refresh token 立即失效';

INSERT INTO app_user (username, password_hash, role, enabled)
VALUES ('admin', '$2a$10$P7t5Qt99w8HWIw2OfGhI9.5eP6NhWwEi7/saMqc0RtgJYTW8KSdJ2', 'ADMIN', TRUE)
ON CONFLICT (username) DO NOTHING;

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS refresh_token_version INTEGER NOT NULL DEFAULT 0;

-- -----------------------------------------------------------------------------
-- 2. 源数据源（source_datasource）
--    业务数据库连接信息（MySQL / PostgreSQL / Oracle / SQL Server）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS source_datasource (
    id                  BIGSERIAL       PRIMARY KEY,
    institution_id      BIGINT          REFERENCES df_etl.institution(id),

    -- 基本信息
    name                VARCHAR(100)    NOT NULL UNIQUE,        -- 数据源名称，全局唯一
    type                VARCHAR(20)     NOT NULL,               -- MYSQL | POSTGRESQL | ORACLE | SQLSERVER | DORIS
    host                VARCHAR(255)    NOT NULL,               -- 主机名或 IP
    port                INTEGER         NOT NULL,               -- 端口
    db_name             VARCHAR(100)    NOT NULL,               -- 数据库名
    schema_name         VARCHAR(100),                           -- 默认 schema（PostgreSQL/Oracle 用）

    -- 认证
    username            VARCHAR(100)    NOT NULL,
    password_enc        TEXT            NOT NULL,               -- AES-256/CBC 加密，格式：Base64(IV):Base64(密文)

    -- 连接参数
    readonly            BOOLEAN         NOT NULL DEFAULT TRUE,  -- 是否只读连接
    query_timeout       INTEGER         NOT NULL DEFAULT 60,    -- 查询超时（秒）
    read_concurrency    INTEGER         NOT NULL DEFAULT 4,     -- 读并发数（对应 dfetl channel 数）
    pool_size           INTEGER         NOT NULL DEFAULT 10,    -- 连接池大小
    ssl                 BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 元信息
    description         TEXT,
    status              VARCHAR(20)     NOT NULL DEFAULT 'NORMAL', -- NORMAL | ERROR | TESTING

    -- 审计
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  source_datasource                  IS '源数据源配置（医疗机构业务库）';
COMMENT ON COLUMN source_datasource.type             IS 'MYSQL | POSTGRESQL | ORACLE | SQLSERVER | DORIS';
COMMENT ON COLUMN source_datasource.password_enc     IS 'AES-256/CBC 加密密码，格式：Base64(IV):Base64(密文)';
COMMENT ON COLUMN source_datasource.read_concurrency IS 'ETL 读取并发数，对应 dfetl channel 数量';
COMMENT ON COLUMN source_datasource.status           IS 'NORMAL=正常 | ERROR=连接失败 | TESTING=测试中';
COMMENT ON COLUMN source_datasource.institution_id   IS '所属机构 ID（df_etl.institution.id），可空以兼容老数据';
CREATE INDEX IF NOT EXISTS idx_source_ds_institution
    ON source_datasource(institution_id) WHERE institution_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 2.1 Doris 类型映射规则（doris_type_mapping_rule）
--     医疗视图字段类型到 Doris 字段类型的默认映射与风险等级。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS doris_type_mapping_rule (
    id                      BIGSERIAL PRIMARY KEY,
    profile_name            VARCHAR(64)  NOT NULL DEFAULT 'DEFAULT_MEDICAL_VIEW',
    profile_version         INTEGER      NOT NULL DEFAULT 1,
    source_dialect          VARCHAR(32)  NOT NULL,
    source_type_pattern     VARCHAR(128) NOT NULL,
    recommended_doris_type  VARCHAR(128) NOT NULL,
    compatibility_level     VARCHAR(16)  NOT NULL DEFAULT 'PASS',
    reason                  TEXT,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    priority                INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_doris_type_mapping_rule UNIQUE (profile_name, source_dialect, source_type_pattern)
);

CREATE INDEX IF NOT EXISTS idx_doris_type_mapping_rule_enabled
    ON doris_type_mapping_rule(enabled, source_dialect, priority DESC);

INSERT INTO doris_type_mapping_rule
    (profile_name, profile_version, source_dialect, source_type_pattern, recommended_doris_type,
     compatibility_level, reason, enabled, priority)
VALUES
    ('DEFAULT_MEDICAL_VIEW', 1, 'MYSQL',      'TIMESTAMP',                       'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'ORACLE',     'TIMESTAMP WITH TIME ZONE',         'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'ORACLE',     'TIMESTAMP WITH LOCAL TIME ZONE',   'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'POSTGRESQL', 'TIMESTAMPTZ',                     'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'POSTGRESQL', 'TIMESTAMP WITH TIME ZONE',         'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'SQLSERVER',  'DATETIMEOFFSET',                  'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100)
ON CONFLICT (profile_name, source_dialect, source_type_pattern) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 3. 目标数据源（target_datasource）
--    Doris 集群连接信息
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS target_datasource (
    id                      BIGSERIAL       PRIMARY KEY,

    -- 基本信息
    name                    VARCHAR(100)    NOT NULL UNIQUE,
    environment             VARCHAR(20)     NOT NULL DEFAULT 'production', -- production | staging | development

    -- Doris FE 连接
    fe_host                 VARCHAR(255)    NOT NULL,           -- FE 主机名或 IP
    fe_port                 INTEGER         NOT NULL DEFAULT 9030,  -- FE MySQL 协议端口（JDBC 用）
    http_port               INTEGER         NOT NULL DEFAULT 8030,  -- FE HTTP 端口（管理用）
    stream_load_port        INTEGER         NOT NULL DEFAULT 8040,  -- BE HTTP 端口（Stream Load 必须直连 BE）

    -- 认证
    username                VARCHAR(100)    NOT NULL,
    password_enc            TEXT            NOT NULL,           -- AES-256 加密

    -- 写入目标
    db_name                 VARCHAR(100)    NOT NULL,           -- Doris 数据库名（JDBC 连接用）
    default_write_database  VARCHAR(100),                       -- 默认写入目标库（可与 db_name 不同）

    -- 写入参数
    write_batch_size        INTEGER         NOT NULL DEFAULT 50000,  -- Stream Load 单批行数
    write_concurrency       INTEGER         NOT NULL DEFAULT 8,      -- 写并发数
    pool_size               INTEGER         NOT NULL DEFAULT 20,     -- 连接池大小
    ssl                     BOOLEAN         NOT NULL DEFAULT FALSE,

    -- 元信息
    description             TEXT,
    status                  VARCHAR(20)     NOT NULL DEFAULT 'NORMAL', -- NORMAL | ERROR | TESTING

    -- 审计
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  target_datasource                  IS 'Doris 目标数据源配置';
COMMENT ON COLUMN target_datasource.stream_load_port IS 'BE HTTP 端口（8040），必须直连 BE；FE 会 307 重定向到 BE 内网 IP 导致失败';
COMMENT ON COLUMN target_datasource.write_batch_size IS 'Stream Load 单批行数，过大增加内存压力';


-- -----------------------------------------------------------------------------
-- 4. 同步任务（sync_task）
--    ETL 同步单元，通常对应"一个源库 → 多张表/视图 → 一个 Doris 库"
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_task (
    id                      BIGSERIAL       PRIMARY KEY,

    -- 基本信息
    name                    VARCHAR(200)    NOT NULL,           -- 任务名称
    institution_id          BIGINT          REFERENCES df_etl.institution(id),

    -- 数据源绑定
    source_datasource_id    BIGINT          NOT NULL REFERENCES source_datasource(id),
    target_datasource_id    BIGINT          NOT NULL REFERENCES target_datasource(id),

    -- 同步对象
    source_schema           VARCHAR(100),                       -- 覆盖数据源默认 schema（单任务级别）
    source_mode             VARCHAR(20)     NOT NULL DEFAULT 'TABLE_VIEW', -- TABLE_VIEW | CUSTOM_SQL
    custom_sql              TEXT,                               -- 自定义 SQL 模式下的只读 SELECT
    custom_sql_name         VARCHAR(100),                       -- 自定义 SQL 逻辑源名
    view_names              TEXT,                               -- JSON 数组，要同步的表/视图名，如 ["v_patient","v_visit"]

    -- 同步策略
    sync_type               VARCHAR(20)     NOT NULL DEFAULT 'FULL',        -- FULL | INCREMENTAL
    sync_mode               VARCHAR(20)     NOT NULL DEFAULT 'TRUNCATE',    -- TRUNCATE | APPEND | UPSERT
    data_scope              VARCHAR(20)     NOT NULL DEFAULT 'FULL',        -- FULL | INCREMENTAL（实际执行数据范围）
    incremental_field       VARCHAR(200),                       -- 增量字段名，如 updated_at（增量模式必填）
    upsert_keys             TEXT,                               -- JSON 数组，UPSERT 主键列，如 ["patient_id"]

    -- 执行参数
    batch_size              INTEGER,                            -- 任务级 JDBC fetch_size 覆盖值，NULL/0=继承全局 etl.fetch_size
    parallelism             INTEGER         NOT NULL DEFAULT 4,             -- 并发 channel 数
    shard_count             INTEGER,                            -- 计划分片数（NULL=自动按 batch_size 计算）
    shard_strategy          VARCHAR(50)     NOT NULL DEFAULT 'PRIMARY_KEY_RANGE', -- PRIMARY_KEY_RANGE
    rate_limit              INTEGER         NOT NULL DEFAULT 0,             -- 速率上限（MB/s），0=不限

    -- 调度配置
    schedule                VARCHAR(100),                       -- Quartz cron 表达式，NULL=仅手动触发
    schedule_label          VARCHAR(100),                       -- cron 的人类可读描述，如"每天凌晨2点"

    -- 状态机
    status                  VARCHAR(20)     NOT NULL DEFAULT 'DISABLED',    -- ENABLED | DISABLED | FAILED
    version_status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',       -- DRAFT | TESTED | PUBLISHED | DEPRECATED
    version                 VARCHAR(20)     NOT NULL DEFAULT 'V1',          -- 配置版本号

    -- 最近运行信息（冗余存储，避免频繁聚合查询）
    last_run_time           TIMESTAMPTZ,                        -- 最近一次执行开始时间
    last_run_status         VARCHAR(20),                        -- SUCCESS | FAILED | RUNNING | RECONCILE_REQUIRED
    incremental_checkpoint  TIMESTAMPTZ,                        -- 增量模式：上次窗口结束时间（下次起点）

    -- 告警
    alert_status            VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',      -- NORMAL | WARNING | ERROR

    -- 业务问答快照
    data_characteristics    TEXT,                               -- 创建/编辑时的业务问答输入 JSON

    -- 审计
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sync_task_source   ON sync_task(source_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_task_target   ON sync_task(target_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_task_status   ON sync_task(status);
CREATE INDEX IF NOT EXISTS idx_sync_task_institution
    ON sync_task(institution_id) WHERE institution_id IS NOT NULL;

COMMENT ON TABLE  sync_task                      IS '同步任务配置';
COMMENT ON COLUMN sync_task.source_mode          IS 'TABLE_VIEW=表/视图模式 | CUSTOM_SQL=自定义 SQL 模式';
COMMENT ON COLUMN sync_task.custom_sql           IS '自定义 SQL 模式下的只读 SELECT 语句';
COMMENT ON COLUMN sync_task.custom_sql_name      IS '自定义 SQL 的逻辑源名，用于目标表映射和任务命名';
COMMENT ON COLUMN sync_task.view_names           IS 'JSON 数组，示例：["v_his_patients","v_his_outpatient"]';
COMMENT ON COLUMN sync_task.sync_mode            IS 'TRUNCATE=清空写入 | APPEND=追加写入 | UPSERT=按主键更新写入';
COMMENT ON COLUMN sync_task.data_scope           IS 'FULL=全量数据 | INCREMENTAL=增量窗口数据';
COMMENT ON COLUMN sync_task.incremental_field    IS '增量同步时间字段，如 updated_at / report_time';
COMMENT ON COLUMN sync_task.upsert_keys          IS 'UPSERT 模式主键列，JSON 数组，如 ["patient_id"]';
COMMENT ON COLUMN sync_task.batch_size           IS '任务级 JDBC fetch_size 覆盖值，NULL/0=继承全局 etl.fetch_size';
COMMENT ON COLUMN sync_task.shard_strategy       IS 'PRIMARY_KEY_RANGE=按主键范围分片（目前唯一支持的策略）';
COMMENT ON COLUMN sync_task.version_status       IS 'DRAFT=草稿 | TESTED=测试通过 | PUBLISHED=已发布 | DEPRECATED=已废弃';
COMMENT ON COLUMN sync_task.incremental_checkpoint IS '增量同步专用：下次执行时的 WHERE field >= checkpoint';
COMMENT ON COLUMN sync_task.institution_id        IS '所属机构 ID；标准路由任务创建时显式写入';


-- -----------------------------------------------------------------------------
-- 5. 视图字段映射配置（task_view_config）
--    记录每个任务下每个视图的字段映射关系和生成的 Doris DDL
--    前端"字段映射"步骤的持久化存储
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_view_config (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         BIGINT          NOT NULL REFERENCES sync_task(id) ON DELETE CASCADE,
    view_name       VARCHAR(200)    NOT NULL,                   -- 视图/表名

    -- 字段映射（JSON 存储，结构复杂不适合关系型）
    field_mappings  TEXT,                                       -- JSON 数组，字段映射列表
    doris_ddl       TEXT,                                       -- 根据映射生成的 Doris CREATE TABLE DDL

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    UNIQUE(task_id, view_name)
);

COMMENT ON TABLE  task_view_config               IS '同步任务的逐视图字段映射配置';
COMMENT ON COLUMN task_view_config.field_mappings IS '字段映射 JSON 数组，每元素格式：'
    '{"sourceField":"patient_id","sourceType":"INTEGER","targetField":"patient_id",'
    '"targetType":"INT","checked":true,"isExtra":false,"defaultValue":null}';
COMMENT ON COLUMN task_view_config.doris_ddl     IS 'DBA 在 Doris 执行的建表语句（由 server 根据映射生成）';


-- =============================================================================
-- ■ Phase 8  执行历史 + 质量追踪
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 6. 任务执行历史（task_execution）
--    每次触发任务产生一条记录，统计整次执行的汇总结果
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_execution (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         BIGINT          NOT NULL REFERENCES sync_task(id),

    -- 批次标识
    batch_no        VARCHAR(30)     NOT NULL,                   -- 批次号，格式：yyyyMMdd_HHmmss

    -- 触发信息
    triggered_by    VARCHAR(50)     NOT NULL DEFAULT 'MANUAL',  -- SCHEDULER | MANUAL | RECOLLECT_*
    worker_node     VARCHAR(100),                               -- 执行节点标识（Phase 12 多节点）

    -- 状态机
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING', -- PENDING | RUNNING | SUCCESS | FAILED | TIMEOUT | CANCELLED | RECONCILE_REQUIRED

    -- 执行快照（防止任务配置变更影响历史记录解读）
    snapshot_sync_type  VARCHAR(20),                            -- 执行时的 sync_type 快照
    snapshot_sync_mode  VARCHAR(20),                            -- 执行时的 sync_mode 快照
    snapshot_view_names TEXT,                                   -- 执行时的 view_names 快照（JSON）

    -- 增量窗口
    window_start    TIMESTAMPTZ,                                -- 增量窗口开始（上次 checkpoint）
    window_end      TIMESTAMPTZ,                                -- 增量窗口结束（本次触发时间）
    window_start_id BIGINT,                                     -- ID_RANGE 窗口起点（上次最大 ID）
    window_end_id   BIGINT,                                     -- ID_RANGE 窗口终点（本次最大 ID）
    window_type     VARCHAR(20),                                -- FULL | INCREMENT | CUSTOM_WINDOW | FULL_THEN_INCREMENT

    -- 运行统计（从 dfetl 标准输出日志解析）
    read_rows       BIGINT,                                     -- 本次业务范围源端行数
    write_rows      BIGINT,                                     -- 本次业务范围目标端可见行数
    failed_rows     BIGINT,                                     -- 同口径未落入目标的业务行数
    source_rows_total BIGINT,                                   -- 医共体源窗口总行数：valid_source_rows + excluded_rows
    valid_source_rows BIGINT,                                   -- 医共体分流后进入 SeaTunnel 的合规源行数
    excluded_rows   BIGINT,                                     -- 医共体阻断剔除、未写入 Doris 的行数
    warning_rows    BIGINT,                                     -- 医共体告警但仍写入 Doris 的行数
    medical_valid_source_query TEXT,                            -- 医共体执行期分流后 SeaTunnel 实际读取的合规源查询快照
    engine_read_rows  BIGINT,                                   -- SeaTunnel 累计读取尝试数（含内部重试）
    engine_write_rows BIGINT,                                   -- SeaTunnel 累计写入尝试数（含内部重试）
    bytes_written   BIGINT,                                     -- 写入字节数
    speed_mb_s      DECIMAL(10, 2),                             -- 平均速度（MB/s）
    channel_count   INTEGER,                                    -- 实际使用的 channel 并发数

    -- 时间线
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,

    -- 日志与错误
    log_path        TEXT,                                       -- 日志文件路径（本地或 S3）
    error_msg       TEXT,                                       -- 失败时的错误摘要

    -- RECONCILE_REQUIRED 人工处置闭环
    reconcile_handled           BOOLEAN     NOT NULL DEFAULT FALSE, -- 是否已人工核对并关闭待办；不代表执行成功
    reconcile_handled_at        TIMESTAMPTZ,                       -- 人工标记已处理时间
    reconcile_handled_by        VARCHAR(100),                      -- 人工处理人
    reconcile_note              TEXT,                              -- 人工处理备注
    reconcile_last_probed_at    TIMESTAMPTZ,                       -- 最近一次人工重新探测时间
    reconcile_last_probe_result TEXT,                              -- 最近一次人工重新探测结果

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_execution_task     ON task_execution(task_id);
CREATE INDEX IF NOT EXISTS idx_task_execution_status   ON task_execution(status);
CREATE INDEX IF NOT EXISTS idx_task_execution_reconcile ON task_execution(status, reconcile_handled);
CREATE INDEX IF NOT EXISTS idx_task_execution_started  ON task_execution(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_execution_batchno  ON task_execution(batch_no);

COMMENT ON TABLE  task_execution                     IS '任务执行历史（每次触发一条记录）';
COMMENT ON COLUMN task_execution.batch_no            IS '批次号，格式 yyyyMMdd_HHmmss，如 20260421_080000';
COMMENT ON COLUMN task_execution.triggered_by        IS 'SCHEDULER=定时触发 | MANUAL=手动触发 | RECOLLECT_TRUNCATE / RECOLLECT_DROP_RECREATE=重采';
COMMENT ON COLUMN task_execution.worker_node         IS '执行节点，格式 hostname:port，Phase 12 多节点时填写';
COMMENT ON COLUMN task_execution.snapshot_view_names IS '本次实际执行的视图列表快照，防止任务配置变更后历史记录失去语义';
COMMENT ON COLUMN task_execution.window_start        IS '增量模式：本批次增量窗口起点（等于上次执行的 incremental_checkpoint）';
COMMENT ON COLUMN task_execution.window_end          IS '增量模式：本批次增量窗口终点（执行触发时刻）';
COMMENT ON COLUMN task_execution.window_start_id     IS 'ID_RANGE 增量模式：本批次 ID 窗口起点（上次最大 ID）';
COMMENT ON COLUMN task_execution.window_end_id       IS 'ID_RANGE 增量模式：本批次 ID 窗口终点（本次最大 ID）';
COMMENT ON COLUMN task_execution.window_type         IS '本批次执行窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT';
COMMENT ON COLUMN task_execution.engine_read_rows    IS 'SeaTunnel vertex 累计读取尝试数，含内部重试，不等同业务 read_rows';
COMMENT ON COLUMN task_execution.engine_write_rows   IS 'SeaTunnel vertex 累计写入尝试数，含内部重试，不等同目标已提交 write_rows';
COMMENT ON COLUMN task_execution.source_rows_total   IS '医共体源窗口总行数：valid_source_rows + excluded_rows';
COMMENT ON COLUMN task_execution.valid_source_rows   IS '医共体分流后进入 SeaTunnel 的合规源行数';
COMMENT ON COLUMN task_execution.excluded_rows       IS '医共体阻断剔除、未写入 Doris 的行数';
COMMENT ON COLUMN task_execution.warning_rows        IS '医共体告警但仍写入 Doris 的行数';
COMMENT ON COLUMN task_execution.medical_valid_source_query IS '医共体执行期分流后 SeaTunnel 实际读取的合规源查询快照，供 Validation 对齐执行范围';
COMMENT ON COLUMN task_execution.reconcile_handled   IS 'RECONCILE_REQUIRED 人工待办是否已处理；不代表执行成功，不推进 watermark';


-- -----------------------------------------------------------------------------
-- 7. 分片执行明细（task_chunk）
--    记录每个 Chunk（并行分片）的执行细节，支持 Chunk 级别重跑和问题定位
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_chunk (
    id              BIGSERIAL       PRIMARY KEY,
    execution_id    BIGINT          NOT NULL REFERENCES task_execution(id) ON DELETE CASCADE,

    -- 分片定位
    view_name       VARCHAR(200),                               -- 所属视图（多视图任务时区分）
    chunk_no        INTEGER         NOT NULL,                   -- 分片序号，从 1 开始
    range_desc      VARCHAR(500),                               -- 分片范围描述，如 "patient_id 1 ~ 240000"

    -- 状态
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING', -- PENDING | RUNNING | SUCCESS | FAILED | RETRYING | SKIPPED

    -- 读写统计
    read_rows       BIGINT,
    write_rows      BIGINT,
    source_checksum VARCHAR(128),                               -- 源端数据摘要（分片级）
    target_checksum VARCHAR(128),                               -- 目标端数据摘要（分片级）
    doris_label     VARCHAR(300),                               -- Doris Stream Load label（用于排查幂等问题）

    -- 执行参数（本次实际使用值）
    fetch_size      INTEGER,
    concurrency     INTEGER,
    retries         INTEGER         NOT NULL DEFAULT 0,         -- 已重试次数

    -- 时间线
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,

    -- 错误
    error_msg       TEXT,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_chunk_execution ON task_chunk(execution_id);
CREATE INDEX IF NOT EXISTS idx_task_chunk_status    ON task_chunk(status);

COMMENT ON TABLE  task_chunk             IS '分片（Chunk）执行明细，一次执行有多个并行 Chunk';
COMMENT ON COLUMN task_chunk.doris_label IS 'Doris Stream Load label，格式：etl_{taskName}_{batchNo}_c{chunkNo}，用于排查"label 已存在"问题';
COMMENT ON COLUMN task_chunk.range_desc  IS '主键分片范围描述，如 "patient_id 720001 ~ 960000"';


-- -----------------------------------------------------------------------------
-- 8. 脏数据记录（dirty_record）
--    Stream Load 或类型转换失败的行记录，支持人工排查和标记处理
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dirty_record (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         BIGINT          NOT NULL REFERENCES sync_task(id),
    execution_id    BIGINT          REFERENCES task_execution(id),
    chunk_id        BIGINT          REFERENCES task_chunk(id),

    -- 定位信息
    view_name       VARCHAR(200),
    chunk_no        INTEGER,

    -- 错误详情
    error_type      VARCHAR(50)     NOT NULL,                   -- 见下方 COMMENT
    target_field    VARCHAR(200),                               -- 出错的目标字段名，"—" 表示行级错误
    error_msg       TEXT,
    raw_data        TEXT,                                       -- 原始行数据（JSON 字符串）

    -- 处理状态
    handled         BOOLEAN         NOT NULL DEFAULT FALSE,
    handled_at      TIMESTAMPTZ,                                -- 标记处理时间

    found_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dirty_record_task      ON dirty_record(task_id);
CREATE INDEX IF NOT EXISTS idx_dirty_record_execution ON dirty_record(execution_id);
CREATE INDEX IF NOT EXISTS idx_dirty_record_handled   ON dirty_record(handled) WHERE NOT handled;
CREATE INDEX IF NOT EXISTS idx_dirty_record_found_at  ON dirty_record(found_at DESC);

COMMENT ON TABLE  dirty_record            IS '脏数据记录（类型转换失败、空值异常、Stream Load 失败等）';
COMMENT ON COLUMN dirty_record.error_type IS 'FIELD_CONVERT_FAIL=字段转换失败 | NULL_VIOLATION=空值异常 | TYPE_MISMATCH=类型不匹配 | WRITE_FAIL=写入失败';
COMMENT ON COLUMN dirty_record.raw_data   IS '原始行的 JSON 字符串，用于人工排查数据问题';


-- -----------------------------------------------------------------------------
-- 9. 医共体行级问题记录（medical_dirty_row / medical_dirty_field）
--    支持合规行写入 Doris，问题行逐行落库并按负责人核对
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS medical_dirty_row (
    id               BIGSERIAL       PRIMARY KEY,
    task_id          BIGINT          NOT NULL REFERENCES sync_task(id),
    execution_id     BIGINT          NOT NULL REFERENCES task_execution(id),
    dataset_code     VARCHAR(100)    NOT NULL,
    dataset_name     VARCHAR(200),
    source_schema    VARCHAR(200),
    source_view      VARCHAR(200)    NOT NULL,
    target_table     VARCHAR(200),
    business_pk_json TEXT,
    source_row_hash  VARCHAR(64)     NOT NULL,
    window_json      TEXT,
    owner_name       VARCHAR(100),
    owner_source     VARCHAR(100),
    row_action       VARCHAR(50)     NOT NULL,
    severity         VARCHAR(50)     NOT NULL,
    status           VARCHAR(50)     NOT NULL DEFAULT 'OPEN',
    raw_row_json     TEXT,
    error_count      INTEGER         NOT NULL DEFAULT 0,
    found_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    handled_at       TIMESTAMPTZ,
    handled_by       VARCHAR(100),
    handle_note      TEXT,
    CONSTRAINT uk_medical_dirty_row_execution_dataset_hash
        UNIQUE (execution_id, dataset_code, source_row_hash)
);

CREATE TABLE IF NOT EXISTS medical_dirty_field (
    id               BIGSERIAL       PRIMARY KEY,
    dirty_row_id     BIGINT          NOT NULL REFERENCES medical_dirty_row(id) ON DELETE CASCADE,
    field_code       VARCHAR(100)    NOT NULL,
    field_name       VARCHAR(200),
    source_column    VARCHAR(200),
    target_column    VARCHAR(200),
    error_type       VARCHAR(80)     NOT NULL,
    standard_rule    VARCHAR(200),
    value_domain_code VARCHAR(100),
    value_domain_mode VARCHAR(30),
    value_domain_allowed_count INTEGER,
    raw_value        TEXT,
    normalized_value TEXT,
    message          TEXT,
    severity         VARCHAR(50)     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_dataset_status
    ON medical_dirty_row(dataset_code, status);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_owner_status
    ON medical_dirty_row(owner_name, status);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_task_execution
    ON medical_dirty_row(task_id, execution_id);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_severity_found
    ON medical_dirty_row(severity, found_at DESC);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_row
    ON medical_dirty_field(dirty_row_id);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_error_type
    ON medical_dirty_field(error_type);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_value_domain
    ON medical_dirty_field(value_domain_code);

COMMENT ON TABLE medical_dirty_row IS '医共体行级问题记录，用于合规行写入后的问题行核对闭环';
COMMENT ON TABLE medical_dirty_field IS '医共体字段级问题明细';
COMMENT ON COLUMN medical_dirty_row.row_action IS 'EXCLUDED=该行未写入Doris | WRITTEN_WITH_WARNING=该行已写入但存在告警';
COMMENT ON COLUMN medical_dirty_row.status IS 'OPEN/SENT/CONFIRMED/FIXED/IGNORED';
COMMENT ON COLUMN medical_dirty_field.error_type IS 'PRIMARY_KEY_NULL/PRIMARY_KEY_DUPLICATE/NON_KEY_INVALID_NUMBER_TO_NULL 等标准化错误类型';


-- -----------------------------------------------------------------------------
-- 10. 操作审计日志（audit_log）
--    记录用户在管理界面的所有操作，不可删除，不可修改
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    action_time     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    user_name       VARCHAR(100)    NOT NULL DEFAULT 'system',  -- 操作人，'scheduler' 表示定时触发
    action          VARCHAR(50)     NOT NULL,                   -- 见下方 COMMENT
    target_type     VARCHAR(50),                                -- sync_task | source_datasource | target_datasource | task_group
    target_id       BIGINT,                                     -- 操作对象 ID
    target_name     VARCHAR(200),                               -- 操作对象名称（冗余，防止对象被删后查不到）
    detail          TEXT,                                       -- 操作详情描述
    client_ip       VARCHAR(50)                                 -- 客户端 IP
);

CREATE INDEX IF NOT EXISTS idx_audit_log_time   ON audit_log(action_time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_user   ON audit_log(user_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_target ON audit_log(target_type, target_id);

COMMENT ON TABLE  audit_log         IS '用户操作审计日志（append-only，禁止修改或删除）';
COMMENT ON COLUMN audit_log.action  IS '操作类型：创建任务 | 修改任务 | 发布任务 | 运行任务 | 停止任务 | 删除任务 | 创建数据源 | 删除数据源 | 测试连接 | 创建分组 | 修改分组 | 删除分组';


-- =============================================================================
-- ■ Phase 9+  调度配置、告警、数据校验
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 10. 系统配置（system_setting）
--     全局 K-V 配置存储：执行参数、调度器参数、ETL 系统字段开关
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS system_setting (
    setting_key     VARCHAR(200)    PRIMARY KEY,
    setting_value   TEXT,
    description     TEXT,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  system_setting             IS '全局系统配置（K-V 存储），前端"设置"页面持久化';
COMMENT ON COLUMN system_setting.setting_key IS '示例键名：scheduler.max_concurrent | etl.fetch_size | etl.system_field.batch_id';

-- 初始化默认配置（幂等，可重复执行）
INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('scheduler.enabled',             'true',             '调度器是否启用'),
    ('scheduler.max_concurrent',      '3',                '最大并发任务数（Semaphore 限制）'),
    ('scheduler.timezone',            'Asia/Shanghai',    '调度时区'),
    ('scheduler.retry_on_fail',       'true',             '任务失败后是否自动重试'),
    ('scheduler.retry_delay_minutes', '5',                '失败重试等待时间（分钟）'),
    ('scheduler.max_retry_attempts',  '2',                '调度层最大重试次数'),
    ('etl.fetch_size',                '50000',            'SeaTunnel JDBC source fetch_size，全局默认源端读取批量'),
    ('etl.default_parallelism',       '4',                '默认并发 channel 数'),
    ('etl.default_batch_size',        '240000',           '历史默认分片行数；SeaTunnel 主同步链路不消费'),
    ('etl.retry_count',               '3',                'Stream Load 最大重试次数'),
    ('etl.retry_interval_sec',        '10',               '重试间隔（秒）'),
    ('etl.connect_timeout_sec',       '30',               '源库连接超时（秒）'),
    ('etl.read_timeout_sec',          '300',              '源库读取超时（秒）'),
    ('etl.default_sync_mode',         'TRUNCATE',         '默认写入模式'),
    ('etl.system_field.batch_id',     'true',             '是否写入 _etl_batch_id 系统字段'),
    ('etl.system_field.job_id',       'true',             '是否写入 _etl_job_id 系统字段'),
    ('etl.system_field.job_version',  'true',             '是否写入 _etl_job_version 系统字段'),
    ('etl.system_field.sync_time',    'true',             '是否写入 _etl_sync_time 系统字段'),
    ('etl.system_field.source',       'true',             '是否写入 _etl_source_system 系统字段'),
    ('etl.system_field.window_start', 'true',             '是否写入 _etl_window_start 系统字段'),
    ('etl.system_field.window_end',   'true',             '是否写入 _etl_window_end 系统字段'),
    ('doris.auto_create.partition.enabled',        'false', 'Doris 自动建表是否生成时间分区'),
    ('doris.auto_create.partition.field',          'xiugaisj', 'Doris 自动分区字段，医疗视图默认 xiugaisj'),
    ('doris.auto_create.partition.granularity',    'MONTH', 'Doris 自动分区粒度：MONTH 或 DAY'),
    ('doris.auto_create.partition.history_months', '36', '按月分区时创建最近 N 个月历史分区'),
    ('doris.auto_create.partition.future_months',  '6', '按月分区时预建未来 N 个月分区'),
    ('doris.auto_create.partition.history_days',   '90', '按日分区时创建最近 N 天历史分区'),
    ('doris.auto_create.partition.future_days',    '30', '按日分区时预建未来 N 天分区'),
    ('doris.auto_create.bucket.strategy',          'FIXED', 'Doris 自动建表 bucket 策略：FIXED 或 DATA_SCALE'),
    ('doris.auto_create.bucket.fixed',             '10', 'Doris 自动建表固定 bucket 数'),
    ('doris.auto_create.bucket.tier.lt_100k',      '1', '预计数据量小于 10 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.100k_1m',      '2', '预计数据量 10 万到 100 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.1m_10m',       '4', '预计数据量 100 万到 1000 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.10m_50m',      '8', '预计数据量 1000 万到 5000 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.50m_200m',     '16', '预计数据量 5000 万到 2 亿行时 bucket 数'),
    ('doris.auto_create.bucket.tier.200m_1b',      '32', '预计数据量 2 亿到 10 亿行时 bucket 数'),
    ('doris.auto_create.bucket.tier.gt_1b',        '64', '预计数据量大于 10 亿行时 bucket 数')
ON CONFLICT (setting_key) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 11. 告警通知渠道（webhook_endpoint）
--     钉钉 / 企微机器人 Webhook，URL 加密存储防止 token 泄露
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL UNIQUE,
    type            VARCHAR(20)     NOT NULL,                   -- DINGTALK | WECOM
    url_enc         TEXT            NOT NULL,                   -- AES-256 加密 Webhook URL
    last_tested_at  TIMESTAMPTZ,
    status          VARCHAR(20)     NOT NULL DEFAULT 'UNTESTED', -- OK | FAIL | UNTESTED
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  webhook_endpoint         IS '告警通知渠道（钉钉/企微 Webhook Robot）';
COMMENT ON COLUMN webhook_endpoint.type    IS 'DINGTALK=钉钉机器人 | WECOM=企业微信机器人';
COMMENT ON COLUMN webhook_endpoint.url_enc IS 'AES-256 加密存储，防止 Webhook token 明文泄露';


-- -----------------------------------------------------------------------------
-- 12. 告警规则（alert_rule）
--     定义触发条件、通知渠道、静默窗口
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_rule (
    id                  BIGSERIAL       PRIMARY KEY,
    name                VARCHAR(200)    NOT NULL,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,

    -- 触发条件
    metric              VARCHAR(50)     NOT NULL,               -- 监控指标，见 COMMENT
    condition_op        VARCHAR(10)     NOT NULL,               -- eq | ne | gt | lt
    threshold           VARCHAR(100)    NOT NULL,               -- 阈值（字符串，支持枚举值和数字）

    -- 告警级别与通知
    severity            VARCHAR(20)     NOT NULL DEFAULT 'warning', -- critical | warning | info
    channels            TEXT,                                   -- JSON 数组：webhook_endpoint.id 列表
    scope               TEXT,                                   -- JSON：{"type":"all"|"group"|"task","value":"..."}

    -- 静默配置
    silence_minutes     INTEGER         NOT NULL DEFAULT 30,    -- 重复告警静默窗口（分钟）
    last_triggered_at   TIMESTAMPTZ,                            -- 最近一次触发时间（用于静默判断）

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  alert_rule              IS '告警规则配置';
COMMENT ON COLUMN alert_rule.metric       IS '可选值：task_status | batch_status | dirty_count | duration | chunk_fail_rate | write_diff | validation_result | read_rows';
COMMENT ON COLUMN alert_rule.condition_op IS 'eq=等于 | ne=不等于 | gt=大于 | lt=小于';
COMMENT ON COLUMN alert_rule.channels     IS 'JSON 数组，webhook_endpoint.id，如 [1, 2]';
COMMENT ON COLUMN alert_rule.scope        IS '作用范围：{"type":"all"} 或 {"type":"group","value":"groupName"} 或 {"type":"task","value":"taskName"}';


-- -----------------------------------------------------------------------------
-- 13. 告警通知记录（notify_record）
--     每次触发告警时记录发送明细（含失败重试情况）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notify_record (
    id              BIGSERIAL       PRIMARY KEY,
    rule_id         BIGINT          REFERENCES alert_rule(id),
    rule_name       VARCHAR(200),                               -- 冗余：规则名（防止规则删除后历史失去语义）
    severity        VARCHAR(20)     NOT NULL,
    content         TEXT,                                       -- 告警消息正文
    channel_id      BIGINT          REFERENCES webhook_endpoint(id),
    channel_name    VARCHAR(100),                               -- 冗余：渠道名
    task_id         BIGINT          REFERENCES sync_task(id),
    task_name       VARCHAR(200),                               -- 冗余：任务名
    batch_no        VARCHAR(30),                                -- 关联批次号
    triggered_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'  -- PENDING | SENT | FAILED
);

CREATE INDEX IF NOT EXISTS idx_notify_record_triggered ON notify_record(triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_notify_record_task      ON notify_record(task_id);
CREATE INDEX IF NOT EXISTS idx_notify_record_rule      ON notify_record(rule_id);

COMMENT ON TABLE notify_record IS '告警通知发送历史（每条渠道一条记录，支持多渠道并行发送）';


-- -----------------------------------------------------------------------------
-- 14. 数据一致性校验任务（validation_task）
--     定期对比源端和目标端的行数/Checksum，确保数据完整性
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS validation_task (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(200)    NOT NULL,
    task_id         BIGINT          REFERENCES sync_task(id),   -- 关联同步任务（可选）
    execution_id    BIGINT,                                     -- AUTO/GATE 触发时关联 task_execution.id
    trigger_type    VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',  -- MANUAL | AUTO | GATE | DRIFT

    -- 校验配置
    method          VARCHAR(20)     NOT NULL DEFAULT 'row_count', -- row_count | checksum | row_count_checksum
    tables          TEXT,                                       -- JSON 数组，参与校验的视图/表名

    -- 校验结果
    status          VARCHAR(20)     NOT NULL DEFAULT 'pending',  -- consistent | diff | pending
    source_rows     BIGINT,
    target_rows     BIGINT,
    diff_rows       BIGINT,
    duration_ms     BIGINT,
    last_run_at     TIMESTAMPTZ,
    error_msg       TEXT,
    window_start    TIMESTAMPTZ,
    window_end      TIMESTAMPTZ,
    window_type     VARCHAR(20),
    window_start_id BIGINT,
    window_end_id   BIGINT,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  validation_task        IS '数据一致性校验任务（行数比对 / Checksum）';
COMMENT ON COLUMN validation_task.method IS 'row_count=行数比对 | checksum=全量摘要校验 | row_count_checksum=行数+Checksum';
COMMENT ON COLUMN validation_task.status IS 'consistent=一致 | diff=发现差异 | pending=待人工审核';

-- -----------------------------------------------------------------------------
-- 14.1 任务级校验配置（task_validation_config）
--      method 为 NULL 时继承系统全局 validation_method
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_validation_config (
    id                       BIGSERIAL       PRIMARY KEY,
    task_id                  BIGINT          NOT NULL UNIQUE REFERENCES sync_task(id) ON DELETE CASCADE,
    enabled                  BOOLEAN         NOT NULL DEFAULT TRUE,
    method                   VARCHAR(30),                         -- NULL=继承全局；ROW_COUNT | CHECKSUM | ROW_COUNT_CHECKSUM
    checksum_algo            VARCHAR(20)     DEFAULT 'XXHASH64',
    sample_rate              NUMERIC(5,2)    DEFAULT 10,
    tolerance_rows           BIGINT          DEFAULT 0,
    tolerance_pct            NUMERIC(8,6)    DEFAULT 0,
    auto_trigger             BOOLEAN,                              -- NULL=继承全局 validation_auto_enabled
    block_on_fail            BOOLEAN,                              -- NULL=继承全局 validation_fail_block
    validation_template      VARCHAR(20)     NOT NULL DEFAULT 'STANDARD',
    failure_action           VARCHAR(20)     NOT NULL DEFAULT 'WARN',
    max_check_rows           BIGINT          NOT NULL DEFAULT 1000000,
    target_tables            TEXT,
    drift_cron               VARCHAR(32),
    checksum_scope           VARCHAR(10)     NOT NULL DEFAULT 'FULL',
    auto_repair              BOOLEAN         NOT NULL DEFAULT FALSE,
    auto_repair_max_rows     BIGINT          NOT NULL DEFAULT 1000,
    validation_lookback_hours INT,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE  task_validation_config IS '任务级数据校验配置；每个同步任务最多一条';
COMMENT ON COLUMN task_validation_config.method IS '任务级校验方式；NULL=继承全局，ROW_COUNT/CHECKSUM/ROW_COUNT_CHECKSUM=覆盖全局';
COMMENT ON COLUMN task_validation_config.target_tables IS '校验表范围，逗号分隔；NULL=任务内全部表';
COMMENT ON COLUMN task_validation_config.auto_trigger IS '任务级同步后自动触发开关；NULL=继承全局 validation_auto_enabled';
COMMENT ON COLUMN task_validation_config.block_on_fail IS '任务级校验失败阻断开关；NULL=继承全局 validation_fail_block';
COMMENT ON COLUMN task_validation_config.checksum_scope IS 'Checksum 范围：FULL=全表，WINDOW=增量窗口';
COMMENT ON COLUMN task_validation_config.validation_lookback_hours IS '任务级校验回看窗口小时；NULL=继承全局，0=只验本次窗口，>0=向前扩展N小时';

-- -----------------------------------------------------------------------------
-- 14.2 校验运行记录（validation_run）
--      spec 062：统一 run 级模型，兼容 legacy exec_id
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS validation_run (
    id              BIGSERIAL      PRIMARY KEY,
    task_id         BIGINT         NOT NULL REFERENCES sync_task(id) ON DELETE CASCADE,
    legacy_exec_id  BIGINT         NOT NULL,
    mode            VARCHAR(32)    NOT NULL,
    scope           VARCHAR(16)    NOT NULL DEFAULT 'FULL',
    window_start    TIMESTAMPTZ,
    window_end      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_validation_run_task_exec UNIQUE (task_id, legacy_exec_id)
);
CREATE INDEX IF NOT EXISTS idx_validation_run_task ON validation_run(task_id);
COMMENT ON TABLE validation_run IS 'spec 062：校验运行记录（run 级锚点，兼容 legacy exec_id）';

-- [spec 069] 2026-06-06  多机构共表校验：非阻塞口径警告列
--   目标端补 _etl_job_id 列后存在 NULL 历史行时，按任务范围过滤会漏算，
--   校验链路检测到 _etl_job_id IS NULL 行数 > 0 时写入此列（与 error_msg 的「执行错误」语义区分）。
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS scope_warning TEXT;


-- =============================================================================
-- 变更记录（生产环境手动执行，开发期由 ddl-auto: update 自动处理）
-- 格式：-- [Phase X] [日期]  说明
-- =============================================================================

-- [Phase 7] 2026-04-22  初始化建表（source_datasource, target_datasource, sync_task）
-- [Phase 7] 2026-04-23  全量重设计：
--   新增 task_view_config（视图字段映射）
--   sync_task 新增：source_schema / sync_mode / data_scope /
--     incremental_field / upsert_keys / batch_size / parallelism /
--     shard_count / shard_strategy / rate_limit / incremental_checkpoint
-- [Phase 8] 待执行  新增 task_execution（含 batch_no / snapshot 字段）
--                   新增 task_chunk（分片明细）
--                   新增 dirty_record（脏数据）
--                   新增 audit_log（操作审计）
-- [Phase 9] 待执行  新增 system_setting（含默认值插入）
--                   新增 webhook_endpoint（告警渠道）
--                   新增 alert_rule（告警规则）
--                   新增 notify_record（通知记录）
--                   新增 validation_task（数据校验）
-- [Phase 9-16] 2026-04-27  sync_task 补全字段（Phase 9~16 期间新增）
--   若已通过 ddl-auto:update 自动建列则下方 ALTER 无害（IF NOT EXISTS 保护）

ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS increment_mode            VARCHAR(20);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS upper_bound_strategy      VARCHAR(20)  NOT NULL DEFAULT 'CURRENT_TIME';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS upper_bound_delay_minutes INTEGER      NOT NULL DEFAULT 5;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS initial_watermark         VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS writer_type               VARCHAR(20)  NOT NULL DEFAULT 'STREAM_LOAD';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS doris_table_model         VARCHAR(20);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS source_mode               VARCHAR(20)  NOT NULL DEFAULT 'TABLE_VIEW';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_sql                TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_sql_name           VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS static_filter             TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS filter_condition_map      TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS target_table_map          TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS data_characteristics      TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_window_start       TIMESTAMPTZ;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_window_end         TIMESTAMPTZ;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS executor_type             VARCHAR(30)  DEFAULT 'SEATUNNEL_CLUSTER';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS split_pk                  VARCHAR(200);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS source_object_type        VARCHAR(30)  NOT NULL DEFAULT 'TABLE';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS soft_delete_field         VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS soft_delete_active_value  VARCHAR(50)  DEFAULT '0';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS enable_doris_merge        BOOLEAN      NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS delete_sign_value         VARCHAR(50)  DEFAULT '1';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS sequence_col              VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS partial_columns           BOOLEAN      NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS lookback_seconds          INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS enable_snapshot_delete    BOOLEAN      NOT NULL DEFAULT false;

-- [spec 020.2] 2026-04-29  快照对账自动调度集成
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS snapshot_auto_capture     BOOLEAN       NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS snapshot_auto_detect_cron VARCHAR(64);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS snapshot_auto_apply       BOOLEAN       NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS snapshot_delete_max_ratio NUMERIC(5,4)  NOT NULL DEFAULT 0.0500;

CREATE TABLE IF NOT EXISTS task_snapshot_key (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES sync_task(id) ON DELETE CASCADE,
    execution_id    BIGINT       NOT NULL,
    key_value       VARCHAR(500) NOT NULL,
    captured_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tsk_task_exec ON task_snapshot_key(task_id, execution_id);
CREATE INDEX IF NOT EXISTS idx_tsk_task_key ON task_snapshot_key(task_id, key_value);
COMMENT ON TABLE task_snapshot_key IS 'spec 020：源端主键集合快照，用于跨次集合差集检测删除';

CREATE TABLE IF NOT EXISTS snapshot_apply_history (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT      NOT NULL REFERENCES sync_task(id) ON DELETE CASCADE,
    prev_execution_id BIGINT      NOT NULL,
    curr_execution_id BIGINT      NOT NULL,
    dry_run           BOOLEAN     NOT NULL DEFAULT true,
    detected_keys     INTEGER     NOT NULL DEFAULT 0,
    loaded_rows       BIGINT      NOT NULL DEFAULT 0,
    filtered_rows     BIGINT      NOT NULL DEFAULT 0,
    result            VARCHAR(40) NOT NULL,
    label             VARCHAR(128),
    message           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sah_task_created ON snapshot_apply_history(task_id, created_at DESC);
COMMENT ON TABLE snapshot_apply_history IS 'spec 067：快照删除校验 Dry-Run / Apply 处理历史';


-- =============================================================================
-- [spec 023] 2026-04-29  Checksum 执行引擎
--   etl_verify_chunk : 分片级 checksum 与计数
--   etl_verify_diff  : 不一致行级差异
-- =============================================================================
CREATE TABLE IF NOT EXISTS etl_verify_chunk (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    exec_id         BIGINT       NOT NULL,
    validation_run_id BIGINT     REFERENCES validation_run(id) ON DELETE SET NULL,
    chunk_no        INT          NOT NULL,
    chunk_start     VARCHAR(256),
    chunk_end       VARCHAR(256),
    source_count    BIGINT,
    target_count    BIGINT,
    source_checksum VARCHAR(64),
    target_checksum VARCHAR(64),
    matched         BOOLEAN      NOT NULL DEFAULT false,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT uk_verify_chunk_run_no UNIQUE (validation_run_id, chunk_no)
);
CREATE INDEX IF NOT EXISTS idx_verify_chunk_task ON etl_verify_chunk(task_id, exec_id);
CREATE INDEX IF NOT EXISTS idx_verify_chunk_run ON etl_verify_chunk(validation_run_id);
COMMENT ON TABLE etl_verify_chunk IS 'spec 023：Checksum 分片级结果';

CREATE TABLE IF NOT EXISTS etl_verify_diff (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    exec_id         BIGINT       NOT NULL,
    validation_run_id BIGINT     REFERENCES validation_run(id) ON DELETE SET NULL,
    chunk_no        INT,
    pk_value        VARCHAR(512) NOT NULL,
    diff_type       VARCHAR(32)  NOT NULL,    -- INSERT_MISSING / UPDATE_DIFF / DELETE_MISSING
    source_hash     VARCHAR(64),
    target_hash     VARCHAR(64),
    repair_status   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    detected_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_verify_diff_task_exec ON etl_verify_diff(task_id, exec_id);
CREATE INDEX IF NOT EXISTS idx_verify_diff_run ON etl_verify_diff(validation_run_id);
COMMENT ON TABLE etl_verify_diff IS 'spec 023：Checksum 行级差异（待 spec 024 Repair 处理）';

ALTER TABLE etl_verify_chunk ADD COLUMN IF NOT EXISTS validation_run_id BIGINT REFERENCES validation_run(id) ON DELETE SET NULL;
ALTER TABLE etl_verify_diff ADD COLUMN IF NOT EXISTS validation_run_id BIGINT REFERENCES validation_run(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_verify_chunk_run ON etl_verify_chunk(validation_run_id);
CREATE INDEX IF NOT EXISTS idx_verify_diff_run ON etl_verify_diff(validation_run_id);


-- =============================================================================
-- [spec 022] 2026-04-29  GlobalSettings 校验策略 + 自动校验触发
--   validation_task 补 execution_id / trigger_type
--   system_setting 补默认值（validation_*）
-- =============================================================================
ALTER TABLE validation_task ADD COLUMN IF NOT EXISTS execution_id BIGINT;
ALTER TABLE validation_task ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
CREATE INDEX IF NOT EXISTS idx_validation_task_exec ON validation_task(execution_id);
COMMENT ON COLUMN validation_task.trigger_type IS 'spec 022：MANUAL=手动触发 | AUTO=任务执行成功后自动';
COMMENT ON COLUMN validation_task.execution_id IS 'spec 022：AUTO 触发时关联的 task_execution.id';

-- [ETL review] 2026-05-13  ID_RANGE 校验窗口上下文
ALTER TABLE task_execution ADD COLUMN IF NOT EXISTS window_start_id BIGINT;
ALTER TABLE task_execution ADD COLUMN IF NOT EXISTS window_end_id BIGINT;
ALTER TABLE task_execution ADD COLUMN IF NOT EXISTS window_type VARCHAR(20);
ALTER TABLE validation_task ADD COLUMN IF NOT EXISTS window_type VARCHAR(20);
ALTER TABLE validation_task ADD COLUMN IF NOT EXISTS window_start_id BIGINT;
ALTER TABLE validation_task ADD COLUMN IF NOT EXISTS window_end_id BIGINT;
COMMENT ON COLUMN task_execution.window_start_id IS 'ID_RANGE 增量模式：本批次 ID 窗口起点（上次最大 ID）';
COMMENT ON COLUMN task_execution.window_end_id IS 'ID_RANGE 增量模式：本批次 ID 窗口终点（本次最大 ID）';
COMMENT ON COLUMN task_execution.window_type IS '本批次执行窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT';
COMMENT ON COLUMN validation_task.window_type IS '校验使用的窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT';
COMMENT ON COLUMN validation_task.window_start_id IS 'ID_RANGE 校验窗口起点（上次最大 ID）';
COMMENT ON COLUMN validation_task.window_end_id IS 'ID_RANGE 校验窗口终点（本次最大 ID）';

INSERT INTO system_setting(setting_key, setting_value, description) VALUES
  ('validation_auto_enabled',      'false',       'spec 022：是否启用自动校验'),
  ('validation_trigger',           'after_sync',  'spec 022：after_sync | manual_only'),
  ('validation_method',            'row_count',   'spec 022：row_count | checksum | row_count_checksum | all'),
  ('validation_row_tolerance',     '0',           'spec 022：行数容差百分比 0~100'),
  ('validation_fail_block',        'false',       'spec 022：校验失败是否阻断后续流程'),
  ('validation_revalidate',        'true',        'spec 022：失败是否自动重校验'),
  ('validation_revalidate_delay',  '30',          'spec 022：重校验延迟秒数')
ON CONFLICT (setting_key) DO NOTHING;

UPDATE system_setting
   SET setting_value = 'row_count'
 WHERE setting_key = 'validation_method'
   AND (setting_value IS NULL OR lower(trim(setting_value)) NOT IN ('row_count', 'checksum', 'row_count_checksum', 'all'));

DO $$
BEGIN
  IF to_regclass('task_validation_config') IS NOT NULL THEN
    ALTER TABLE task_validation_config
      ADD COLUMN IF NOT EXISTS method VARCHAR(30);
    ALTER TABLE task_validation_config
      ADD COLUMN IF NOT EXISTS auto_trigger BOOLEAN;
    ALTER TABLE task_validation_config
      ADD COLUMN IF NOT EXISTS block_on_fail BOOLEAN;
    ALTER TABLE task_validation_config
      ALTER COLUMN method DROP NOT NULL;
    ALTER TABLE task_validation_config
      ALTER COLUMN method DROP DEFAULT;
    COMMENT ON COLUMN task_validation_config.method
      IS '任务级校验方式；NULL=继承全局，ROW_COUNT/CHECKSUM/ROW_COUNT_CHECKSUM=覆盖全局';
    ALTER TABLE task_validation_config
      ALTER COLUMN auto_trigger DROP NOT NULL;
    ALTER TABLE task_validation_config
      ALTER COLUMN auto_trigger DROP DEFAULT;
    ALTER TABLE task_validation_config
      ALTER COLUMN block_on_fail DROP NOT NULL;
    ALTER TABLE task_validation_config
      ALTER COLUMN block_on_fail DROP DEFAULT;
    COMMENT ON COLUMN task_validation_config.auto_trigger
      IS '任务级同步后自动触发开关；NULL=继承全局 validation_auto_enabled';
    COMMENT ON COLUMN task_validation_config.block_on_fail
      IS '任务级校验失败阻断开关；NULL=继承全局 validation_fail_block';
    ALTER TABLE task_validation_config
      ADD COLUMN IF NOT EXISTS validation_lookback_hours INT;
    COMMENT ON COLUMN task_validation_config.validation_lookback_hours
      IS '任务级校验回看窗口小时；NULL=继承全局，0=只验本次窗口，>0=向前扩展N小时';
    UPDATE task_validation_config
       SET method = NULL
     WHERE method IS NOT NULL
       AND upper(trim(method)) NOT IN ('ROW_COUNT', 'CHECKSUM', 'ROW_COUNT_CHECKSUM');
  END IF;
END $$;


-- =============================================================================
-- 附录：源库字段类型 → Doris 类型对照参考（DBA 建表时使用）
-- engine 不感知类型，Doris 目标表必须使用正确类型才能 Stream Load 成功。
-- =============================================================================
--
-- ★ 通用原则
--   1. LOB 类型（CLOB/BLOB/BYTEA/IMAGE）一律排除，不支持同步，从 column 列表去掉
--   2. Oracle DATE 含时分秒，JDBC DATE 会截断；源库建视图 CAST 为 TIMESTAMP 后再同步
--   3. 时区统一 Asia/Shanghai，JDBC 连接串加 serverTimezone=Asia/Shanghai（MySQL）
--   4. 无精度 NUMBER/NUMERIC 需人工确认精度，不要用 STRING 存数值
--
-- ┌──────────────────────────┬───────────────────┬────────────────────────────┐
-- │ MySQL 类型                │ Doris 推荐类型     │ 注意事项                    │
-- ├──────────────────────────┼───────────────────┼────────────────────────────┤
-- │ TINYINT                  │ TINYINT           │ 连接串加 tinyInt1isBit=false │
-- │ TINYINT(1)               │ BOOLEAN/TINYINT   │ 取决于业务语义               │
-- │ INT / INTEGER            │ INT               │                             │
-- │ BIGINT                   │ BIGINT            │                             │
-- │ DECIMAL(p,s)             │ DECIMAL(p,s)      │                             │
-- │ FLOAT / DOUBLE           │ FLOAT / DOUBLE    │ 业务尽量用 DECIMAL           │
-- │ VARCHAR(n)               │ VARCHAR(n)        │                             │
-- │ TEXT / MEDIUMTEXT        │ STRING            │                             │
-- │ DATETIME                 │ DATETIME          │ 微秒用 DATETIME(6)           │
-- │ DATE                     │ DATE              │                             │
-- │ TIMESTAMP                │ DATETIME          │ 按服务器时区转换              │
-- │ JSON                     │ STRING            │                             │
-- │ BLOB / LONGBLOB          │ 不支持，排除        │                             │
-- └──────────────────────────┴───────────────────┴────────────────────────────┘
--
-- ┌──────────────────────────┬───────────────────┬────────────────────────────┐
-- │ PostgreSQL 类型           │ Doris 推荐类型     │ 注意事项                    │
-- ├──────────────────────────┼───────────────────┼────────────────────────────┤
-- │ INTEGER / INT4           │ INT               │                             │
-- │ BIGINT / INT8            │ BIGINT            │                             │
-- │ NUMERIC(p,s)             │ DECIMAL(p,s)      │ 无精度 NUMERIC 需人工确认    │
-- │ TEXT / VARCHAR(n)        │ STRING / VARCHAR  │                             │
-- │ BOOLEAN                  │ BOOLEAN           │ PG 原生 bool 映射正确        │
-- │ TIMESTAMP                │ DATETIME          │ 无时区，按 JVM 时区处理       │
-- │ TIMESTAMPTZ              │ DATETIME          │ 转为 Asia/Shanghai           │
-- │ UUID                     │ VARCHAR(36)       │                             │
-- │ JSONB / JSON             │ STRING            │                             │
-- │ ARRAY                    │ 不支持，排除        │ 需视图展开                   │
-- │ BYTEA                    │ 不支持，排除        │                             │
-- └──────────────────────────┴───────────────────┴────────────────────────────┘
--
-- ┌──────────────────────────┬───────────────────┬────────────────────────────┐
-- │ Oracle 类型               │ Doris 推荐类型     │ 注意事项                    │
-- ├──────────────────────────┼───────────────────┼────────────────────────────┤
-- │ NUMBER(p,0)              │ BIGINT / INT      │                             │
-- │ NUMBER(p,s)              │ DECIMAL(p,s)      │                             │
-- │ NUMBER（无精度）           │ DOUBLE            │ scale=-127 时为浮点          │
-- │ VARCHAR2(n)              │ VARCHAR(n)        │ 单位默认字节，注意长度        │
-- │ CLOB                     │ 不支持，排除        │                             │
-- │ DATE                     │ DATETIME          │ ⚠ 含时分秒！必须建视图 CAST  │
-- │ TIMESTAMP                │ DATETIME          │                             │
-- │ BLOB / RAW               │ 不支持，排除        │                             │
-- └──────────────────────────┴───────────────────┴────────────────────────────┘
--
-- ┌──────────────────────────┬───────────────────┬────────────────────────────┐
-- │ SQL Server 类型           │ Doris 推荐类型     │ 注意事项                    │
-- ├──────────────────────────┼───────────────────┼────────────────────────────┤
-- │ INT / BIGINT             │ INT / BIGINT      │                             │
-- │ TINYINT                  │ TINYINT           │ SS TINYINT 无符号(0~255)    │
-- │ DECIMAL(p,s)             │ DECIMAL(p,s)      │                             │
-- │ FLOAT                    │ DOUBLE            │ SS FLOAT 默认53位            │
-- │ MONEY                    │ DECIMAL(19,4)     │                             │
-- │ VARCHAR(n)/NVARCHAR(n)   │ VARCHAR(n)        │                             │
-- │ NVARCHAR(MAX)            │ STRING            │                             │
-- │ DATETIME / DATETIME2     │ DATETIME          │                             │
-- │ DATE                     │ DATE              │                             │
-- │ UNIQUEIDENTIFIER         │ VARCHAR(36)       │ GUID 格式                   │
-- │ BIT                      │ BOOLEAN           │                             │
-- │ TIME                     │ VARCHAR(12)       │ Doris 无 TIME 类型           │
-- │ VARBINARY / IMAGE        │ 不支持，排除        │                             │
-- └──────────────────────────┴───────────────────┴────────────────────────────┘

-- =============================================================================
-- [spec 025] 2026-04-30  DataX 引擎彻底移除
--   存量任务的 executor_type='DATAX' 全部迁移到 SEATUNNEL_CLUSTER
-- =============================================================================
UPDATE sync_task      SET executor_type='SEATUNNEL_CLUSTER' WHERE executor_type='DATAX';
UPDATE task_execution SET executor_type='SEATUNNEL_CLUSTER' WHERE executor_type='DATAX';

-- =============================================================================
-- [spec 056] 2026-05-08  字段级差异预计算（异步 + 缓存 + CSV 导出）
--   不存原值，仅 display + hash；与 etl_verify_diff 通过 ON DELETE CASCADE 联动
-- =============================================================================
CREATE TABLE IF NOT EXISTS etl_verify_diff_field (
    id                BIGSERIAL    PRIMARY KEY,
    diff_id           BIGINT       NOT NULL REFERENCES etl_verify_diff(id) ON DELETE CASCADE,
    task_id           BIGINT       NOT NULL,
    exec_id           BIGINT       NOT NULL,
    validation_run_id BIGINT       REFERENCES validation_run(id) ON DELETE SET NULL,
    column_name       VARCHAR(128) NOT NULL,
    target_column     VARCHAR(128),
    diff_kind         VARCHAR(32)  NOT NULL,    -- VALUE_DIFF | MISSING_IN_TARGET | EXTRA_IN_TARGET | EQUAL
    src_value_display TEXT,
    tgt_value_display TEXT,
    src_value_hash    VARCHAR(64),
    tgt_value_hash    VARCHAR(64),
    masked            BOOLEAN      NOT NULL DEFAULT false,
    truncated         BOOLEAN      NOT NULL DEFAULT false,
    normalized_differ BOOLEAN      NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_verify_diff_field_diff      ON etl_verify_diff_field(diff_id);
CREATE INDEX IF NOT EXISTS idx_verify_diff_field_task_exec ON etl_verify_diff_field(task_id, exec_id);
CREATE INDEX IF NOT EXISTS idx_verify_diff_field_run       ON etl_verify_diff_field(validation_run_id);
COMMENT ON TABLE etl_verify_diff_field IS 'Spec 056：异步预计算的字段级差异（display + hash，不存原值）';

ALTER TABLE etl_verify_diff_field ADD COLUMN IF NOT EXISTS validation_run_id BIGINT REFERENCES validation_run(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_verify_diff_field_run ON etl_verify_diff_field(validation_run_id);

-- =============================================================================
-- [spec 070] 2026-06-06  数据源稳定编码（source_code）自动生成
--   source_datasource 新增 source_code（机构首字母-库类型-序号），
--   作为 _etl_source_system 的稳定来源标识，替代可变的 name；
--   部分唯一索引兼容存量 NULL 行，新值唯一由唯一约束 + 序号兜底。
-- =============================================================================
ALTER TABLE source_datasource ADD COLUMN IF NOT EXISTS source_code VARCHAR(100);
CREATE UNIQUE INDEX IF NOT EXISTS idx_source_datasource_code
    ON source_datasource(source_code) WHERE source_code IS NOT NULL;
COMMENT ON COLUMN source_datasource.source_code IS 'spec 070：数据源稳定编码（机构首字母-库类型-序号），创建时系统生成、不可改';

-- [2026-08-10] 删除已废弃的业务分组维度。
-- 新建表本身不再包含 group_id/task_group；以下语句用于幂等升级旧环境。
DROP INDEX IF EXISTS idx_source_datasource_group_id;
DROP INDEX IF EXISTS idx_target_datasource_group_id;
DROP INDEX IF EXISTS idx_sync_task_group;
ALTER TABLE source_datasource DROP COLUMN IF EXISTS group_id;
ALTER TABLE target_datasource DROP COLUMN IF EXISTS group_id;
ALTER TABLE sync_task DROP COLUMN IF EXISTS group_id;
DROP TABLE IF EXISTS task_group;

-- =============================================================================
-- [external-sync-task-api] 2026-07-06  外部同步任务 API 幂等审计
--   调用方只传医疗机构编码、业务编码和源对象；后端用现有接入配置自动解析数据源。
--   external_task_request   : externalRequestId 幂等与解析审计
-- =============================================================================
CREATE TABLE IF NOT EXISTS external_task_request (
    id                    BIGSERIAL    PRIMARY KEY,
    external_request_id   VARCHAR(128) NOT NULL UNIQUE,
    caller                VARCHAR(100),
    yi_liao_jg_dm         VARCHAR(50)  NOT NULL,
    business_code         VARCHAR(50)  NOT NULL,
    source_schema         VARCHAR(100),
    source_object         VARCHAR(200) NOT NULL,
    source_object_type    VARCHAR(30),
    task_id               BIGINT       REFERENCES sync_task(id),
    status                VARCHAR(30)  NOT NULL,
    error_code            VARCHAR(80),
    error_message         TEXT,
    request_body          TEXT,
    resolved_plan         TEXT,
    external_batch_id     VARCHAR(128),
    batch_item_key        VARCHAR(256),
    batch_item_status     VARCHAR(30),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_task_batch_request (
    id                    BIGSERIAL    PRIMARY KEY,
    external_batch_id     VARCHAR(128) NOT NULL UNIQUE,
    yi_liao_jg_dm         VARCHAR(50)  NOT NULL,
    business_code         VARCHAR(50)  NOT NULL,
    request_hash          VARCHAR(128) NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    failure_policy        VARCHAR(30)  NOT NULL,
    total_count           INTEGER      NOT NULL DEFAULT 0,
    created_count         INTEGER      NOT NULL DEFAULT 0,
    existing_count        INTEGER      NOT NULL DEFAULT 0,
    failed_count          INTEGER      NOT NULL DEFAULT 0,
    request_body          TEXT,
    result_body           TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_api_client (
    id                       BIGSERIAL    PRIMARY KEY,
    client_id                VARCHAR(100) NOT NULL UNIQUE,
    client_name              VARCHAR(100) NOT NULL,
    secret_enc               TEXT         NOT NULL,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    allowed_yi_liao_jg_dm    VARCHAR(50),
    description              TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_api_request_nonce (
    id           BIGSERIAL    PRIMARY KEY,
    client_id    VARCHAR(100) NOT NULL,
    nonce        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_task_batch_operation_audit (
    id                    BIGSERIAL    PRIMARY KEY,
    external_batch_id     VARCHAR(128) NOT NULL,
    operation             VARCHAR(30)  NOT NULL,
    dry_run               BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(30)  NOT NULL,
    total_count           INTEGER      NOT NULL DEFAULT 0,
    success_count         INTEGER      NOT NULL DEFAULT 0,
    failed_count          INTEGER      NOT NULL DEFAULT 0,
    skipped_count         INTEGER      NOT NULL DEFAULT 0,
    caller                VARCHAR(100),
    client_id             VARCHAR(100),
    result_body           TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_external_task_request_task
    ON external_task_request(task_id)
    WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_external_task_request_org_biz
    ON external_task_request(yi_liao_jg_dm, business_code, source_object);
CREATE INDEX IF NOT EXISTS idx_external_task_batch_org_biz
    ON external_task_batch_request(yi_liao_jg_dm, business_code, status);
CREATE INDEX IF NOT EXISTS idx_external_task_request_batch
    ON external_task_request(external_batch_id)
    WHERE external_batch_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_task_request_batch_item
    ON external_task_request(external_batch_id, batch_item_key)
    WHERE external_batch_id IS NOT NULL AND batch_item_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_api_request_nonce
    ON external_api_request_nonce(client_id, nonce);
CREATE INDEX IF NOT EXISTS idx_external_api_request_nonce_created
    ON external_api_request_nonce(created_at);
CREATE INDEX IF NOT EXISTS idx_external_task_batch_operation_batch
    ON external_task_batch_operation_audit(external_batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_external_task_batch_operation_client
    ON external_task_batch_operation_audit(client_id, created_at DESC)
    WHERE client_id IS NOT NULL;

COMMENT ON TABLE external_task_request IS '外部任务创建请求幂等与解析审计记录';
COMMENT ON TABLE external_task_batch_request IS '外部批量任务创建请求幂等与解析审计记录';
COMMENT ON TABLE external_api_client IS '外部 API HMAC client、密钥密文和机构/业务授权范围';
COMMENT ON TABLE external_api_request_nonce IS '外部 API HMAC nonce 防重放记录';
COMMENT ON TABLE external_task_batch_operation_audit IS '外部批量任务运行/删除操作审计记录';
COMMENT ON COLUMN external_task_request.external_request_id IS '调用方幂等请求号；重复提交返回同一内部任务';
COMMENT ON COLUMN external_task_request.yi_liao_jg_dm IS '医疗机构代码，不等同 tenantId';
COMMENT ON COLUMN external_task_request.resolved_plan IS '后端解析出的源/目标/医共体合约计划 JSON';
COMMENT ON COLUMN external_task_batch_request.external_batch_id IS '外部批量请求幂等号；重复提交返回同一批结果';
COMMENT ON COLUMN external_task_batch_request.request_hash IS '批量请求业务字段 hash，用于识别 externalBatchId 复用冲突';
COMMENT ON COLUMN external_task_batch_request.result_body IS '批量创建结果 JSON';
COMMENT ON COLUMN external_task_request.external_batch_id IS '外部批量请求幂等号';
COMMENT ON COLUMN external_task_request.batch_item_key IS '批量请求内 sourceObject 稳定键，例如 schema.view';
COMMENT ON COLUMN external_task_request.batch_item_status IS '批量 item 状态：CREATED/EXISTING/FAILED/SKIPPED';
COMMENT ON COLUMN external_api_client.secret_enc IS 'AES 加密后的外部 API shared secret，禁止存明文';
COMMENT ON COLUMN external_api_client.allowed_yi_liao_jg_dm IS '允许访问的医疗机构编码；NULL 或 * 表示不限';
COMMENT ON COLUMN external_task_batch_operation_audit.result_body IS '批量运行/删除响应 JSON 快照';

-- =============================================================================
-- [spec 006] 2026-05-09  Quartz JDBC JobStore（PostgreSQL）
--   采用幂等 DDL；不使用 Quartz 官方 DROP TABLE 脚本，避免覆盖现有调度状态
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS df_etl AUTHORIZATION df_etl;

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_JOB_DETAILS (
  SCHED_NAME        VARCHAR(120) NOT NULL,
  JOB_NAME          VARCHAR(200) NOT NULL,
  JOB_GROUP         VARCHAR(200) NOT NULL,
  DESCRIPTION       VARCHAR(250) NULL,
  JOB_CLASS_NAME    VARCHAR(250) NOT NULL,
  IS_DURABLE        BOOL         NOT NULL,
  IS_NONCONCURRENT  BOOL         NOT NULL,
  IS_UPDATE_DATA    BOOL         NOT NULL,
  REQUESTS_RECOVERY BOOL         NOT NULL,
  JOB_DATA          BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_TRIGGERS (
  SCHED_NAME     VARCHAR(120) NOT NULL,
  TRIGGER_NAME   VARCHAR(200) NOT NULL,
  TRIGGER_GROUP  VARCHAR(200) NOT NULL,
  JOB_NAME       VARCHAR(200) NOT NULL,
  JOB_GROUP      VARCHAR(200) NOT NULL,
  DESCRIPTION    VARCHAR(250) NULL,
  NEXT_FIRE_TIME BIGINT       NULL,
  PREV_FIRE_TIME BIGINT       NULL,
  PRIORITY       INTEGER      NULL,
  TRIGGER_STATE  VARCHAR(16)  NOT NULL,
  TRIGGER_TYPE   VARCHAR(8)   NOT NULL,
  START_TIME     BIGINT       NOT NULL,
  END_TIME       BIGINT       NULL,
  CALENDAR_NAME  VARCHAR(200) NULL,
  MISFIRE_INSTR  SMALLINT     NULL,
  JOB_DATA       BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
    REFERENCES df_etl.QRTZ_JOB_DETAILS (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_SIMPLE_TRIGGERS (
  SCHED_NAME      VARCHAR(120) NOT NULL,
  TRIGGER_NAME    VARCHAR(200) NOT NULL,
  TRIGGER_GROUP   VARCHAR(200) NOT NULL,
  REPEAT_COUNT    BIGINT       NOT NULL,
  REPEAT_INTERVAL BIGINT       NOT NULL,
  TIMES_TRIGGERED BIGINT       NOT NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
    REFERENCES df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_CRON_TRIGGERS (
  SCHED_NAME      VARCHAR(120) NOT NULL,
  TRIGGER_NAME    VARCHAR(200) NOT NULL,
  TRIGGER_GROUP   VARCHAR(200) NOT NULL,
  CRON_EXPRESSION VARCHAR(120) NOT NULL,
  TIME_ZONE_ID    VARCHAR(80),
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
    REFERENCES df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_SIMPROP_TRIGGERS (
  SCHED_NAME    VARCHAR(120)   NOT NULL,
  TRIGGER_NAME  VARCHAR(200)   NOT NULL,
  TRIGGER_GROUP VARCHAR(200)   NOT NULL,
  STR_PROP_1    VARCHAR(512)   NULL,
  STR_PROP_2    VARCHAR(512)   NULL,
  STR_PROP_3    VARCHAR(512)   NULL,
  INT_PROP_1    INT            NULL,
  INT_PROP_2    INT            NULL,
  LONG_PROP_1   BIGINT         NULL,
  LONG_PROP_2   BIGINT         NULL,
  DEC_PROP_1    NUMERIC(13, 4) NULL,
  DEC_PROP_2    NUMERIC(13, 4) NULL,
  BOOL_PROP_1   BOOL           NULL,
  BOOL_PROP_2   BOOL           NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
    REFERENCES df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_BLOB_TRIGGERS (
  SCHED_NAME    VARCHAR(120) NOT NULL,
  TRIGGER_NAME  VARCHAR(200) NOT NULL,
  TRIGGER_GROUP VARCHAR(200) NOT NULL,
  BLOB_DATA     BYTEA        NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
    REFERENCES df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_CALENDARS (
  SCHED_NAME    VARCHAR(120) NOT NULL,
  CALENDAR_NAME VARCHAR(200) NOT NULL,
  CALENDAR      BYTEA        NOT NULL,
  PRIMARY KEY (SCHED_NAME, CALENDAR_NAME)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_PAUSED_TRIGGER_GRPS (
  SCHED_NAME    VARCHAR(120) NOT NULL,
  TRIGGER_GROUP VARCHAR(200) NOT NULL,
  PRIMARY KEY (SCHED_NAME, TRIGGER_GROUP)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_FIRED_TRIGGERS (
  SCHED_NAME        VARCHAR(120) NOT NULL,
  ENTRY_ID          VARCHAR(95)  NOT NULL,
  TRIGGER_NAME      VARCHAR(200) NOT NULL,
  TRIGGER_GROUP     VARCHAR(200) NOT NULL,
  INSTANCE_NAME     VARCHAR(200) NOT NULL,
  FIRED_TIME        BIGINT       NOT NULL,
  SCHED_TIME        BIGINT       NOT NULL,
  PRIORITY          INTEGER      NOT NULL,
  STATE             VARCHAR(16)  NOT NULL,
  JOB_NAME          VARCHAR(200) NULL,
  JOB_GROUP         VARCHAR(200) NULL,
  IS_NONCONCURRENT  BOOL         NULL,
  REQUESTS_RECOVERY BOOL         NULL,
  PRIMARY KEY (SCHED_NAME, ENTRY_ID)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_SCHEDULER_STATE (
  SCHED_NAME        VARCHAR(120) NOT NULL,
  INSTANCE_NAME     VARCHAR(200) NOT NULL,
  LAST_CHECKIN_TIME BIGINT       NOT NULL,
  CHECKIN_INTERVAL  BIGINT       NOT NULL,
  PRIMARY KEY (SCHED_NAME, INSTANCE_NAME)
);

CREATE TABLE IF NOT EXISTS df_etl.QRTZ_LOCKS (
  SCHED_NAME VARCHAR(120) NOT NULL,
  LOCK_NAME  VARCHAR(40)  NOT NULL,
  PRIMARY KEY (SCHED_NAME, LOCK_NAME)
);

CREATE INDEX IF NOT EXISTS IDX_QRTZ_J_REQ_RECOVERY
  ON df_etl.QRTZ_JOB_DETAILS (SCHED_NAME, REQUESTS_RECOVERY);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_J_GRP
  ON df_etl.QRTZ_JOB_DETAILS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_J
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_JG
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_C
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, CALENDAR_NAME);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_G
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_STATE
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_N_STATE
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_N_G_STATE
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NEXT_FIRE_TIME
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, NEXT_FIRE_TIME);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE, NEXT_FIRE_TIME);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_MISFIRE
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST_MISFIRE
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_STATE);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST_MISFIRE_GRP
  ON df_etl.QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_TRIG_INST_NAME
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_INST_JOB_REQ_RCVRY
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME, REQUESTS_RECOVERY);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_J_G
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_JG
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_T_G
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP);
CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_TG
  ON df_etl.QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);

-- alert-webhook-notification: 钉钉/企微告警渠道字段扩展（2026-06-06）
ALTER TABLE alert_channel ADD COLUMN IF NOT EXISTS secret TEXT;
ALTER TABLE alert_channel ADD COLUMN IF NOT EXISTS mentioned_mobiles TEXT;
ALTER TABLE alert_channel ADD COLUMN IF NOT EXISTS at_all BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE alert_channel ADD COLUMN IF NOT EXISTS message_format VARCHAR(20) NOT NULL DEFAULT 'text';

-- alert-rule-evaluator-completion: 告警规则适用范围 scopeValue 持久化（2026-06-06）
ALTER TABLE alert_rule ADD COLUMN IF NOT EXISTS scope_value VARCHAR(200);

-- =============================================================================
-- [message-publish] 消息发布配置与批次日志
-- =============================================================================
CREATE TABLE IF NOT EXISTS df_etl.message_publish_config (
    id                    BIGSERIAL    PRIMARY KEY,
    task_id               BIGINT       NOT NULL UNIQUE,
    enabled               BOOLEAN      NOT NULL DEFAULT false,
    channel               VARCHAR(200) NOT NULL,
    message_type          VARCHAR(50)  NOT NULL,
    topic                 VARCHAR(100) NOT NULL,
    message_key_template  VARCHAR(500),
    full_sync_mode        VARCHAR(20)  NOT NULL DEFAULT 'SKIP',
    rate_limit            INTEGER,
    page_size             INTEGER      DEFAULT 1000,
    source_system         VARCHAR(50)  DEFAULT 'HIS',
    tenant_id             VARCHAR(50)  DEFAULT '0',
    field_mapping_json    TEXT,
    stream_max_len        INTEGER      DEFAULT 10000,
    send_truncate_signal  BOOLEAN      DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- CREATE TABLE IF NOT EXISTS 不会修正已存在列的默认值，显式收口为安全默认 SKIP。
ALTER TABLE df_etl.message_publish_config
    ALTER COLUMN full_sync_mode SET DEFAULT 'SKIP';

CREATE INDEX IF NOT EXISTS idx_mpc_task_id
    ON df_etl.message_publish_config(task_id);

CREATE TABLE IF NOT EXISTS df_etl.message_publish_log (
    id                BIGSERIAL    PRIMARY KEY,
    task_id           BIGINT       NOT NULL,
    batch_id          BIGINT,
    channel           VARCHAR(200) NOT NULL,
    topic             VARCHAR(100) NOT NULL,
    message_count     INTEGER,
    status            VARCHAR(20)  NOT NULL,
    error_message     TEXT,
    publish_time      TIMESTAMPTZ  NOT NULL,
    data_scope        VARCHAR(20),
    window_start      TIMESTAMPTZ,
    window_end        TIMESTAMPTZ,
    sample_messages   TEXT,
    retry_attempts    INTEGER      NOT NULL DEFAULT 0,
    next_retry_time   TIMESTAMPTZ
);

ALTER TABLE df_etl.message_publish_log
    ADD COLUMN IF NOT EXISTS retry_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE df_etl.message_publish_log
    ADD COLUMN IF NOT EXISTS next_retry_time TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_mpl_task_id
    ON df_etl.message_publish_log(task_id);
CREATE INDEX IF NOT EXISTS idx_mpl_batch_id
    ON df_etl.message_publish_log(batch_id);
CREATE INDEX IF NOT EXISTS idx_mpl_publish_time
    ON df_etl.message_publish_log(publish_time);
CREATE INDEX IF NOT EXISTS idx_mpl_task_status_batch
    ON df_etl.message_publish_log(task_id, status, batch_id);
CREATE INDEX IF NOT EXISTS idx_mpl_recovery_scan
    ON df_etl.message_publish_log(next_retry_time, publish_time, id)
    WHERE status IN ('PENDING', 'RUNNING', 'WAIT_RETRY') AND batch_id > 0;

COMMENT ON TABLE df_etl.message_publish_config IS '消息发布配置 — 每个同步任务的消息发布参数';
COMMENT ON COLUMN df_etl.message_publish_config.full_sync_mode IS '全量同步模式：ALL/SKIP/NOTIFY_ONLY，默认 SKIP';
COMMENT ON TABLE df_etl.message_publish_log IS '消息发布日志 — 记录每次发布操作的状态';
COMMENT ON COLUMN df_etl.message_publish_log.status IS '发布状态：PENDING/RUNNING/SUCCESS/FAILED/PARTIAL/SKIPPED/WAIT_RETRY/FAILED_FINAL';
COMMENT ON COLUMN df_etl.message_publish_log.retry_attempts IS '尚未生成逐条消息前的运行级恢复尝试次数';
COMMENT ON COLUMN df_etl.message_publish_log.next_retry_time IS '运行级恢复的下一次允许领取时间';

-- =============================================================================
-- [message-send-record] 2026-06-18  dfetl 本地逐条消息发送记录
--   记录生产者侧 RabbitMQ 发送闭环：发送前 SENDING，confirm ack 后 SENT，
--   nack/mandatory return/同步发送异常后 SEND_FAILED。
-- =============================================================================
CREATE TABLE IF NOT EXISTS df_etl.message_send_record (
    id                   BIGSERIAL     PRIMARY KEY,
    message_id           VARCHAR(64)   NOT NULL,
    task_id              BIGINT,
    batch_id             BIGINT,
    publish_log_id       BIGINT,
    channel_mode         VARCHAR(32)   NOT NULL,
    exchange_name        VARCHAR(128),
    route_key            VARCHAR(128)  NOT NULL,
    topic                VARCHAR(128)  NOT NULL,
    message_key          VARCHAR(256),
    business_key         VARCHAR(256),
    tenant_id            VARCHAR(64),
    source_system        VARCHAR(128),
    trace_id             VARCHAR(128),
    payload_type         VARCHAR(256)  NOT NULL DEFAULT 'com.dfygt.dfetl.server.service.publish.EtlMessage',
    message_json         TEXT          NOT NULL,
    payload_json         TEXT,
    headers_json         TEXT,
    send_status          VARCHAR(32)   NOT NULL,
    send_attempts        INTEGER       NOT NULL DEFAULT 0,
    send_start_time      TIMESTAMPTZ,
    broker_confirm_time  TIMESTAMPTZ,
    sent_time            TIMESTAMPTZ,
    next_retry_time      TIMESTAMPTZ,
    last_error           TEXT,
    external_record_status VARCHAR(32),
    external_record_time TIMESTAMPTZ,
    external_record_error TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_status VARCHAR(32);
ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_time TIMESTAMPTZ;
ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_error TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_message_send_record_message_id
    ON df_etl.message_send_record(message_id);
CREATE INDEX IF NOT EXISTS idx_msr_task_batch
    ON df_etl.message_send_record(task_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_msr_topic
    ON df_etl.message_send_record(topic);
CREATE INDEX IF NOT EXISTS idx_msr_route_key
    ON df_etl.message_send_record(route_key);
CREATE INDEX IF NOT EXISTS idx_msr_message_key
    ON df_etl.message_send_record(message_key);
CREATE INDEX IF NOT EXISTS idx_msr_send_status
    ON df_etl.message_send_record(send_status);
CREATE INDEX IF NOT EXISTS idx_msr_sent_time
    ON df_etl.message_send_record(sent_time);
CREATE INDEX IF NOT EXISTS idx_msr_external_record_status
    ON df_etl.message_send_record(external_record_status);
CREATE INDEX IF NOT EXISTS idx_msr_recovery_sending
    ON df_etl.message_send_record(send_start_time, id)
    WHERE channel_mode = 'RABBITMQ' AND send_status = 'SENDING';
CREATE INDEX IF NOT EXISTS idx_msr_recovery_retry
    ON df_etl.message_send_record(next_retry_time, id)
    WHERE channel_mode = 'RABBITMQ' AND send_status IN ('SEND_FAILED', 'WAIT_RETRY');
CREATE INDEX IF NOT EXISTS idx_msr_publish_log_status
    ON df_etl.message_send_record(publish_log_id, send_status);

COMMENT ON TABLE df_etl.message_send_record IS 'dfetl 本地逐条消息发送记录，记录生产者侧 RabbitMQ 发送闭环';
COMMENT ON COLUMN df_etl.message_send_record.message_id IS '消息 ID，全链路唯一';
COMMENT ON COLUMN df_etl.message_send_record.task_id IS '关联同步任务 ID';
COMMENT ON COLUMN df_etl.message_send_record.batch_id IS '关联同步批次 ID；重发时使用新的负数批次 ID';
COMMENT ON COLUMN df_etl.message_send_record.publish_log_id IS '关联批次级 message_publish_log ID';
COMMENT ON COLUMN df_etl.message_send_record.channel_mode IS '投递通道：RABBITMQ/REDIS';
COMMENT ON COLUMN df_etl.message_send_record.exchange_name IS 'RabbitMQ exchange';
COMMENT ON COLUMN df_etl.message_send_record.route_key IS 'RabbitMQ routing key / 消息 routeKey';
COMMENT ON COLUMN df_etl.message_send_record.topic IS '消息 body.topic';
COMMENT ON COLUMN df_etl.message_send_record.message_key IS '消息业务唯一键';
COMMENT ON COLUMN df_etl.message_send_record.business_key IS 'headers.businessKey';
COMMENT ON COLUMN df_etl.message_send_record.message_json IS '实际发送到 RabbitMQ 的完整 JSON body';
COMMENT ON COLUMN df_etl.message_send_record.payload_json IS 'body.payload JSON';
COMMENT ON COLUMN df_etl.message_send_record.headers_json IS 'body.headers JSON';
COMMENT ON COLUMN df_etl.message_send_record.send_status IS '发送状态：SENDING/SENT/SEND_FAILED/WAIT_RETRY/FAILED_FINAL';
COMMENT ON COLUMN df_etl.message_send_record.send_attempts IS '发送尝试次数，同一 messageId 重试时递增';
COMMENT ON COLUMN df_etl.message_send_record.broker_confirm_time IS 'RabbitMQ confirm/return 回写时间';
COMMENT ON COLUMN df_etl.message_send_record.sent_time IS 'RabbitMQ confirm ack 成功时间';
COMMENT ON COLUMN df_etl.message_send_record.last_error IS '最后一次发送失败原因';
COMMENT ON COLUMN df_etl.message_send_record.external_record_status IS '外部医共体 msg_send 写入状态：WAIT_SEND/SENT/SEND_FAILED';
COMMENT ON COLUMN df_etl.message_send_record.external_record_time IS '外部医共体 msg_send 状态更新时间';
COMMENT ON COLUMN df_etl.message_send_record.external_record_error IS '外部医共体 msg_send 最后写入错误';

-- =============================================================================
-- 2026-08-05  医共体标准模型与机构数据集路由（Spec 101）
-- =============================================================================

CREATE TABLE IF NOT EXISTS dfetl_dataset (
    id                         BIGSERIAL      PRIMARY KEY,
    medical_dataset_id         VARCHAR(64)    NOT NULL,
    dataset_code               VARCHAR(100)   NOT NULL,
    dataset_name               VARCHAR(200),
    contract_hash              VARCHAR(128)   NOT NULL,
    dataset_status             VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    last_synced_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),

    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_dfetl_dataset_medical_id UNIQUE (medical_dataset_id),
    CONSTRAINT ck_dfetl_dataset_status CHECK (dataset_status IN ('ACTIVE', 'VOID'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_dataset_code_ci
    ON dfetl_dataset(lower(dataset_code));
CREATE INDEX IF NOT EXISTS idx_dfetl_dataset_status
    ON dfetl_dataset(dataset_status, dataset_code);

CREATE TABLE IF NOT EXISTS dfetl_sync_policy (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    write_mode VARCHAR(20) NOT NULL DEFAULT 'TRUNCATE',
    sync_template VARCHAR(30) NOT NULL DEFAULT 'FULL_ONLY',
    incremental_field VARCHAR(100),
    increment_mode VARCHAR(20) NOT NULL DEFAULT 'TIME_FIELD',
    upper_bound_strategy VARCHAR(30) NOT NULL DEFAULT 'CURRENT_TIME',
    upper_bound_delay_minutes INTEGER NOT NULL DEFAULT 5,
    lookback_seconds INTEGER NOT NULL DEFAULT 0,
    reader_parallelism INTEGER NOT NULL DEFAULT 4,
    fetch_size INTEGER,
    rate_limit INTEGER NOT NULL DEFAULT 0,
    schedule_enabled BOOLEAN NOT NULL DEFAULT true,
    schedule_mode VARCHAR(30) NOT NULL DEFAULT 'EVERY_N_HOURS',
    schedule_interval_hours INTEGER DEFAULT 4,
    schedule_cron VARCHAR(128),
    schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    policy_revision BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_sync_numbers CHECK (
        upper_bound_delay_minutes >= 0 AND lookback_seconds >= 0
        AND reader_parallelism BETWEEN 1 AND 64
        AND (fetch_size IS NULL OR fetch_size > 0) AND rate_limit >= 0),
    CONSTRAINT ck_dfetl_sync_schedule CHECK (
        (NOT schedule_enabled AND schedule_mode = 'MANUAL')
        OR (schedule_enabled AND schedule_mode = 'EVERY_N_HOURS' AND schedule_interval_hours > 0)
        OR (schedule_enabled AND schedule_mode = 'ADVANCED' AND length(trim(schedule_cron)) > 0))
);

CREATE TABLE IF NOT EXISTS dfetl_validation_policy (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    inherit_global BOOLEAN NOT NULL DEFAULT true,
    enabled BOOLEAN NOT NULL DEFAULT false,
    trigger_mode VARCHAR(30) NOT NULL DEFAULT 'AFTER_SYNC',
    validation_method VARCHAR(30) NOT NULL DEFAULT 'ROW_COUNT',
    row_tolerance NUMERIC(8,4) NOT NULL DEFAULT 0,
    fail_block BOOLEAN NOT NULL DEFAULT false,
    revalidate_enabled BOOLEAN NOT NULL DEFAULT true,
    revalidate_delay INTEGER NOT NULL DEFAULT 30,
    lookback_hours INTEGER NOT NULL DEFAULT 2,
    policy_revision BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_validation_tolerance CHECK (row_tolerance BETWEEN 0 AND 100),
    CONSTRAINT ck_dfetl_validation_numbers CHECK (revalidate_delay >= 0 AND lookback_hours BETWEEN 0 AND 168)
);

CREATE TABLE IF NOT EXISTS dfetl_message_policy (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT false,
    transport VARCHAR(30) NOT NULL DEFAULT 'RABBITMQ',
    full_sync_mode VARCHAR(30) NOT NULL DEFAULT 'ALL',
    rate_limit INTEGER NOT NULL DEFAULT 1000,
    routing_key VARCHAR(100),
    topic VARCHAR(100),
    key_template VARCHAR(500),
    page_size INTEGER NOT NULL DEFAULT 1000,
    tenant_id VARCHAR(50) NOT NULL DEFAULT '0',
    source_system VARCHAR(50) NOT NULL DEFAULT 'HIS',
    policy_revision BIGINT NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_message_numbers CHECK (rate_limit >= 0 AND page_size > 0),
    CONSTRAINT ck_dfetl_message_route CHECK (NOT enabled OR length(trim(routing_key)) > 0)
);

CREATE TABLE IF NOT EXISTS dfetl_field (
    id                         BIGSERIAL      PRIMARY KEY,
    dataset_id                 BIGINT         NOT NULL REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    medical_field_id           VARCHAR(64)    NOT NULL,
    field_code                 VARCHAR(100)   NOT NULL,
    target_field_code          VARCHAR(100)   NOT NULL,
    field_name                 VARCHAR(200),
    field_order                INTEGER,
    standard_type              VARCHAR(30),
    standard_format            VARCHAR(100),
    doris_type                 VARCHAR(100)   NOT NULL,
    primary_key                BOOLEAN        NOT NULL DEFAULT false,
    required_by_standard       BOOLEAN        NOT NULL DEFAULT false,
    value_domain_code          VARCHAR(100),
    standard_version           VARCHAR(50),
    field_status               VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_dfetl_field_medical_id UNIQUE (dataset_id, medical_field_id),
    CONSTRAINT ck_dfetl_field_status CHECK (field_status IN ('ACTIVE', 'VOID'))
);

CREATE INDEX IF NOT EXISTS idx_dfetl_field_dataset
    ON dfetl_field(dataset_id, field_status, field_order);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_field_active_code_ci
    ON dfetl_field(dataset_id, lower(field_code)) WHERE field_status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS institution_dataset_route (
    id                          BIGSERIAL      PRIMARY KEY,
    institution_id              BIGINT         NOT NULL REFERENCES df_etl.institution(id),
    dataset_id                  BIGINT         NOT NULL REFERENCES dfetl_dataset(id),
    source_datasource_id        BIGINT         NOT NULL REFERENCES source_datasource(id),
    source_schema               VARCHAR(100)   NOT NULL,
    source_object               VARCHAR(200)   NOT NULL,
    source_object_type          VARCHAR(30)    NOT NULL DEFAULT 'VIEW',
    target_datasource_id        BIGINT         NOT NULL REFERENCES target_datasource(id),
    target_table                VARCHAR(200)   NOT NULL,

    enabled                     BOOLEAN        NOT NULL DEFAULT false,
    validation_status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    validation_summary          TEXT,
    validation_details_json     TEXT,
    last_validated_at           TIMESTAMPTZ,
    validated_contract_hash     VARCHAR(128),
    validated_route_revision    BIGINT,
    route_revision              BIGINT         NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_institution_dataset_route_source_type
        CHECK (source_object_type IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW')),
    CONSTRAINT ck_institution_dataset_route_validation_status
        CHECK (validation_status IN ('PENDING', 'PASSED', 'FAILED')),
    CONSTRAINT ck_institution_dataset_route_revision_positive CHECK (route_revision > 0),
    CONSTRAINT ck_institution_dataset_route_enable_requires_validation
        CHECK (NOT enabled OR (
            validation_status = 'PASSED'
            AND last_validated_at IS NOT NULL
            AND validated_route_revision = route_revision
            AND validated_contract_hash IS NOT NULL
        ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_institution_dataset_route_active
    ON institution_dataset_route(institution_id, dataset_id) WHERE enabled = true;
CREATE INDEX IF NOT EXISTS idx_institution_dataset_route_dataset
    ON institution_dataset_route(dataset_id);
CREATE INDEX IF NOT EXISTS idx_institution_dataset_route_source
    ON institution_dataset_route(source_datasource_id, source_schema, source_object);
CREATE INDEX IF NOT EXISTS idx_institution_dataset_route_target
    ON institution_dataset_route(target_datasource_id, target_table);

CREATE TABLE IF NOT EXISTS dfetl_precheck_run (
    id                  BIGSERIAL PRIMARY KEY,
    route_id            BIGINT NOT NULL REFERENCES institution_dataset_route(id) ON DELETE CASCADE,
    dataset_id          BIGINT NOT NULL REFERENCES dfetl_dataset(id),
    institution_id      BIGINT NOT NULL REFERENCES df_etl.institution(id),
    task_id              BIGINT REFERENCES sync_task(id) ON DELETE SET NULL,
    execution_id         BIGINT REFERENCES task_execution(id) ON DELETE SET NULL,
    retry_of_run_id      BIGINT REFERENCES dfetl_precheck_run(id) ON DELETE SET NULL,
    run_type             VARCHAR(30) NOT NULL,
    scope_type           VARCHAR(30) NOT NULL,
    window_start         TIMESTAMPTZ,
    window_end           TIMESTAMPTZ,
    window_start_id      BIGINT,
    window_end_id        BIGINT,
    contract_hash        VARCHAR(128) NOT NULL,
    route_revision       BIGINT NOT NULL,
    target_schema_hash   VARCHAR(128),
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    stage                VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    progress_percent     SMALLINT NOT NULL DEFAULT 0,
    engine_job_id        VARCHAR(128),
    staging_table        VARCHAR(200),
    source_rows          BIGINT NOT NULL DEFAULT 0,
    loaded_rows          BIGINT NOT NULL DEFAULT 0,
    checked_rows         BIGINT NOT NULL DEFAULT 0,
    issue_count          BIGINT NOT NULL DEFAULT 0,
    scanned_rows         BIGINT NOT NULL DEFAULT 0,
    passed_rows          BIGINT NOT NULL DEFAULT 0,
    blocker_rows         BIGINT NOT NULL DEFAULT 0,
    warning_rows         BIGINT NOT NULL DEFAULT 0,
    fixed_issue_rows     BIGINT NOT NULL DEFAULT 0,
    error_message        TEXT,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    raw_cleaned_at       TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_precheck_run_type
        CHECK (run_type IN ('ROUTE_FULL', 'EXECUTION_WINDOW')),
    CONSTRAINT ck_dfetl_precheck_scope
        CHECK (scope_type IN ('FULL', 'FULL_THEN_INCREMENT', 'INCREMENT')),
    CONSTRAINT ck_dfetl_precheck_status
        CHECK (status IN ('PENDING', 'LOADING', 'VALIDATING', 'HAS_ERRORS', 'PASSED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_dfetl_precheck_stage
        CHECK (stage IN ('PREPARING', 'LOADING', 'VALIDATING', 'FINALIZING', 'COMPLETED')),
    CONSTRAINT ck_dfetl_precheck_progress
        CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_dfetl_precheck_counts CHECK (
        scanned_rows >= 0 AND passed_rows >= 0 AND blocker_rows >= 0
        AND warning_rows >= 0 AND fixed_issue_rows >= 0
        AND source_rows >= 0 AND loaded_rows >= 0 AND checked_rows >= 0
        AND issue_count >= 0),
    CONSTRAINT ck_dfetl_precheck_revision CHECK (route_revision > 0)
);

CREATE TABLE IF NOT EXISTS dfetl_precheck_issue (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES dfetl_precheck_run(id) ON DELETE CASCADE,
    issue_key           VARCHAR(128) NOT NULL,
    source_row_hash     VARCHAR(64) NOT NULL,
    business_pk_json    TEXT,
    raw_row_json        TEXT NOT NULL,
    field_code          VARCHAR(100),
    field_name          VARCHAR(200),
    source_column       VARCHAR(100),
    target_column       VARCHAR(100),
    error_type          VARCHAR(50) NOT NULL,
    standard_rule       VARCHAR(500),
    raw_value           TEXT,
    normalized_value    TEXT,
    error_message       TEXT NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    remediation_status  VARCHAR(20) NOT NULL DEFAULT 'NEW',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dfetl_precheck_issue_key UNIQUE (run_id, issue_key),
    CONSTRAINT ck_dfetl_precheck_issue_severity
        CHECK (severity IN ('BLOCKER', 'WARNING')),
    CONSTRAINT ck_dfetl_precheck_remediation
        CHECK (remediation_status IN ('NEW', 'STILL_OPEN', 'FIXED'))
);

CREATE TABLE IF NOT EXISTS dfetl_precheck_export (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES dfetl_precheck_run(id) ON DELETE CASCADE,
    request_key         VARCHAR(128) NOT NULL,
    filter_snapshot     JSONB NOT NULL DEFAULT '{}'::jsonb,
    export_format       VARCHAR(10) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    file_manifest       JSONB NOT NULL DEFAULT '[]'::jsonb,
    row_count           BIGINT NOT NULL DEFAULT 0,
    byte_count          BIGINT NOT NULL DEFAULT 0,
    requested_by        VARCHAR(100) NOT NULL,
    error_message       TEXT,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_precheck_export_format
        CHECK (export_format IN ('CSV', 'XLSX')),
    CONSTRAINT ck_dfetl_precheck_export_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_dfetl_precheck_export_counts
        CHECK (row_count >= 0 AND byte_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_route
    ON dfetl_precheck_run(route_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_execution
    ON dfetl_precheck_run(execution_id) WHERE execution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_status
    ON dfetl_precheck_run(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_raw_cleanup
    ON dfetl_precheck_run(status, finished_at)
    WHERE raw_cleaned_at IS NULL AND status IN ('PASSED', 'HAS_ERRORS');
CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_precheck_run_active
    ON dfetl_precheck_run(route_id, contract_hash, route_revision)
    WHERE status IN ('PENDING', 'LOADING', 'VALIDATING');
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_issue_run
    ON dfetl_precheck_issue(run_id, severity, remediation_status, id);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_issue_row
    ON dfetl_precheck_issue(run_id, source_row_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_precheck_export_request
    ON dfetl_precheck_export(request_key);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_run
    ON dfetl_precheck_export(run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_status
    ON dfetl_precheck_export(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_expiry
    ON dfetl_precheck_export(status, expires_at)
    WHERE expires_at IS NOT NULL;

COMMENT ON TABLE dfetl_dataset IS '医共体有效标准数据集只读快照';
COMMENT ON TABLE dfetl_sync_policy IS '标准数据集共享的同步、性能和调度策略';
COMMENT ON TABLE dfetl_validation_policy IS '标准数据集共享的校验策略';
COMMENT ON TABLE dfetl_message_policy IS '标准数据集共享的消息发布策略';
COMMENT ON TABLE dfetl_field IS '医共体标准字段当前快照';
COMMENT ON TABLE institution_dataset_route IS '机构标准数据集到实际源对象和目标表的已验证路由';
COMMENT ON TABLE dfetl_precheck_run IS 'Doris STRING 暂存层数据预检运行及小型汇总';
COMMENT ON COLUMN dfetl_precheck_run.raw_cleaned_at IS '该运行在 Doris STRING 原始暂存中的精确批次清理完成时间';
COMMENT ON TABLE dfetl_precheck_issue IS '历史数据预检问题明细；新暂存层运行的问题明细存储在 Doris';
COMMENT ON TABLE dfetl_precheck_export IS '数据预检问题异步导出任务和审计元数据';


ALTER SCHEMA df_etl OWNER TO df_etl;
ALTER TABLE df_etl.message_send_record OWNER TO df_etl;
ALTER SEQUENCE IF EXISTS df_etl.message_send_record_id_seq OWNER TO df_etl;

GRANT USAGE ON SCHEMA df_etl TO df_etl;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE df_etl.message_send_record TO df_etl;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE df_etl.message_send_record_id_seq TO df_etl;

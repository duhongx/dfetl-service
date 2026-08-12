-- =============================================================================
-- 消息发布配置与日志表
-- spec: redis-message-publish
-- 执行前确认 schema df_etl 已存在
-- =============================================================================

-- -----------------------------------------------------------------------------
-- message_publish_config: 消息发布配置（每个同步任务一条）
-- -----------------------------------------------------------------------------
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

CREATE INDEX IF NOT EXISTS idx_mpc_task_id
    ON df_etl.message_publish_config(task_id);

COMMENT ON TABLE df_etl.message_publish_config IS '消息发布配置 — 每个同步任务的 Redis 消息发布参数';
COMMENT ON COLUMN df_etl.message_publish_config.task_id IS '关联同步任务 ID（唯一）';
COMMENT ON COLUMN df_etl.message_publish_config.enabled IS '是否启用消息发布';
COMMENT ON COLUMN df_etl.message_publish_config.channel IS 'Redis Pub/Sub channel';
COMMENT ON COLUMN df_etl.message_publish_config.message_type IS '消息类型，如 MFN^ZB3';
COMMENT ON COLUMN df_etl.message_publish_config.topic IS '业务主题，如 base.department';
COMMENT ON COLUMN df_etl.message_publish_config.message_key_template IS 'messageKey 模板，如 {yljgdm}:{ksdm}';
COMMENT ON COLUMN df_etl.message_publish_config.full_sync_mode IS '全量同步模式：ALL/SKIP/NOTIFY_ONLY';
COMMENT ON COLUMN df_etl.message_publish_config.rate_limit IS '限速（条/秒），null 表示不限速';
COMMENT ON COLUMN df_etl.message_publish_config.page_size IS '全量分页大小';
COMMENT ON COLUMN df_etl.message_publish_config.source_system IS '来源系统标识';
COMMENT ON COLUMN df_etl.message_publish_config.tenant_id IS '租户 ID';
COMMENT ON COLUMN df_etl.message_publish_config.field_mapping_json IS '手动字段映射 JSON（退化方案）';
COMMENT ON COLUMN df_etl.message_publish_config.stream_max_len IS 'Redis Stream MAXLEN 限制';
COMMENT ON COLUMN df_etl.message_publish_config.send_truncate_signal IS '全量 ALL 模式是否发送 TRUNCATE 信号';

-- -----------------------------------------------------------------------------
-- message_publish_log: 消息发布日志
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS df_etl.message_publish_log (
    id              BIGSERIAL    PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    batch_id        BIGINT,
    channel         VARCHAR(200) NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    message_count   INTEGER,
    status          VARCHAR(20)  NOT NULL,
    error_message   TEXT,
    publish_time    TIMESTAMPTZ  NOT NULL,
    data_scope      VARCHAR(20),
    window_start    TIMESTAMPTZ,
    window_end      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_mpl_task_id
    ON df_etl.message_publish_log(task_id);

CREATE INDEX IF NOT EXISTS idx_mpl_batch_id
    ON df_etl.message_publish_log(batch_id);

CREATE INDEX IF NOT EXISTS idx_mpl_publish_time
    ON df_etl.message_publish_log(publish_time);

COMMENT ON TABLE df_etl.message_publish_log IS '消息发布日志 — 记录每次发布操作的状态';
COMMENT ON COLUMN df_etl.message_publish_log.task_id IS '关联同步任务 ID';
COMMENT ON COLUMN df_etl.message_publish_log.batch_id IS '批次 ID';
COMMENT ON COLUMN df_etl.message_publish_log.channel IS 'Redis Pub/Sub channel';
COMMENT ON COLUMN df_etl.message_publish_log.topic IS '业务主题';
COMMENT ON COLUMN df_etl.message_publish_log.message_count IS '发送消息数';
COMMENT ON COLUMN df_etl.message_publish_log.status IS '发布状态：SUCCESS/FAILED/PARTIAL';
COMMENT ON COLUMN df_etl.message_publish_log.error_message IS '错误信息';
COMMENT ON COLUMN df_etl.message_publish_log.publish_time IS '发布时间';
COMMENT ON COLUMN df_etl.message_publish_log.data_scope IS '数据范围：INCREMENTAL/FULL';
COMMENT ON COLUMN df_etl.message_publish_log.window_start IS '增量窗口起始时间';
COMMENT ON COLUMN df_etl.message_publish_log.window_end IS '增量窗口结束时间';

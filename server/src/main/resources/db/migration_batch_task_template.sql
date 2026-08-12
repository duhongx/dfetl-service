-- 批量任务模板（区域医共体场景：多个医疗机构的相同视图同步到同一张 Doris 目标表）
-- 执行前确认 schema df_etl 已存在

CREATE TABLE IF NOT EXISTS df_etl.batch_task_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    -- 目标配置
    target_datasource_id BIGINT NOT NULL,
    target_table VARCHAR(200) NOT NULL,
    -- 源端配置（模板级，各机构共用）
    view_name VARCHAR(200) NOT NULL,
    source_schema VARCHAR(100),
    -- 同步配置
    data_scope VARCHAR(20) DEFAULT 'INCREMENTAL',
    increment_mode VARCHAR(20) DEFAULT 'TIME_FIELD',
    incremental_field VARCHAR(100),
    sync_mode VARCHAR(20) DEFAULT 'UPSERT',
    upsert_keys TEXT,                    -- JSON 数组
    parallelism INT DEFAULT 1,
    cron_expression VARCHAR(100),
    -- 校验配置
    validation_method VARCHAR(20) DEFAULT 'CHECKSUM',
    validation_drift_cron VARCHAR(100),
    validation_lookback_hours INT DEFAULT 24,
    auto_trigger BOOLEAN DEFAULT TRUE,
    -- Doris 配置
    doris_table_model VARCHAR(20) DEFAULT 'UNIQUE_KEY',
    enable_doris_merge BOOLEAN DEFAULT FALSE,
    soft_delete_field VARCHAR(100),
    delete_sign_value VARCHAR(20) DEFAULT '1',
    sequence_col VARCHAR(100),
    -- 元数据
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS df_etl.batch_task_template_source (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES df_etl.batch_task_template(id),
    source_datasource_id BIGINT NOT NULL,
    source_schema VARCHAR(100),          -- 覆盖模板的 schema（可选）
    static_filter VARCHAR(1000),         -- 该机构特有的过滤条件
    institution_name VARCHAR(200),       -- 机构名称（展示用）
    institution_code VARCHAR(50),        -- 机构代码
    enabled BOOLEAN DEFAULT TRUE,
    sync_task_id BIGINT,                 -- 关联已创建的 sync_task（创建后回填）
    created_at TIMESTAMP DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_batch_tpl_source_template_id
    ON df_etl.batch_task_template_source(template_id);

CREATE INDEX IF NOT EXISTS idx_batch_tpl_source_sync_task_id
    ON df_etl.batch_task_template_source(sync_task_id)
    WHERE sync_task_id IS NOT NULL;

COMMENT ON TABLE df_etl.batch_task_template IS '批量任务模板 — 区域医共体多机构同步统一配置';
COMMENT ON TABLE df_etl.batch_task_template_source IS '批量任务模板关联的数据源（每条代表一个医疗机构）';

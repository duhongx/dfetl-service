-- =============================================================================
-- 2026-07-31  DFETL 数据集采集配置
-- =============================================================================

CREATE TABLE IF NOT EXISTS dfetl_dataset (
    id                          BIGSERIAL      PRIMARY KEY,

    institution_id              BIGINT         REFERENCES institution(id),
    org_code                    VARCHAR(80)    NOT NULL,
    source_system               VARCHAR(50)    NOT NULL DEFAULT 'HIS',
    business_code               VARCHAR(50)    NOT NULL DEFAULT 'HIS',

    dataset_code                VARCHAR(100)   NOT NULL,
    dataset_name                VARCHAR(200),
    dataset_type                VARCHAR(50)    NOT NULL DEFAULT 'MEDICAL',
    dataset_version             VARCHAR(50),
    contract_hash               VARCHAR(128),

    source_datasource_id        BIGINT         NOT NULL REFERENCES source_datasource(id),
    source_schema               VARCHAR(100),
    source_object               VARCHAR(200)   NOT NULL,
    source_object_type          VARCHAR(30)    NOT NULL DEFAULT 'VIEW',

    target_datasource_id        BIGINT         NOT NULL REFERENCES target_datasource(id),
    target_table                VARCHAR(200)   NOT NULL,

    collect_enabled             BOOLEAN        NOT NULL DEFAULT true,
    collect_status              VARCHAR(30)    NOT NULL DEFAULT 'ACTIVE',

    sync_template               VARCHAR(30)    NOT NULL DEFAULT 'FULL_THEN_INCREMENT',
    write_mode                  VARCHAR(20)    NOT NULL DEFAULT 'UPSERT',
    incremental_field           VARCHAR(100),
    increment_mode              VARCHAR(20)    DEFAULT 'TIME_FIELD',
    upper_bound_strategy        VARCHAR(30)    DEFAULT 'CURRENT_TIME',
    upper_bound_delay_minutes   INTEGER        DEFAULT 5,
    lookback_seconds            INTEGER        DEFAULT 0,
    upsert_keys                 TEXT           NOT NULL,

    schedule_enabled            BOOLEAN        NOT NULL DEFAULT true,
    schedule_mode               VARCHAR(30)    NOT NULL DEFAULT 'EVERY_N_HOURS',
    schedule_interval_hours     INTEGER        DEFAULT 4,
    schedule_cron               VARCHAR(128),
    schedule_timezone           VARCHAR(64)    DEFAULT 'Asia/Shanghai',

    validation_enabled          BOOLEAN        NOT NULL DEFAULT true,
    validation_method           VARCHAR(30)    NOT NULL DEFAULT 'ROW_COUNT_CHECKSUM',
    validation_checksum_scope   VARCHAR(20)    NOT NULL DEFAULT 'WINDOW',
    validation_auto_trigger     BOOLEAN        NOT NULL DEFAULT true,
    validation_block_on_fail    BOOLEAN        DEFAULT false,
    validation_drift_cron       VARCHAR(128),

    message_enabled             BOOLEAN        NOT NULL DEFAULT true,
    message_transport           VARCHAR(30)    DEFAULT 'RABBITMQ',
    message_full_sync_mode      VARCHAR(30)    DEFAULT 'ALL',
    message_rate_limit          INTEGER        DEFAULT 1000,
    message_topic               VARCHAR(100),
    message_key_template        VARCHAR(500),

    owner_name                  VARCHAR(100),
    owner_source                VARCHAR(100),
    remark                      TEXT,

    config_version              VARCHAR(50)    NOT NULL DEFAULT 'V1',
    extra_json                  TEXT,

    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_dfetl_dataset UNIQUE (org_code, source_system, business_code, dataset_code)
);

CREATE INDEX IF NOT EXISTS idx_dfetl_dataset_code
    ON dfetl_dataset(dataset_code);
CREATE INDEX IF NOT EXISTS idx_dfetl_dataset_source_object
    ON dfetl_dataset(source_datasource_id, source_schema, source_object);
CREATE INDEX IF NOT EXISTS idx_dfetl_dataset_collect
    ON dfetl_dataset(collect_enabled, collect_status);

ALTER TABLE dfetl_dataset
    ADD COLUMN IF NOT EXISTS validation_drift_cron VARCHAR(128);

CREATE TABLE IF NOT EXISTS dfetl_field (
    id                          BIGSERIAL      PRIMARY KEY,
    dataset_id                  BIGINT         NOT NULL REFERENCES dfetl_dataset(id) ON DELETE CASCADE,

    field_code                  VARCHAR(100)   NOT NULL,
    field_name                  VARCHAR(200),
    field_order                 INTEGER,

    source_column               VARCHAR(200),
    target_column               VARCHAR(200)   NOT NULL,
    target_type                 VARCHAR(100),
    standard_type               VARCHAR(30),
    standard_format             VARCHAR(100),
    standard_version            VARCHAR(50),

    collect_enabled             BOOLEAN        NOT NULL DEFAULT true,
    primary_key                 BOOLEAN        NOT NULL DEFAULT false,
    upsert_key                  BOOLEAN        NOT NULL DEFAULT false,
    required_by_standard        BOOLEAN        NOT NULL DEFAULT false,

    value_domain_code           VARCHAR(100),
    value_domain_source         VARCHAR(100),
    value_domain_version        VARCHAR(50),
    value_domain_mode           VARCHAR(30),

    transform_type              VARCHAR(50)    DEFAULT 'STANDARD',
    transform_expression        TEXT,
    default_value               TEXT,

    field_status                VARCHAR(30)    NOT NULL DEFAULT 'ACTIVE',
    remark                      TEXT,

    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_dfetl_field UNIQUE (dataset_id, field_code)
);

CREATE INDEX IF NOT EXISTS idx_dfetl_field_dataset
    ON dfetl_field(dataset_id);
CREATE INDEX IF NOT EXISTS idx_dfetl_field_collect
    ON dfetl_field(dataset_id, collect_enabled);
CREATE INDEX IF NOT EXISTS idx_dfetl_field_target
    ON dfetl_field(dataset_id, target_column);

CREATE TABLE IF NOT EXISTS dfetl_task (
    id                          BIGSERIAL      PRIMARY KEY,
    dataset_id                  BIGINT         NOT NULL REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    task_id                     BIGINT         REFERENCES sync_task(id) ON DELETE SET NULL,

    generation_type             VARCHAR(30)    NOT NULL,
    generation_status           VARCHAR(30)    NOT NULL,
    active                      BOOLEAN        NOT NULL DEFAULT true,

    external_batch_id           VARCHAR(128),
    config_version              VARCHAR(50),
    config_hash                 VARCHAR(128),
    error_message               TEXT,

    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_dfetl_task UNIQUE (dataset_id, task_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_task_active
    ON dfetl_task(dataset_id)
    WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_dfetl_task_task_id
    ON dfetl_task(task_id);

COMMENT ON TABLE dfetl_dataset IS 'DFETL 数据集采集配置主表';
COMMENT ON TABLE dfetl_field IS 'DFETL 数据集字段采集配置表';
COMMENT ON TABLE dfetl_task IS 'DFETL 数据集配置生成 sync_task 的绑定记录';

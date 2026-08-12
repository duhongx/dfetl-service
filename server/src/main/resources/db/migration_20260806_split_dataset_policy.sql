-- Spec 103: split dataset-level sync, validation and message policies from dfetl_dataset.
BEGIN;

SET LOCAL search_path TO df_etl, public;

CREATE TABLE IF NOT EXISTS dfetl_sync_policy (
    id                          BIGSERIAL PRIMARY KEY,
    dataset_id                  BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    write_mode                  VARCHAR(20) NOT NULL DEFAULT 'TRUNCATE',
    sync_template               VARCHAR(30) NOT NULL DEFAULT 'FULL_ONLY',
    incremental_field           VARCHAR(100),
    increment_mode              VARCHAR(20) NOT NULL DEFAULT 'TIME_FIELD',
    upper_bound_strategy        VARCHAR(30) NOT NULL DEFAULT 'CURRENT_TIME',
    upper_bound_delay_minutes   INTEGER NOT NULL DEFAULT 5,
    lookback_seconds            INTEGER NOT NULL DEFAULT 0,
    reader_parallelism          INTEGER NOT NULL DEFAULT 4,
    fetch_size                  INTEGER,
    rate_limit                  INTEGER NOT NULL DEFAULT 0,
    schedule_enabled            BOOLEAN NOT NULL DEFAULT true,
    schedule_mode               VARCHAR(30) NOT NULL DEFAULT 'EVERY_N_HOURS',
    schedule_interval_hours     INTEGER DEFAULT 4,
    schedule_cron               VARCHAR(128),
    schedule_timezone           VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    policy_revision             BIGINT NOT NULL DEFAULT 1,
    row_version                 BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
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
    id                          BIGSERIAL PRIMARY KEY,
    dataset_id                  BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    inherit_global              BOOLEAN NOT NULL DEFAULT true,
    enabled                     BOOLEAN NOT NULL DEFAULT false,
    trigger_mode                VARCHAR(30) NOT NULL DEFAULT 'AFTER_SYNC',
    validation_method           VARCHAR(30) NOT NULL DEFAULT 'ROW_COUNT',
    row_tolerance               NUMERIC(8,4) NOT NULL DEFAULT 0,
    fail_block                  BOOLEAN NOT NULL DEFAULT false,
    revalidate_enabled          BOOLEAN NOT NULL DEFAULT true,
    revalidate_delay            INTEGER NOT NULL DEFAULT 30,
    lookback_hours              INTEGER NOT NULL DEFAULT 2,
    policy_revision             BIGINT NOT NULL DEFAULT 1,
    row_version                 BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_validation_tolerance CHECK (row_tolerance BETWEEN 0 AND 100),
    CONSTRAINT ck_dfetl_validation_numbers CHECK (revalidate_delay >= 0 AND lookback_hours BETWEEN 0 AND 168)
);

CREATE TABLE IF NOT EXISTS dfetl_message_policy (
    id                          BIGSERIAL PRIMARY KEY,
    dataset_id                  BIGINT NOT NULL UNIQUE REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    enabled                     BOOLEAN NOT NULL DEFAULT false,
    transport                   VARCHAR(30) NOT NULL DEFAULT 'RABBITMQ',
    full_sync_mode              VARCHAR(30) NOT NULL DEFAULT 'ALL',
    rate_limit                  INTEGER NOT NULL DEFAULT 1000,
    routing_key                 VARCHAR(100),
    topic                       VARCHAR(100),
    key_template                VARCHAR(500),
    page_size                   INTEGER NOT NULL DEFAULT 1000,
    tenant_id                   VARCHAR(50) NOT NULL DEFAULT '0',
    source_system               VARCHAR(50) NOT NULL DEFAULT 'HIS',
    policy_revision             BIGINT NOT NULL DEFAULT 1,
    row_version                 BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_message_numbers CHECK (rate_limit >= 0 AND page_size > 0),
    CONSTRAINT ck_dfetl_message_route CHECK (NOT enabled OR length(trim(routing_key)) > 0)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'dfetl_dataset'
          AND column_name = 'sync_template'
    ) THEN
        EXECUTE $sql$
            INSERT INTO dfetl_sync_policy (
                dataset_id, write_mode, sync_template, incremental_field, increment_mode,
                upper_bound_strategy, upper_bound_delay_minutes, lookback_seconds,
                reader_parallelism, fetch_size, rate_limit, schedule_enabled, schedule_mode,
                schedule_interval_hours, schedule_cron, schedule_timezone)
            SELECT id, write_mode, sync_template, incremental_field, increment_mode,
                   upper_bound_strategy, upper_bound_delay_minutes, lookback_seconds,
                   reader_parallelism, fetch_size, rate_limit, schedule_enabled, schedule_mode,
                   schedule_interval_hours, schedule_cron, schedule_timezone
            FROM dfetl_dataset
            ON CONFLICT (dataset_id) DO NOTHING
        $sql$;
    END IF;
END $$;

INSERT INTO dfetl_validation_policy (dataset_id)
SELECT id FROM dfetl_dataset
ON CONFLICT (dataset_id) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'dfetl_dataset'
          AND column_name = 'message_enabled'
    ) THEN
        EXECUTE $sql$
            INSERT INTO dfetl_message_policy (
                dataset_id, enabled, transport, full_sync_mode, rate_limit, routing_key,
                topic, key_template, page_size, tenant_id, source_system)
            SELECT id, message_enabled, message_transport, message_full_sync_mode,
                   message_rate_limit, message_routing_key, message_topic,
                   message_key_template, message_page_size, tenant_id, 'HIS'
            FROM dfetl_dataset
            ON CONFLICT (dataset_id) DO NOTHING
        $sql$;
    END IF;
END $$;

DO $$
DECLARE
    dataset_count BIGINT;
    sync_count BIGINT;
    validation_count BIGINT;
    message_count BIGINT;
BEGIN
    SELECT count(*) INTO dataset_count FROM dfetl_dataset;
    SELECT count(*) INTO sync_count FROM dfetl_sync_policy;
    SELECT count(*) INTO validation_count FROM dfetl_validation_policy;
    SELECT count(*) INTO message_count FROM dfetl_message_policy;
    IF sync_count <> dataset_count
       OR validation_count <> dataset_count
       OR message_count <> dataset_count THEN
        RAISE EXCEPTION '策略表回填不完整: dataset=%, sync=%, validation=%, message=%',
            dataset_count, sync_count, validation_count, message_count;
    END IF;
END $$;

ALTER TABLE dfetl_dataset
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_task_numbers,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_schedule,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_message_rate_limit_nonnegative,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_message_page_size_positive,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_message_route_required;

ALTER TABLE dfetl_dataset
    DROP COLUMN IF EXISTS sync_template,
    DROP COLUMN IF EXISTS write_mode,
    DROP COLUMN IF EXISTS incremental_field,
    DROP COLUMN IF EXISTS increment_mode,
    DROP COLUMN IF EXISTS upper_bound_strategy,
    DROP COLUMN IF EXISTS upper_bound_delay_minutes,
    DROP COLUMN IF EXISTS lookback_seconds,
    DROP COLUMN IF EXISTS reader_parallelism,
    DROP COLUMN IF EXISTS fetch_size,
    DROP COLUMN IF EXISTS rate_limit,
    DROP COLUMN IF EXISTS schedule_enabled,
    DROP COLUMN IF EXISTS schedule_mode,
    DROP COLUMN IF EXISTS schedule_interval_hours,
    DROP COLUMN IF EXISTS schedule_cron,
    DROP COLUMN IF EXISTS schedule_timezone,
    DROP COLUMN IF EXISTS message_enabled,
    DROP COLUMN IF EXISTS message_transport,
    DROP COLUMN IF EXISTS message_full_sync_mode,
    DROP COLUMN IF EXISTS message_rate_limit,
    DROP COLUMN IF EXISTS message_routing_key,
    DROP COLUMN IF EXISTS message_topic,
    DROP COLUMN IF EXISTS message_key_template,
    DROP COLUMN IF EXISTS message_page_size,
    DROP COLUMN IF EXISTS tenant_id;

COMMENT ON TABLE dfetl_dataset IS '医共体有效标准数据集只读快照';
COMMENT ON TABLE dfetl_sync_policy IS '标准数据集共享的同步、性能和调度策略';
COMMENT ON TABLE dfetl_validation_policy IS '标准数据集共享的校验策略';
COMMENT ON TABLE dfetl_message_policy IS '标准数据集共享的消息发布策略';

COMMIT;

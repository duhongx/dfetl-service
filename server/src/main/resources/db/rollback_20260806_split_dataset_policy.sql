-- Spec 103 rollback: restore policy columns to dfetl_dataset before dropping split tables.
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE dfetl_dataset
    ADD COLUMN IF NOT EXISTS sync_template VARCHAR(30) NOT NULL DEFAULT 'FULL_ONLY',
    ADD COLUMN IF NOT EXISTS write_mode VARCHAR(20) NOT NULL DEFAULT 'TRUNCATE',
    ADD COLUMN IF NOT EXISTS incremental_field VARCHAR(100),
    ADD COLUMN IF NOT EXISTS increment_mode VARCHAR(20) NOT NULL DEFAULT 'TIME_FIELD',
    ADD COLUMN IF NOT EXISTS upper_bound_strategy VARCHAR(30) NOT NULL DEFAULT 'CURRENT_TIME',
    ADD COLUMN IF NOT EXISTS upper_bound_delay_minutes INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS lookback_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reader_parallelism INTEGER NOT NULL DEFAULT 4,
    ADD COLUMN IF NOT EXISTS fetch_size INTEGER,
    ADD COLUMN IF NOT EXISTS rate_limit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS schedule_enabled BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS schedule_mode VARCHAR(30) NOT NULL DEFAULT 'EVERY_N_HOURS',
    ADD COLUMN IF NOT EXISTS schedule_interval_hours INTEGER DEFAULT 4,
    ADD COLUMN IF NOT EXISTS schedule_cron VARCHAR(128),
    ADD COLUMN IF NOT EXISTS schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    ADD COLUMN IF NOT EXISTS message_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS message_transport VARCHAR(30) NOT NULL DEFAULT 'RABBITMQ',
    ADD COLUMN IF NOT EXISTS message_full_sync_mode VARCHAR(30) NOT NULL DEFAULT 'ALL',
    ADD COLUMN IF NOT EXISTS message_rate_limit INTEGER NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS message_routing_key VARCHAR(100),
    ADD COLUMN IF NOT EXISTS message_topic VARCHAR(100),
    ADD COLUMN IF NOT EXISTS message_key_template VARCHAR(500),
    ADD COLUMN IF NOT EXISTS message_page_size INTEGER NOT NULL DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(50) NOT NULL DEFAULT '0';

UPDATE dfetl_dataset d SET
    sync_template = p.sync_template, write_mode = p.write_mode,
    incremental_field = p.incremental_field, increment_mode = p.increment_mode,
    upper_bound_strategy = p.upper_bound_strategy,
    upper_bound_delay_minutes = p.upper_bound_delay_minutes,
    lookback_seconds = p.lookback_seconds, reader_parallelism = p.reader_parallelism,
    fetch_size = p.fetch_size, rate_limit = p.rate_limit,
    schedule_enabled = p.schedule_enabled, schedule_mode = p.schedule_mode,
    schedule_interval_hours = p.schedule_interval_hours,
    schedule_cron = p.schedule_cron, schedule_timezone = p.schedule_timezone
FROM dfetl_sync_policy p WHERE p.dataset_id = d.id;

UPDATE dfetl_dataset d SET
    message_enabled = p.enabled, message_transport = p.transport,
    message_full_sync_mode = p.full_sync_mode, message_rate_limit = p.rate_limit,
    message_routing_key = p.routing_key, message_topic = p.topic,
    message_key_template = p.key_template, message_page_size = p.page_size,
    tenant_id = p.tenant_id
FROM dfetl_message_policy p WHERE p.dataset_id = d.id;

ALTER TABLE dfetl_dataset
    ADD CONSTRAINT ck_dfetl_dataset_task_numbers CHECK (
        upper_bound_delay_minutes >= 0 AND lookback_seconds >= 0
        AND reader_parallelism BETWEEN 1 AND 64
        AND (fetch_size IS NULL OR fetch_size > 0) AND rate_limit >= 0),
    ADD CONSTRAINT ck_dfetl_dataset_schedule CHECK (
        (NOT schedule_enabled AND schedule_mode = 'MANUAL')
        OR (schedule_enabled AND schedule_mode = 'EVERY_N_HOURS' AND schedule_interval_hours > 0)
        OR (schedule_enabled AND schedule_mode = 'ADVANCED' AND length(trim(schedule_cron)) > 0)),
    ADD CONSTRAINT ck_dfetl_dataset_message_rate_limit_nonnegative CHECK (message_rate_limit >= 0),
    ADD CONSTRAINT ck_dfetl_dataset_message_page_size_positive CHECK (message_page_size > 0),
    ADD CONSTRAINT ck_dfetl_dataset_message_route_required CHECK (
        NOT message_enabled OR length(trim(message_routing_key)) > 0);

DROP TABLE IF EXISTS dfetl_message_policy;
DROP TABLE IF EXISTS dfetl_validation_policy;
DROP TABLE IF EXISTS dfetl_sync_policy;

COMMIT;

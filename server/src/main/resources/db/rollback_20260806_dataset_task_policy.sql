-- Rollback for migration_20260806_dataset_task_policy.sql.
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE IF EXISTS institution_dataset_route
    ADD COLUMN IF NOT EXISTS sync_template VARCHAR(30) NOT NULL DEFAULT 'FULL_THEN_INCREMENT',
    ADD COLUMN IF NOT EXISTS write_mode VARCHAR(20) NOT NULL DEFAULT 'UPSERT',
    ADD COLUMN IF NOT EXISTS incremental_field VARCHAR(100),
    ADD COLUMN IF NOT EXISTS increment_mode VARCHAR(20) NOT NULL DEFAULT 'TIME_FIELD',
    ADD COLUMN IF NOT EXISTS upper_bound_strategy VARCHAR(30) NOT NULL DEFAULT 'CURRENT_TIME',
    ADD COLUMN IF NOT EXISTS upper_bound_delay_minutes INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS lookback_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS schedule_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS schedule_mode VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS schedule_interval_hours INTEGER,
    ADD COLUMN IF NOT EXISTS schedule_cron VARCHAR(128),
    ADD COLUMN IF NOT EXISTS schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';

UPDATE institution_dataset_route route
SET sync_template = dataset.sync_template,
    write_mode = dataset.write_mode,
    incremental_field = dataset.incremental_field,
    increment_mode = dataset.increment_mode,
    upper_bound_strategy = dataset.upper_bound_strategy,
    upper_bound_delay_minutes = dataset.upper_bound_delay_minutes,
    lookback_seconds = dataset.lookback_seconds,
    schedule_enabled = dataset.schedule_enabled,
    schedule_mode = dataset.schedule_mode,
    schedule_interval_hours = dataset.schedule_interval_hours,
    schedule_cron = dataset.schedule_cron,
    schedule_timezone = dataset.schedule_timezone
FROM dfetl_dataset dataset
WHERE dataset.id = route.dataset_id;

ALTER TABLE IF EXISTS institution_dataset_route
    DROP CONSTRAINT IF EXISTS ck_institution_dataset_route_delay_nonnegative,
    DROP CONSTRAINT IF EXISTS ck_institution_dataset_route_schedule;

ALTER TABLE IF EXISTS institution_dataset_route
    ADD CONSTRAINT ck_institution_dataset_route_delay_nonnegative
        CHECK (upper_bound_delay_minutes >= 0 AND lookback_seconds >= 0),
    ADD CONSTRAINT ck_institution_dataset_route_schedule CHECK (
        (NOT schedule_enabled AND schedule_mode = 'MANUAL')
        OR (schedule_enabled AND schedule_mode = 'EVERY_N_HOURS'
            AND schedule_interval_hours > 0)
        OR (schedule_enabled AND schedule_mode = 'ADVANCED'
            AND length(trim(schedule_cron)) > 0)
    );

ALTER TABLE IF EXISTS dfetl_dataset
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_task_numbers,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_schedule;

ALTER TABLE IF EXISTS dfetl_dataset
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
    DROP COLUMN IF EXISTS schedule_timezone;

ALTER TABLE IF EXISTS dfetl_field
    DROP COLUMN IF EXISTS target_field_code,
    DROP COLUMN IF EXISTS doris_type;

COMMIT;

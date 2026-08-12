-- Spec 102: move task defaults to dfetl_dataset and materialize field target metadata.
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE IF EXISTS dfetl_dataset
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
    ADD COLUMN IF NOT EXISTS schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';

ALTER TABLE IF EXISTS dfetl_field
    ADD COLUMN IF NOT EXISTS target_field_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS doris_type VARCHAR(100);

UPDATE dfetl_field
SET target_field_code = lower(field_code)
WHERE target_field_code IS NULL OR btrim(target_field_code) = '';

ALTER TABLE IF EXISTS dfetl_field
    ALTER COLUMN target_field_code SET NOT NULL;

-- Preserve one deterministic legacy route policy per dataset before removing duplication.
WITH preferred_route AS (
    SELECT DISTINCT ON (dataset_id)
           dataset_id, sync_template, write_mode, incremental_field, increment_mode,
           upper_bound_strategy, upper_bound_delay_minutes, lookback_seconds,
           schedule_enabled, schedule_mode, schedule_interval_hours, schedule_cron,
           schedule_timezone
    FROM institution_dataset_route
    ORDER BY dataset_id, enabled DESC, id
)
UPDATE dfetl_dataset dataset
SET sync_template = route.sync_template,
    write_mode = route.write_mode,
    incremental_field = route.incremental_field,
    increment_mode = route.increment_mode,
    upper_bound_strategy = route.upper_bound_strategy,
    upper_bound_delay_minutes = route.upper_bound_delay_minutes,
    lookback_seconds = route.lookback_seconds,
    schedule_enabled = route.schedule_enabled,
    schedule_mode = route.schedule_mode,
    schedule_interval_hours = route.schedule_interval_hours,
    schedule_cron = route.schedule_cron,
    schedule_timezone = route.schedule_timezone
FROM preferred_route route
WHERE dataset.id = route.dataset_id;

ALTER TABLE IF EXISTS dfetl_dataset
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_task_numbers,
    DROP CONSTRAINT IF EXISTS ck_dfetl_dataset_schedule;

ALTER TABLE IF EXISTS dfetl_dataset
    ADD CONSTRAINT ck_dfetl_dataset_task_numbers CHECK (
        upper_bound_delay_minutes >= 0
        AND lookback_seconds >= 0
        AND reader_parallelism BETWEEN 1 AND 64
        AND (fetch_size IS NULL OR fetch_size > 0)
        AND rate_limit >= 0
    ),
    ADD CONSTRAINT ck_dfetl_dataset_schedule CHECK (
        (NOT schedule_enabled AND schedule_mode = 'MANUAL')
        OR (schedule_enabled AND schedule_mode = 'EVERY_N_HOURS'
            AND schedule_interval_hours > 0)
        OR (schedule_enabled AND schedule_mode = 'ADVANCED'
            AND length(trim(schedule_cron)) > 0)
    );

ALTER TABLE IF EXISTS institution_dataset_route
    DROP CONSTRAINT IF EXISTS ck_institution_dataset_route_delay_nonnegative,
    DROP CONSTRAINT IF EXISTS ck_institution_dataset_route_schedule;

ALTER TABLE IF EXISTS institution_dataset_route
    DROP COLUMN IF EXISTS sync_template,
    DROP COLUMN IF EXISTS write_mode,
    DROP COLUMN IF EXISTS incremental_field,
    DROP COLUMN IF EXISTS increment_mode,
    DROP COLUMN IF EXISTS upper_bound_strategy,
    DROP COLUMN IF EXISTS upper_bound_delay_minutes,
    DROP COLUMN IF EXISTS lookback_seconds,
    DROP COLUMN IF EXISTS schedule_enabled,
    DROP COLUMN IF EXISTS schedule_mode,
    DROP COLUMN IF EXISTS schedule_interval_hours,
    DROP COLUMN IF EXISTS schedule_cron,
    DROP COLUMN IF EXISTS schedule_timezone;

COMMIT;

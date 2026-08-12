-- spec 048: Window-Scoped Checksum
-- 运行前确认目标库已存在 df_etl schema 以及三张表

ALTER TABLE df_etl.etl_verify_chunk
    ADD COLUMN IF NOT EXISTS scoped_window_start TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS scoped_window_end   TIMESTAMPTZ NULL;

ALTER TABLE df_etl.task_validation_config
    ADD COLUMN IF NOT EXISTS checksum_scope VARCHAR(10) NOT NULL DEFAULT 'FULL';

ALTER TABLE df_etl.validation_task
    ADD COLUMN IF NOT EXISTS window_start TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS window_end   TIMESTAMPTZ NULL;

-- =============================================================================
-- Spec: 063-sync-validation-hardening · VRR-10
-- Purpose: etl_verify_chunk 的幂等边界从 legacy execId 收口到 validation_run_id。
--
-- 背景：
--   旧唯一约束 uk_verify_chunk_exec_no(exec_id, chunk_no) 会让默认/手动 legacyExecId
--   在不同 task 或不同 ValidationRun 间互相冲突，也会导致断点续跑串读旧分片。
--   新约束 uk_verify_chunk_run_no(validation_run_id, chunk_no) 与业务事实一致：
--   chunk 属于一次 ValidationRun。
--
-- 执行：
--   psql "$DF_ETL_DB_URL" -f migration_etl_verify_chunk_run_unique.sql
--
-- 兼容：
--   - 不删除数据。
--   - validation_run_id 为空的历史行在 PostgreSQL UNIQUE 中允许多行 NULL。
--   - 如果同一 validation_run_id 下已有重复 chunk_no，本脚本会在 ADD CONSTRAINT 时报错，
--     需要人工确认历史数据后再重跑。
-- =============================================================================

DO $$
DECLARE
    chunk_schema TEXT;
    new_constraint_exists BOOLEAN;
BEGIN
    PERFORM set_config('lock_timeout', '5s', true);

    SELECT table_schema
      INTO chunk_schema
      FROM information_schema.tables
     WHERE table_name = 'etl_verify_chunk'
       AND table_type = 'BASE TABLE'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = 'df_etl' THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    IF chunk_schema IS NULL THEN
        RAISE EXCEPTION 'etl_verify_chunk table not found in current database';
    END IF;

    EXECUTE format(
        'ALTER TABLE %I.etl_verify_chunk DROP CONSTRAINT IF EXISTS uk_verify_chunk_exec_no',
        chunk_schema);
    EXECUTE format('DROP INDEX IF EXISTS %I.uk_verify_chunk_exec_no', chunk_schema);

    SELECT EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = chunk_schema
           AND t.relname = 'etl_verify_chunk'
           AND c.conname = 'uk_verify_chunk_run_no'
           AND c.contype = 'u'
    ) INTO new_constraint_exists;

    IF NOT new_constraint_exists THEN
        EXECUTE format(
            'ALTER TABLE %I.etl_verify_chunk '
            || 'ADD CONSTRAINT uk_verify_chunk_run_no UNIQUE (validation_run_id, chunk_no)',
            chunk_schema);
    END IF;

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_verify_chunk_task ON %I.etl_verify_chunk(task_id, exec_id)',
        chunk_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_verify_chunk_run ON %I.etl_verify_chunk(validation_run_id)',
        chunk_schema);
END $$;

-- =============================================================================
-- ID_RANGE validation/checksum 窗口上下文补丁迁移脚本
-- 适用：已有元数据库缺少 ID_RANGE 执行/校验窗口字段。
-- 执行：psql -U df_etl -d df_ygt -f migration_id_range_validation_window.sql
-- 幂等：自动识别 task_execution / validation_task 所在 schema，ADD COLUMN IF NOT EXISTS，重复执行安全。
-- =============================================================================

DO $$
DECLARE
    task_execution_schema TEXT;
    validation_task_schema TEXT;
BEGIN
    SELECT table_schema
      INTO task_execution_schema
      FROM information_schema.tables
     WHERE table_name = 'task_execution'
       AND table_type = 'BASE TABLE'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = 'df_etl' THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    SELECT table_schema
      INTO validation_task_schema
      FROM information_schema.tables
     WHERE table_name = 'validation_task'
       AND table_type = 'BASE TABLE'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = 'df_etl' THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    IF task_execution_schema IS NULL THEN
        RAISE EXCEPTION 'task_execution table not found in current database';
    END IF;
    IF validation_task_schema IS NULL THEN
        RAISE EXCEPTION 'validation_task table not found in current database';
    END IF;

    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I BIGINT',
                   task_execution_schema, 'task_execution', 'window_start_id');
    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I BIGINT',
                   task_execution_schema, 'task_execution', 'window_end_id');
    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I VARCHAR(20)',
                   task_execution_schema, 'task_execution', 'window_type');

    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I VARCHAR(20)',
                   validation_task_schema, 'validation_task', 'window_type');
    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I BIGINT',
                   validation_task_schema, 'validation_task', 'window_start_id');
    EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS %I BIGINT',
                   validation_task_schema, 'validation_task', 'window_end_id');

    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   task_execution_schema, 'task_execution', 'window_start_id',
                   'ID_RANGE 增量模式：本批次 ID 窗口起点（上次最大 ID）');
    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   task_execution_schema, 'task_execution', 'window_end_id',
                   'ID_RANGE 增量模式：本批次 ID 窗口终点（本次最大 ID）');
    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   task_execution_schema, 'task_execution', 'window_type',
                   '本批次执行窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT');
    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   validation_task_schema, 'validation_task', 'window_type',
                   '校验使用的窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT');
    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   validation_task_schema, 'validation_task', 'window_start_id',
                   'ID_RANGE 校验窗口起点（上次最大 ID）');
    EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',
                   validation_task_schema, 'validation_task', 'window_end_id',
                   'ID_RANGE 校验窗口终点（本次最大 ID）');
END $$;

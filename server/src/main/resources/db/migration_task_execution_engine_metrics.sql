-- Spec 080：把 SeaTunnel 内部重试累计指标与业务范围行数分离。
-- 幂等：自动识别 task_execution 所在 schema，可重复执行。

DO $$
DECLARE
    task_execution_schema TEXT;
BEGIN
    SELECT table_schema
      INTO task_execution_schema
      FROM information_schema.tables
     WHERE table_name = 'task_execution'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = current_schema() THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    IF task_execution_schema IS NULL THEN
        RAISE EXCEPTION 'task_execution table not found in current database';
    END IF;

    EXECUTE format(
        'ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS engine_read_rows BIGINT',
        task_execution_schema);
    EXECUTE format(
        'ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS engine_write_rows BIGINT',
        task_execution_schema);

    EXECUTE format(
        'COMMENT ON COLUMN %I.task_execution.engine_read_rows IS %L',
        task_execution_schema,
        'SeaTunnel vertex 累计读取尝试数，含内部重试，不等同业务 read_rows');
    EXECUTE format(
        'COMMENT ON COLUMN %I.task_execution.engine_write_rows IS %L',
        task_execution_schema,
        'SeaTunnel vertex 累计写入尝试数，含内部重试，不等同目标已提交 write_rows');
END $$;

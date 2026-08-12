-- =============================================================================
-- task_execution RECONCILE_REQUIRED 人工处置字段迁移脚本
-- 适用：已有元数据库存在 task_execution 表，但缺少人工处理闭环字段。
-- 执行：在 ETL 元数据库中执行本脚本；不要在日志或工单中记录连接凭据。
-- 幂等：自动识别 task_execution 所在 schema，重复执行安全。
--
-- 语义：
-- 1. reconcile_handled=true 只表示人工已经核对并关闭运维待办。
-- 2. 不代表 task_execution 成功。
-- 3. 不推进 watermark。
-- 4. 不触发 snapshot / auto validation。
-- =============================================================================

DO $$
DECLARE
    task_execution_schema TEXT;
BEGIN
    PERFORM set_config('lock_timeout', '5s', true);

    SELECT table_schema
      INTO task_execution_schema
      FROM information_schema.tables
     WHERE table_name = 'task_execution'
       AND table_type = 'BASE TABLE'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = 'df_etl' THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    IF task_execution_schema IS NULL THEN
        RAISE EXCEPTION 'task_execution table not found in current database';
    END IF;

    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_handled BOOLEAN DEFAULT FALSE',
                   task_execution_schema);
    EXECUTE format('UPDATE %I.task_execution SET reconcile_handled = FALSE WHERE reconcile_handled IS NULL',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ALTER COLUMN reconcile_handled SET DEFAULT FALSE',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ALTER COLUMN reconcile_handled SET NOT NULL',
                   task_execution_schema);

    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_handled_at TIMESTAMPTZ',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_handled_by VARCHAR(100)',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_note TEXT',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_last_probed_at TIMESTAMPTZ',
                   task_execution_schema);
    EXECUTE format('ALTER TABLE %I.task_execution ADD COLUMN IF NOT EXISTS reconcile_last_probe_result TEXT',
                   task_execution_schema);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_task_execution_reconcile ON %I.task_execution(status, reconcile_handled)',
                   task_execution_schema);

    EXECUTE format(
        'COMMENT ON COLUMN %I.task_execution.reconcile_handled IS %L',
        task_execution_schema,
        'RECONCILE_REQUIRED 人工待办是否已处理；不代表执行成功，不推进 watermark');
END $$;

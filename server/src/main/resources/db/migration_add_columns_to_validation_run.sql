-- =============================================================================
-- Spec: validation-table-consolidation · Step 1
-- Validates: Requirement 1 (AC 1, 2, 3, 4, 5)
--
-- 向 validation_run 表添加原 validation_task 的独有字段，为双表合并做准备。
-- 使用 ADD COLUMN IF NOT EXISTS 保证幂等性。
--
-- 执行方式：上传 /tmp/ 后手动 psql 执行。
-- =============================================================================

BEGIN;

-- ── 新增列 ──────────────────────────────────────────────────────────────────

ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS method VARCHAR(20);
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS diff_rows BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS source_rows BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS target_rows BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS error_msg TEXT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS name VARCHAR(100);
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS execution_id BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS tables_text TEXT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS last_run_at TIMESTAMPTZ;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS window_type VARCHAR(20);
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS window_start_id BIGINT;
ALTER TABLE validation_run ADD COLUMN IF NOT EXISTS window_end_id BIGINT;

-- ── 索引 ────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_validation_run_status
    ON validation_run (status);

CREATE INDEX IF NOT EXISTS idx_validation_run_task_status
    ON validation_run (task_id, status);

-- ── 列注释 ──────────────────────────────────────────────────────────────────

COMMENT ON COLUMN validation_run.status IS '校验状态：PENDING/RUNNING/CONSISTENT/DIFF/ERROR';
COMMENT ON COLUMN validation_run.method IS '校验方式：ROW_COUNT/CHECKSUM/ROW_COUNT_CHECKSUM';
COMMENT ON COLUMN validation_run.diff_rows IS '差异行数';
COMMENT ON COLUMN validation_run.duration_ms IS '校验耗时（毫秒）';
COMMENT ON COLUMN validation_run.source_rows IS '源端行数';
COMMENT ON COLUMN validation_run.target_rows IS '目标端行数';
COMMENT ON COLUMN validation_run.error_msg IS '错误信息（截断至 2000 字符）';
COMMENT ON COLUMN validation_run.name IS '校验任务名称';
COMMENT ON COLUMN validation_run.execution_id IS '触发本次校验的执行批次 ID';
COMMENT ON COLUMN validation_run.tables_text IS '校验表列表（逗号分隔）';
COMMENT ON COLUMN validation_run.last_run_at IS '最后执行时间';
COMMENT ON COLUMN validation_run.window_type IS '窗口类型：FULL/INCREMENT/ID_RANGE/TIME_FIELD';
COMMENT ON COLUMN validation_run.window_start_id IS 'ID_RANGE 窗口起点';
COMMENT ON COLUMN validation_run.window_end_id IS 'ID_RANGE 窗口终点';

COMMIT;

-- =============================================================================
-- 上线后验证 SQL
-- =============================================================================
-- SELECT column_name, data_type, character_maximum_length, column_default
--   FROM information_schema.columns
--  WHERE table_name = 'validation_run'
--  ORDER BY ordinal_position;
--
-- SELECT indexname, indexdef FROM pg_indexes
--  WHERE tablename = 'validation_run';
-- =============================================================================

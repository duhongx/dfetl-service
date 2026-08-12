-- =============================================================================
-- 2026-08-04  医共体执行分流摘要
-- =============================================================================

ALTER TABLE task_execution
    ADD COLUMN IF NOT EXISTS source_rows_total BIGINT,
    ADD COLUMN IF NOT EXISTS valid_source_rows BIGINT,
    ADD COLUMN IF NOT EXISTS excluded_rows BIGINT,
    ADD COLUMN IF NOT EXISTS warning_rows BIGINT;

COMMENT ON COLUMN task_execution.source_rows_total IS '医共体源窗口总行数：valid_source_rows + excluded_rows';
COMMENT ON COLUMN task_execution.valid_source_rows IS '医共体分流后进入 SeaTunnel 的合规源行数';
COMMENT ON COLUMN task_execution.excluded_rows IS '医共体阻断剔除、未写入 Doris 的行数';
COMMENT ON COLUMN task_execution.warning_rows IS '医共体告警但仍写入 Doris 的行数';

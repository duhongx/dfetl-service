-- =============================================================================
-- Spec: validation-table-consolidation · Step 2
-- Validates: Requirement 2 (AC 1, 2, 3, 4, 5)
--
-- 将 validation_task 的历史数据迁移到 validation_run。
-- 分两阶段：A) UPDATE 已有对应记录；B) INSERT 孤立记录。
-- 幂等：重复执行以 validation_task 值覆盖。
--
-- 前置：Step 1（migration_add_columns_to_validation_run.sql）已执行。
-- 执行方式：上传 /tmp/ 后手动 psql 执行。
-- =============================================================================

BEGIN;

-- ── 阶段 A：UPDATE 已有对应记录 ─────────────────────────────────────────────
-- 关联条件：validation_run.task_id = validation_task.task_id
--           AND validation_run.legacy_exec_id = validation_task.execution_id
-- （PG 不支持 UPDATE ... JOIN，改用子查询）

UPDATE validation_run r
SET
    status        = t.status,
    method        = t.method,
    diff_rows     = t.diff_rows,
    duration_ms   = t.duration_ms,
    source_rows   = t.source_rows,
    target_rows   = t.target_rows,
    error_msg     = t.error_msg,
    name          = t.name,
    execution_id  = t.execution_id,
    tables_text   = t.tables,
    last_run_at   = t.last_run_at,
    window_type   = t.window_type,
    window_start_id = t.window_start_id,
    window_end_id   = t.window_end_id,
    updated_at    = NOW()
FROM validation_task t
WHERE r.task_id = t.task_id
  AND r.legacy_exec_id = t.execution_id;

-- ── 阶段 B：INSERT 无对应记录的孤立 validation_task ──────────────────────────
-- execution_id 为 NULL 时使用 validation_task.id 作为 legacy_exec_id

INSERT INTO validation_run (
    task_id, legacy_exec_id, mode, scope,
    window_start, window_end, created_at, updated_at,
    trigger_type,
    status, method, diff_rows, duration_ms, source_rows, target_rows,
    error_msg, name, execution_id, tables_text, last_run_at,
    window_type, window_start_id, window_end_id
)
SELECT
    t.task_id,
    COALESCE(t.execution_id, t.id) AS legacy_exec_id,
    COALESCE(t.method, 'ROW_COUNT') AS mode,
    CASE
        WHEN t.window_start IS NULL AND t.window_end IS NULL THEN 'FULL'
        ELSE 'WINDOW'
    END AS scope,
    t.window_start,
    t.window_end,
    t.created_at,
    COALESCE(t.updated_at, t.created_at, NOW()),
    t.trigger_type,
    t.status,
    t.method,
    t.diff_rows,
    t.duration_ms,
    t.source_rows,
    t.target_rows,
    t.error_msg,
    t.name,
    t.execution_id,
    t.tables,
    t.last_run_at,
    t.window_type,
    t.window_start_id,
    t.window_end_id
FROM validation_task t
WHERE NOT EXISTS (
    SELECT 1 FROM validation_run r
    WHERE r.task_id = t.task_id
      AND r.legacy_exec_id = COALESCE(t.execution_id, t.id)
)
ON CONFLICT (task_id, legacy_exec_id) DO NOTHING;

COMMIT;

-- ── 迁移统计 ────────────────────────────────────────────────────────────────

SELECT '迁移统计' AS label;
SELECT
    (SELECT COUNT(*) FROM validation_run WHERE status IS NOT NULL AND status != 'PENDING') AS updated_or_inserted,
    (SELECT COUNT(*) FROM validation_task) AS total_task_count,
    (SELECT COUNT(*) FROM validation_run) AS total_run_count;

-- =============================================================================
-- 上线后验证 SQL
-- =============================================================================
-- -- 验证迁移完整性：validation_run 中有 status 的记录数 ≥ validation_task 总数
-- SELECT
--     (SELECT COUNT(*) FROM validation_run WHERE status IS NOT NULL) AS run_with_status,
--     (SELECT COUNT(*) FROM validation_task) AS task_total;
--
-- -- 抽样对比：取 10 条 validation_task 与对应 validation_run 比对字段值
-- SELECT t.id, t.task_id, t.status AS task_status, r.status AS run_status,
--        t.diff_rows AS task_diff, r.diff_rows AS run_diff
--   FROM validation_task t
--   LEFT JOIN validation_run r ON r.task_id = t.task_id
--        AND r.legacy_exec_id = COALESCE(t.execution_id, t.id)
--  LIMIT 10;
-- =============================================================================

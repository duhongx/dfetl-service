-- WARNING: rollback removes staging lifecycle/export metadata created after migration.
-- It preserves dfetl_precheck_run and dfetl_precheck_issue history, but export rows
-- and the new progress/job/hash/count columns are not recoverable after this script.
BEGIN;

SET LOCAL search_path TO df_etl, public;

DROP INDEX IF EXISTS idx_dfetl_precheck_export_status;
DROP INDEX IF EXISTS idx_dfetl_precheck_export_expiry;
DROP INDEX IF EXISTS idx_dfetl_precheck_run_raw_cleanup;
DROP INDEX IF EXISTS idx_dfetl_precheck_export_run;
DROP INDEX IF EXISTS uk_dfetl_precheck_export_request;
DROP TABLE IF EXISTS dfetl_precheck_export;
DROP INDEX IF EXISTS uk_dfetl_precheck_run_active;

ALTER TABLE dfetl_precheck_run
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_status,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_stage,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_progress,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_counts;

UPDATE dfetl_precheck_run
SET status = CASE
        WHEN status IN ('LOADING', 'VALIDATING', 'CANCELLED') THEN 'ERROR'
        WHEN status = 'HAS_ERRORS' THEN 'FAILED'
        ELSE status
    END;

ALTER TABLE dfetl_precheck_run
    ADD CONSTRAINT ck_dfetl_precheck_status
        CHECK (status IN ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'ERROR')),
    ADD CONSTRAINT ck_dfetl_precheck_counts CHECK (
        scanned_rows >= 0 AND passed_rows >= 0 AND blocker_rows >= 0
        AND warning_rows >= 0 AND fixed_issue_rows >= 0);

ALTER TABLE dfetl_precheck_run
    DROP COLUMN IF EXISTS raw_cleaned_at,
    DROP COLUMN IF EXISTS issue_count,
    DROP COLUMN IF EXISTS checked_rows,
    DROP COLUMN IF EXISTS loaded_rows,
    DROP COLUMN IF EXISTS source_rows,
    DROP COLUMN IF EXISTS staging_table,
    DROP COLUMN IF EXISTS engine_job_id,
    DROP COLUMN IF EXISTS progress_percent,
    DROP COLUMN IF EXISTS stage,
    DROP COLUMN IF EXISTS target_schema_hash;

COMMENT ON TABLE dfetl_precheck_run IS '标准数据集路由全量或任务执行窗口的数据预检批次';
COMMENT ON TABLE dfetl_precheck_issue IS '数据预检发现的完整问题明细及整改对比状态';

COMMIT;

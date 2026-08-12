-- Spec 104 redesign: migrate precheck metadata to the Doris STRING staging lifecycle.
-- This migration preserves dfetl_precheck_issue as immutable legacy history.
BEGIN;

SET LOCAL search_path TO df_etl, public;

ALTER TABLE dfetl_precheck_run
    ADD COLUMN IF NOT EXISTS target_schema_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS stage VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    ADD COLUMN IF NOT EXISTS progress_percent SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS engine_job_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS staging_table VARCHAR(200),
    ADD COLUMN IF NOT EXISTS source_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS loaded_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS checked_rows BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS issue_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS raw_cleaned_at TIMESTAMPTZ;

ALTER TABLE dfetl_precheck_run
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_status,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_stage,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_progress,
    DROP CONSTRAINT IF EXISTS ck_dfetl_precheck_counts;

-- Old FAILED without a system error represented data issues, not an engine failure.
UPDATE dfetl_precheck_run
SET status = 'HAS_ERRORS'
WHERE status = 'FAILED'
  AND blocker_rows > 0
  AND NULLIF(BTRIM(error_message), '') IS NULL;

-- An in-flight legacy run cannot be resumed by the new staging orchestrator.
UPDATE dfetl_precheck_run
SET status = 'FAILED',
    error_message = COALESCE(NULLIF(error_message, ''),
        '旧版预检运行在 Doris 暂存层架构迁移时中断，需创建新的全量预检运行'),
    finished_at = COALESCE(finished_at, now())
WHERE status IN ('RUNNING', 'ERROR');

UPDATE dfetl_precheck_run
SET stage = CASE
        WHEN status = 'PENDING' THEN 'PREPARING'
        ELSE 'COMPLETED'
    END,
    progress_percent = CASE
        WHEN status = 'PENDING' THEN 0
        ELSE 100
    END,
    source_rows = GREATEST(source_rows, scanned_rows),
    checked_rows = GREATEST(checked_rows, scanned_rows),
    issue_count = GREATEST(issue_count, blocker_rows + warning_rows);

-- Keep the newest pending fact if legacy code created duplicate unfinished runs.
WITH ranked_pending AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY route_id, contract_hash, route_revision
               ORDER BY created_at DESC, id DESC
           ) AS position
    FROM dfetl_precheck_run
    WHERE status = 'PENDING'
)
UPDATE dfetl_precheck_run run
SET status = 'FAILED',
    stage = 'COMPLETED',
    progress_percent = 100,
    error_message = COALESCE(NULLIF(run.error_message, ''),
        '旧版重复待运行预检在架构迁移时关闭，需保留最新运行'),
    finished_at = COALESCE(run.finished_at, now())
FROM ranked_pending ranked
WHERE run.id = ranked.id
  AND ranked.position > 1;

ALTER TABLE dfetl_precheck_run
    ADD CONSTRAINT ck_dfetl_precheck_status
        CHECK (status IN ('PENDING', 'LOADING', 'VALIDATING', 'HAS_ERRORS', 'PASSED', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT ck_dfetl_precheck_stage
        CHECK (stage IN ('PREPARING', 'LOADING', 'VALIDATING', 'FINALIZING', 'COMPLETED')),
    ADD CONSTRAINT ck_dfetl_precheck_progress
        CHECK (progress_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_dfetl_precheck_counts CHECK (
        scanned_rows >= 0 AND passed_rows >= 0 AND blocker_rows >= 0
        AND warning_rows >= 0 AND fixed_issue_rows >= 0
        AND source_rows >= 0 AND loaded_rows >= 0 AND checked_rows >= 0
        AND issue_count >= 0);

CREATE TABLE IF NOT EXISTS dfetl_precheck_export (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES dfetl_precheck_run(id) ON DELETE CASCADE,
    request_key         VARCHAR(128) NOT NULL,
    filter_snapshot     JSONB NOT NULL DEFAULT '{}'::jsonb,
    export_format       VARCHAR(10) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    file_manifest       JSONB NOT NULL DEFAULT '[]'::jsonb,
    row_count           BIGINT NOT NULL DEFAULT 0,
    byte_count          BIGINT NOT NULL DEFAULT 0,
    requested_by        VARCHAR(100) NOT NULL,
    error_message       TEXT,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_precheck_export_format
        CHECK (export_format IN ('CSV', 'XLSX')),
    CONSTRAINT ck_dfetl_precheck_export_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_dfetl_precheck_export_counts
        CHECK (row_count >= 0 AND byte_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_precheck_run_active
    ON dfetl_precheck_run(route_id, contract_hash, route_revision)
    WHERE status IN ('PENDING', 'LOADING', 'VALIDATING');
CREATE UNIQUE INDEX IF NOT EXISTS uk_dfetl_precheck_export_request
    ON dfetl_precheck_export(request_key);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_run
    ON dfetl_precheck_export(run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_status
    ON dfetl_precheck_export(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_export_expiry
    ON dfetl_precheck_export(status, expires_at)
    WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_raw_cleanup
    ON dfetl_precheck_run(status, finished_at)
    WHERE raw_cleaned_at IS NULL AND status IN ('PASSED', 'HAS_ERRORS');

COMMENT ON TABLE dfetl_precheck_run IS 'Doris STRING 暂存层数据预检运行及小型汇总';
COMMENT ON TABLE dfetl_precheck_issue IS '历史数据预检问题明细；新暂存层运行的问题明细存储在 Doris';
COMMENT ON TABLE dfetl_precheck_export IS '数据预检问题异步导出任务和审计元数据';
COMMENT ON COLUMN dfetl_precheck_run.raw_cleaned_at IS '该运行在 Doris STRING 原始暂存中的精确批次清理完成时间';

COMMIT;

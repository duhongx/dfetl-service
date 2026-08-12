-- Spec 104: durable route/full and execution/window data precheck history.
BEGIN;

SET LOCAL search_path TO df_etl, public;

CREATE TABLE IF NOT EXISTS dfetl_precheck_run (
    id                  BIGSERIAL PRIMARY KEY,
    route_id            BIGINT NOT NULL REFERENCES institution_dataset_route(id) ON DELETE CASCADE,
    dataset_id          BIGINT NOT NULL REFERENCES dfetl_dataset(id),
    institution_id      BIGINT NOT NULL REFERENCES df_etl.institution(id),
    task_id              BIGINT REFERENCES sync_task(id) ON DELETE SET NULL,
    execution_id         BIGINT REFERENCES task_execution(id) ON DELETE SET NULL,
    retry_of_run_id      BIGINT REFERENCES dfetl_precheck_run(id) ON DELETE SET NULL,
    run_type             VARCHAR(30) NOT NULL,
    scope_type           VARCHAR(30) NOT NULL,
    window_start         TIMESTAMPTZ,
    window_end           TIMESTAMPTZ,
    window_start_id      BIGINT,
    window_end_id        BIGINT,
    contract_hash        VARCHAR(128) NOT NULL,
    route_revision       BIGINT NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scanned_rows         BIGINT NOT NULL DEFAULT 0,
    passed_rows          BIGINT NOT NULL DEFAULT 0,
    blocker_rows         BIGINT NOT NULL DEFAULT 0,
    warning_rows         BIGINT NOT NULL DEFAULT 0,
    fixed_issue_rows     BIGINT NOT NULL DEFAULT 0,
    error_message        TEXT,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dfetl_precheck_run_type
        CHECK (run_type IN ('ROUTE_FULL', 'EXECUTION_WINDOW')),
    CONSTRAINT ck_dfetl_precheck_scope
        CHECK (scope_type IN ('FULL', 'FULL_THEN_INCREMENT', 'INCREMENT')),
    CONSTRAINT ck_dfetl_precheck_status
        CHECK (status IN ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'ERROR')),
    CONSTRAINT ck_dfetl_precheck_counts CHECK (
        scanned_rows >= 0 AND passed_rows >= 0 AND blocker_rows >= 0
        AND warning_rows >= 0 AND fixed_issue_rows >= 0),
    CONSTRAINT ck_dfetl_precheck_revision CHECK (route_revision > 0)
);

CREATE TABLE IF NOT EXISTS dfetl_precheck_issue (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES dfetl_precheck_run(id) ON DELETE CASCADE,
    issue_key           VARCHAR(128) NOT NULL,
    source_row_hash     VARCHAR(64) NOT NULL,
    business_pk_json    TEXT,
    raw_row_json        TEXT NOT NULL,
    field_code          VARCHAR(100),
    field_name          VARCHAR(200),
    source_column       VARCHAR(100),
    target_column       VARCHAR(100),
    error_type          VARCHAR(50) NOT NULL,
    standard_rule       VARCHAR(500),
    raw_value           TEXT,
    normalized_value    TEXT,
    error_message       TEXT NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    remediation_status  VARCHAR(20) NOT NULL DEFAULT 'NEW',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dfetl_precheck_issue_key UNIQUE (run_id, issue_key),
    CONSTRAINT ck_dfetl_precheck_issue_severity
        CHECK (severity IN ('BLOCKER', 'WARNING')),
    CONSTRAINT ck_dfetl_precheck_remediation
        CHECK (remediation_status IN ('NEW', 'STILL_OPEN', 'FIXED'))
);

CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_route
    ON dfetl_precheck_run(route_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_execution
    ON dfetl_precheck_run(execution_id) WHERE execution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_run_status
    ON dfetl_precheck_run(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_issue_run
    ON dfetl_precheck_issue(run_id, severity, remediation_status, id);
CREATE INDEX IF NOT EXISTS idx_dfetl_precheck_issue_row
    ON dfetl_precheck_issue(run_id, source_row_hash);

COMMENT ON TABLE dfetl_precheck_run IS '标准数据集路由全量或任务执行窗口的数据预检批次';
COMMENT ON TABLE dfetl_precheck_issue IS '数据预检发现的完整问题明细及整改对比状态';

COMMIT;

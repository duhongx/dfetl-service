CREATE TABLE IF NOT EXISTS dfetl_rebuild_run (
    id                          BIGSERIAL      PRIMARY KEY,
    dataset_id                  BIGINT         NOT NULL REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
    dataset_code                VARCHAR(100)   NOT NULL,
    old_task_id                 BIGINT         REFERENCES sync_task(id) ON DELETE SET NULL,
    new_task_id                 BIGINT         REFERENCES sync_task(id) ON DELETE SET NULL,
    status                      VARCHAR(30)    NOT NULL,
    current_stage               VARCHAR(50),
    request_json                TEXT,
    error_message               TEXT,
    started_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    finished_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dfetl_rebuild_run_dataset
    ON dfetl_rebuild_run(dataset_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_dfetl_rebuild_run_status
    ON dfetl_rebuild_run(status, created_at DESC);

CREATE TABLE IF NOT EXISTS dfetl_rebuild_run_stage (
    id                          BIGSERIAL      PRIMARY KEY,
    run_id                      BIGINT         NOT NULL REFERENCES dfetl_rebuild_run(id) ON DELETE CASCADE,
    stage                       VARCHAR(50)    NOT NULL,
    status                      VARCHAR(30)    NOT NULL,
    message                     TEXT,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dfetl_rebuild_run_stage_run
    ON dfetl_rebuild_run_stage(run_id, id);

COMMENT ON TABLE dfetl_rebuild_run IS 'DFETL 数据集受控重建运行记录';
COMMENT ON TABLE dfetl_rebuild_run_stage IS 'DFETL 数据集受控重建阶段记录';

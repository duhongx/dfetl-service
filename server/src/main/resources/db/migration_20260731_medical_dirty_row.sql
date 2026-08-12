-- 医共体行级问题分流记录

CREATE TABLE IF NOT EXISTS medical_dirty_row (
    id               BIGSERIAL       PRIMARY KEY,
    task_id          BIGINT          NOT NULL REFERENCES sync_task(id),
    execution_id     BIGINT          NOT NULL REFERENCES task_execution(id),
    dataset_code     VARCHAR(100)    NOT NULL,
    dataset_name     VARCHAR(200),
    source_schema    VARCHAR(200),
    source_view      VARCHAR(200)    NOT NULL,
    target_table     VARCHAR(200),
    business_pk_json TEXT,
    source_row_hash  VARCHAR(64)     NOT NULL,
    window_json      TEXT,
    owner_name       VARCHAR(100),
    owner_source     VARCHAR(100),
    row_action       VARCHAR(50)     NOT NULL,
    severity         VARCHAR(50)     NOT NULL,
    status           VARCHAR(50)     NOT NULL DEFAULT 'OPEN',
    raw_row_json     TEXT,
    error_count      INTEGER         NOT NULL DEFAULT 0,
    found_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    handled_at       TIMESTAMPTZ,
    handled_by       VARCHAR(100),
    handle_note      TEXT,
    CONSTRAINT uk_medical_dirty_row_execution_dataset_hash
        UNIQUE (execution_id, dataset_code, source_row_hash)
);

CREATE TABLE IF NOT EXISTS medical_dirty_field (
    id               BIGSERIAL       PRIMARY KEY,
    dirty_row_id     BIGINT          NOT NULL REFERENCES medical_dirty_row(id) ON DELETE CASCADE,
    field_code       VARCHAR(100)    NOT NULL,
    field_name       VARCHAR(200),
    source_column    VARCHAR(200),
    target_column    VARCHAR(200),
    error_type       VARCHAR(80)     NOT NULL,
    standard_rule    VARCHAR(200),
    raw_value        TEXT,
    normalized_value TEXT,
    message          TEXT,
    severity         VARCHAR(50)     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_dataset_status
    ON medical_dirty_row(dataset_code, status);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_owner_status
    ON medical_dirty_row(owner_name, status);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_task_execution
    ON medical_dirty_row(task_id, execution_id);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_row_severity_found
    ON medical_dirty_row(severity, found_at DESC);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_row
    ON medical_dirty_field(dirty_row_id);
CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_error_type
    ON medical_dirty_field(error_type);

COMMENT ON TABLE medical_dirty_row IS '医共体行级问题记录，用于合规行写入后的问题行核对闭环';
COMMENT ON TABLE medical_dirty_field IS '医共体字段级问题明细';
COMMENT ON COLUMN medical_dirty_row.row_action IS 'EXCLUDED=该行未写入Doris | WRITTEN_WITH_WARNING=该行已写入但存在告警';
COMMENT ON COLUMN medical_dirty_row.status IS 'OPEN/SENT/CONFIRMED/FIXED/IGNORED';
COMMENT ON COLUMN medical_dirty_field.error_type IS 'PRIMARY_KEY_NULL/PRIMARY_KEY_DUPLICATE/NON_KEY_INVALID_NUMBER_TO_NULL 等标准化错误类型';

-- =============================================================================
-- [external-api-production] 2026-07-07 外部 API 生产化
--   external_api_client: 外部系统 HMAC client 与机构授权范围
--   external_api_request_nonce: HMAC nonce 防重放
--   external_task_batch_operation_audit: 批量运行 / 删除操作审计
-- =============================================================================

CREATE TABLE IF NOT EXISTS external_api_client (
    id                       BIGSERIAL    PRIMARY KEY,
    client_id                VARCHAR(100) NOT NULL UNIQUE,
    client_name              VARCHAR(100) NOT NULL,
    secret_enc               TEXT         NOT NULL,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    allowed_yi_liao_jg_dm    VARCHAR(50),
    description              TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_api_request_nonce (
    id           BIGSERIAL    PRIMARY KEY,
    client_id    VARCHAR(100) NOT NULL,
    nonce        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_external_api_request_nonce
    ON external_api_request_nonce(client_id, nonce);
CREATE INDEX IF NOT EXISTS idx_external_api_request_nonce_created
    ON external_api_request_nonce(created_at);

CREATE TABLE IF NOT EXISTS external_task_batch_operation_audit (
    id                    BIGSERIAL    PRIMARY KEY,
    external_batch_id     VARCHAR(128) NOT NULL,
    operation             VARCHAR(30)  NOT NULL,
    dry_run               BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(30)  NOT NULL,
    total_count           INTEGER      NOT NULL DEFAULT 0,
    success_count         INTEGER      NOT NULL DEFAULT 0,
    failed_count          INTEGER      NOT NULL DEFAULT 0,
    skipped_count         INTEGER      NOT NULL DEFAULT 0,
    caller                VARCHAR(100),
    client_id             VARCHAR(100),
    result_body           TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_external_task_batch_operation_batch
    ON external_task_batch_operation_audit(external_batch_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_external_task_batch_operation_client
    ON external_task_batch_operation_audit(client_id, created_at DESC)
    WHERE client_id IS NOT NULL;

COMMENT ON TABLE external_api_client IS '外部 API HMAC client、密钥密文和机构授权范围';
COMMENT ON COLUMN external_api_client.secret_enc IS 'AES 加密后的外部 API shared secret，禁止存明文';
COMMENT ON COLUMN external_api_client.allowed_yi_liao_jg_dm IS '允许访问的医疗机构编码；NULL 或 * 表示不限';
COMMENT ON TABLE external_api_request_nonce IS '外部 API HMAC nonce 防重放记录';
COMMENT ON TABLE external_task_batch_operation_audit IS '外部批量任务运行/删除操作审计记录';
COMMENT ON COLUMN external_task_batch_operation_audit.result_body IS '批量运行/删除响应 JSON 快照';

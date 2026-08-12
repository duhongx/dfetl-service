-- =============================================================================
-- 外部同步任务批量创建 API 幂等审计
-- spec: external-sync-task-batch-api
--
-- 说明：
--   external_task_batch_request 记录外部批次级幂等状态；
--   external_task_request 增加批次字段，记录每个 sourceObject 对应的 item 状态。
--
-- 执行：
--   psql -U df_etl -d df_ygt -f migration_external_sync_task_batch_api.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS external_task_batch_request (
    id                    BIGSERIAL    PRIMARY KEY,
    external_batch_id     VARCHAR(128) NOT NULL UNIQUE,
    yi_liao_jg_dm         VARCHAR(50)  NOT NULL,
    business_code         VARCHAR(50)  NOT NULL,
    request_hash          VARCHAR(128) NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    failure_policy        VARCHAR(30)  NOT NULL,
    total_count           INTEGER      NOT NULL DEFAULT 0,
    created_count         INTEGER      NOT NULL DEFAULT 0,
    existing_count        INTEGER      NOT NULL DEFAULT 0,
    failed_count          INTEGER      NOT NULL DEFAULT 0,
    request_body          TEXT,
    result_body           TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE external_task_request
    ADD COLUMN IF NOT EXISTS external_batch_id VARCHAR(128);
ALTER TABLE external_task_request
    ADD COLUMN IF NOT EXISTS batch_item_key VARCHAR(256);
ALTER TABLE external_task_request
    ADD COLUMN IF NOT EXISTS batch_item_status VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_external_task_batch_org_biz
    ON external_task_batch_request(yi_liao_jg_dm, business_code, status);
CREATE INDEX IF NOT EXISTS idx_external_task_request_batch
    ON external_task_request(external_batch_id)
    WHERE external_batch_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_task_request_batch_item
    ON external_task_request(external_batch_id, batch_item_key)
    WHERE external_batch_id IS NOT NULL AND batch_item_key IS NOT NULL;

COMMENT ON TABLE external_task_batch_request IS '外部批量任务创建请求幂等与解析审计记录';
COMMENT ON COLUMN external_task_batch_request.external_batch_id IS '外部批量请求幂等号；重复提交返回同一批结果';
COMMENT ON COLUMN external_task_batch_request.request_hash IS '批量请求业务字段 hash，用于识别 externalBatchId 复用冲突';
COMMENT ON COLUMN external_task_batch_request.result_body IS '批量创建结果 JSON';
COMMENT ON COLUMN external_task_request.external_batch_id IS '外部批量请求幂等号';
COMMENT ON COLUMN external_task_request.batch_item_key IS '批量请求内 sourceObject 稳定键，例如 schema.view';
COMMENT ON COLUMN external_task_request.batch_item_status IS '批量 item 状态：CREATED/EXISTING/FAILED/SKIPPED';

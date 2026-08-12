-- =============================================================================
-- 外部同步任务 API 幂等审计
-- spec: external-sync-task-api
--
-- 说明：
--   外部调用方只传医疗机构编码、业务编码和源对象。
--   后端通过现有 institution / task_group / source_datasource / target_datasource 配置自动解析数据源。
--   external_task_request 用于 externalRequestId 幂等、审计和故障排查。
--
-- 执行：
--   psql -U df_etl -d df_ygt -f migration_external_sync_task_api.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS external_task_request (
    id                    BIGSERIAL    PRIMARY KEY,
    external_request_id   VARCHAR(128) NOT NULL UNIQUE,
    caller                VARCHAR(100),
    yi_liao_jg_dm         VARCHAR(50)  NOT NULL,
    business_code         VARCHAR(50)  NOT NULL,
    source_schema         VARCHAR(100),
    source_object         VARCHAR(200) NOT NULL,
    source_object_type    VARCHAR(30),
    task_id               BIGINT       REFERENCES sync_task(id),
    status                VARCHAR(30)  NOT NULL,
    error_code            VARCHAR(80),
    error_message         TEXT,
    request_body          TEXT,
    resolved_plan         TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_external_task_request_task
    ON external_task_request(task_id)
    WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_external_task_request_org_biz
    ON external_task_request(yi_liao_jg_dm, business_code, source_object);

COMMENT ON TABLE external_task_request IS '外部任务创建请求幂等与解析审计记录';
COMMENT ON COLUMN external_task_request.external_request_id IS '调用方幂等请求号；重复提交返回同一内部任务';
COMMENT ON COLUMN external_task_request.yi_liao_jg_dm IS '医疗机构代码，不等同 tenantId';
COMMENT ON COLUMN external_task_request.resolved_plan IS '后端解析出的源/目标/医共体合约计划 JSON';

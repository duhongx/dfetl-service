-- =============================================================================
-- 告警通知字段补齐
--
-- 对应 alert-webhook-notification / alert-rule-evaluator-completion：
--   - alert_channel：钉钉/企微加签、@人、消息格式
--   - alert_rule：scopeValue 持久化
--
-- 生产部署时显式执行；幂等，可重复运行。
-- =============================================================================

ALTER TABLE df_etl.alert_channel ADD COLUMN IF NOT EXISTS secret TEXT;
ALTER TABLE df_etl.alert_channel ADD COLUMN IF NOT EXISTS mentioned_mobiles TEXT;
ALTER TABLE df_etl.alert_channel ADD COLUMN IF NOT EXISTS at_all BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE df_etl.alert_channel ADD COLUMN IF NOT EXISTS message_format VARCHAR(20) NOT NULL DEFAULT 'text';

ALTER TABLE df_etl.alert_rule ADD COLUMN IF NOT EXISTS scope_value VARCHAR(200);

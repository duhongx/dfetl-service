-- Spec 075 / MSG-1：统一消息发布全量模式的数据库默认值。
-- 只修改列默认值，不覆盖已有配置行已经显式选择的模式。

ALTER TABLE df_etl.message_publish_config
    ALTER COLUMN full_sync_mode SET DEFAULT 'SKIP';

COMMENT ON COLUMN df_etl.message_publish_config.full_sync_mode
    IS '全量同步模式：ALL/SKIP/NOTIFY_ONLY，默认 SKIP';

COMMENT ON COLUMN df_etl.message_publish_log.status
    IS '发布状态：SUCCESS/FAILED/PARTIAL/SKIPPED';

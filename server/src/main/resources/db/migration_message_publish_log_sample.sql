ALTER TABLE df_etl.message_publish_log ADD COLUMN IF NOT EXISTS sample_messages TEXT DEFAULT NULL;
COMMENT ON COLUMN df_etl.message_publish_log.sample_messages IS '本次发布的消息样本（前5条完整JSON数组），用于调试预览';

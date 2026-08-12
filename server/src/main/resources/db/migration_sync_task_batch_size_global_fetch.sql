-- =============================================================================
-- sync_task.batch_size 语义收口：任务级 JDBC fetch_size 覆盖值
--
-- 取值顺序：
--   1. sync_task.batch_size > 0：任务级覆盖
--   2. system_setting('etl.fetch_size') > 0：全局默认
--   3. 50000：代码兜底
--
-- 说明：
--   - 不强制 UPDATE 已有 system_setting.etl.fetch_size，避免覆盖用户已有调优值。
--   - 已有 sync_task.batch_size 值保留为任务级覆盖；如需继承全局，可手工置 NULL 或 0。
-- =============================================================================

ALTER TABLE sync_task ALTER COLUMN batch_size DROP DEFAULT;
ALTER TABLE sync_task ALTER COLUMN batch_size DROP NOT NULL;

COMMENT ON COLUMN sync_task.batch_size
    IS '任务级 JDBC fetch_size 覆盖值，NULL/0=继承全局 etl.fetch_size';

INSERT INTO system_setting(setting_key, setting_value, description)
VALUES ('etl.fetch_size', '50000', 'SeaTunnel JDBC source fetch_size，全局默认源端读取批量')
ON CONFLICT (setting_key) DO UPDATE
SET description = EXCLUDED.description;

UPDATE system_setting
SET description = '历史默认分片行数；SeaTunnel 主同步链路不消费'
WHERE setting_key = 'etl.default_batch_size';

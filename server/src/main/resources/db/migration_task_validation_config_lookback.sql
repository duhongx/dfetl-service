-- =============================================================================
-- task_validation_config validation profile hardening
--
-- 语义：
--   method NULL = 继承全局 validation_method
--   NULL = 继承全局 validation_lookback_hours
--   0    = 只验本次增量窗口，不额外回看
--   >0   = 基于本次窗口向前扩展 N 小时
--
-- 注意：JPA ddl-auto 默认为 none，必须显式执行该迁移。
-- =============================================================================

ALTER TABLE task_validation_config
    ADD COLUMN IF NOT EXISTS method VARCHAR(30);

ALTER TABLE task_validation_config
    ADD COLUMN IF NOT EXISTS auto_trigger BOOLEAN;

ALTER TABLE task_validation_config
    ADD COLUMN IF NOT EXISTS block_on_fail BOOLEAN;

ALTER TABLE task_validation_config
    ALTER COLUMN method DROP NOT NULL;

ALTER TABLE task_validation_config
    ALTER COLUMN method DROP DEFAULT;

ALTER TABLE task_validation_config
    ALTER COLUMN auto_trigger DROP NOT NULL;

ALTER TABLE task_validation_config
    ALTER COLUMN auto_trigger DROP DEFAULT;

ALTER TABLE task_validation_config
    ALTER COLUMN block_on_fail DROP NOT NULL;

ALTER TABLE task_validation_config
    ALTER COLUMN block_on_fail DROP DEFAULT;

COMMENT ON COLUMN task_validation_config.method
    IS '任务级校验方式；NULL=继承全局，ROW_COUNT/CHECKSUM/ROW_COUNT_CHECKSUM=覆盖全局';

COMMENT ON COLUMN task_validation_config.auto_trigger
    IS '任务级同步后自动触发开关；NULL=继承全局 validation_auto_enabled';

COMMENT ON COLUMN task_validation_config.block_on_fail
    IS '任务级校验失败阻断开关；NULL=继承全局 validation_fail_block';

UPDATE task_validation_config
   SET method = NULL
 WHERE method IS NOT NULL
   AND upper(trim(method)) NOT IN ('ROW_COUNT', 'CHECKSUM', 'ROW_COUNT_CHECKSUM');

ALTER TABLE task_validation_config
    ADD COLUMN IF NOT EXISTS validation_lookback_hours INT;

COMMENT ON COLUMN task_validation_config.validation_lookback_hours
    IS '任务级校验回看窗口小时；NULL=继承全局，0=只验本次窗口，>0=向前扩展N小时';

UPDATE system_setting
   SET setting_value = 'row_count'
 WHERE setting_key = 'validation_method'
   AND (setting_value IS NULL OR lower(trim(setting_value)) NOT IN ('row_count', 'checksum', 'row_count_checksum', 'all'));

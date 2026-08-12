-- spec 053: CronBuilder（可视化调度配置持久化）
-- 在保留旧 schedule/scheduleLabel/snapshot_auto_detect_cron 的基础上，新增统一的 schedule_config JSON
-- 后端权威：保存任务时按 schedule_config 重新生成 cron_expression / schedule_description
-- 数据库为 PostgreSQL，schedule_config 用 JSONB

ALTER TABLE df_etl.sync_task
    ADD COLUMN IF NOT EXISTS cron_expression       VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS schedule_config       TEXT         NULL,
    ADD COLUMN IF NOT EXISTS schedule_description  VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS schedule_timezone     VARCHAR(64)  NULL DEFAULT 'Asia/Shanghai';

COMMENT ON COLUMN df_etl.sync_task.cron_expression       IS 'spec 053 - Quartz Cron 表达式（由 scheduleConfig 后端生成；MANUAL 模式为 null）';
COMMENT ON COLUMN df_etl.sync_task.schedule_config       IS 'spec 053 - 可视化调度配置 JSON（mode/intervalMinutes/hour/.../version）';
COMMENT ON COLUMN df_etl.sync_task.schedule_description  IS 'spec 053 - 中文描述（如 "每天 02:30 执行"）';
COMMENT ON COLUMN df_etl.sync_task.schedule_timezone     IS 'spec 053 - 调度时区，默认 Asia/Shanghai';

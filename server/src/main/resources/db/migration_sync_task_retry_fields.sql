-- 同步任务新增自动重试配置字段
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS retry_max_attempts INTEGER DEFAULT NULL;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS retry_interval_seconds INTEGER DEFAULT NULL;

COMMENT ON COLUMN sync_task.retry_max_attempts IS '任务级自动重试最大次数，NULL=使用全局默认值(0=不重试)';
COMMENT ON COLUMN sync_task.retry_interval_seconds IS '任务级自动重试间隔秒数，NULL=使用全局默认值(30)';

-- 2026-08-05 RabbitMQ 生产者恢复与发布运行保护索引。
-- 仅新增恢复列与索引，不改写、不删除历史消息记录。

ALTER TABLE df_etl.message_publish_log
    ADD COLUMN IF NOT EXISTS retry_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE df_etl.message_publish_log
    ADD COLUMN IF NOT EXISTS next_retry_time TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_mpl_task_status_batch
    ON df_etl.message_publish_log(task_id, status, batch_id);

CREATE INDEX IF NOT EXISTS idx_mpl_recovery_scan
    ON df_etl.message_publish_log(next_retry_time, publish_time, id)
    WHERE status IN ('PENDING', 'RUNNING', 'WAIT_RETRY') AND batch_id > 0;

CREATE INDEX IF NOT EXISTS idx_msr_recovery_sending
    ON df_etl.message_send_record(send_start_time, id)
    WHERE channel_mode = 'RABBITMQ' AND send_status = 'SENDING';

CREATE INDEX IF NOT EXISTS idx_msr_recovery_retry
    ON df_etl.message_send_record(next_retry_time, id)
    WHERE channel_mode = 'RABBITMQ' AND send_status IN ('SEND_FAILED', 'WAIT_RETRY');

CREATE INDEX IF NOT EXISTS idx_msr_publish_log_status
    ON df_etl.message_send_record(publish_log_id, send_status);

-- =============================================================================
-- dfetl 本地逐条消息发送记录
-- 用途：证明 dfetl-server 何时开始发送、RabbitMQ confirm/return 的最终生产者侧状态
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS df_etl AUTHORIZATION df_etl;

CREATE TABLE IF NOT EXISTS df_etl.message_send_record (
    id                   BIGSERIAL     PRIMARY KEY,
    message_id           VARCHAR(64)   NOT NULL,
    task_id              BIGINT,
    batch_id             BIGINT,
    publish_log_id       BIGINT,
    channel_mode         VARCHAR(32)   NOT NULL,
    exchange_name        VARCHAR(128),
    route_key            VARCHAR(128)  NOT NULL,
    topic                VARCHAR(128)  NOT NULL,
    message_key          VARCHAR(256),
    business_key         VARCHAR(256),
    tenant_id            VARCHAR(64),
    source_system        VARCHAR(128),
    trace_id             VARCHAR(128),
    payload_type         VARCHAR(256)  NOT NULL DEFAULT 'com.dfygt.dfetl.server.service.publish.EtlMessage',
    message_json         TEXT          NOT NULL,
    payload_json         TEXT,
    headers_json         TEXT,
    send_status          VARCHAR(32)   NOT NULL,
    send_attempts        INTEGER       NOT NULL DEFAULT 0,
    send_start_time      TIMESTAMPTZ,
    broker_confirm_time  TIMESTAMPTZ,
    sent_time            TIMESTAMPTZ,
    next_retry_time      TIMESTAMPTZ,
    last_error           TEXT,
    external_record_status VARCHAR(32),
    external_record_time TIMESTAMPTZ,
    external_record_error TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_status VARCHAR(32);

ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_time TIMESTAMPTZ;

ALTER TABLE df_etl.message_send_record
    ADD COLUMN IF NOT EXISTS external_record_error TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_message_send_record_message_id
    ON df_etl.message_send_record(message_id);

CREATE INDEX IF NOT EXISTS idx_msr_task_batch
    ON df_etl.message_send_record(task_id, batch_id);

CREATE INDEX IF NOT EXISTS idx_msr_topic
    ON df_etl.message_send_record(topic);

CREATE INDEX IF NOT EXISTS idx_msr_route_key
    ON df_etl.message_send_record(route_key);

CREATE INDEX IF NOT EXISTS idx_msr_message_key
    ON df_etl.message_send_record(message_key);

CREATE INDEX IF NOT EXISTS idx_msr_send_status
    ON df_etl.message_send_record(send_status);

CREATE INDEX IF NOT EXISTS idx_msr_sent_time
    ON df_etl.message_send_record(sent_time);

CREATE INDEX IF NOT EXISTS idx_msr_external_record_status
    ON df_etl.message_send_record(external_record_status);

COMMENT ON TABLE df_etl.message_send_record IS 'dfetl 本地逐条消息发送记录，记录生产者侧 RabbitMQ 发送闭环';
COMMENT ON COLUMN df_etl.message_send_record.message_id IS '消息 ID，全链路唯一';
COMMENT ON COLUMN df_etl.message_send_record.task_id IS '关联同步任务 ID';
COMMENT ON COLUMN df_etl.message_send_record.batch_id IS '关联同步批次 ID；重发时使用新的负数批次 ID';
COMMENT ON COLUMN df_etl.message_send_record.publish_log_id IS '预留：关联批次级 message_publish_log ID';
COMMENT ON COLUMN df_etl.message_send_record.channel_mode IS '投递通道：RABBITMQ/REDIS';
COMMENT ON COLUMN df_etl.message_send_record.exchange_name IS 'RabbitMQ exchange';
COMMENT ON COLUMN df_etl.message_send_record.route_key IS 'RabbitMQ routing key / 消息 routeKey';
COMMENT ON COLUMN df_etl.message_send_record.topic IS '消息 body.topic';
COMMENT ON COLUMN df_etl.message_send_record.message_key IS '消息业务唯一键';
COMMENT ON COLUMN df_etl.message_send_record.business_key IS 'headers.businessKey';
COMMENT ON COLUMN df_etl.message_send_record.message_json IS '实际发送到 RabbitMQ 的完整 JSON body';
COMMENT ON COLUMN df_etl.message_send_record.payload_json IS 'body.payload JSON';
COMMENT ON COLUMN df_etl.message_send_record.headers_json IS 'body.headers JSON';
COMMENT ON COLUMN df_etl.message_send_record.send_status IS '发送状态：SENDING/SENT/SEND_FAILED/WAIT_RETRY/FAILED_FINAL';
COMMENT ON COLUMN df_etl.message_send_record.send_attempts IS '发送尝试次数，同一 messageId 重试时递增';
COMMENT ON COLUMN df_etl.message_send_record.broker_confirm_time IS 'RabbitMQ confirm/return 回写时间';
COMMENT ON COLUMN df_etl.message_send_record.sent_time IS 'RabbitMQ confirm ack 成功时间';
COMMENT ON COLUMN df_etl.message_send_record.last_error IS '最后一次发送失败原因';
COMMENT ON COLUMN df_etl.message_send_record.external_record_status IS '外部医共体 msg_send 写入状态：WAIT_SEND/SENT/SEND_FAILED';
COMMENT ON COLUMN df_etl.message_send_record.external_record_time IS '外部医共体 msg_send 状态更新时间';
COMMENT ON COLUMN df_etl.message_send_record.external_record_error IS '外部医共体 msg_send 最后写入错误';

ALTER SCHEMA df_etl OWNER TO df_etl;
ALTER TABLE df_etl.message_send_record OWNER TO df_etl;
ALTER SEQUENCE IF EXISTS df_etl.message_send_record_id_seq OWNER TO df_etl;

GRANT USAGE ON SCHEMA df_etl TO df_etl;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE df_etl.message_send_record TO df_etl;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE df_etl.message_send_record_id_seq TO df_etl;

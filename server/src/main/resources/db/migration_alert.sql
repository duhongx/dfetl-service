-- alert_channel: 通知渠道（Webhook）
CREATE TABLE IF NOT EXISTS alert_channel (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    type              VARCHAR(20)  NOT NULL,         -- dingtalk | wecom
    webhook_url       TEXT         NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT true,
    last_tested_at    TIMESTAMPTZ,
    last_test_status  VARCHAR(20)  DEFAULT 'untested', -- ok | fail | untested
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- alert_rule: 告警规则
CREATE TABLE IF NOT EXISTS alert_rule (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    metric           VARCHAR(50)  NOT NULL,
    "condition"      VARCHAR(20)  NOT NULL,
    threshold        VARCHAR(200) NOT NULL,
    severity         VARCHAR(20)  NOT NULL DEFAULT 'warning',  -- critical | warning | info
    scope_type       VARCHAR(20)  DEFAULT 'all',               -- all | group | task
    scope_value      VARCHAR(200),
    channel_ids      TEXT         DEFAULT '[]',                -- JSON 数组
    silence_minutes  INTEGER      DEFAULT 60,
    last_triggered_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

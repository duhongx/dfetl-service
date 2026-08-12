-- =============================================================================
-- Doris 类型映射规则表迁移脚本
-- 适用：医疗视图同步场景中，源端 MySQL/Oracle/PostgreSQL/SQL Server 字段
--      到 Doris 类型的默认映射与风险等级可由系统配置维护。
-- 幂等：重复执行安全。
-- =============================================================================

CREATE TABLE IF NOT EXISTS doris_type_mapping_rule (
    id                      BIGSERIAL PRIMARY KEY,
    profile_name            VARCHAR(64)  NOT NULL DEFAULT 'DEFAULT_MEDICAL_VIEW',
    profile_version         INTEGER      NOT NULL DEFAULT 1,
    source_dialect          VARCHAR(32)  NOT NULL,
    source_type_pattern     VARCHAR(128) NOT NULL,
    recommended_doris_type  VARCHAR(128) NOT NULL,
    compatibility_level     VARCHAR(16)  NOT NULL DEFAULT 'PASS',
    reason                  TEXT,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    priority                INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_doris_type_mapping_rule UNIQUE (profile_name, source_dialect, source_type_pattern)
);

CREATE INDEX IF NOT EXISTS idx_doris_type_mapping_rule_enabled
    ON doris_type_mapping_rule(enabled, source_dialect, priority DESC);

INSERT INTO doris_type_mapping_rule
    (profile_name, profile_version, source_dialect, source_type_pattern, recommended_doris_type,
     compatibility_level, reason, enabled, priority)
VALUES
    ('DEFAULT_MEDICAL_VIEW', 1, 'MYSQL',      'TIMESTAMP',                       'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'ORACLE',     'TIMESTAMP WITH TIME ZONE',         'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'ORACLE',     'TIMESTAMP WITH LOCAL TIME ZONE',   'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'POSTGRESQL', 'TIMESTAMPTZ',                     'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'POSTGRESQL', 'TIMESTAMP WITH TIME ZONE',         'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100),
    ('DEFAULT_MEDICAL_VIEW', 1, 'SQLSERVER',  'DATETIMEOFFSET',                  'DATETIME', 'PASS', '医疗视图时间字段按年月日时分秒写入 Doris DATETIME', TRUE, 100)
ON CONFLICT (profile_name, source_dialect, source_type_pattern) DO NOTHING;

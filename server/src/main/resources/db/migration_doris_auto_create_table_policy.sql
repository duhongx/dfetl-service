-- Doris 自动建表策略默认配置（幂等）
-- 说明：复用 system_setting KV 表，不新增业务表。

INSERT INTO system_setting (setting_key, setting_value, description) VALUES
    ('doris.auto_create.partition.enabled',        'false', 'Doris 自动建表是否生成时间分区'),
    ('doris.auto_create.partition.field',          'xiugaisj', 'Doris 自动分区字段，医疗视图默认 xiugaisj'),
    ('doris.auto_create.partition.granularity',    'MONTH', 'Doris 自动分区粒度：MONTH 或 DAY'),
    ('doris.auto_create.partition.history_months', '36', '按月分区时创建最近 N 个月历史分区'),
    ('doris.auto_create.partition.future_months',  '6', '按月分区时预建未来 N 个月分区'),
    ('doris.auto_create.partition.history_days',   '90', '按日分区时创建最近 N 天历史分区'),
    ('doris.auto_create.partition.future_days',    '30', '按日分区时预建未来 N 天分区'),
    ('doris.auto_create.bucket.strategy',          'FIXED', 'Doris 自动建表 bucket 策略：FIXED 或 DATA_SCALE'),
    ('doris.auto_create.bucket.fixed',             '10', 'Doris 自动建表固定 bucket 数'),
    ('doris.auto_create.bucket.tier.lt_100k',      '1', '预计数据量小于 10 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.100k_1m',      '2', '预计数据量 10 万到 100 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.1m_10m',       '4', '预计数据量 100 万到 1000 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.10m_50m',      '8', '预计数据量 1000 万到 5000 万行时 bucket 数'),
    ('doris.auto_create.bucket.tier.50m_200m',     '16', '预计数据量 5000 万到 2 亿行时 bucket 数'),
    ('doris.auto_create.bucket.tier.200m_1b',      '32', '预计数据量 2 亿到 10 亿行时 bucket 数'),
    ('doris.auto_create.bucket.tier.gt_1b',        '64', '预计数据量大于 10 亿行时 bucket 数')
ON CONFLICT (setting_key) DO NOTHING;

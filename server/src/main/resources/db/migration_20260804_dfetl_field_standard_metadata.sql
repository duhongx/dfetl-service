-- 为 dfetl_field 补充医共体/外部系统字段标准定义元数据。
-- 这些字段会进入 dfetl_dataset 配置指纹，用于判断已生成任务是否过期。

ALTER TABLE dfetl_field
    ADD COLUMN IF NOT EXISTS standard_type VARCHAR(30);

ALTER TABLE dfetl_field
    ADD COLUMN IF NOT EXISTS standard_format VARCHAR(100);

ALTER TABLE dfetl_field
    ADD COLUMN IF NOT EXISTS standard_version VARCHAR(50);

ALTER TABLE dfetl_field
    ADD COLUMN IF NOT EXISTS value_domain_source VARCHAR(100);

ALTER TABLE dfetl_field
    ADD COLUMN IF NOT EXISTS value_domain_version VARCHAR(50);

COMMENT ON COLUMN dfetl_field.standard_type IS '字段标准类型，例如 S1/S2/S3/N/D/DT';
COMMENT ON COLUMN dfetl_field.standard_format IS '字段标准表示格式，例如 AN..50、N..8,2、D8、DT15';
COMMENT ON COLUMN dfetl_field.standard_version IS '字段标准定义版本';
COMMENT ON COLUMN dfetl_field.value_domain_source IS '值域定义来源，例如 JY_WSSY_ZHIYU';
COMMENT ON COLUMN dfetl_field.value_domain_version IS '值域定义版本';

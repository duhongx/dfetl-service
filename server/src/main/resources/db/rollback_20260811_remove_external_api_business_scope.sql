-- 仅用于回滚数据库结构；应用代码已不再读取该字段。
ALTER TABLE external_api_client
    ADD COLUMN IF NOT EXISTS allowed_business_code VARCHAR(50);

COMMENT ON COLUMN external_api_client.allowed_business_code IS '允许访问的业务编码；NULL 或 * 表示不限';
COMMENT ON TABLE external_api_client IS '外部 API HMAC client、密钥密文和机构/业务授权范围';

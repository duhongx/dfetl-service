-- 外部授权已统一收敛为机构范围，删除废弃的业务范围字段。
ALTER TABLE external_api_client
    DROP COLUMN IF EXISTS allowed_business_code;

COMMENT ON TABLE external_api_client IS '外部 API HMAC client、密钥密文和机构授权范围';

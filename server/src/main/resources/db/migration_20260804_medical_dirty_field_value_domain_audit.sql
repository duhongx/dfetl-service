-- =============================================================================
-- 2026-08-04  医共体问题字段值域审计字段
-- =============================================================================

ALTER TABLE medical_dirty_field
    ADD COLUMN IF NOT EXISTS value_domain_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS value_domain_mode VARCHAR(30),
    ADD COLUMN IF NOT EXISTS value_domain_allowed_count INTEGER;

CREATE INDEX IF NOT EXISTS idx_medical_dirty_field_value_domain
    ON medical_dirty_field(value_domain_code);

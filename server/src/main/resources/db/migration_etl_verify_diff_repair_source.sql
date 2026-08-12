-- =============================================================================
-- Spec: validation-workbench-redesign · Task P1-6.1
-- Validates: Requirement 6 (AC 1) + Property 7
--
-- 给 etl_verify_diff 表加 repair_source 列，区分修复来源：
--   AUTO   — 自动修复（AutoValidationTrigger / autoRepair 调度产生）
--   MANUAL — 用户主动（运维点「确认修复」按钮）
--   NULL   — 未修复（repair_status = PENDING）或 本需求落地前的历史记录
--
-- 注意：不补默认值，存量记录保持 NULL；UI 上展示为「来源未知」灰色虚线徽章
-- （Requirement 6 AC 2/3）。
--
-- 执行方式：上传 /tmp/ 后手动 psql 执行。
-- =============================================================================

BEGIN;

ALTER TABLE etl_verify_diff
    ADD COLUMN IF NOT EXISTS repair_source VARCHAR(16) DEFAULT NULL;

COMMENT ON COLUMN etl_verify_diff.repair_source IS
'Repair 来源（spec validation-workbench-redesign）：AUTO=自动修复 / MANUAL=用户主动 / NULL=未修复或本需求落地前历史数据';

COMMIT;

-- =============================================================================
-- 上线后验证 SQL
-- =============================================================================
-- -- 1. 列结构
-- SELECT column_name, data_type, character_maximum_length, is_nullable
--   FROM information_schema.columns
--  WHERE table_name = 'etl_verify_diff' AND column_name = 'repair_source';
--
-- -- 2. 来源分布（落地后应观察到 AUTO/MANUAL 两类）
-- SELECT repair_source, count(*) FROM etl_verify_diff GROUP BY repair_source;
--
-- -- 3. repair_status != PENDING 但 repair_source = NULL 的记录数
-- --    （应仅为本次落地前的历史数据；落地后新数据该值应为 0）
-- SELECT count(*) FROM etl_verify_diff
--  WHERE repair_status != 'PENDING' AND repair_source IS NULL;
-- =============================================================================

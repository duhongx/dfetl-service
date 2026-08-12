-- =============================================================================
-- Spec: validation-workbench-redesign · Task P1-5.1 / P1-5.2
-- Validates: Requirement 4 (AC 2) + Requirement 5 (AC 1, 4) + Property 5
--
-- 给 validation_run 表加 trigger_type 列，区分校验来源：
--   AUTO        — AutoValidationTrigger（同步成功后自动）
--   AUTO_COUNT  — L1 ROW_COUNT 哨兵（SeaTunnelExecutorStrategy 写入）
--   MANUAL      — 用户在工作台/列表页手动触发
--   DRIFT       — DriftWatchService（driftCron 定时漂移检测）
--   GATE        — ValidationGateService（门控校验，block 同步）
--   MANUAL_REPAIR_RECHECK — Repair 闭环 B 异步 ROW_COUNT 复查（P1-12.1）
--
-- 历史记录保持 NULL，UI 上展示为「未知触发类型」灰色徽章（Requirement 4 AC 3）。
--
-- 执行方式：上传 /tmp/ 后手动 psql 执行。
-- =============================================================================

BEGIN;

-- 1. 添加 trigger_type 列（可空字符串，长度 32 容纳所有枚举 + 未来扩展）
ALTER TABLE validation_run
    ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(32) DEFAULT NULL;

-- 2. 列注释
COMMENT ON COLUMN validation_run.trigger_type IS
'校验触发来源（spec validation-workbench-redesign）：AUTO=同步后自动 / AUTO_COUNT=L1 行数哨兵 / '
'MANUAL=用户主动 / DRIFT=定时漂移 / GATE=门控 / MANUAL_REPAIR_RECHECK=修复后异步复查；NULL=本需求落地前历史数据';

-- 3. 索引：执行历史 Tab 按 task_id + 时间倒序读，trigger_type 仅做着色不需要单列索引；
--    但 AUTO_COUNT 归档脚本（P1-9.3）按 trigger_type + created_at 删除，需要复合索引
CREATE INDEX IF NOT EXISTS idx_validation_run_trigger_created
    ON validation_run(trigger_type, created_at);

COMMIT;

-- =============================================================================
-- 上线后验证 SQL
-- =============================================================================
-- -- 检查列是否存在
-- SELECT column_name, data_type, character_maximum_length, is_nullable
--   FROM information_schema.columns
--  WHERE table_name = 'validation_run' AND column_name = 'trigger_type';
--
-- -- 检查索引
-- SELECT indexname, indexdef FROM pg_indexes
--  WHERE tablename = 'validation_run' AND indexname = 'idx_validation_run_trigger_created';
--
-- -- 历史 NULL 数据计数（应 = 表总行数；Java 端写入新数据后会下降）
-- SELECT count(*) FROM validation_run WHERE trigger_type IS NULL;
-- SELECT trigger_type, count(*) FROM validation_run GROUP BY trigger_type;
-- =============================================================================

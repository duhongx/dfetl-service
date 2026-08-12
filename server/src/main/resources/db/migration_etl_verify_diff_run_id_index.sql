-- =============================================================================
-- Spec: validation-workbench-redesign · Task P0-1.2
-- Validates: Requirement 1 (AC 6) — etl_verify_diff(validation_run_id) 索引
--            保证 ValidationGoalSummaryService.diffRows 的 count(*) 查询
--            走索引扫描，最近 1 小时滑动窗口 P95 < 200ms、单次最大 < 1000ms
--
-- 索引现状说明（2026-05 截止）
--   - init.sql (spec 023, 2026-04-29) 已建立同列索引 idx_verify_diff_run
--   - entity/EtlVerifyDiff.java 通过 JPA @Index 标注同名索引
--   - 任务原文要求名称 idx_evd_run_id；为避免与现有 idx_verify_diff_run
--     形成等价重复索引（浪费空间且无收益），本脚本沿用现网命名
--     idx_verify_diff_run 作为权威源，与 init.sql / JPA Entity 单源对齐
--   - 所有创建语句使用 CREATE INDEX IF NOT EXISTS 与 pg_indexes 双重保护，
--     全新部署、二次执行、增量升级三种场景均可幂等
--
-- 执行方式
--   - 不使用 Flyway，按仓库约定上传 /tmp/ 后手动 psql 执行：
--     psql "$DF_ETL_DB_URL" -f /tmp/migration_etl_verify_diff_run_id_index.sql
--
-- 关联文件
--   - server/src/main/resources/db/init.sql (spec 023 表结构 + 索引)
--   - server/src/main/java/com/dfygt/dfetl/server/entity/EtlVerifyDiff.java
--   - server/src/main/java/com/dfygt/dfetl/server/service/ValidationGoalSummaryService.java
-- =============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 1. 索引保障：若 validation_run_id 列上已存在任意 B-tree 索引则跳过
-- ----------------------------------------------------------------------------
-- 直接 CREATE INDEX IF NOT EXISTS 仅按索引名判重，无法识别「同列不同名」
-- 的等价索引；此处用 pg_indexes + pg_index 元数据二次校验，确保不创建重复索引。
DO $$
DECLARE
    existing_index_count INTEGER;
    existing_index_name  TEXT;
BEGIN
    SELECT COUNT(*), MIN(i.relname)
      INTO existing_index_count, existing_index_name
      FROM pg_class t
      JOIN pg_index ix ON ix.indrelid = t.oid
      JOIN pg_class i  ON i.oid = ix.indexrelid
      JOIN pg_attribute a ON a.attrelid = t.oid
                          AND a.attnum = ANY (ix.indkey)
     WHERE t.relname = 'etl_verify_diff'
       AND a.attname = 'validation_run_id'
       AND ix.indnatts = 1;  -- 仅 validation_run_id 单列索引；联合索引不计

    IF existing_index_count > 0 THEN
        RAISE NOTICE '[spec validation-workbench-redesign · P0-1.2] '
                     'etl_verify_diff(validation_run_id) 已存在单列索引 % ，跳过创建。',
                     existing_index_name;
    ELSE
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_verify_diff_run '
             || 'ON etl_verify_diff(validation_run_id)';
        RAISE NOTICE '[spec validation-workbench-redesign · P0-1.2] '
                     '已为 etl_verify_diff(validation_run_id) 创建索引 idx_verify_diff_run。';
    END IF;
END $$;

-- 与 init.sql / JPA Entity 命名保持单源；幂等兜底，便于全新部署直接执行本脚本。
CREATE INDEX IF NOT EXISTS idx_verify_diff_run
    ON etl_verify_diff(validation_run_id);

-- ----------------------------------------------------------------------------
-- 2. ANALYZE：刷新统计信息，确保 PG 优化器可立即选用新索引
-- ----------------------------------------------------------------------------
ANALYZE etl_verify_diff;

COMMIT;

-- =============================================================================
-- 3. 上线后验证 SQL（仅供运维参考，不在迁移事务内执行）
--    要求：在生产 PG 上对真实数据执行 EXPLAIN ANALYZE，确认走 Index Scan
--    且 P95 < 200ms（单次响应 < 1000ms 上限）。
--    采样：建议挑选差异规模 0 / 100 / 1000 / 10000 / 100000 五个 runId 各跑一轮。
-- =============================================================================
-- -- 3.1 期望计划：Index Only Scan / Index Scan using idx_verify_diff_run
-- EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
-- SELECT count(*)
--   FROM etl_verify_diff
--  WHERE validation_run_id = :run_id;
--
-- -- 3.2 分类计数（与 ValidationGoalSummaryService 同口径，验证加和守恒）
-- EXPLAIN (ANALYZE, BUFFERS)
-- SELECT diff_type, count(*)
--   FROM etl_verify_diff
--  WHERE validation_run_id = :run_id
--  GROUP BY diff_type;
--
-- -- 3.3 索引体积与膨胀检查（运维回访）
-- SELECT indexname,
--        pg_size_pretty(pg_relation_size(indexrelid))    AS index_size,
--        idx_scan, idx_tup_read, idx_tup_fetch
--   FROM pg_stat_user_indexes
--   JOIN pg_indexes USING (schemaname, indexrelname)
--  WHERE tablename = 'etl_verify_diff'
--    AND indexname IN ('idx_verify_diff_run');
--
-- -- 3.4 P95 抽样脚本（pgbench 或自定义 shell）
-- --     for run_id in $(psql -At -c "SELECT id FROM validation_run ORDER BY id DESC LIMIT 100"); do
-- --       psql -c "EXPLAIN ANALYZE SELECT count(*) FROM etl_verify_diff WHERE validation_run_id = $run_id"
-- --     done | awk '/Execution Time/{print $3}' | sort -n
-- =============================================================================

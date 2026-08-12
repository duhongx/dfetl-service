-- =============================================================================
-- 机构管理（Institution Management）— Phase 3 复合索引补建
-- spec: .kiro/specs/institution-management
-- task: 15.1 检查并补建复合索引
--
-- 背景：
--   Phase 1 的 migration_institution.sql 已建立单列索引：
--     - sync_task(institution_id)            部分索引，WHERE institution_id IS NOT NULL
--     - source_datasource(institution_id)    部分索引
--     - df_etl.batch_task_template_source(institution_id)
--   InstitutionQueryService 提供两类典型业务查询：
--     A) 按机构 ID + 状态汇总同步任务（管理页统计、按机构查任务）
--     B) 按目标表反查贡献机构
--
-- 决策：
--   1) idx_sync_task_inst_status — 新建复合索引 (institution_id, status)
--      用于 Tab B「按机构查任务」、机构数据视图统计 statusSummary 时的过滤聚合。
--
--   2) idx_sync_task_target_inst — 「不建」
--      原因：sync_task 表没有 target_table 列。目标表名存储于
--      sync_task.target_table_map（TEXT/JSON），格式 {"源表名":"目标表名"}，
--      由 InstitutionQueryService.taskMatchesTargetTable 在内存中解析匹配。
--      复合索引 (target_table, institution_id) 不适用；JSON 表达式索引收益
--      有限，且当前阶段 sync_task 数据规模在万级以内，已规划在数据规模继续
--      增长后再做物化视图或反规范化（见 InstitutionQueryService 顶部注释）。
--      因此本任务仅落地索引 1。
--
-- 执行：
--   psql -U dfetl -d dfetl_meta -f migration_institution_indexes.sql
--   可重复执行（IF NOT EXISTS 保护）。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (institution_id, status) 复合索引
--
-- 服务查询：
--   SELECT ... FROM sync_task
--    WHERE institution_id = ? AND status = ?
--   或在内存聚合前的 WHERE institution_id IN (...) 后再按 status 分组。
--
-- 部分索引：仅索引 institution_id IS NOT NULL 行，与
-- idx_sync_task_institution 保持一致策略，避免对历史未关联机构的行做无效维护。
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_sync_task_inst_status
    ON sync_task(institution_id, status)
    WHERE institution_id IS NOT NULL;

COMMENT ON INDEX idx_sync_task_inst_status IS
    '机构维度任务过滤/状态聚合：InstitutionQueryService.listTasksByInstitution、statusSummary';


-- =============================================================================
-- 验证查询（手工执行）
-- =============================================================================
-- 列出 sync_task 上的所有索引：
--   SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'sync_task';
-- 检查索引是否被使用：
--   EXPLAIN (ANALYZE, BUFFERS)
--     SELECT id, name, status FROM sync_task
--      WHERE institution_id = 1 AND status = 'ENABLED';

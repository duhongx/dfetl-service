-- =============================================================================
-- source_datasource 关联修复（B 方案后续）
-- spec: task-group-business-line-realign 配套
--
-- 背景：
--   migration_task_group_realign.sql 删除了旧的医院/环境性质分组（id 1/2/3/4）
--   并新建了业务条线分组（id 8 HIS / 9 LIS / 10 PACS），但只重打了
--   sync_task.group_id，遗漏了 source_datasource.group_id 与
--   source_datasource.institution_id 的修复。
--
--   现状（执行前）：
--   - source_datasource.group_id = 1/2/3/4 → 全部悬挂（旧分组已删除）
--   - source_datasource.institution_id 部分是 Phase 1 反推时基于已变更的
--     sync_task 关联得到的脏值（如 zyy-oracle 名字是中医院但归到了人民医院）
--
-- 修复策略（基于 sync_task 反推 + 兜底人工）：
--   - 被 sync_task 引用的数据源：取众数 group_id 与众数 institution_id
--   - 未被 sync_task 引用的数据源（含目标端 Doris、未投入使用的源端）：
--       group_id 与 institution_id 全部 NULL，由用户在 UI 重新指定
--
-- 执行：
--   psql -U df_etl -d df_ygt -f migration_source_datasource_realign.sql
--   全程包在事务中，可整体 ROLLBACK 撤销。
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- ① 先把所有 source_datasource 的 group_id 和 institution_id 清零
--    避免悬挂引用 + 旧脏数据继续误导用户
-- -----------------------------------------------------------------------------
UPDATE df_etl.source_datasource
   SET group_id       = NULL,
       institution_id = NULL;


-- -----------------------------------------------------------------------------
-- ② 基于 sync_task 反推：取众数 group_id 与 institution_id
--
--    实现：
--    - 对每个被 sync_task 引用的 source_datasource_id，分别按 group_id 与
--      institution_id 计数排序，取出现次数最多的那个值。
--    - DISTINCT ON 配合 ORDER BY count DESC, value ASC 保证稳定（计数相同时
--      取较小 ID，便于排查）。
-- -----------------------------------------------------------------------------
WITH grp_mode AS (
    SELECT DISTINCT ON (source_datasource_id)
           source_datasource_id, group_id, count(*) AS cnt
      FROM df_etl.sync_task
     WHERE group_id IS NOT NULL
     GROUP BY source_datasource_id, group_id
     ORDER BY source_datasource_id, count(*) DESC, group_id ASC
),
inst_mode AS (
    SELECT DISTINCT ON (source_datasource_id)
           source_datasource_id, institution_id, count(*) AS cnt
      FROM df_etl.sync_task
     WHERE institution_id IS NOT NULL
     GROUP BY source_datasource_id, institution_id
     ORDER BY source_datasource_id, count(*) DESC, institution_id ASC
)
UPDATE df_etl.source_datasource ds
   SET group_id       = COALESCE(g.group_id, ds.group_id),
       institution_id = COALESCE(i.institution_id, ds.institution_id)
  FROM grp_mode g
  FULL OUTER JOIN inst_mode i ON i.source_datasource_id = g.source_datasource_id
 WHERE ds.id = COALESCE(g.source_datasource_id, i.source_datasource_id);


-- =============================================================================
-- 验证（手工执行，COMMIT 前后均可）
-- =============================================================================
-- 修复后状态：
--   SELECT ds.id, ds.name, ds.type, ds.group_id, tg.name AS 分组,
--          ds.institution_id, i.name AS 机构
--     FROM df_etl.source_datasource ds
--     LEFT JOIN df_etl.task_group  tg ON tg.id = ds.group_id
--     LEFT JOIN df_etl.institution i  ON i.id = ds.institution_id
--    ORDER BY ds.id;
--
-- 仍然为空的数据源（用户需要在 UI 手动配置）：
--   SELECT id, name, type FROM df_etl.source_datasource
--    WHERE group_id IS NULL OR institution_id IS NULL ORDER BY id;

COMMIT;

-- =============================================================================
-- 任务分组语义重定义（B 方案）— 数据迁移
-- spec: task-group-business-line-realign（与 institution-management 互补）
--
-- 背景：
--   原 task_group 表混合了「医院/机构」与「环境」两类语义（截图所见：
--   xx县全民健康平台 / xx县人民医院 / xx县中医院 / 东昉预发环境），
--   实际上「医院」属于 institution 主表的范畴，分组应只承载业务条线
--   （HIS / LIS / PACS / 病理 …）。本次迁移把两类语义解耦：
--     - institution.id → 任务从哪来（强归属）
--     - task_group.id  → 任务属于哪条业务线（标签）
--   sync_task 同时持有 institution_id + group_id，二者正交。
--
-- 处理对象：当前生产 sync_task 共 10 条，旧 group_id 取值 1/2/3/4。
--
-- 映射规则：
--   旧 group_id=1 (xx县全民健康平台) → 新建机构 YGT-004
--   旧 group_id=2 (xx县人民医院)     → 已有机构 id=1 (YGT001 县人民医院)
--   旧 group_id=3 (xx县中医院)       → 已有机构 id=2 (YGT-002 县中医院)
--   旧 group_id=4 (东昉预发环境)     → 新建机构 YGT-TEST（占位测试机构，用户后续改名）
--
-- 业务条线分类规则（视图名启发式）：
--   v_gy_jianyan*（检验项目）→ LIS
--   其余（v_yl_*/v_gy_*/zy_*/yf_*/gy_*） → HIS
--   PACS / 病理 暂无任务，仅建分组备用
--
-- 执行：
--   psql -U df_etl -d df_ygt -f migration_task_group_realign.sql
--   全程包在事务中；可整体 ROLLBACK 撤销。
--
-- 注意：不可重复执行 —— DELETE 旧 group 记录是不可逆的。
--   建议先 dry-run（注释 COMMIT 改为 ROLLBACK）观察结果再放开提交。
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- ① 新建缺失的机构（旧分组里语义为「医院/平台」的两条）
-- -----------------------------------------------------------------------------
INSERT INTO df_etl.institution (code, name, enabled, description)
VALUES ('YGT-004', 'xx县全民健康平台', true,
        '从原 task_group 迁移：业务平台主体（运维侧使用）')
ON CONFLICT (code) DO NOTHING;

INSERT INTO df_etl.institution (code, name, enabled, description)
VALUES ('YGT-TEST', 'XX县XX医院（测试）', true,
        '从原 task_group「东昉预发环境」迁移；占位测试机构，后续在管理页改名')
ON CONFLICT (code) DO NOTHING;


-- -----------------------------------------------------------------------------
-- ② 回填 sync_task.institution_id（按旧 group_id 映射）
--    仅处理 institution_id 仍为 NULL 的记录，幂等。
-- -----------------------------------------------------------------------------
UPDATE df_etl.sync_task
   SET institution_id = (SELECT id FROM df_etl.institution WHERE code='YGT-004')
 WHERE group_id = 1 AND institution_id IS NULL;

UPDATE df_etl.sync_task
   SET institution_id = (SELECT id FROM df_etl.institution WHERE code='YGT001')
 WHERE group_id = 2 AND institution_id IS NULL;

UPDATE df_etl.sync_task
   SET institution_id = (SELECT id FROM df_etl.institution WHERE code='YGT-002')
 WHERE group_id = 3 AND institution_id IS NULL;

UPDATE df_etl.sync_task
   SET institution_id = (SELECT id FROM df_etl.institution WHERE code='YGT-TEST')
 WHERE group_id = 4 AND institution_id IS NULL;


-- -----------------------------------------------------------------------------
-- ③ 顺便回填 source_datasource.institution_id（基于 sync_task 反推）
--    一个数据源被多个任务引用且任务机构不一致时，取任意一条（首条命中）。
--    生产侧当前 10 个任务都共用 sourceDatasourceId=54，将取首次命中的机构；
--    后续在管理页可手工修正。
-- -----------------------------------------------------------------------------
UPDATE df_etl.source_datasource ds
   SET institution_id = sub.institution_id
  FROM (
        SELECT DISTINCT ON (st.source_datasource_id)
               st.source_datasource_id, st.institution_id
          FROM df_etl.sync_task st
         WHERE st.institution_id IS NOT NULL
        ORDER BY st.source_datasource_id, st.id
       ) sub
 WHERE ds.id = sub.source_datasource_id
   AND ds.institution_id IS NULL;


-- -----------------------------------------------------------------------------
-- ④ 新建标准业务分组（HIS / LIS / PACS）
--    name 在 task_group 表上是 UNIQUE，DO NOTHING 即幂等。
-- -----------------------------------------------------------------------------
INSERT INTO df_etl.task_group (name, description, status, created_at, updated_at)
VALUES ('HIS业务', '医院信息系统：门诊/住院/医嘱/费用/药房等', 'ACTIVE', now(), now())
ON CONFLICT (name) DO NOTHING;

INSERT INTO df_etl.task_group (name, description, status, created_at, updated_at)
VALUES ('LIS业务', '检验信息系统：检验项目/报告/样本等', 'ACTIVE', now(), now())
ON CONFLICT (name) DO NOTHING;

INSERT INTO df_etl.task_group (name, description, status, created_at, updated_at)
VALUES ('PACS业务', '影像归档系统：影像检查/报告等（暂无关联任务，备用）', 'ACTIVE', now(), now())
ON CONFLICT (name) DO NOTHING;


-- -----------------------------------------------------------------------------
-- ⑤ 重打 sync_task.group_id —— 按视图名规则
--    重要：先打 LIS（精确匹配），再把剩余仍指向旧分组（1~4）的全部归到 HIS。
--    避免顺序错乱导致已重打过的任务被二次覆盖。
-- -----------------------------------------------------------------------------
-- LIS：view_names 中含 jianyan（检验）
UPDATE df_etl.sync_task
   SET group_id = (SELECT id FROM df_etl.task_group WHERE name='LIS业务')
 WHERE group_id IN (1, 2, 3, 4)
   AND view_names::text LIKE '%jianyan%';

-- HIS：剩余仍指向旧分组的任务
UPDATE df_etl.sync_task
   SET group_id = (SELECT id FROM df_etl.task_group WHERE name='HIS业务')
 WHERE group_id IN (1, 2, 3, 4);


-- -----------------------------------------------------------------------------
-- ⑥ 删除旧分组（机构性质 + 环境性质）
--    前置：⑤ 已确保没有 sync_task 仍指向 1~4，可安全 DELETE。
--    防御性断言：若仍存在引用则整体 ROLLBACK。
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    leftover INT;
BEGIN
    SELECT count(*) INTO leftover
      FROM df_etl.sync_task
     WHERE group_id IN (1, 2, 3, 4);
    IF leftover > 0 THEN
        RAISE EXCEPTION '仍有 % 条 sync_task 引用旧 group_id (1..4)，迁移中止', leftover;
    END IF;
END $$;

DELETE FROM df_etl.task_group WHERE id IN (1, 2, 3, 4);


-- =============================================================================
-- 验证（在 COMMIT 之前/之后手工执行）
-- =============================================================================
-- 任务全景（应 10 条都已挂上机构 + 业务分组）：
--   SELECT st.id, st.view_names, i.name AS 机构, tg.name AS 业务分组
--     FROM df_etl.sync_task st
--     LEFT JOIN df_etl.institution i ON i.id = st.institution_id
--     LEFT JOIN df_etl.task_group tg ON tg.id = st.group_id
--    ORDER BY st.id;
--
-- 机构表全景：
--   SELECT id, code, name, enabled FROM df_etl.institution ORDER BY id;
--
-- 业务分组全景（仅 HIS/LIS/PACS）：
--   SELECT id, name, status FROM df_etl.task_group ORDER BY id;

COMMIT;

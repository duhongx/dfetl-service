-- =============================================================================
-- 机构管理（Institution Management）数据库迁移
-- spec: .kiro/specs/institution-management
--
-- 目标：
--   1) 新增 df_etl.institution 主表（含字段、索引、自引用外键）
--   2) 给 source_datasource、sync_task、df_etl.batch_task_template_source
--      增加 institution_id BIGINT NULL 与外键、索引
--   3) 历史数据回填（DISTINCT 抽取 + 三表 backfill），全部幂等可重跑
--
-- 注意（与 design.md 的差异，以仓库实际 schema 为准）：
--   - source_datasource、sync_task 位于默认（public）schema，非 df_etl
--   - sync_task 中外键列名为 source_datasource_id（不是 source_data_source_id）
--   - institution 主表放入 df_etl（与 batch_task_template_source 一致）
--
-- 执行：
--   psql -U dfetl -d dfetl_meta -f migration_institution.sql
--   可重复执行；二次运行不会修改任何已存在数据。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) 主表：df_etl.institution
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS df_etl.institution (
    id           BIGSERIAL    PRIMARY KEY,
    code         VARCHAR(50)  NOT NULL UNIQUE,                  -- 业务唯一标识，如 YGT330106H001
    name         VARCHAR(200) NOT NULL,                         -- 机构全称
    short_name   VARCHAR(50),                                   -- 机构简称
    type         VARCHAR(20),                                   -- HOSPITAL / CLINIC / CENTER / COMMUNITY
    level        VARCHAR(20),                                   -- TIER_3 / TIER_2 / TIER_1
    region_code  VARCHAR(20),                                   -- 行政区划代码
    parent_id    BIGINT REFERENCES df_etl.institution(id),      -- 自引用：上级机构（医共体层级）
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 索引：UNIQUE(code) 已由 UNIQUE 约束自带索引；这里补 parent_id 与 enabled 的部分索引
CREATE INDEX IF NOT EXISTS idx_institution_parent
    ON df_etl.institution(parent_id)
    WHERE parent_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_institution_enabled
    ON df_etl.institution(enabled)
    WHERE enabled = true;

COMMENT ON TABLE  df_etl.institution            IS '机构主表 — 医共体场景下的医疗机构一等公民';
COMMENT ON COLUMN df_etl.institution.code        IS '机构业务唯一编码，如 YGT330106H001';
COMMENT ON COLUMN df_etl.institution.name        IS '机构全称';
COMMENT ON COLUMN df_etl.institution.short_name  IS '机构简称';
COMMENT ON COLUMN df_etl.institution.type        IS '机构类型：HOSPITAL / CLINIC / CENTER / COMMUNITY';
COMMENT ON COLUMN df_etl.institution.level       IS '机构等级：TIER_3 / TIER_2 / TIER_1';
COMMENT ON COLUMN df_etl.institution.region_code IS '行政区划代码';
COMMENT ON COLUMN df_etl.institution.parent_id   IS '上级机构 ID（医共体层级，自引用）';
COMMENT ON COLUMN df_etl.institution.enabled     IS '启用状态；删除采用软删除（设为 false）';


-- -----------------------------------------------------------------------------
-- 2) 给 source_datasource 增加 institution_id（注意：表在 public schema，列名为 source_datasource_id）
-- -----------------------------------------------------------------------------
ALTER TABLE source_datasource
    ADD COLUMN IF NOT EXISTS institution_id BIGINT REFERENCES df_etl.institution(id);

CREATE INDEX IF NOT EXISTS idx_source_ds_institution
    ON source_datasource(institution_id)
    WHERE institution_id IS NOT NULL;

COMMENT ON COLUMN source_datasource.institution_id IS '所属机构 ID（df_etl.institution.id），可空以兼容老数据';


-- -----------------------------------------------------------------------------
-- 3) 给 sync_task 增加 institution_id
-- -----------------------------------------------------------------------------
ALTER TABLE sync_task
    ADD COLUMN IF NOT EXISTS institution_id BIGINT REFERENCES df_etl.institution(id);

CREATE INDEX IF NOT EXISTS idx_sync_task_institution
    ON sync_task(institution_id)
    WHERE institution_id IS NOT NULL;

COMMENT ON COLUMN sync_task.institution_id IS '所属机构 ID；创建时若 dto 未指定，将从 source_datasource.institution_id 继承';


-- -----------------------------------------------------------------------------
-- 4) 给 df_etl.batch_task_template_source 增加 institution_id
--    （保留旧的 institution_code/institution_name 字段，向后兼容）
-- -----------------------------------------------------------------------------
ALTER TABLE df_etl.batch_task_template_source
    ADD COLUMN IF NOT EXISTS institution_id BIGINT REFERENCES df_etl.institution(id);

CREATE INDEX IF NOT EXISTS idx_btts_institution
    ON df_etl.batch_task_template_source(institution_id)
    WHERE institution_id IS NOT NULL;

COMMENT ON COLUMN df_etl.batch_task_template_source.institution_id IS '关联机构主表 id；创建/编辑模板时填入，apply 时透传到 sync_task';


-- =============================================================================
-- 5) 历史数据回填（幂等：可重复执行）
--    顺序：institution 抽取 → batch_task_template_source → sync_task → source_datasource
-- =============================================================================

-- 5.1 从 batch_task_template_source 抽取 distinct 机构写入主表
--     ON CONFLICT (code) DO NOTHING 保证重复执行不会改动已有数据
INSERT INTO df_etl.institution (code, name, enabled, created_at, updated_at)
SELECT DISTINCT
       bts.institution_code,
       MIN(bts.institution_name) AS name,        -- 同 code 多 name 时取一个稳定值
       true,
       now(),
       now()
  FROM df_etl.batch_task_template_source bts
 WHERE bts.institution_code IS NOT NULL
   AND bts.institution_name IS NOT NULL
 GROUP BY bts.institution_code
ON CONFLICT (code) DO NOTHING;

-- 5.2 回填 batch_task_template_source.institution_id（仅 NULL 行）
UPDATE df_etl.batch_task_template_source bts
   SET institution_id = i.id
  FROM df_etl.institution i
 WHERE bts.institution_code = i.code
   AND bts.institution_id IS NULL;

-- 5.3 回填 sync_task.institution_id（基于 batch_task_template_source 的 sync_task_id 关联，仅 NULL 行）
UPDATE sync_task st
   SET institution_id = bts.institution_id
  FROM df_etl.batch_task_template_source bts
 WHERE bts.sync_task_id = st.id
   AND st.institution_id IS NULL
   AND bts.institution_id IS NOT NULL;

-- 5.4 回填 source_datasource.institution_id（基于 sync_task 反推，仅 NULL 行）
--     注意：sync_task 的外键列名为 source_datasource_id（无下划线分隔 data / source）
UPDATE source_datasource ds
   SET institution_id = st.institution_id
  FROM sync_task st
 WHERE st.source_datasource_id = ds.id
   AND ds.institution_id IS NULL
   AND st.institution_id IS NOT NULL;


-- =============================================================================
-- 验证查询（手工执行，用于确认迁移效果；不影响数据）
-- =============================================================================
-- 已建机构数量：
--   SELECT count(*) FROM df_etl.institution;
-- 三表 institution_id 覆盖率：
--   SELECT count(*) FILTER (WHERE institution_id IS NOT NULL)::float / NULLIF(count(*),0)
--     FROM df_etl.batch_task_template_source;
--   SELECT count(*) FILTER (WHERE institution_id IS NOT NULL)::float / NULLIF(count(*),0)
--     FROM sync_task;
--   SELECT count(*) FILTER (WHERE institution_id IS NOT NULL)::float / NULLIF(count(*),0)
--     FROM source_datasource;

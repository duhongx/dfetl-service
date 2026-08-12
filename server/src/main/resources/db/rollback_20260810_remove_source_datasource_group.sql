-- migration_20260810_remove_source_datasource_group.sql 的结构回滚。
-- 仅恢复兼容结构；已删除的 task_group 数据和 group_id 历史值无法恢复。
BEGIN;

SET LOCAL search_path TO df_etl, public;

CREATE TABLE IF NOT EXISTS task_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE IF EXISTS source_datasource
    ADD COLUMN IF NOT EXISTS group_id BIGINT REFERENCES task_group(id);
ALTER TABLE IF EXISTS target_datasource
    ADD COLUMN IF NOT EXISTS group_id BIGINT REFERENCES task_group(id);
ALTER TABLE IF EXISTS sync_task
    ADD COLUMN IF NOT EXISTS group_id BIGINT REFERENCES task_group(id);

CREATE INDEX IF NOT EXISTS idx_source_datasource_group_id
    ON source_datasource(group_id);
CREATE INDEX IF NOT EXISTS idx_target_datasource_group_id
    ON target_datasource(group_id);
CREATE INDEX IF NOT EXISTS idx_sync_task_group
    ON sync_task(group_id);

COMMIT;

-- 删除已废弃的业务分组维度。
-- 执行前请停止 dfetl 服务；group_id 历史值不可恢复。
BEGIN;

SET LOCAL search_path TO df_etl, public;

DROP INDEX IF EXISTS idx_source_datasource_group_id;
DROP INDEX IF EXISTS idx_target_datasource_group_id;
DROP INDEX IF EXISTS idx_sync_task_group;

ALTER TABLE IF EXISTS source_datasource
    DROP COLUMN IF EXISTS group_id;
ALTER TABLE IF EXISTS target_datasource
    DROP COLUMN IF EXISTS group_id;
ALTER TABLE IF EXISTS sync_task
    DROP COLUMN IF EXISTS group_id;

DROP TABLE IF EXISTS task_group;

COMMIT;

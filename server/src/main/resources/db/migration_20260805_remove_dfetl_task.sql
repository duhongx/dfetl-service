-- Spec 100：系统配置只作为 sync_task 创建预设，不保留第二套任务绑定和重建状态。
-- 历史 sync_task 本身不删除；任务运行继续依赖 sync_task / task_view_config 快照。
DO $$
DECLARE
    binding_count BIGINT := 0;
    rebuild_count BIGINT := 0;
    missing_snapshot_count BIGINT := 0;
BEGIN
    IF to_regclass('df_etl.dfetl_task') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM df_etl.dfetl_task' INTO binding_count;
        IF to_regclass('df_etl.task_view_config') IS NOT NULL THEN
            EXECUTE 'SELECT count(*) FROM df_etl.dfetl_task t '
                || 'LEFT JOIN df_etl.task_view_config v ON v.task_id = t.task_id '
                || 'WHERE v.id IS NULL' INTO missing_snapshot_count;
        END IF;
    ELSIF to_regclass('dfetl_task') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM dfetl_task' INTO binding_count;
        IF to_regclass('task_view_config') IS NOT NULL THEN
            EXECUTE 'SELECT count(*) FROM dfetl_task t '
                || 'LEFT JOIN task_view_config v ON v.task_id = t.task_id '
                || 'WHERE v.id IS NULL' INTO missing_snapshot_count;
        END IF;
    END IF;
    IF to_regclass('df_etl.dfetl_rebuild_run') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM df_etl.dfetl_rebuild_run' INTO rebuild_count;
    ELSIF to_regclass('dfetl_rebuild_run') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM dfetl_rebuild_run' INTO rebuild_count;
    END IF;
    RAISE NOTICE 'Spec 100 removing legacy dfetl task bindings: %, rebuild runs: %, bindings without task_view_config snapshot: %',
        binding_count, rebuild_count, missing_snapshot_count;
    IF missing_snapshot_count > 0 THEN
        RAISE EXCEPTION 'Refusing to drop dfetl_task: % bound sync_task rows lack task_view_config snapshots',
            missing_snapshot_count;
    END IF;
END $$;

DROP TABLE IF EXISTS df_etl.dfetl_rebuild_run_stage;
DROP TABLE IF EXISTS df_etl.dfetl_rebuild_run;
DROP TABLE IF EXISTS df_etl.dfetl_task;

-- 兼容由运维脚本在其他 search_path 下创建过的同名历史表。
DROP TABLE IF EXISTS dfetl_rebuild_run_stage;
DROP TABLE IF EXISTS dfetl_rebuild_run;
DROP TABLE IF EXISTS dfetl_task;

-- =============================================================================
-- Spec 101 迁移前只读检查
-- 本脚本仅执行 SELECT，用于决定是否允许重建空配置表。
-- =============================================================================

SELECT
    to_regclass('df_etl.institution') IS NOT NULL AS institution_table_ready,
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = ANY (current_schemas(false))
           AND table_name = 'source_datasource'
           AND column_name = 'institution_id'
    ) AS source_institution_column_ready,
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = ANY (current_schemas(false))
           AND table_name = 'sync_task'
           AND column_name = 'institution_id'
    ) AS task_institution_column_ready,
    (SELECT count(*) FROM dfetl_dataset) AS dfetl_dataset_count,
    (SELECT count(*) FROM dfetl_field) AS dfetl_field_count,
    (SELECT count(*)
       FROM sync_task
      WHERE data_characteristics ILIKE '%"dfetlDatasetId"%') AS dfetl_dataset_task_count,
    (SELECT count(*)
       FROM task_execution
      WHERE status IN ('PENDING', 'RUNNING')) AS open_execution_count;

SELECT
    id,
    org_code,
    dataset_code,
    source_datasource_id,
    source_schema,
    source_object,
    target_datasource_id,
    target_table,
    collect_status
FROM dfetl_dataset
ORDER BY org_code, dataset_code, id;

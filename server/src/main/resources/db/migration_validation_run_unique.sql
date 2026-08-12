-- =============================================================================
-- validation_run 并发幂等唯一约束补丁迁移脚本
-- 适用：已有元数据库存在 validation_run 表，但缺少 (task_id, legacy_exec_id) 唯一约束/唯一索引。
-- 执行：psql -U df_etl -d df_ygt -f migration_validation_run_unique.sql
-- 幂等：自动识别 validation_run 所在 schema，重复执行安全。
--
-- 注意：
-- 1. 如果历史库中已经存在重复 (task_id, legacy_exec_id)，本脚本会明确失败。
-- 2. 如果历史库中存在 legacy_exec_id IS NULL，本脚本会明确失败。
-- 3. 以上两类数据需先人工确认并清理，再重新执行，避免误删历史校验记录。
-- =============================================================================

DO $$
DECLARE
    validation_run_schema TEXT;
    duplicate_groups BIGINT;
    null_legacy_exec_rows BIGINT;
    same_name_index_exists BOOLEAN;
    index_exists BOOLEAN;
BEGIN
    PERFORM set_config('lock_timeout', '5s', true);

    SELECT table_schema
      INTO validation_run_schema
      FROM information_schema.tables
     WHERE table_name = 'validation_run'
       AND table_type = 'BASE TABLE'
       AND table_schema NOT IN ('pg_catalog', 'information_schema')
     ORDER BY CASE WHEN table_schema = 'df_etl' THEN 0 ELSE 1 END, table_schema
     LIMIT 1;

    IF validation_run_schema IS NULL THEN
        RAISE EXCEPTION 'validation_run table not found in current database';
    END IF;

    EXECUTE format(
        'SELECT COUNT(*) FROM %I.validation_run WHERE legacy_exec_id IS NULL',
        validation_run_schema)
    INTO null_legacy_exec_rows;

    IF null_legacy_exec_rows > 0 THEN
        RAISE EXCEPTION
            'validation_run contains rows with legacy_exec_id IS NULL: %, clean or backfill before adding unique index',
            null_legacy_exec_rows;
    END IF;

    EXECUTE format(
        'SELECT COUNT(*) FROM (
             SELECT task_id, legacy_exec_id
               FROM %I.validation_run
              GROUP BY task_id, legacy_exec_id
             HAVING COUNT(*) > 1
         ) d',
        validation_run_schema)
    INTO duplicate_groups;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'validation_run contains duplicate (task_id, legacy_exec_id) groups: %, clean duplicates before adding unique index',
            duplicate_groups;
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM pg_class idx
          JOIN pg_namespace ns ON ns.oid = idx.relnamespace
         WHERE ns.nspname = validation_run_schema
           AND idx.relname = 'uk_validation_run_task_exec'
    ) INTO same_name_index_exists;

    SELECT EXISTS (
        SELECT 1
          FROM pg_class idx
          JOIN pg_namespace ns ON ns.oid = idx.relnamespace
          JOIN pg_index i ON i.indexrelid = idx.oid
          JOIN pg_class tbl ON tbl.oid = i.indrelid
         WHERE ns.nspname = validation_run_schema
           AND tbl.relname = 'validation_run'
           AND idx.relname = 'uk_validation_run_task_exec'
           AND i.indisunique
           AND (
               SELECT array_agg(att.attname::TEXT ORDER BY ord.ordinality)
                 FROM unnest(i.indkey) WITH ORDINALITY AS ord(attnum, ordinality)
                 JOIN pg_attribute att
                   ON att.attrelid = tbl.oid
                  AND att.attnum = ord.attnum
           ) = ARRAY['task_id', 'legacy_exec_id']::TEXT[]
    ) INTO index_exists;

    IF same_name_index_exists AND NOT index_exists THEN
        RAISE EXCEPTION
            'index uk_validation_run_task_exec exists but is not a unique index on (task_id, legacy_exec_id)';
    END IF;

    IF NOT index_exists THEN
        EXECUTE format(
            'CREATE UNIQUE INDEX uk_validation_run_task_exec ON %I.validation_run(task_id, legacy_exec_id)',
            validation_run_schema);
    END IF;
END $$;

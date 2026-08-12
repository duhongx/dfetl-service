-- =============================================================================
-- df-etl sync_task 补丁迁移脚本
-- 适用：DB 用旧版 init.sql 初始化（Phase 7~8），
--       Phase 9-16 期间新增字段未同步到 DB。
-- 执行：psql -U df_etl -d df_ygt -f migration_sync_task_phase9_16.sql
-- 幂等：ADD COLUMN IF NOT EXISTS，重复执行安全。
-- =============================================================================

-- sync_task 补全 Phase 9-16 新增字段
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS increment_mode            VARCHAR(20);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS upper_bound_strategy      VARCHAR(20)  NOT NULL DEFAULT 'CURRENT_TIME';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS upper_bound_delay_minutes INTEGER      NOT NULL DEFAULT 5;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS initial_watermark         VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS writer_type               VARCHAR(20)  NOT NULL DEFAULT 'STREAM_LOAD';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS doris_table_model         VARCHAR(20);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS source_mode               VARCHAR(20)  NOT NULL DEFAULT 'TABLE_VIEW';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_sql                TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_sql_name           VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS static_filter             TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS filter_condition_map      TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS target_table_map          TEXT;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_window_start       TIMESTAMPTZ;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS custom_window_end         TIMESTAMPTZ;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS executor_type             VARCHAR(30)  DEFAULT 'DATAX';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS split_pk                  VARCHAR(200);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS source_object_type        VARCHAR(30)  NOT NULL DEFAULT 'TABLE';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS soft_delete_field         VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS soft_delete_active_value  VARCHAR(50)  DEFAULT '0';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS enable_doris_merge        BOOLEAN      NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS delete_sign_value         VARCHAR(50)  DEFAULT '1';
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS sequence_col              VARCHAR(100);
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS partial_columns           BOOLEAN      NOT NULL DEFAULT false;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS lookback_seconds          INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS enable_snapshot_delete    BOOLEAN      NOT NULL DEFAULT false;

-- 验证：执行后列数应 >= 49（含原始 27 列 + 新增 22 列）
SELECT count(*) AS column_count
FROM information_schema.columns
WHERE table_name = 'sync_task';

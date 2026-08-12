-- =============================================================================
-- Spec 101  医共体标准模型与机构数据集路由
--
-- 前置：先执行 migration_20260805_medical_standard_route_precheck.sql。
-- 规则：旧 dfetl_dataset / dfetl_field 非空或存在未闭合 execution 时主动失败；
--       不删除 sync_task、task_view_config、执行、日志、水位、校验或消息历史。
-- 幂等：目标三表已经完整建立时仅输出 NOTICE，不重复重建。
-- =============================================================================

DO $$
DECLARE
    dataset_count BIGINT := 0;
    field_count BIGINT := 0;
    open_execution_count BIGINT := 0;
    dataset_is_final BOOLEAN := false;
    field_is_final BOOLEAN := false;
    route_exists BOOLEAN := false;
BEGIN
    IF to_regclass('df_etl.institution') IS NULL THEN
        RAISE EXCEPTION
            'df_etl.institution is missing; run migration_institution.sql before Spec 101 migration';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = ANY (current_schemas(false))
           AND table_name = 'source_datasource'
           AND column_name = 'institution_id'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = ANY (current_schemas(false))
           AND table_name = 'sync_task'
           AND column_name = 'institution_id'
    ) THEN
        RAISE EXCEPTION
            'institution_id columns are missing; run migration_institution.sql before Spec 101 migration';
    END IF;

    IF to_regclass('dfetl_dataset') IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = ANY (current_schemas(false))
               AND table_name = 'dfetl_dataset'
               AND column_name = 'medical_dataset_id'
        ) INTO dataset_is_final;
    END IF;
    IF to_regclass('dfetl_field') IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = ANY (current_schemas(false))
               AND table_name = 'dfetl_field'
               AND column_name = 'medical_field_id'
        ) INTO field_is_final;
    END IF;
    route_exists := to_regclass('institution_dataset_route') IS NOT NULL;

    IF dataset_is_final AND field_is_final AND route_exists THEN
        RAISE NOTICE 'medical standard dataset and institution route schema already migrated';
        RETURN;
    END IF;
    IF dataset_is_final OR field_is_final OR route_exists THEN
        RAISE EXCEPTION 'incomplete Spec 101 schema detected; refusing automatic repair';
    END IF;

    IF to_regclass('dfetl_dataset') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM dfetl_dataset' INTO dataset_count;
    END IF;
    IF to_regclass('dfetl_field') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM dfetl_field' INTO field_count;
    END IF;
    IF dataset_count > 0 OR field_count > 0 THEN
        RAISE EXCEPTION
            'refusing to rebuild non-empty dfetl_dataset: dataset_count=%, field_count=%; run precheck and choose explicit conversion or cleanup',
            dataset_count, field_count;
    END IF;

    IF to_regclass('task_execution') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM task_execution WHERE status IN (''PENDING'', ''RUNNING'')'
           INTO open_execution_count;
    END IF;
    IF open_execution_count > 0 THEN
        RAISE EXCEPTION
            'refusing schema migration while task executions are open: open_execution_count=%',
            open_execution_count;
    END IF;

    EXECUTE 'DROP TABLE IF EXISTS dfetl_field';
    EXECUTE 'DROP TABLE IF EXISTS dfetl_dataset';

    EXECUTE $sql$
        CREATE TABLE dfetl_dataset (
            id BIGSERIAL PRIMARY KEY,
            medical_dataset_id VARCHAR(64) NOT NULL,
            dataset_code VARCHAR(100) NOT NULL,
            dataset_name VARCHAR(200),
            dataset_type VARCHAR(50) NOT NULL DEFAULT 'MEDICAL',
            dataset_version VARCHAR(50),
            contract_hash VARCHAR(128) NOT NULL,
            sync_revision BIGINT NOT NULL DEFAULT 1,
            dataset_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
            last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            message_enabled BOOLEAN NOT NULL DEFAULT false,
            message_transport VARCHAR(30) NOT NULL DEFAULT 'RABBITMQ',
            message_full_sync_mode VARCHAR(30) NOT NULL DEFAULT 'ALL',
            message_rate_limit INTEGER NOT NULL DEFAULT 1000,
            message_routing_key VARCHAR(100),
            message_topic VARCHAR(100),
            message_key_template VARCHAR(500),
            message_page_size INTEGER NOT NULL DEFAULT 1000,
            tenant_id VARCHAR(50) NOT NULL DEFAULT '0',
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT uk_dfetl_dataset_medical_id UNIQUE (medical_dataset_id),
            CONSTRAINT ck_dfetl_dataset_status CHECK (dataset_status IN ('ACTIVE', 'VOID')),
            CONSTRAINT ck_dfetl_dataset_sync_revision_positive CHECK (sync_revision > 0),
            CONSTRAINT ck_dfetl_dataset_message_rate_limit_nonnegative CHECK (message_rate_limit >= 0),
            CONSTRAINT ck_dfetl_dataset_message_page_size_positive CHECK (message_page_size > 0),
            CONSTRAINT ck_dfetl_dataset_message_route_required
                CHECK (NOT message_enabled OR length(trim(message_routing_key)) > 0)
        )
    $sql$;
    EXECUTE 'CREATE UNIQUE INDEX uk_dfetl_dataset_code_ci ON dfetl_dataset(lower(dataset_code))';
    EXECUTE 'CREATE INDEX idx_dfetl_dataset_status ON dfetl_dataset(dataset_status, dataset_code)';

    EXECUTE $sql$
        CREATE TABLE dfetl_field (
            id BIGSERIAL PRIMARY KEY,
            dataset_id BIGINT NOT NULL REFERENCES dfetl_dataset(id) ON DELETE CASCADE,
            medical_field_id VARCHAR(64) NOT NULL,
            field_code VARCHAR(100) NOT NULL,
            field_name VARCHAR(200),
            field_order INTEGER,
            standard_type VARCHAR(30),
            standard_format VARCHAR(100),
            primary_key BOOLEAN NOT NULL DEFAULT false,
            required_by_standard BOOLEAN NOT NULL DEFAULT false,
            value_domain_code VARCHAR(100),
            standard_version VARCHAR(50),
            field_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT uk_dfetl_field_medical_id UNIQUE (dataset_id, medical_field_id),
            CONSTRAINT ck_dfetl_field_status CHECK (field_status IN ('ACTIVE', 'VOID'))
        )
    $sql$;
    EXECUTE 'CREATE INDEX idx_dfetl_field_dataset ON dfetl_field(dataset_id, field_status, field_order)';
    EXECUTE $sql$
        CREATE UNIQUE INDEX uk_dfetl_field_active_code_ci
            ON dfetl_field(dataset_id, lower(field_code)) WHERE field_status = 'ACTIVE'
    $sql$;

    EXECUTE $sql$
        CREATE TABLE institution_dataset_route (
            id BIGSERIAL PRIMARY KEY,
            institution_id BIGINT NOT NULL REFERENCES df_etl.institution(id),
            dataset_id BIGINT NOT NULL REFERENCES dfetl_dataset(id),
            source_datasource_id BIGINT NOT NULL REFERENCES source_datasource(id),
            source_schema VARCHAR(100) NOT NULL,
            source_object VARCHAR(200) NOT NULL,
            source_object_type VARCHAR(30) NOT NULL DEFAULT 'VIEW',
            target_datasource_id BIGINT NOT NULL REFERENCES target_datasource(id),
            target_table VARCHAR(200) NOT NULL,
            sync_template VARCHAR(30) NOT NULL DEFAULT 'FULL_THEN_INCREMENT',
            write_mode VARCHAR(20) NOT NULL DEFAULT 'UPSERT',
            incremental_field VARCHAR(100),
            increment_mode VARCHAR(20) NOT NULL DEFAULT 'TIME_FIELD',
            upper_bound_strategy VARCHAR(30) NOT NULL DEFAULT 'CURRENT_TIME',
            upper_bound_delay_minutes INTEGER NOT NULL DEFAULT 5,
            lookback_seconds INTEGER NOT NULL DEFAULT 0,
            schedule_enabled BOOLEAN NOT NULL DEFAULT false,
            schedule_mode VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
            schedule_interval_hours INTEGER,
            schedule_cron VARCHAR(128),
            schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
            enabled BOOLEAN NOT NULL DEFAULT false,
            validation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
            validation_summary TEXT,
            validation_details_json TEXT,
            last_validated_at TIMESTAMPTZ,
            validated_contract_hash VARCHAR(128),
            validated_route_revision BIGINT,
            route_revision BIGINT NOT NULL DEFAULT 1,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT ck_institution_dataset_route_source_type
                CHECK (source_object_type IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW')),
            CONSTRAINT ck_institution_dataset_route_validation_status
                CHECK (validation_status IN ('PENDING', 'PASSED', 'FAILED')),
            CONSTRAINT ck_institution_dataset_route_revision_positive CHECK (route_revision > 0),
            CONSTRAINT ck_institution_dataset_route_delay_nonnegative
                CHECK (upper_bound_delay_minutes >= 0 AND lookback_seconds >= 0),
            CONSTRAINT ck_institution_dataset_route_schedule
                CHECK (
                    (NOT schedule_enabled AND schedule_mode = 'MANUAL')
                    OR (schedule_enabled AND schedule_mode = 'EVERY_N_HOURS'
                        AND schedule_interval_hours > 0)
                    OR (schedule_enabled AND schedule_mode = 'ADVANCED'
                        AND length(trim(schedule_cron)) > 0)
                ),
            CONSTRAINT ck_institution_dataset_route_enable_requires_validation
                CHECK (NOT enabled OR (
                    validation_status = 'PASSED'
                    AND last_validated_at IS NOT NULL
                    AND validated_route_revision = route_revision
                    AND validated_contract_hash IS NOT NULL
                ))
        )
    $sql$;
    EXECUTE $sql$
        CREATE UNIQUE INDEX uk_institution_dataset_route_active
            ON institution_dataset_route(institution_id, dataset_id) WHERE enabled = true
    $sql$;
    EXECUTE 'CREATE INDEX idx_institution_dataset_route_dataset ON institution_dataset_route(dataset_id)';
    EXECUTE $sql$
        CREATE INDEX idx_institution_dataset_route_source
            ON institution_dataset_route(source_datasource_id, source_schema, source_object)
    $sql$;
    EXECUTE $sql$
        CREATE INDEX idx_institution_dataset_route_target
            ON institution_dataset_route(target_datasource_id, target_table)
    $sql$;

    COMMENT ON TABLE dfetl_dataset IS
        '医共体标准数据集当前快照及数据集级消息默认配置';
    COMMENT ON TABLE dfetl_field IS '医共体标准字段当前快照';
    COMMENT ON TABLE institution_dataset_route IS
        '机构标准数据集到实际源对象和目标表的已验证路由';
END $$;

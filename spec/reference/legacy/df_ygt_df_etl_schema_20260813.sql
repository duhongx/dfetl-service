--
-- PostgreSQL database dump
--

\restrict YsdAaOIKlWSLcL0e1bSQ1uJ3nuNlMM95Co29UkUp3pVFmthVTAHeeY1YhgazWkZ

-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: df_etl; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA df_etl;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: alert_channel; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.alert_channel (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    webhook_url text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_tested_at timestamp with time zone,
    last_test_status character varying(20) DEFAULT 'untested'::character varying,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    secret text,
    mentioned_mobiles text,
    at_all boolean DEFAULT false NOT NULL,
    message_format character varying(20) DEFAULT 'text'::character varying NOT NULL
);


--
-- Name: alert_channel_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.alert_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_channel_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.alert_channel_id_seq OWNED BY df_etl.alert_channel.id;


--
-- Name: alert_rule; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.alert_rule (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    metric character varying(50) NOT NULL,
    condition_op character varying(10) NOT NULL,
    threshold character varying(100) NOT NULL,
    severity character varying(20) DEFAULT 'warning'::character varying NOT NULL,
    channels text,
    scope text,
    silence_minutes integer DEFAULT 30 NOT NULL,
    last_triggered_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    scope_value character varying(200)
);


--
-- Name: TABLE alert_rule; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.alert_rule IS '告警规则配置';


--
-- Name: COLUMN alert_rule.metric; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.alert_rule.metric IS '可选值：task_status | batch_status | dirty_count | duration | chunk_fail_rate | write_diff | validation_result | read_rows';


--
-- Name: COLUMN alert_rule.condition_op; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.alert_rule.condition_op IS 'eq=等于 | ne=不等于 | gt=大于 | lt=小于';


--
-- Name: COLUMN alert_rule.channels; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.alert_rule.channels IS 'JSON 数组，webhook_endpoint.id，如 [1, 2]';


--
-- Name: COLUMN alert_rule.scope; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.alert_rule.scope IS '作用范围：{"type":"all"} 或 {"type":"group","value":"groupName"} 或 {"type":"task","value":"taskName"}';


--
-- Name: alert_rule_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.alert_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_rule_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.alert_rule_id_seq OWNED BY df_etl.alert_rule.id;


--
-- Name: app_user; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.app_user (
    id bigint NOT NULL,
    username character varying(100) NOT NULL,
    password_hash text NOT NULL,
    role character varying(30) DEFAULT 'ADMIN'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    refresh_token_version integer DEFAULT 0 NOT NULL
);


--
-- Name: TABLE app_user; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.app_user IS '系统登录用户（JWT 鉴权）';


--
-- Name: COLUMN app_user.refresh_token_version; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.app_user.refresh_token_version IS 'refresh token 版本号；logout 后自增，旧 refresh token 立即失效';


--
-- Name: app_user_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.app_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: app_user_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.app_user_id_seq OWNED BY df_etl.app_user.id;


--
-- Name: audit_log; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.audit_log (
    id bigint NOT NULL,
    action_time timestamp with time zone DEFAULT now() NOT NULL,
    user_name character varying(100) DEFAULT 'system'::character varying NOT NULL,
    action character varying(50) NOT NULL,
    target_type character varying(50),
    target_id bigint,
    target_name character varying(200),
    detail text,
    client_ip character varying(50)
);


--
-- Name: TABLE audit_log; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.audit_log IS '用户操作审计日志（append-only，禁止修改或删除）';


--
-- Name: COLUMN audit_log.action; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.audit_log.action IS '操作类型：创建任务 | 修改任务 | 发布任务 | 运行任务 | 停止任务 | 删除任务 | 创建数据源 | 删除数据源 | 测试连接 | 创建分组 | 修改分组 | 删除分组';


--
-- Name: audit_log_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_log_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.audit_log_id_seq OWNED BY df_etl.audit_log.id;


--
-- Name: batch_task_template; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.batch_task_template (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(500),
    target_datasource_id bigint NOT NULL,
    target_table character varying(200) NOT NULL,
    view_name character varying(200) NOT NULL,
    source_schema character varying(100),
    data_scope character varying(20) DEFAULT 'INCREMENTAL'::character varying,
    increment_mode character varying(20) DEFAULT 'TIME_FIELD'::character varying,
    incremental_field character varying(100),
    sync_mode character varying(20) DEFAULT 'UPSERT'::character varying,
    upsert_keys text,
    parallelism integer DEFAULT 1,
    cron_expression character varying(100),
    validation_method character varying(20) DEFAULT 'CHECKSUM'::character varying,
    validation_drift_cron character varying(100),
    validation_lookback_hours integer DEFAULT 24,
    auto_trigger boolean DEFAULT true,
    doris_table_model character varying(20) DEFAULT 'UNIQUE_KEY'::character varying,
    enable_doris_merge boolean DEFAULT false,
    soft_delete_field character varying(100),
    delete_sign_value character varying(20) DEFAULT '1'::character varying,
    sequence_col character varying(100),
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: TABLE batch_task_template; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.batch_task_template IS '批量任务模板 — 区域医共体多机构同步统一配置';


--
-- Name: batch_task_template_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.batch_task_template_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_task_template_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.batch_task_template_id_seq OWNED BY df_etl.batch_task_template.id;


--
-- Name: batch_task_template_source; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.batch_task_template_source (
    id bigint NOT NULL,
    template_id bigint NOT NULL,
    source_datasource_id bigint NOT NULL,
    source_schema character varying(100),
    static_filter character varying(1000),
    institution_name character varying(200),
    institution_code character varying(50),
    enabled boolean DEFAULT true,
    sync_task_id bigint,
    created_at timestamp without time zone DEFAULT now(),
    institution_id bigint
);


--
-- Name: TABLE batch_task_template_source; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.batch_task_template_source IS '批量任务模板关联的数据源（每条代表一个医疗机构）';


--
-- Name: COLUMN batch_task_template_source.institution_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.batch_task_template_source.institution_id IS '关联机构主表 id；创建/编辑模板时填入，apply 时透传到 sync_task';


--
-- Name: batch_task_template_source_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.batch_task_template_source_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_task_template_source_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.batch_task_template_source_id_seq OWNED BY df_etl.batch_task_template_source.id;


--
-- Name: dfetl_dataset; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_dataset (
    id bigint NOT NULL,
    medical_dataset_id character varying(64) NOT NULL,
    dataset_code character varying(100) NOT NULL,
    dataset_name character varying(200),
    contract_hash character varying(128) NOT NULL,
    dataset_status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    last_synced_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_dataset_status CHECK (((dataset_status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'VOID'::character varying])::text[])))
);


--
-- Name: TABLE dfetl_dataset; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_dataset IS '医共体有效标准数据集只读快照';


--
-- Name: dfetl_dataset_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_dataset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_dataset_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_dataset_id_seq OWNED BY df_etl.dfetl_dataset.id;


--
-- Name: dfetl_field; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_field (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    medical_field_id character varying(64) NOT NULL,
    field_code character varying(100) NOT NULL,
    field_name character varying(200),
    field_order integer,
    standard_type character varying(30),
    standard_format character varying(100),
    primary_key boolean DEFAULT false NOT NULL,
    required_by_standard boolean DEFAULT false NOT NULL,
    value_domain_code character varying(100),
    standard_version character varying(50),
    field_status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    target_field_code character varying(100) NOT NULL,
    doris_type character varying(100),
    CONSTRAINT ck_dfetl_field_status CHECK (((field_status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'VOID'::character varying])::text[])))
);


--
-- Name: TABLE dfetl_field; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_field IS '医共体标准字段当前快照';


--
-- Name: dfetl_field_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_field_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_field_id_seq OWNED BY df_etl.dfetl_field.id;


--
-- Name: dfetl_message_policy; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_message_policy (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    transport character varying(30) DEFAULT 'RABBITMQ'::character varying NOT NULL,
    full_sync_mode character varying(30) DEFAULT 'ALL'::character varying NOT NULL,
    rate_limit integer DEFAULT 1000 NOT NULL,
    routing_key character varying(100),
    topic character varying(100),
    key_template character varying(500),
    page_size integer DEFAULT 1000 NOT NULL,
    tenant_id character varying(50) DEFAULT '0'::character varying NOT NULL,
    source_system character varying(50) DEFAULT 'HIS'::character varying NOT NULL,
    policy_revision bigint DEFAULT 1 NOT NULL,
    row_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_message_numbers CHECK (((rate_limit >= 0) AND (page_size > 0))),
    CONSTRAINT ck_dfetl_message_route CHECK (((NOT enabled) OR (length(TRIM(BOTH FROM routing_key)) > 0)))
);


--
-- Name: TABLE dfetl_message_policy; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_message_policy IS '标准数据集共享的消息发布策略';


--
-- Name: dfetl_message_policy_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_message_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_message_policy_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_message_policy_id_seq OWNED BY df_etl.dfetl_message_policy.id;


--
-- Name: dfetl_precheck_export; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_precheck_export (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    request_key character varying(128) NOT NULL,
    filter_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    export_format character varying(10) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    file_manifest jsonb DEFAULT '[]'::jsonb NOT NULL,
    row_count bigint DEFAULT 0 NOT NULL,
    byte_count bigint DEFAULT 0 NOT NULL,
    requested_by character varying(100) NOT NULL,
    error_message text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_precheck_export_counts CHECK (((row_count >= 0) AND (byte_count >= 0))),
    CONSTRAINT ck_dfetl_precheck_export_format CHECK (((export_format)::text = ANY ((ARRAY['CSV'::character varying, 'XLSX'::character varying])::text[]))),
    CONSTRAINT ck_dfetl_precheck_export_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: TABLE dfetl_precheck_export; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_precheck_export IS '数据预检问题异步导出任务和审计元数据';


--
-- Name: dfetl_precheck_export_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_precheck_export_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_precheck_export_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_precheck_export_id_seq OWNED BY df_etl.dfetl_precheck_export.id;


--
-- Name: dfetl_precheck_issue; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_precheck_issue (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    issue_key character varying(128) NOT NULL,
    source_row_hash character varying(64) NOT NULL,
    business_pk_json text,
    raw_row_json text NOT NULL,
    field_code character varying(100),
    field_name character varying(200),
    source_column character varying(100),
    target_column character varying(100),
    error_type character varying(50) NOT NULL,
    standard_rule character varying(500),
    raw_value text,
    normalized_value text,
    error_message text NOT NULL,
    severity character varying(20) NOT NULL,
    remediation_status character varying(20) DEFAULT 'NEW'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_precheck_issue_severity CHECK (((severity)::text = ANY ((ARRAY['BLOCKER'::character varying, 'WARNING'::character varying])::text[]))),
    CONSTRAINT ck_dfetl_precheck_remediation CHECK (((remediation_status)::text = ANY ((ARRAY['NEW'::character varying, 'STILL_OPEN'::character varying, 'FIXED'::character varying])::text[])))
);


--
-- Name: TABLE dfetl_precheck_issue; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_precheck_issue IS '历史数据预检问题明细；新暂存层运行的问题明细存储在 Doris';


--
-- Name: dfetl_precheck_issue_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_precheck_issue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_precheck_issue_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_precheck_issue_id_seq OWNED BY df_etl.dfetl_precheck_issue.id;


--
-- Name: dfetl_precheck_run; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_precheck_run (
    id bigint NOT NULL,
    route_id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    institution_id bigint NOT NULL,
    task_id bigint,
    execution_id bigint,
    retry_of_run_id bigint,
    run_type character varying(30) NOT NULL,
    scope_type character varying(30) NOT NULL,
    window_start timestamp with time zone,
    window_end timestamp with time zone,
    window_start_id bigint,
    window_end_id bigint,
    contract_hash character varying(128) NOT NULL,
    route_revision bigint NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    scanned_rows bigint DEFAULT 0 NOT NULL,
    passed_rows bigint DEFAULT 0 NOT NULL,
    blocker_rows bigint DEFAULT 0 NOT NULL,
    warning_rows bigint DEFAULT 0 NOT NULL,
    fixed_issue_rows bigint DEFAULT 0 NOT NULL,
    error_message text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    target_schema_hash character varying(128),
    stage character varying(20) DEFAULT 'PREPARING'::character varying NOT NULL,
    progress_percent smallint DEFAULT 0 NOT NULL,
    engine_job_id character varying(128),
    staging_table character varying(200),
    source_rows bigint DEFAULT 0 NOT NULL,
    loaded_rows bigint DEFAULT 0 NOT NULL,
    checked_rows bigint DEFAULT 0 NOT NULL,
    issue_count bigint DEFAULT 0 NOT NULL,
    raw_cleaned_at timestamp with time zone,
    CONSTRAINT ck_dfetl_precheck_counts CHECK (((scanned_rows >= 0) AND (passed_rows >= 0) AND (blocker_rows >= 0) AND (warning_rows >= 0) AND (fixed_issue_rows >= 0) AND (source_rows >= 0) AND (loaded_rows >= 0) AND (checked_rows >= 0) AND (issue_count >= 0))),
    CONSTRAINT ck_dfetl_precheck_progress CHECK (((progress_percent >= 0) AND (progress_percent <= 100))),
    CONSTRAINT ck_dfetl_precheck_revision CHECK ((route_revision > 0)),
    CONSTRAINT ck_dfetl_precheck_run_type CHECK (((run_type)::text = ANY ((ARRAY['ROUTE_FULL'::character varying, 'EXECUTION_WINDOW'::character varying])::text[]))),
    CONSTRAINT ck_dfetl_precheck_scope CHECK (((scope_type)::text = ANY ((ARRAY['FULL'::character varying, 'FULL_THEN_INCREMENT'::character varying, 'INCREMENT'::character varying])::text[]))),
    CONSTRAINT ck_dfetl_precheck_stage CHECK (((stage)::text = ANY ((ARRAY['PREPARING'::character varying, 'LOADING'::character varying, 'VALIDATING'::character varying, 'FINALIZING'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT ck_dfetl_precheck_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'LOADING'::character varying, 'VALIDATING'::character varying, 'HAS_ERRORS'::character varying, 'PASSED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: TABLE dfetl_precheck_run; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_precheck_run IS 'Doris STRING 暂存层数据预检运行及小型汇总';


--
-- Name: COLUMN dfetl_precheck_run.raw_cleaned_at; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.dfetl_precheck_run.raw_cleaned_at IS '该运行在 Doris STRING 原始暂存中的精确批次清理完成时间';


--
-- Name: dfetl_precheck_run_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_precheck_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_precheck_run_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_precheck_run_id_seq OWNED BY df_etl.dfetl_precheck_run.id;


--
-- Name: dfetl_sync_policy; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_sync_policy (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    write_mode character varying(20) DEFAULT 'TRUNCATE'::character varying NOT NULL,
    sync_template character varying(30) DEFAULT 'FULL_ONLY'::character varying NOT NULL,
    incremental_field character varying(100),
    increment_mode character varying(20) DEFAULT 'TIME_FIELD'::character varying NOT NULL,
    upper_bound_strategy character varying(30) DEFAULT 'CURRENT_TIME'::character varying NOT NULL,
    upper_bound_delay_minutes integer DEFAULT 5 NOT NULL,
    lookback_seconds integer DEFAULT 0 NOT NULL,
    reader_parallelism integer DEFAULT 4 NOT NULL,
    fetch_size integer,
    rate_limit integer DEFAULT 0 NOT NULL,
    schedule_enabled boolean DEFAULT true NOT NULL,
    schedule_mode character varying(30) DEFAULT 'EVERY_N_HOURS'::character varying NOT NULL,
    schedule_interval_hours integer DEFAULT 4,
    schedule_cron character varying(128),
    schedule_timezone character varying(64) DEFAULT 'Asia/Shanghai'::character varying NOT NULL,
    policy_revision bigint DEFAULT 1 NOT NULL,
    row_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_sync_numbers CHECK (((upper_bound_delay_minutes >= 0) AND (lookback_seconds >= 0) AND ((reader_parallelism >= 1) AND (reader_parallelism <= 64)) AND ((fetch_size IS NULL) OR (fetch_size > 0)) AND (rate_limit >= 0))),
    CONSTRAINT ck_dfetl_sync_schedule CHECK ((((NOT schedule_enabled) AND ((schedule_mode)::text = 'MANUAL'::text)) OR (schedule_enabled AND ((schedule_mode)::text = 'EVERY_N_HOURS'::text) AND (schedule_interval_hours > 0)) OR (schedule_enabled AND ((schedule_mode)::text = 'ADVANCED'::text) AND (length(TRIM(BOTH FROM schedule_cron)) > 0))))
);


--
-- Name: TABLE dfetl_sync_policy; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_sync_policy IS '标准数据集共享的同步、性能和调度策略';


--
-- Name: dfetl_sync_policy_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_sync_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_sync_policy_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_sync_policy_id_seq OWNED BY df_etl.dfetl_sync_policy.id;


--
-- Name: dfetl_validation_policy; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dfetl_validation_policy (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    inherit_global boolean DEFAULT true NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    trigger_mode character varying(30) DEFAULT 'AFTER_SYNC'::character varying NOT NULL,
    validation_method character varying(30) DEFAULT 'ROW_COUNT'::character varying NOT NULL,
    row_tolerance numeric(8,4) DEFAULT 0 NOT NULL,
    fail_block boolean DEFAULT false NOT NULL,
    revalidate_enabled boolean DEFAULT true NOT NULL,
    revalidate_delay integer DEFAULT 30 NOT NULL,
    lookback_hours integer DEFAULT 2 NOT NULL,
    policy_revision bigint DEFAULT 1 NOT NULL,
    row_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_dfetl_validation_numbers CHECK (((revalidate_delay >= 0) AND ((lookback_hours >= 0) AND (lookback_hours <= 168)))),
    CONSTRAINT ck_dfetl_validation_tolerance CHECK (((row_tolerance >= (0)::numeric) AND (row_tolerance <= (100)::numeric)))
);


--
-- Name: TABLE dfetl_validation_policy; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dfetl_validation_policy IS '标准数据集共享的校验策略';


--
-- Name: dfetl_validation_policy_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dfetl_validation_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dfetl_validation_policy_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dfetl_validation_policy_id_seq OWNED BY df_etl.dfetl_validation_policy.id;


--
-- Name: dirty_record; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.dirty_record (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    execution_id bigint,
    chunk_id bigint,
    view_name character varying(200),
    chunk_no integer,
    error_type character varying(50) NOT NULL,
    target_field character varying(200),
    error_msg text,
    raw_data text,
    handled boolean DEFAULT false NOT NULL,
    handled_at timestamp with time zone,
    found_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE dirty_record; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.dirty_record IS '脏数据记录（类型转换失败、空值异常、Stream Load 失败等）';


--
-- Name: COLUMN dirty_record.error_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.dirty_record.error_type IS 'FIELD_CONVERT_FAIL=字段转换失败 | NULL_VIOLATION=空值异常 | TYPE_MISMATCH=类型不匹配 | WRITE_FAIL=写入失败';


--
-- Name: COLUMN dirty_record.raw_data; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.dirty_record.raw_data IS '原始行的 JSON 字符串，用于人工排查数据问题';


--
-- Name: dirty_record_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.dirty_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dirty_record_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.dirty_record_id_seq OWNED BY df_etl.dirty_record.id;


--
-- Name: doris_type_mapping_rule; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.doris_type_mapping_rule (
    id bigint NOT NULL,
    profile_name character varying(64) DEFAULT 'DEFAULT_MEDICAL_VIEW'::character varying NOT NULL,
    profile_version integer DEFAULT 1 NOT NULL,
    source_dialect character varying(32) NOT NULL,
    source_type_pattern character varying(128) NOT NULL,
    recommended_doris_type character varying(128) NOT NULL,
    compatibility_level character varying(16) DEFAULT 'PASS'::character varying NOT NULL,
    reason text,
    enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: doris_type_mapping_rule_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.doris_type_mapping_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: doris_type_mapping_rule_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.doris_type_mapping_rule_id_seq OWNED BY df_etl.doris_type_mapping_rule.id;


--
-- Name: etl_verify_chunk; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.etl_verify_chunk (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    exec_id bigint NOT NULL,
    chunk_no integer NOT NULL,
    chunk_start character varying(256),
    chunk_end character varying(256),
    source_count bigint,
    target_count bigint,
    source_checksum character varying(64),
    target_checksum character varying(64),
    matched boolean DEFAULT false NOT NULL,
    finished_at timestamp with time zone,
    scoped_window_start timestamp with time zone,
    scoped_window_end timestamp with time zone,
    validation_run_id bigint
);


--
-- Name: TABLE etl_verify_chunk; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.etl_verify_chunk IS 'spec 023：Checksum 分片级结果';


--
-- Name: etl_verify_chunk_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.etl_verify_chunk_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: etl_verify_chunk_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.etl_verify_chunk_id_seq OWNED BY df_etl.etl_verify_chunk.id;


--
-- Name: etl_verify_diff; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.etl_verify_diff (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    exec_id bigint NOT NULL,
    chunk_no integer,
    pk_value character varying(512) NOT NULL,
    diff_type character varying(32) NOT NULL,
    source_hash character varying(64),
    target_hash character varying(64),
    repair_status character varying(32) DEFAULT 'PENDING'::character varying NOT NULL,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    repaired_at timestamp with time zone,
    repair_label character varying(128),
    validation_run_id bigint,
    repair_source character varying(16) DEFAULT NULL::character varying
);


--
-- Name: TABLE etl_verify_diff; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.etl_verify_diff IS 'spec 023：Checksum 行级差异（待 spec 024 Repair 处理）';


--
-- Name: COLUMN etl_verify_diff.repair_source; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.etl_verify_diff.repair_source IS 'Repair 来源（spec validation-workbench-redesign）：AUTO=自动修复 / MANUAL=用户主动 / NULL=未修复或本需求落地前历史数据';


--
-- Name: etl_verify_diff_field; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.etl_verify_diff_field (
    id bigint NOT NULL,
    diff_id bigint NOT NULL,
    task_id bigint NOT NULL,
    exec_id bigint NOT NULL,
    column_name character varying(128) NOT NULL,
    target_column character varying(128),
    diff_kind character varying(32) NOT NULL,
    src_value_display text,
    tgt_value_display text,
    src_value_hash character varying(64),
    tgt_value_hash character varying(64),
    masked boolean DEFAULT false NOT NULL,
    truncated boolean DEFAULT false NOT NULL,
    normalized_differ boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    validation_run_id bigint
);


--
-- Name: TABLE etl_verify_diff_field; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.etl_verify_diff_field IS 'Spec 056：异步预计算的字段级差异（display + hash，不存原值）';


--
-- Name: etl_verify_diff_field_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.etl_verify_diff_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: etl_verify_diff_field_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.etl_verify_diff_field_id_seq OWNED BY df_etl.etl_verify_diff_field.id;


--
-- Name: etl_verify_diff_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.etl_verify_diff_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: etl_verify_diff_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.etl_verify_diff_id_seq OWNED BY df_etl.etl_verify_diff.id;


--
-- Name: external_api_client; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.external_api_client (
    id bigint NOT NULL,
    client_id character varying(100) NOT NULL,
    client_name character varying(100) NOT NULL,
    secret_enc text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    allowed_yi_liao_jg_dm character varying(50),
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE external_api_client; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.external_api_client IS '外部 API HMAC client、密钥密文和机构/业务授权范围';


--
-- Name: COLUMN external_api_client.secret_enc; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_api_client.secret_enc IS 'AES 加密后的外部 API shared secret，禁止存明文';


--
-- Name: COLUMN external_api_client.allowed_yi_liao_jg_dm; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_api_client.allowed_yi_liao_jg_dm IS '允许访问的医疗机构编码；NULL 或 * 表示不限';


--
-- Name: external_api_client_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.external_api_client_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: external_api_client_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.external_api_client_id_seq OWNED BY df_etl.external_api_client.id;


--
-- Name: external_api_request_nonce; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.external_api_request_nonce (
    id bigint NOT NULL,
    client_id character varying(100) NOT NULL,
    nonce character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE external_api_request_nonce; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.external_api_request_nonce IS '外部 API HMAC nonce 防重放记录';


--
-- Name: external_api_request_nonce_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.external_api_request_nonce_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: external_api_request_nonce_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.external_api_request_nonce_id_seq OWNED BY df_etl.external_api_request_nonce.id;


--
-- Name: external_task_batch_operation_audit; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.external_task_batch_operation_audit (
    id bigint NOT NULL,
    external_batch_id character varying(128) NOT NULL,
    operation character varying(30) NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    status character varying(30) NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    success_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    skipped_count integer DEFAULT 0 NOT NULL,
    caller character varying(100),
    client_id character varying(100),
    result_body text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE external_task_batch_operation_audit; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.external_task_batch_operation_audit IS '外部批量任务运行/删除操作审计记录';


--
-- Name: COLUMN external_task_batch_operation_audit.result_body; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_batch_operation_audit.result_body IS '批量运行/删除响应 JSON 快照';


--
-- Name: external_task_batch_operation_audit_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.external_task_batch_operation_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: external_task_batch_operation_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.external_task_batch_operation_audit_id_seq OWNED BY df_etl.external_task_batch_operation_audit.id;


--
-- Name: external_task_batch_request; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.external_task_batch_request (
    id bigint NOT NULL,
    external_batch_id character varying(128) NOT NULL,
    yi_liao_jg_dm character varying(50) NOT NULL,
    request_hash character varying(128) NOT NULL,
    status character varying(30) NOT NULL,
    failure_policy character varying(30) NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    created_count integer DEFAULT 0 NOT NULL,
    existing_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    request_body text,
    result_body text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE external_task_batch_request; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.external_task_batch_request IS '外部批量任务创建请求幂等与解析审计记录';


--
-- Name: COLUMN external_task_batch_request.external_batch_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_batch_request.external_batch_id IS '外部批量请求幂等号；重复提交返回同一批结果';


--
-- Name: COLUMN external_task_batch_request.request_hash; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_batch_request.request_hash IS '批量请求业务字段 hash，用于识别 externalBatchId 复用冲突';


--
-- Name: COLUMN external_task_batch_request.result_body; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_batch_request.result_body IS '批量创建结果 JSON';


--
-- Name: external_task_batch_request_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.external_task_batch_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: external_task_batch_request_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.external_task_batch_request_id_seq OWNED BY df_etl.external_task_batch_request.id;


--
-- Name: external_task_request; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.external_task_request (
    id bigint NOT NULL,
    external_request_id character varying(128) NOT NULL,
    caller character varying(100),
    yi_liao_jg_dm character varying(50) NOT NULL,
    source_schema character varying(100),
    source_object character varying(200) NOT NULL,
    source_object_type character varying(30),
    task_id bigint,
    status character varying(30) NOT NULL,
    error_code character varying(80),
    error_message text,
    request_body text,
    resolved_plan text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    external_batch_id character varying(128),
    batch_item_key character varying(256),
    batch_item_status character varying(30)
);


--
-- Name: TABLE external_task_request; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.external_task_request IS '外部任务创建请求幂等与解析审计记录';


--
-- Name: COLUMN external_task_request.external_request_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.external_request_id IS '调用方幂等请求号；重复提交返回同一内部任务';


--
-- Name: COLUMN external_task_request.yi_liao_jg_dm; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.yi_liao_jg_dm IS '医疗机构代码，不等同 tenantId';


--
-- Name: COLUMN external_task_request.resolved_plan; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.resolved_plan IS '后端解析出的源/目标/医共体合约计划 JSON';


--
-- Name: COLUMN external_task_request.external_batch_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.external_batch_id IS '外部批量请求幂等号';


--
-- Name: COLUMN external_task_request.batch_item_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.batch_item_key IS '批量请求内 sourceObject 稳定键，例如 schema.view';


--
-- Name: COLUMN external_task_request.batch_item_status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.external_task_request.batch_item_status IS '批量 item 状态：CREATED/EXISTING/FAILED/SKIPPED';


--
-- Name: external_task_request_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.external_task_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: external_task_request_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.external_task_request_id_seq OWNED BY df_etl.external_task_request.id;


--
-- Name: institution; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.institution (
    id bigint NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    short_name character varying(50),
    type character varying(20),
    level character varying(20),
    region_code character varying(20),
    parent_id bigint,
    enabled boolean DEFAULT true NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE institution; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.institution IS '机构主表 — 医共体场景下的医疗机构一等公民';


--
-- Name: COLUMN institution.code; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.code IS '机构业务唯一编码，如 YGT330106H001';


--
-- Name: COLUMN institution.name; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.name IS '机构全称';


--
-- Name: COLUMN institution.short_name; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.short_name IS '机构简称';


--
-- Name: COLUMN institution.type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.type IS '机构类型：HOSPITAL / CLINIC / CENTER / COMMUNITY';


--
-- Name: COLUMN institution.level; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.level IS '机构等级：TIER_3 / TIER_2 / TIER_1';


--
-- Name: COLUMN institution.region_code; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.region_code IS '行政区划代码';


--
-- Name: COLUMN institution.parent_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.parent_id IS '上级机构 ID（医共体层级，自引用）';


--
-- Name: COLUMN institution.enabled; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.institution.enabled IS '启用状态；删除采用软删除（设为 false）';


--
-- Name: institution_dataset_route; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.institution_dataset_route (
    id bigint NOT NULL,
    institution_id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    source_datasource_id bigint NOT NULL,
    source_schema character varying(100) NOT NULL,
    source_object character varying(200) NOT NULL,
    source_object_type character varying(30) DEFAULT 'VIEW'::character varying NOT NULL,
    target_datasource_id bigint NOT NULL,
    target_table character varying(200) NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    validation_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    validation_summary text,
    validation_details_json text,
    last_validated_at timestamp with time zone,
    validated_contract_hash character varying(128),
    validated_route_revision bigint,
    route_revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_institution_dataset_route_enable_requires_validation CHECK (((NOT enabled) OR (((validation_status)::text = 'PASSED'::text) AND (last_validated_at IS NOT NULL) AND (validated_route_revision = route_revision) AND (validated_contract_hash IS NOT NULL)))),
    CONSTRAINT ck_institution_dataset_route_revision_positive CHECK ((route_revision > 0)),
    CONSTRAINT ck_institution_dataset_route_source_type CHECK (((source_object_type)::text = ANY ((ARRAY['TABLE'::character varying, 'VIEW'::character varying, 'MATERIALIZED_VIEW'::character varying])::text[]))),
    CONSTRAINT ck_institution_dataset_route_validation_status CHECK (((validation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'PASSED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: TABLE institution_dataset_route; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.institution_dataset_route IS '机构标准数据集到实际源对象和目标表的已验证路由';


--
-- Name: institution_dataset_route_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.institution_dataset_route_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: institution_dataset_route_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.institution_dataset_route_id_seq OWNED BY df_etl.institution_dataset_route.id;


--
-- Name: institution_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.institution_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: institution_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.institution_id_seq OWNED BY df_etl.institution.id;


--
-- Name: medical_dirty_field; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.medical_dirty_field (
    id bigint NOT NULL,
    dirty_row_id bigint NOT NULL,
    field_code character varying(100) NOT NULL,
    field_name character varying(200),
    source_column character varying(200),
    target_column character varying(200),
    error_type character varying(80) NOT NULL,
    standard_rule character varying(200),
    raw_value text,
    normalized_value text,
    message text,
    severity character varying(50) NOT NULL,
    value_domain_code character varying(100),
    value_domain_mode character varying(30),
    value_domain_allowed_count integer
);


--
-- Name: TABLE medical_dirty_field; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.medical_dirty_field IS '医共体字段级问题明细';


--
-- Name: COLUMN medical_dirty_field.error_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.medical_dirty_field.error_type IS 'PRIMARY_KEY_NULL/PRIMARY_KEY_DUPLICATE/NON_KEY_INVALID_NUMBER_TO_NULL 等标准化错误类型';


--
-- Name: medical_dirty_field_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.medical_dirty_field_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: medical_dirty_field_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.medical_dirty_field_id_seq OWNED BY df_etl.medical_dirty_field.id;


--
-- Name: medical_dirty_row; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.medical_dirty_row (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    execution_id bigint NOT NULL,
    dataset_code character varying(100) NOT NULL,
    dataset_name character varying(200),
    source_schema character varying(200),
    source_view character varying(200) NOT NULL,
    target_table character varying(200),
    business_pk_json text,
    source_row_hash character varying(64) NOT NULL,
    window_json text,
    owner_name character varying(100),
    owner_source character varying(100),
    row_action character varying(50) NOT NULL,
    severity character varying(50) NOT NULL,
    status character varying(50) DEFAULT 'OPEN'::character varying NOT NULL,
    raw_row_json text,
    error_count integer DEFAULT 0 NOT NULL,
    found_at timestamp with time zone DEFAULT now() NOT NULL,
    sent_at timestamp with time zone,
    handled_at timestamp with time zone,
    handled_by character varying(100),
    handle_note text
);


--
-- Name: TABLE medical_dirty_row; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.medical_dirty_row IS '医共体行级问题记录，用于合规行写入后的问题行核对闭环';


--
-- Name: COLUMN medical_dirty_row.row_action; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.medical_dirty_row.row_action IS 'EXCLUDED=该行未写入Doris | WRITTEN_WITH_WARNING=该行已写入但存在告警';


--
-- Name: COLUMN medical_dirty_row.status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.medical_dirty_row.status IS 'OPEN/SENT/CONFIRMED/FIXED/IGNORED';


--
-- Name: medical_dirty_row_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.medical_dirty_row_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: medical_dirty_row_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.medical_dirty_row_id_seq OWNED BY df_etl.medical_dirty_row.id;


--
-- Name: message_publish_config; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.message_publish_config (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    channel character varying(200) NOT NULL,
    message_type character varying(50) NOT NULL,
    topic character varying(100) NOT NULL,
    message_key_template character varying(500),
    full_sync_mode character varying(20) DEFAULT 'SKIP'::character varying NOT NULL,
    rate_limit integer,
    page_size integer DEFAULT 1000,
    source_system character varying(50) DEFAULT 'HIS'::character varying,
    tenant_id character varying(50) DEFAULT '0'::character varying,
    field_mapping_json text,
    stream_max_len integer DEFAULT 10000,
    send_truncate_signal boolean DEFAULT true,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE message_publish_config; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.message_publish_config IS '消息发布配置 — 每个同步任务的 Redis 消息发布参数';


--
-- Name: COLUMN message_publish_config.task_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.task_id IS '关联同步任务 ID（唯一）';


--
-- Name: COLUMN message_publish_config.enabled; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.enabled IS '是否启用消息发布';


--
-- Name: COLUMN message_publish_config.channel; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.channel IS 'Redis Pub/Sub channel';


--
-- Name: COLUMN message_publish_config.message_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.message_type IS '消息类型，如 MFN^ZB3';


--
-- Name: COLUMN message_publish_config.topic; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.topic IS '业务主题，如 base.department';


--
-- Name: COLUMN message_publish_config.message_key_template; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.message_key_template IS 'messageKey 模板，如 {yljgdm}:{ksdm}';


--
-- Name: COLUMN message_publish_config.full_sync_mode; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.full_sync_mode IS '全量同步模式：ALL/SKIP/NOTIFY_ONLY';


--
-- Name: COLUMN message_publish_config.rate_limit; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.rate_limit IS '限速（条/秒），null 表示不限速';


--
-- Name: COLUMN message_publish_config.page_size; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.page_size IS '全量分页大小';


--
-- Name: COLUMN message_publish_config.source_system; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.source_system IS '来源系统标识';


--
-- Name: COLUMN message_publish_config.tenant_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.tenant_id IS '租户 ID';


--
-- Name: COLUMN message_publish_config.field_mapping_json; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.field_mapping_json IS '手动字段映射 JSON（退化方案）';


--
-- Name: COLUMN message_publish_config.stream_max_len; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.stream_max_len IS 'Redis Stream MAXLEN 限制';


--
-- Name: COLUMN message_publish_config.send_truncate_signal; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_config.send_truncate_signal IS '全量 ALL 模式是否发送 TRUNCATE 信号';


--
-- Name: message_publish_config_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.message_publish_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_publish_config_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.message_publish_config_id_seq OWNED BY df_etl.message_publish_config.id;


--
-- Name: message_publish_log; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.message_publish_log (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    batch_id bigint,
    channel character varying(200) NOT NULL,
    topic character varying(100) NOT NULL,
    message_count integer,
    status character varying(20) NOT NULL,
    error_message text,
    publish_time timestamp with time zone NOT NULL,
    data_scope character varying(20),
    window_start timestamp with time zone,
    window_end timestamp with time zone,
    sample_messages text,
    tenant_id character varying(50),
    source_system character varying(50),
    message_type character varying(50),
    retry_attempts integer DEFAULT 0 NOT NULL,
    next_retry_time timestamp with time zone
);


--
-- Name: TABLE message_publish_log; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.message_publish_log IS '消息发布日志 — 记录每次发布操作的状态';


--
-- Name: COLUMN message_publish_log.task_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.task_id IS '关联同步任务 ID';


--
-- Name: COLUMN message_publish_log.batch_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.batch_id IS '批次 ID';


--
-- Name: COLUMN message_publish_log.channel; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.channel IS 'Redis Pub/Sub channel';


--
-- Name: COLUMN message_publish_log.topic; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.topic IS '业务主题';


--
-- Name: COLUMN message_publish_log.message_count; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.message_count IS '发送消息数';


--
-- Name: COLUMN message_publish_log.status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.status IS '发布状态：SUCCESS/FAILED/PARTIAL';


--
-- Name: COLUMN message_publish_log.error_message; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.error_message IS '错误信息';


--
-- Name: COLUMN message_publish_log.publish_time; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.publish_time IS '发布时间';


--
-- Name: COLUMN message_publish_log.data_scope; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.data_scope IS '数据范围：INCREMENTAL/FULL';


--
-- Name: COLUMN message_publish_log.window_start; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.window_start IS '增量窗口起始时间';


--
-- Name: COLUMN message_publish_log.window_end; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.window_end IS '增量窗口结束时间';


--
-- Name: COLUMN message_publish_log.sample_messages; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_publish_log.sample_messages IS '本次发布的消息样本（前5条完整JSON数组），用于调试预览';


--
-- Name: message_publish_log_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.message_publish_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_publish_log_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.message_publish_log_id_seq OWNED BY df_etl.message_publish_log.id;


--
-- Name: message_send_record; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.message_send_record (
    id bigint NOT NULL,
    message_id character varying(64) NOT NULL,
    task_id bigint,
    batch_id bigint,
    publish_log_id bigint,
    channel_mode character varying(32) NOT NULL,
    exchange_name character varying(128),
    route_key character varying(128) NOT NULL,
    topic character varying(128) NOT NULL,
    message_key character varying(256),
    business_key character varying(256),
    tenant_id character varying(64),
    source_system character varying(128),
    trace_id character varying(128),
    payload_type character varying(256) DEFAULT 'com.dfygt.dfetl.server.service.publish.EtlMessage'::character varying NOT NULL,
    message_json text NOT NULL,
    payload_json text,
    headers_json text,
    send_status character varying(32) NOT NULL,
    send_attempts integer DEFAULT 0 NOT NULL,
    send_start_time timestamp with time zone,
    broker_confirm_time timestamp with time zone,
    sent_time timestamp with time zone,
    next_retry_time timestamp with time zone,
    last_error text,
    external_record_status character varying(32),
    external_record_time timestamp with time zone,
    external_record_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE message_send_record; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.message_send_record IS 'dfetl 本地逐条消息发送记录，记录生产者侧 RabbitMQ 发送闭环';


--
-- Name: COLUMN message_send_record.message_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.message_id IS '消息 ID，全链路唯一';


--
-- Name: COLUMN message_send_record.task_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.task_id IS '关联同步任务 ID';


--
-- Name: COLUMN message_send_record.batch_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.batch_id IS '关联同步批次 ID；重发时使用新的负数批次 ID';


--
-- Name: COLUMN message_send_record.publish_log_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.publish_log_id IS '预留：关联批次级 message_publish_log ID';


--
-- Name: COLUMN message_send_record.channel_mode; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.channel_mode IS '投递通道：RABBITMQ/REDIS';


--
-- Name: COLUMN message_send_record.exchange_name; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.exchange_name IS 'RabbitMQ exchange';


--
-- Name: COLUMN message_send_record.route_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.route_key IS 'RabbitMQ routing key / 消息 routeKey';


--
-- Name: COLUMN message_send_record.topic; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.topic IS '消息 body.topic';


--
-- Name: COLUMN message_send_record.message_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.message_key IS '消息业务唯一键';


--
-- Name: COLUMN message_send_record.business_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.business_key IS 'headers.businessKey';


--
-- Name: COLUMN message_send_record.message_json; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.message_json IS '实际发送到 RabbitMQ 的完整 JSON body';


--
-- Name: COLUMN message_send_record.payload_json; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.payload_json IS 'body.payload JSON';


--
-- Name: COLUMN message_send_record.headers_json; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.headers_json IS 'body.headers JSON';


--
-- Name: COLUMN message_send_record.send_status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.send_status IS '发送状态：SENDING/SENT/SEND_FAILED/WAIT_RETRY/FAILED_FINAL';


--
-- Name: COLUMN message_send_record.send_attempts; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.send_attempts IS '发送尝试次数，同一 messageId 重试时递增';


--
-- Name: COLUMN message_send_record.broker_confirm_time; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.broker_confirm_time IS 'RabbitMQ confirm/return 回写时间';


--
-- Name: COLUMN message_send_record.sent_time; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.sent_time IS 'RabbitMQ confirm ack 成功时间';


--
-- Name: COLUMN message_send_record.last_error; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.last_error IS '最后一次发送失败原因';


--
-- Name: COLUMN message_send_record.external_record_status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.external_record_status IS '外部医共体 msg_send 写入状态：WAIT_SEND/SENT/SEND_FAILED';


--
-- Name: COLUMN message_send_record.external_record_time; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.external_record_time IS '外部医共体 msg_send 状态更新时间';


--
-- Name: COLUMN message_send_record.external_record_error; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.message_send_record.external_record_error IS '外部医共体 msg_send 最后写入错误';


--
-- Name: message_send_record_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.message_send_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: message_send_record_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.message_send_record_id_seq OWNED BY df_etl.message_send_record.id;


--
-- Name: notify_record; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.notify_record (
    id bigint NOT NULL,
    rule_id bigint,
    rule_name character varying(200),
    severity character varying(20) NOT NULL,
    content text,
    channel_id bigint,
    channel_name character varying(100),
    task_id bigint,
    task_name character varying(200),
    batch_no character varying(30),
    triggered_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL
);


--
-- Name: TABLE notify_record; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.notify_record IS '告警通知发送历史（每条渠道一条记录，支持多渠道并行发送）';


--
-- Name: notify_record_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.notify_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notify_record_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.notify_record_id_seq OWNED BY df_etl.notify_record.id;


--
-- Name: qrtz_blob_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_blob_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    blob_data bytea
);


--
-- Name: qrtz_calendars; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_calendars (
    sched_name character varying(120) NOT NULL,
    calendar_name character varying(200) NOT NULL,
    calendar bytea NOT NULL
);


--
-- Name: qrtz_cron_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_cron_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    cron_expression character varying(120) NOT NULL,
    time_zone_id character varying(80)
);


--
-- Name: qrtz_fired_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_fired_triggers (
    sched_name character varying(120) NOT NULL,
    entry_id character varying(95) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    instance_name character varying(200) NOT NULL,
    fired_time bigint NOT NULL,
    sched_time bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(16) NOT NULL,
    job_name character varying(200),
    job_group character varying(200),
    is_nonconcurrent boolean,
    requests_recovery boolean
);


--
-- Name: qrtz_job_details; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_job_details (
    sched_name character varying(120) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    job_class_name character varying(250) NOT NULL,
    is_durable boolean NOT NULL,
    is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL,
    requests_recovery boolean NOT NULL,
    job_data bytea
);


--
-- Name: qrtz_locks; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_locks (
    sched_name character varying(120) NOT NULL,
    lock_name character varying(40) NOT NULL
);


--
-- Name: qrtz_paused_trigger_grps; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_paused_trigger_grps (
    sched_name character varying(120) NOT NULL,
    trigger_group character varying(200) NOT NULL
);


--
-- Name: qrtz_scheduler_state; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_scheduler_state (
    sched_name character varying(120) NOT NULL,
    instance_name character varying(200) NOT NULL,
    last_checkin_time bigint NOT NULL,
    checkin_interval bigint NOT NULL
);


--
-- Name: qrtz_simple_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_simple_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL,
    times_triggered bigint NOT NULL
);


--
-- Name: qrtz_simprop_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_simprop_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    str_prop_1 character varying(512),
    str_prop_2 character varying(512),
    str_prop_3 character varying(512),
    int_prop_1 integer,
    int_prop_2 integer,
    long_prop_1 bigint,
    long_prop_2 bigint,
    dec_prop_1 numeric(13,4),
    dec_prop_2 numeric(13,4),
    bool_prop_1 boolean,
    bool_prop_2 boolean
);


--
-- Name: qrtz_triggers; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.qrtz_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority integer,
    trigger_state character varying(16) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    start_time bigint NOT NULL,
    end_time bigint,
    calendar_name character varying(200),
    misfire_instr smallint,
    job_data bytea
);


--
-- Name: snapshot_apply_history; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.snapshot_apply_history (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    prev_execution_id bigint NOT NULL,
    curr_execution_id bigint NOT NULL,
    dry_run boolean DEFAULT false NOT NULL,
    detected_keys integer DEFAULT 0 NOT NULL,
    loaded_rows bigint DEFAULT 0 NOT NULL,
    filtered_rows bigint DEFAULT 0 NOT NULL,
    result character varying(40) NOT NULL,
    label character varying(128),
    message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE snapshot_apply_history; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.snapshot_apply_history IS 'spec 067：快照删除校验 Dry-Run / Apply 处理历史';


--
-- Name: snapshot_apply_history_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.snapshot_apply_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_apply_history_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.snapshot_apply_history_id_seq OWNED BY df_etl.snapshot_apply_history.id;


--
-- Name: source_datasource; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.source_datasource (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    host character varying(255) NOT NULL,
    port integer NOT NULL,
    db_name character varying(100) NOT NULL,
    schema_name character varying(100),
    username character varying(100) NOT NULL,
    password_enc text NOT NULL,
    readonly boolean DEFAULT true NOT NULL,
    query_timeout integer DEFAULT 60 NOT NULL,
    read_concurrency integer DEFAULT 4 NOT NULL,
    pool_size integer DEFAULT 10 NOT NULL,
    ssl boolean DEFAULT false NOT NULL,
    description text,
    status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    institution_id bigint,
    source_code character varying(100)
);


--
-- Name: TABLE source_datasource; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.source_datasource IS '源数据源配置（医疗机构业务库）';


--
-- Name: COLUMN source_datasource.type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.type IS 'MYSQL | POSTGRESQL | ORACLE | SQLSERVER | DORIS';


--
-- Name: COLUMN source_datasource.password_enc; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.password_enc IS 'AES-256/CBC 加密密码，格式：Base64(IV):Base64(密文)';


--
-- Name: COLUMN source_datasource.read_concurrency; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.read_concurrency IS 'ETL 读取并发数，对应 dfetl channel 数量';


--
-- Name: COLUMN source_datasource.status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.status IS 'NORMAL=正常 | ERROR=连接失败 | TESTING=测试中';


--
-- Name: COLUMN source_datasource.institution_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.institution_id IS '所属机构 ID（df_etl.institution.id），可空以兼容老数据';


--
-- Name: COLUMN source_datasource.source_code; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.source_datasource.source_code IS 'spec 070：数据源稳定编码（机构首字母-业务首字母-库类型-序号），创建时系统生成、不可改';


--
-- Name: source_datasource_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.source_datasource_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: source_datasource_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.source_datasource_id_seq OWNED BY df_etl.source_datasource.id;


--
-- Name: sync_task; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.sync_task (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    source_datasource_id bigint NOT NULL,
    target_datasource_id bigint NOT NULL,
    source_schema character varying(100),
    view_names text,
    sync_type character varying(20) DEFAULT 'FULL'::character varying NOT NULL,
    sync_mode character varying(20) DEFAULT 'TRUNCATE'::character varying NOT NULL,
    data_scope character varying(20) DEFAULT 'FULL'::character varying NOT NULL,
    incremental_field character varying(200),
    upsert_keys text,
    batch_size integer,
    parallelism integer DEFAULT 4 NOT NULL,
    shard_count integer,
    shard_strategy character varying(50) DEFAULT 'PRIMARY_KEY_RANGE'::character varying NOT NULL,
    rate_limit integer DEFAULT 0 NOT NULL,
    schedule character varying(100),
    schedule_label character varying(100),
    status character varying(20) DEFAULT 'DISABLED'::character varying NOT NULL,
    version_status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    version character varying(20) DEFAULT 'V1'::character varying NOT NULL,
    last_run_time timestamp with time zone,
    last_run_status character varying(20),
    incremental_checkpoint timestamp with time zone,
    alert_status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    increment_mode character varying(20),
    upper_bound_strategy character varying(20) DEFAULT 'CURRENT_TIME'::character varying NOT NULL,
    upper_bound_delay_minutes integer DEFAULT 5 NOT NULL,
    initial_watermark character varying(100),
    writer_type character varying(20) DEFAULT 'STREAM_LOAD'::character varying NOT NULL,
    doris_table_model character varying(20),
    static_filter text,
    custom_window_start timestamp with time zone,
    custom_window_end timestamp with time zone,
    executor_type character varying(30) DEFAULT 'DATAX'::character varying,
    split_pk character varying(200),
    source_object_type character varying(30) DEFAULT 'TABLE'::character varying NOT NULL,
    soft_delete_field character varying(100),
    soft_delete_active_value character varying(50) DEFAULT '0'::character varying,
    enable_doris_merge boolean DEFAULT false NOT NULL,
    delete_sign_value character varying(50) DEFAULT '1'::character varying,
    sequence_col character varying(100),
    partial_columns boolean DEFAULT false NOT NULL,
    lookback_seconds integer DEFAULT 0 NOT NULL,
    enable_snapshot_delete boolean DEFAULT false NOT NULL,
    filter_condition_map text,
    target_table_map text,
    initial_full_sync boolean DEFAULT false NOT NULL,
    initial_full_sync_done boolean DEFAULT false NOT NULL,
    snapshot_auto_capture boolean DEFAULT false NOT NULL,
    snapshot_auto_detect_cron character varying(64),
    snapshot_auto_apply boolean DEFAULT false NOT NULL,
    snapshot_delete_max_ratio numeric(5,4) DEFAULT 0.0500 NOT NULL,
    snapshot_capture_interval_minutes integer DEFAULT 0 NOT NULL,
    cron_expression character varying(128),
    schedule_config text,
    schedule_description character varying(255),
    schedule_timezone character varying(64) DEFAULT 'Asia/Shanghai'::character varying,
    data_characteristics text,
    source_mode character varying(20) DEFAULT 'TABLE_VIEW'::character varying NOT NULL,
    custom_sql text,
    custom_sql_name character varying(100),
    institution_id bigint,
    retry_max_attempts integer,
    retry_interval_seconds integer
);


--
-- Name: TABLE sync_task; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.sync_task IS '同步任务配置';


--
-- Name: COLUMN sync_task.view_names; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.view_names IS 'JSON 数组，示例：["v_his_patients","v_his_outpatient"]';


--
-- Name: COLUMN sync_task.sync_mode; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.sync_mode IS 'TRUNCATE=清空写入 | APPEND=追加写入 | UPSERT=按主键更新写入';


--
-- Name: COLUMN sync_task.data_scope; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.data_scope IS 'FULL=全量数据 | INCREMENTAL=增量窗口数据';


--
-- Name: COLUMN sync_task.incremental_field; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.incremental_field IS '增量同步时间字段，如 updated_at / report_time';


--
-- Name: COLUMN sync_task.upsert_keys; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.upsert_keys IS 'UPSERT 模式主键列，JSON 数组，如 ["patient_id"]';


--
-- Name: COLUMN sync_task.batch_size; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.batch_size IS '任务级 JDBC fetch_size 覆盖值，NULL/0=继承全局 etl.fetch_size';


--
-- Name: COLUMN sync_task.shard_strategy; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.shard_strategy IS 'PRIMARY_KEY_RANGE=按主键范围分片（目前唯一支持的策略）';


--
-- Name: COLUMN sync_task.version_status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.version_status IS 'DRAFT=草稿 | TESTED=测试通过 | PUBLISHED=已发布 | DEPRECATED=已废弃';


--
-- Name: COLUMN sync_task.incremental_checkpoint; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.incremental_checkpoint IS '增量同步专用：下次执行时的 WHERE field >= checkpoint';


--
-- Name: COLUMN sync_task.cron_expression; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.cron_expression IS 'spec 053 - Quartz Cron 表达式（由 scheduleConfig 后端生成；MANUAL 模式为 null）';


--
-- Name: COLUMN sync_task.schedule_config; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.schedule_config IS 'spec 053 - 可视化调度配置 JSON（mode/intervalMinutes/hour/.../version）';


--
-- Name: COLUMN sync_task.schedule_description; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.schedule_description IS 'spec 053 - 中文描述（如 "每天 02:30 执行"）';


--
-- Name: COLUMN sync_task.schedule_timezone; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.schedule_timezone IS 'spec 053 - 调度时区，默认 Asia/Shanghai';


--
-- Name: COLUMN sync_task.source_mode; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.source_mode IS 'TABLE_VIEW=表/视图模式 | CUSTOM_SQL=自定义 SQL 模式';


--
-- Name: COLUMN sync_task.custom_sql; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.custom_sql IS '自定义 SQL 模式下的只读 SELECT 语句';


--
-- Name: COLUMN sync_task.custom_sql_name; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.custom_sql_name IS '自定义 SQL 的逻辑源名，用于目标表映射和任务命名';


--
-- Name: COLUMN sync_task.institution_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.institution_id IS '所属机构 ID；创建时若 dto 未指定，将从 source_datasource.institution_id 继承';


--
-- Name: COLUMN sync_task.retry_max_attempts; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.retry_max_attempts IS '任务级自动重试最大次数，NULL=使用全局默认值(0=不重试)';


--
-- Name: COLUMN sync_task.retry_interval_seconds; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.sync_task.retry_interval_seconds IS '任务级自动重试间隔秒数，NULL=使用全局默认值(30)';


--
-- Name: sync_task_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.sync_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sync_task_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.sync_task_id_seq OWNED BY df_etl.sync_task.id;


--
-- Name: system_setting; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.system_setting (
    setting_key character varying(200) NOT NULL,
    setting_value text,
    description text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE system_setting; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.system_setting IS '全局系统配置（K-V 存储），前端"设置"页面持久化';


--
-- Name: COLUMN system_setting.setting_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.system_setting.setting_key IS '示例键名：scheduler.max_concurrent | etl.fetch_size | etl.system_field.batch_id';


--
-- Name: target_datasource; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.target_datasource (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    environment character varying(20) DEFAULT 'production'::character varying NOT NULL,
    fe_host character varying(255) NOT NULL,
    fe_port integer DEFAULT 9030 NOT NULL,
    http_port integer DEFAULT 8030 NOT NULL,
    stream_load_port integer DEFAULT 8040 NOT NULL,
    username character varying(100) NOT NULL,
    password_enc text NOT NULL,
    db_name character varying(100) NOT NULL,
    default_write_database character varying(100),
    write_batch_size integer DEFAULT 50000 NOT NULL,
    write_concurrency integer DEFAULT 8 NOT NULL,
    pool_size integer DEFAULT 20 NOT NULL,
    ssl boolean DEFAULT false NOT NULL,
    description text,
    status character varying(20) DEFAULT 'NORMAL'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE target_datasource; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.target_datasource IS 'Doris 目标数据源配置';


--
-- Name: COLUMN target_datasource.stream_load_port; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.target_datasource.stream_load_port IS 'BE HTTP 端口（8040），必须直连 BE；FE 会 307 重定向到 BE 内网 IP 导致失败';


--
-- Name: COLUMN target_datasource.write_batch_size; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.target_datasource.write_batch_size IS 'Stream Load 单批行数，过大增加内存压力';


--
-- Name: target_datasource_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.target_datasource_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: target_datasource_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.target_datasource_id_seq OWNED BY df_etl.target_datasource.id;


--
-- Name: task_chunk; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.task_chunk (
    id bigint NOT NULL,
    execution_id bigint NOT NULL,
    view_name character varying(200),
    chunk_no integer NOT NULL,
    range_desc character varying(500),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    read_rows bigint,
    write_rows bigint,
    source_checksum character varying(128),
    target_checksum character varying(128),
    doris_label character varying(300),
    fetch_size integer,
    concurrency integer,
    retries integer DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms bigint,
    error_msg text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE task_chunk; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.task_chunk IS '分片（Chunk）执行明细，一次执行有多个并行 Chunk';


--
-- Name: COLUMN task_chunk.range_desc; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_chunk.range_desc IS '主键分片范围描述，如 "patient_id 720001 ~ 960000"';


--
-- Name: COLUMN task_chunk.doris_label; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_chunk.doris_label IS 'Doris Stream Load label，格式：etl_{taskName}_{batchNo}_c{chunkNo}，用于排查"label 已存在"问题';


--
-- Name: task_chunk_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.task_chunk_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_chunk_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.task_chunk_id_seq OWNED BY df_etl.task_chunk.id;


--
-- Name: task_execution; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.task_execution (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    batch_no character varying(30) NOT NULL,
    triggered_by character varying(50) DEFAULT 'MANUAL'::character varying NOT NULL,
    worker_node character varying(100),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    snapshot_sync_type character varying(20),
    snapshot_sync_mode character varying(20),
    snapshot_view_names text,
    window_start timestamp with time zone,
    window_end timestamp with time zone,
    read_rows bigint,
    write_rows bigint,
    failed_rows bigint,
    bytes_written bigint,
    speed_mb_s numeric(10,2),
    channel_count integer,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    duration_ms bigint,
    log_path text,
    error_msg text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    window_type character varying(20),
    executor_type character varying(30),
    engine_job_id character varying(100),
    window_start_id bigint,
    window_end_id bigint,
    reconcile_handled boolean DEFAULT false NOT NULL,
    reconcile_handled_at timestamp with time zone,
    reconcile_handled_by character varying(100),
    reconcile_note text,
    reconcile_last_probed_at timestamp with time zone,
    reconcile_last_probe_result text,
    engine_read_rows bigint,
    engine_write_rows bigint,
    source_rows_total bigint,
    valid_source_rows bigint,
    excluded_rows bigint,
    warning_rows bigint,
    medical_valid_source_query text
);


--
-- Name: TABLE task_execution; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.task_execution IS '任务执行历史（每次触发一条记录）';


--
-- Name: COLUMN task_execution.batch_no; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.batch_no IS '批次号，格式 yyyyMMdd_HHmmss，如 20260421_080000';


--
-- Name: COLUMN task_execution.triggered_by; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.triggered_by IS 'SCHEDULER=定时触发 | MANUAL=手动触发 | RECOLLECT_TRUNCATE / RECOLLECT_DROP_RECREATE=重采';


--
-- Name: COLUMN task_execution.worker_node; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.worker_node IS '执行节点，格式 hostname:port，Phase 12 多节点时填写';


--
-- Name: COLUMN task_execution.snapshot_view_names; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.snapshot_view_names IS '本次实际执行的视图列表快照，防止任务配置变更后历史记录失去语义';


--
-- Name: COLUMN task_execution.window_start; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.window_start IS '增量模式：本批次增量窗口起点（等于上次执行的 incremental_checkpoint）';


--
-- Name: COLUMN task_execution.window_end; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.window_end IS '增量模式：本批次增量窗口终点（执行触发时刻）';


--
-- Name: COLUMN task_execution.window_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.window_type IS '本批次执行窗口类型：FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT';


--
-- Name: COLUMN task_execution.window_start_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.window_start_id IS 'ID_RANGE 增量模式：本批次 ID 窗口起点（上次最大 ID）';


--
-- Name: COLUMN task_execution.window_end_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.window_end_id IS 'ID_RANGE 增量模式：本批次 ID 窗口终点（本次最大 ID）';


--
-- Name: COLUMN task_execution.reconcile_handled; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.reconcile_handled IS 'RECONCILE_REQUIRED 人工待办是否已处理；不代表执行成功，不推进 watermark';


--
-- Name: COLUMN task_execution.engine_read_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.engine_read_rows IS 'SeaTunnel vertex 累计读取尝试数，含内部重试，不等同业务 read_rows';


--
-- Name: COLUMN task_execution.engine_write_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.engine_write_rows IS 'SeaTunnel vertex 累计写入尝试数，含内部重试，不等同目标已提交 write_rows';


--
-- Name: COLUMN task_execution.source_rows_total; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.source_rows_total IS '医共体源窗口总行数：valid_source_rows + excluded_rows';


--
-- Name: COLUMN task_execution.valid_source_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.valid_source_rows IS '医共体分流后进入 SeaTunnel 的合规源行数';


--
-- Name: COLUMN task_execution.excluded_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.excluded_rows IS '医共体阻断剔除、未写入 Doris 的行数';


--
-- Name: COLUMN task_execution.warning_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.warning_rows IS '医共体告警但仍写入 Doris 的行数';


--
-- Name: COLUMN task_execution.medical_valid_source_query; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_execution.medical_valid_source_query IS '医共体执行期分流后 SeaTunnel 实际读取的合规源查询快照，供 Validation 对齐执行范围';


--
-- Name: task_execution_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.task_execution_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_execution_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.task_execution_id_seq OWNED BY df_etl.task_execution.id;


--
-- Name: task_snapshot_key; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.task_snapshot_key (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    execution_id bigint NOT NULL,
    key_value character varying(500) NOT NULL,
    captured_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE task_snapshot_key; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.task_snapshot_key IS 'spec 020：源端主键集合快照，用于跨次集合差集检测删除';


--
-- Name: task_snapshot_key_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.task_snapshot_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_snapshot_key_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.task_snapshot_key_id_seq OWNED BY df_etl.task_snapshot_key.id;


--
-- Name: task_validation_config; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.task_validation_config (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    method character varying(30),
    checksum_algo character varying(20) DEFAULT 'XXHASH64'::character varying,
    sample_rate numeric(5,2) DEFAULT 10.0,
    tolerance_rows bigint DEFAULT 0,
    tolerance_pct numeric(8,6) DEFAULT 0,
    auto_trigger boolean,
    block_on_fail boolean,
    target_tables text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    validation_template character varying(20) DEFAULT 'STANDARD'::character varying NOT NULL,
    failure_action character varying(20) DEFAULT 'WARN'::character varying NOT NULL,
    max_check_rows bigint DEFAULT 1000000 NOT NULL,
    drift_cron character varying(32),
    auto_repair boolean DEFAULT false NOT NULL,
    auto_repair_max_rows bigint DEFAULT 1000 NOT NULL,
    checksum_scope character varying(10) DEFAULT 'FULL'::character varying NOT NULL,
    validation_lookback_hours integer
);


--
-- Name: TABLE task_validation_config; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.task_validation_config IS '任务校验策略配置（每个同步任务一条）';


--
-- Name: COLUMN task_validation_config.method; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_validation_config.method IS 'ROW_COUNT | CHECKSUM | SAMPLE | ROW_COUNT_CHECKSUM';


--
-- Name: COLUMN task_validation_config.tolerance_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_validation_config.tolerance_rows IS '允许差异绝对行数（0=严格一致）';


--
-- Name: COLUMN task_validation_config.tolerance_pct; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_validation_config.tolerance_pct IS '允许差异百分比（0.01=1%）';


--
-- Name: COLUMN task_validation_config.auto_trigger; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_validation_config.auto_trigger IS '同步完成后自动触发校验';


--
-- Name: COLUMN task_validation_config.block_on_fail; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_validation_config.block_on_fail IS '校验失败阻断下次同步';


--
-- Name: task_validation_config_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.task_validation_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_validation_config_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.task_validation_config_id_seq OWNED BY df_etl.task_validation_config.id;


--
-- Name: task_view_config; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.task_view_config (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    view_name character varying(200) NOT NULL,
    field_mappings text,
    doris_ddl text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE task_view_config; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.task_view_config IS '同步任务的逐视图字段映射配置';


--
-- Name: COLUMN task_view_config.field_mappings; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_view_config.field_mappings IS '字段映射 JSON 数组，每元素格式：{"sourceField":"patient_id","sourceType":"INTEGER","targetField":"patient_id","targetType":"INT","checked":true,"isExtra":false,"defaultValue":null}';


--
-- Name: COLUMN task_view_config.doris_ddl; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.task_view_config.doris_ddl IS 'DBA 在 Doris 执行的建表语句（由 server 根据映射生成）';


--
-- Name: task_view_config_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.task_view_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_view_config_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.task_view_config_id_seq OWNED BY df_etl.task_view_config.id;


--
-- Name: validation_run; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.validation_run (
    id bigint NOT NULL,
    task_id bigint NOT NULL,
    legacy_exec_id bigint NOT NULL,
    mode character varying(32) NOT NULL,
    scope character varying(16) DEFAULT 'FULL'::character varying NOT NULL,
    window_start timestamp with time zone,
    window_end timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    source_sql text,
    target_sql text,
    source_where text,
    target_where text,
    trigger_type character varying(32) DEFAULT NULL::character varying,
    status character varying(20) DEFAULT 'PENDING'::character varying,
    method character varying(20),
    diff_rows bigint,
    duration_ms bigint,
    source_rows bigint,
    target_rows bigint,
    error_msg text,
    name character varying(100),
    execution_id bigint,
    tables_text text,
    last_run_at timestamp with time zone,
    window_type character varying(20),
    window_start_id bigint,
    window_end_id bigint,
    scope_warning text
);


--
-- Name: TABLE validation_run; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.validation_run IS 'spec 062：校验运行记录（run 级锚点，兼容 legacy exec_id）';


--
-- Name: COLUMN validation_run.trigger_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.trigger_type IS '校验触发来源（spec validation-workbench-redesign）：AUTO=同步后自动 / AUTO_COUNT=L1 行数哨兵 / MANUAL=用户主动 / DRIFT=定时漂移 / GATE=门控 / MANUAL_REPAIR_RECHECK=修复后异步复查；NULL=本需求落地前历史数据';


--
-- Name: COLUMN validation_run.status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.status IS '校验状态：PENDING/RUNNING/CONSISTENT/DIFF/ERROR';


--
-- Name: COLUMN validation_run.method; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.method IS '校验方式：ROW_COUNT/CHECKSUM/SAMPLE';


--
-- Name: COLUMN validation_run.diff_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.diff_rows IS '差异行数';


--
-- Name: COLUMN validation_run.duration_ms; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.duration_ms IS '校验耗时（毫秒）';


--
-- Name: COLUMN validation_run.source_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.source_rows IS '源端行数';


--
-- Name: COLUMN validation_run.target_rows; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.target_rows IS '目标端行数';


--
-- Name: COLUMN validation_run.error_msg; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.error_msg IS '错误信息（截断至 2000 字符）';


--
-- Name: COLUMN validation_run.name; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.name IS '校验任务名称';


--
-- Name: COLUMN validation_run.execution_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.execution_id IS '触发本次校验的执行批次 ID';


--
-- Name: COLUMN validation_run.tables_text; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.tables_text IS '校验表列表（逗号分隔）';


--
-- Name: COLUMN validation_run.last_run_at; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.last_run_at IS '最后执行时间';


--
-- Name: COLUMN validation_run.window_type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.window_type IS '窗口类型：FULL/INCREMENT/ID_RANGE/TIME_FIELD';


--
-- Name: COLUMN validation_run.window_start_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.window_start_id IS 'ID_RANGE 窗口起点';


--
-- Name: COLUMN validation_run.window_end_id; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.validation_run.window_end_id IS 'ID_RANGE 窗口终点';


--
-- Name: validation_run_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.validation_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: validation_run_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.validation_run_id_seq OWNED BY df_etl.validation_run.id;


--
-- Name: webhook_endpoint; Type: TABLE; Schema: df_etl; Owner: -
--

CREATE TABLE df_etl.webhook_endpoint (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL,
    url_enc text NOT NULL,
    last_tested_at timestamp with time zone,
    status character varying(20) DEFAULT 'UNTESTED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE webhook_endpoint; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON TABLE df_etl.webhook_endpoint IS '告警通知渠道（钉钉/企微 Webhook Robot）';


--
-- Name: COLUMN webhook_endpoint.type; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.webhook_endpoint.type IS 'DINGTALK=钉钉机器人 | WECOM=企业微信机器人';


--
-- Name: COLUMN webhook_endpoint.url_enc; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON COLUMN df_etl.webhook_endpoint.url_enc IS 'AES-256 加密存储，防止 Webhook token 明文泄露';


--
-- Name: webhook_endpoint_id_seq; Type: SEQUENCE; Schema: df_etl; Owner: -
--

CREATE SEQUENCE df_etl.webhook_endpoint_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: webhook_endpoint_id_seq; Type: SEQUENCE OWNED BY; Schema: df_etl; Owner: -
--

ALTER SEQUENCE df_etl.webhook_endpoint_id_seq OWNED BY df_etl.webhook_endpoint.id;


--
-- Name: alert_channel id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.alert_channel ALTER COLUMN id SET DEFAULT nextval('df_etl.alert_channel_id_seq'::regclass);


--
-- Name: alert_rule id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.alert_rule ALTER COLUMN id SET DEFAULT nextval('df_etl.alert_rule_id_seq'::regclass);


--
-- Name: app_user id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.app_user ALTER COLUMN id SET DEFAULT nextval('df_etl.app_user_id_seq'::regclass);


--
-- Name: audit_log id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.audit_log ALTER COLUMN id SET DEFAULT nextval('df_etl.audit_log_id_seq'::regclass);


--
-- Name: batch_task_template id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template ALTER COLUMN id SET DEFAULT nextval('df_etl.batch_task_template_id_seq'::regclass);


--
-- Name: batch_task_template_source id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template_source ALTER COLUMN id SET DEFAULT nextval('df_etl.batch_task_template_source_id_seq'::regclass);


--
-- Name: dfetl_dataset id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_dataset ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_dataset_id_seq'::regclass);


--
-- Name: dfetl_field id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_field ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_field_id_seq'::regclass);


--
-- Name: dfetl_message_policy id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_message_policy ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_message_policy_id_seq'::regclass);


--
-- Name: dfetl_precheck_export id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_export ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_precheck_export_id_seq'::regclass);


--
-- Name: dfetl_precheck_issue id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_issue ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_precheck_issue_id_seq'::regclass);


--
-- Name: dfetl_precheck_run id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_precheck_run_id_seq'::regclass);


--
-- Name: dfetl_sync_policy id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_sync_policy ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_sync_policy_id_seq'::regclass);


--
-- Name: dfetl_validation_policy id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_validation_policy ALTER COLUMN id SET DEFAULT nextval('df_etl.dfetl_validation_policy_id_seq'::regclass);


--
-- Name: dirty_record id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dirty_record ALTER COLUMN id SET DEFAULT nextval('df_etl.dirty_record_id_seq'::regclass);


--
-- Name: doris_type_mapping_rule id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.doris_type_mapping_rule ALTER COLUMN id SET DEFAULT nextval('df_etl.doris_type_mapping_rule_id_seq'::regclass);


--
-- Name: etl_verify_chunk id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_chunk ALTER COLUMN id SET DEFAULT nextval('df_etl.etl_verify_chunk_id_seq'::regclass);


--
-- Name: etl_verify_diff id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff ALTER COLUMN id SET DEFAULT nextval('df_etl.etl_verify_diff_id_seq'::regclass);


--
-- Name: etl_verify_diff_field id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff_field ALTER COLUMN id SET DEFAULT nextval('df_etl.etl_verify_diff_field_id_seq'::regclass);


--
-- Name: external_api_client id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_api_client ALTER COLUMN id SET DEFAULT nextval('df_etl.external_api_client_id_seq'::regclass);


--
-- Name: external_api_request_nonce id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_api_request_nonce ALTER COLUMN id SET DEFAULT nextval('df_etl.external_api_request_nonce_id_seq'::regclass);


--
-- Name: external_task_batch_operation_audit id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_batch_operation_audit ALTER COLUMN id SET DEFAULT nextval('df_etl.external_task_batch_operation_audit_id_seq'::regclass);


--
-- Name: external_task_batch_request id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_batch_request ALTER COLUMN id SET DEFAULT nextval('df_etl.external_task_batch_request_id_seq'::regclass);


--
-- Name: external_task_request id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_request ALTER COLUMN id SET DEFAULT nextval('df_etl.external_task_request_id_seq'::regclass);


--
-- Name: institution id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution ALTER COLUMN id SET DEFAULT nextval('df_etl.institution_id_seq'::regclass);


--
-- Name: institution_dataset_route id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route ALTER COLUMN id SET DEFAULT nextval('df_etl.institution_dataset_route_id_seq'::regclass);


--
-- Name: medical_dirty_field id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_field ALTER COLUMN id SET DEFAULT nextval('df_etl.medical_dirty_field_id_seq'::regclass);


--
-- Name: medical_dirty_row id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_row ALTER COLUMN id SET DEFAULT nextval('df_etl.medical_dirty_row_id_seq'::regclass);


--
-- Name: message_publish_config id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_publish_config ALTER COLUMN id SET DEFAULT nextval('df_etl.message_publish_config_id_seq'::regclass);


--
-- Name: message_publish_log id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_publish_log ALTER COLUMN id SET DEFAULT nextval('df_etl.message_publish_log_id_seq'::regclass);


--
-- Name: message_send_record id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_send_record ALTER COLUMN id SET DEFAULT nextval('df_etl.message_send_record_id_seq'::regclass);


--
-- Name: notify_record id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.notify_record ALTER COLUMN id SET DEFAULT nextval('df_etl.notify_record_id_seq'::regclass);


--
-- Name: snapshot_apply_history id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.snapshot_apply_history ALTER COLUMN id SET DEFAULT nextval('df_etl.snapshot_apply_history_id_seq'::regclass);


--
-- Name: source_datasource id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.source_datasource ALTER COLUMN id SET DEFAULT nextval('df_etl.source_datasource_id_seq'::regclass);


--
-- Name: sync_task id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.sync_task ALTER COLUMN id SET DEFAULT nextval('df_etl.sync_task_id_seq'::regclass);


--
-- Name: target_datasource id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.target_datasource ALTER COLUMN id SET DEFAULT nextval('df_etl.target_datasource_id_seq'::regclass);


--
-- Name: task_chunk id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_chunk ALTER COLUMN id SET DEFAULT nextval('df_etl.task_chunk_id_seq'::regclass);


--
-- Name: task_execution id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_execution ALTER COLUMN id SET DEFAULT nextval('df_etl.task_execution_id_seq'::regclass);


--
-- Name: task_snapshot_key id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_snapshot_key ALTER COLUMN id SET DEFAULT nextval('df_etl.task_snapshot_key_id_seq'::regclass);


--
-- Name: task_validation_config id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_validation_config ALTER COLUMN id SET DEFAULT nextval('df_etl.task_validation_config_id_seq'::regclass);


--
-- Name: task_view_config id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_view_config ALTER COLUMN id SET DEFAULT nextval('df_etl.task_view_config_id_seq'::regclass);


--
-- Name: validation_run id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.validation_run ALTER COLUMN id SET DEFAULT nextval('df_etl.validation_run_id_seq'::regclass);


--
-- Name: webhook_endpoint id; Type: DEFAULT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.webhook_endpoint ALTER COLUMN id SET DEFAULT nextval('df_etl.webhook_endpoint_id_seq'::regclass);


--
-- Name: alert_channel alert_channel_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.alert_channel
    ADD CONSTRAINT alert_channel_pkey PRIMARY KEY (id);


--
-- Name: alert_rule alert_rule_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_username_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.app_user
    ADD CONSTRAINT app_user_username_key UNIQUE (username);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: batch_task_template batch_task_template_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template
    ADD CONSTRAINT batch_task_template_pkey PRIMARY KEY (id);


--
-- Name: batch_task_template_source batch_task_template_source_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template_source
    ADD CONSTRAINT batch_task_template_source_pkey PRIMARY KEY (id);


--
-- Name: dfetl_dataset dfetl_dataset_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_dataset
    ADD CONSTRAINT dfetl_dataset_pkey PRIMARY KEY (id);


--
-- Name: dfetl_field dfetl_field_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_field
    ADD CONSTRAINT dfetl_field_pkey PRIMARY KEY (id);


--
-- Name: dfetl_message_policy dfetl_message_policy_dataset_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_message_policy
    ADD CONSTRAINT dfetl_message_policy_dataset_id_key UNIQUE (dataset_id);


--
-- Name: dfetl_message_policy dfetl_message_policy_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_message_policy
    ADD CONSTRAINT dfetl_message_policy_pkey PRIMARY KEY (id);


--
-- Name: dfetl_precheck_export dfetl_precheck_export_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_export
    ADD CONSTRAINT dfetl_precheck_export_pkey PRIMARY KEY (id);


--
-- Name: dfetl_precheck_issue dfetl_precheck_issue_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_issue
    ADD CONSTRAINT dfetl_precheck_issue_pkey PRIMARY KEY (id);


--
-- Name: dfetl_precheck_run dfetl_precheck_run_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_pkey PRIMARY KEY (id);


--
-- Name: dfetl_sync_policy dfetl_sync_policy_dataset_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_sync_policy
    ADD CONSTRAINT dfetl_sync_policy_dataset_id_key UNIQUE (dataset_id);


--
-- Name: dfetl_sync_policy dfetl_sync_policy_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_sync_policy
    ADD CONSTRAINT dfetl_sync_policy_pkey PRIMARY KEY (id);


--
-- Name: dfetl_validation_policy dfetl_validation_policy_dataset_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_validation_policy
    ADD CONSTRAINT dfetl_validation_policy_dataset_id_key UNIQUE (dataset_id);


--
-- Name: dfetl_validation_policy dfetl_validation_policy_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_validation_policy
    ADD CONSTRAINT dfetl_validation_policy_pkey PRIMARY KEY (id);


--
-- Name: dirty_record dirty_record_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dirty_record
    ADD CONSTRAINT dirty_record_pkey PRIMARY KEY (id);


--
-- Name: doris_type_mapping_rule doris_type_mapping_rule_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.doris_type_mapping_rule
    ADD CONSTRAINT doris_type_mapping_rule_pkey PRIMARY KEY (id);


--
-- Name: etl_verify_chunk etl_verify_chunk_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_chunk
    ADD CONSTRAINT etl_verify_chunk_pkey PRIMARY KEY (id);


--
-- Name: etl_verify_diff_field etl_verify_diff_field_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff_field
    ADD CONSTRAINT etl_verify_diff_field_pkey PRIMARY KEY (id);


--
-- Name: etl_verify_diff etl_verify_diff_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff
    ADD CONSTRAINT etl_verify_diff_pkey PRIMARY KEY (id);


--
-- Name: external_api_client external_api_client_client_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_api_client
    ADD CONSTRAINT external_api_client_client_id_key UNIQUE (client_id);


--
-- Name: external_api_client external_api_client_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_api_client
    ADD CONSTRAINT external_api_client_pkey PRIMARY KEY (id);


--
-- Name: external_api_request_nonce external_api_request_nonce_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_api_request_nonce
    ADD CONSTRAINT external_api_request_nonce_pkey PRIMARY KEY (id);


--
-- Name: external_task_batch_operation_audit external_task_batch_operation_audit_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_batch_operation_audit
    ADD CONSTRAINT external_task_batch_operation_audit_pkey PRIMARY KEY (id);


--
-- Name: external_task_batch_request external_task_batch_request_external_batch_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_batch_request
    ADD CONSTRAINT external_task_batch_request_external_batch_id_key UNIQUE (external_batch_id);


--
-- Name: external_task_batch_request external_task_batch_request_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_batch_request
    ADD CONSTRAINT external_task_batch_request_pkey PRIMARY KEY (id);


--
-- Name: external_task_request external_task_request_external_request_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_request
    ADD CONSTRAINT external_task_request_external_request_id_key UNIQUE (external_request_id);


--
-- Name: external_task_request external_task_request_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_request
    ADD CONSTRAINT external_task_request_pkey PRIMARY KEY (id);


--
-- Name: institution institution_code_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution
    ADD CONSTRAINT institution_code_key UNIQUE (code);


--
-- Name: institution_dataset_route institution_dataset_route_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route
    ADD CONSTRAINT institution_dataset_route_pkey PRIMARY KEY (id);


--
-- Name: institution institution_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution
    ADD CONSTRAINT institution_pkey PRIMARY KEY (id);


--
-- Name: medical_dirty_field medical_dirty_field_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_field
    ADD CONSTRAINT medical_dirty_field_pkey PRIMARY KEY (id);


--
-- Name: medical_dirty_row medical_dirty_row_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_row
    ADD CONSTRAINT medical_dirty_row_pkey PRIMARY KEY (id);


--
-- Name: message_publish_config message_publish_config_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_publish_config
    ADD CONSTRAINT message_publish_config_pkey PRIMARY KEY (id);


--
-- Name: message_publish_config message_publish_config_task_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_publish_config
    ADD CONSTRAINT message_publish_config_task_id_key UNIQUE (task_id);


--
-- Name: message_publish_log message_publish_log_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_publish_log
    ADD CONSTRAINT message_publish_log_pkey PRIMARY KEY (id);


--
-- Name: message_send_record message_send_record_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.message_send_record
    ADD CONSTRAINT message_send_record_pkey PRIMARY KEY (id);


--
-- Name: notify_record notify_record_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.notify_record
    ADD CONSTRAINT notify_record_pkey PRIMARY KEY (id);


--
-- Name: qrtz_blob_triggers qrtz_blob_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_calendars qrtz_calendars_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_calendars
    ADD CONSTRAINT qrtz_calendars_pkey PRIMARY KEY (sched_name, calendar_name);


--
-- Name: qrtz_cron_triggers qrtz_cron_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_fired_triggers qrtz_fired_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_fired_triggers
    ADD CONSTRAINT qrtz_fired_triggers_pkey PRIMARY KEY (sched_name, entry_id);


--
-- Name: qrtz_job_details qrtz_job_details_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_job_details
    ADD CONSTRAINT qrtz_job_details_pkey PRIMARY KEY (sched_name, job_name, job_group);


--
-- Name: qrtz_locks qrtz_locks_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_locks
    ADD CONSTRAINT qrtz_locks_pkey PRIMARY KEY (sched_name, lock_name);


--
-- Name: qrtz_paused_trigger_grps qrtz_paused_trigger_grps_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_paused_trigger_grps
    ADD CONSTRAINT qrtz_paused_trigger_grps_pkey PRIMARY KEY (sched_name, trigger_group);


--
-- Name: qrtz_scheduler_state qrtz_scheduler_state_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_scheduler_state
    ADD CONSTRAINT qrtz_scheduler_state_pkey PRIMARY KEY (sched_name, instance_name);


--
-- Name: qrtz_simple_triggers qrtz_simple_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers qrtz_simprop_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers qrtz_triggers_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: snapshot_apply_history snapshot_apply_history_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.snapshot_apply_history
    ADD CONSTRAINT snapshot_apply_history_pkey PRIMARY KEY (id);


--
-- Name: source_datasource source_datasource_name_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.source_datasource
    ADD CONSTRAINT source_datasource_name_key UNIQUE (name);


--
-- Name: source_datasource source_datasource_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.source_datasource
    ADD CONSTRAINT source_datasource_pkey PRIMARY KEY (id);


--
-- Name: sync_task sync_task_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.sync_task
    ADD CONSTRAINT sync_task_pkey PRIMARY KEY (id);


--
-- Name: system_setting system_setting_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.system_setting
    ADD CONSTRAINT system_setting_pkey PRIMARY KEY (setting_key);


--
-- Name: target_datasource target_datasource_name_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.target_datasource
    ADD CONSTRAINT target_datasource_name_key UNIQUE (name);


--
-- Name: target_datasource target_datasource_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.target_datasource
    ADD CONSTRAINT target_datasource_pkey PRIMARY KEY (id);


--
-- Name: task_chunk task_chunk_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_chunk
    ADD CONSTRAINT task_chunk_pkey PRIMARY KEY (id);


--
-- Name: task_execution task_execution_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_execution
    ADD CONSTRAINT task_execution_pkey PRIMARY KEY (id);


--
-- Name: task_snapshot_key task_snapshot_key_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_snapshot_key
    ADD CONSTRAINT task_snapshot_key_pkey PRIMARY KEY (id);


--
-- Name: task_validation_config task_validation_config_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_validation_config
    ADD CONSTRAINT task_validation_config_pkey PRIMARY KEY (id);


--
-- Name: task_validation_config task_validation_config_task_id_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_validation_config
    ADD CONSTRAINT task_validation_config_task_id_key UNIQUE (task_id);


--
-- Name: task_view_config task_view_config_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_view_config
    ADD CONSTRAINT task_view_config_pkey PRIMARY KEY (id);


--
-- Name: task_view_config task_view_config_task_id_view_name_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_view_config
    ADD CONSTRAINT task_view_config_task_id_view_name_key UNIQUE (task_id, view_name);


--
-- Name: dfetl_dataset uk_dfetl_dataset_medical_id; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_dataset
    ADD CONSTRAINT uk_dfetl_dataset_medical_id UNIQUE (medical_dataset_id);


--
-- Name: dfetl_field uk_dfetl_field_medical_id; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_field
    ADD CONSTRAINT uk_dfetl_field_medical_id UNIQUE (dataset_id, medical_field_id);


--
-- Name: dfetl_precheck_issue uk_dfetl_precheck_issue_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_issue
    ADD CONSTRAINT uk_dfetl_precheck_issue_key UNIQUE (run_id, issue_key);


--
-- Name: doris_type_mapping_rule uk_doris_type_mapping_rule; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.doris_type_mapping_rule
    ADD CONSTRAINT uk_doris_type_mapping_rule UNIQUE (profile_name, source_dialect, source_type_pattern);


--
-- Name: medical_dirty_row uk_medical_dirty_row_execution_dataset_hash; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_row
    ADD CONSTRAINT uk_medical_dirty_row_execution_dataset_hash UNIQUE (execution_id, dataset_code, source_row_hash);


--
-- Name: validation_run uk_validation_run_task_exec; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.validation_run
    ADD CONSTRAINT uk_validation_run_task_exec UNIQUE (task_id, legacy_exec_id);


--
-- Name: etl_verify_chunk uk_verify_chunk_exec_no; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_chunk
    ADD CONSTRAINT uk_verify_chunk_exec_no UNIQUE (exec_id, chunk_no);


--
-- Name: validation_run validation_run_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.validation_run
    ADD CONSTRAINT validation_run_pkey PRIMARY KEY (id);


--
-- Name: webhook_endpoint webhook_endpoint_name_key; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.webhook_endpoint
    ADD CONSTRAINT webhook_endpoint_name_key UNIQUE (name);


--
-- Name: webhook_endpoint webhook_endpoint_pkey; Type: CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.webhook_endpoint
    ADD CONSTRAINT webhook_endpoint_pkey PRIMARY KEY (id);


--
-- Name: idx_audit_log_target; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_audit_log_target ON df_etl.audit_log USING btree (target_type, target_id);


--
-- Name: idx_audit_log_time; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_audit_log_time ON df_etl.audit_log USING btree (action_time DESC);


--
-- Name: idx_audit_log_user; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_audit_log_user ON df_etl.audit_log USING btree (user_name);


--
-- Name: idx_batch_tpl_source_sync_task_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_batch_tpl_source_sync_task_id ON df_etl.batch_task_template_source USING btree (sync_task_id) WHERE (sync_task_id IS NOT NULL);


--
-- Name: idx_batch_tpl_source_template_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_batch_tpl_source_template_id ON df_etl.batch_task_template_source USING btree (template_id);


--
-- Name: idx_btts_institution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_btts_institution ON df_etl.batch_task_template_source USING btree (institution_id) WHERE (institution_id IS NOT NULL);


--
-- Name: idx_dfetl_dataset_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_dataset_status ON df_etl.dfetl_dataset USING btree (dataset_status, dataset_code);


--
-- Name: idx_dfetl_field_dataset; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_field_dataset ON df_etl.dfetl_field USING btree (dataset_id, field_status, field_order);


--
-- Name: idx_dfetl_precheck_export_expiry; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_export_expiry ON df_etl.dfetl_precheck_export USING btree (status, expires_at) WHERE (expires_at IS NOT NULL);


--
-- Name: idx_dfetl_precheck_export_run; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_export_run ON df_etl.dfetl_precheck_export USING btree (run_id, created_at DESC);


--
-- Name: idx_dfetl_precheck_export_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_export_status ON df_etl.dfetl_precheck_export USING btree (status, created_at DESC);


--
-- Name: idx_dfetl_precheck_issue_row; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_issue_row ON df_etl.dfetl_precheck_issue USING btree (run_id, source_row_hash);


--
-- Name: idx_dfetl_precheck_issue_run; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_issue_run ON df_etl.dfetl_precheck_issue USING btree (run_id, severity, remediation_status, id);


--
-- Name: idx_dfetl_precheck_run_execution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_run_execution ON df_etl.dfetl_precheck_run USING btree (execution_id) WHERE (execution_id IS NOT NULL);


--
-- Name: idx_dfetl_precheck_run_raw_cleanup; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_run_raw_cleanup ON df_etl.dfetl_precheck_run USING btree (status, finished_at) WHERE ((raw_cleaned_at IS NULL) AND ((status)::text = ANY ((ARRAY['PASSED'::character varying, 'HAS_ERRORS'::character varying])::text[])));


--
-- Name: idx_dfetl_precheck_run_route; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_run_route ON df_etl.dfetl_precheck_run USING btree (route_id, created_at DESC);


--
-- Name: idx_dfetl_precheck_run_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dfetl_precheck_run_status ON df_etl.dfetl_precheck_run USING btree (status, created_at DESC);


--
-- Name: idx_dirty_record_execution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dirty_record_execution ON df_etl.dirty_record USING btree (execution_id);


--
-- Name: idx_dirty_record_found_at; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dirty_record_found_at ON df_etl.dirty_record USING btree (found_at DESC);


--
-- Name: idx_dirty_record_handled; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dirty_record_handled ON df_etl.dirty_record USING btree (handled) WHERE (NOT handled);


--
-- Name: idx_dirty_record_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_dirty_record_task ON df_etl.dirty_record USING btree (task_id);


--
-- Name: idx_doris_type_mapping_rule_enabled; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_doris_type_mapping_rule_enabled ON df_etl.doris_type_mapping_rule USING btree (enabled, source_dialect, priority DESC);


--
-- Name: idx_external_api_request_nonce_created; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_api_request_nonce_created ON df_etl.external_api_request_nonce USING btree (created_at);


--
-- Name: idx_external_task_batch_operation_batch; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_batch_operation_batch ON df_etl.external_task_batch_operation_audit USING btree (external_batch_id, created_at DESC);


--
-- Name: idx_external_task_batch_operation_client; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_batch_operation_client ON df_etl.external_task_batch_operation_audit USING btree (client_id, created_at DESC) WHERE (client_id IS NOT NULL);


--
-- Name: idx_external_task_batch_org; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_batch_org ON df_etl.external_task_batch_request USING btree (yi_liao_jg_dm, status);


--
-- Name: idx_external_task_request_batch; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_request_batch ON df_etl.external_task_request USING btree (external_batch_id) WHERE (external_batch_id IS NOT NULL);


--
-- Name: idx_external_task_request_org; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_request_org ON df_etl.external_task_request USING btree (yi_liao_jg_dm, source_object);


--
-- Name: idx_external_task_request_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_external_task_request_task ON df_etl.external_task_request USING btree (task_id) WHERE (task_id IS NOT NULL);


--
-- Name: idx_institution_dataset_route_dataset; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_institution_dataset_route_dataset ON df_etl.institution_dataset_route USING btree (dataset_id);


--
-- Name: idx_institution_dataset_route_source; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_institution_dataset_route_source ON df_etl.institution_dataset_route USING btree (source_datasource_id, source_schema, source_object);


--
-- Name: idx_institution_dataset_route_target; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_institution_dataset_route_target ON df_etl.institution_dataset_route USING btree (target_datasource_id, target_table);


--
-- Name: idx_institution_enabled; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_institution_enabled ON df_etl.institution USING btree (enabled) WHERE (enabled = true);


--
-- Name: idx_institution_parent; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_institution_parent ON df_etl.institution USING btree (parent_id) WHERE (parent_id IS NOT NULL);


--
-- Name: idx_medical_dirty_field_error_type; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_field_error_type ON df_etl.medical_dirty_field USING btree (error_type);


--
-- Name: idx_medical_dirty_field_row; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_field_row ON df_etl.medical_dirty_field USING btree (dirty_row_id);


--
-- Name: idx_medical_dirty_field_value_domain; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_field_value_domain ON df_etl.medical_dirty_field USING btree (value_domain_code);


--
-- Name: idx_medical_dirty_row_dataset_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_row_dataset_status ON df_etl.medical_dirty_row USING btree (dataset_code, status);


--
-- Name: idx_medical_dirty_row_owner_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_row_owner_status ON df_etl.medical_dirty_row USING btree (owner_name, status);


--
-- Name: idx_medical_dirty_row_severity_found; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_row_severity_found ON df_etl.medical_dirty_row USING btree (severity, found_at DESC);


--
-- Name: idx_medical_dirty_row_task_execution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_medical_dirty_row_task_execution ON df_etl.medical_dirty_row USING btree (task_id, execution_id);


--
-- Name: idx_mpc_task_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpc_task_id ON df_etl.message_publish_config USING btree (task_id);


--
-- Name: idx_mpl_batch_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpl_batch_id ON df_etl.message_publish_log USING btree (batch_id);


--
-- Name: idx_mpl_publish_time; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpl_publish_time ON df_etl.message_publish_log USING btree (publish_time);


--
-- Name: idx_mpl_recovery_scan; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpl_recovery_scan ON df_etl.message_publish_log USING btree (next_retry_time, publish_time, id) WHERE (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'WAIT_RETRY'::character varying])::text[])) AND (batch_id > 0));


--
-- Name: idx_mpl_task_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpl_task_id ON df_etl.message_publish_log USING btree (task_id);


--
-- Name: idx_mpl_task_status_batch; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_mpl_task_status_batch ON df_etl.message_publish_log USING btree (task_id, status, batch_id);


--
-- Name: idx_msr_external_record_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_external_record_status ON df_etl.message_send_record USING btree (external_record_status);


--
-- Name: idx_msr_message_key; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_message_key ON df_etl.message_send_record USING btree (message_key);


--
-- Name: idx_msr_publish_log_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_publish_log_status ON df_etl.message_send_record USING btree (publish_log_id, send_status);


--
-- Name: idx_msr_recovery_retry; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_recovery_retry ON df_etl.message_send_record USING btree (next_retry_time, id) WHERE (((channel_mode)::text = 'RABBITMQ'::text) AND ((send_status)::text = ANY ((ARRAY['SEND_FAILED'::character varying, 'WAIT_RETRY'::character varying])::text[])));


--
-- Name: idx_msr_recovery_sending; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_recovery_sending ON df_etl.message_send_record USING btree (send_start_time, id) WHERE (((channel_mode)::text = 'RABBITMQ'::text) AND ((send_status)::text = 'SENDING'::text));


--
-- Name: idx_msr_route_key; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_route_key ON df_etl.message_send_record USING btree (route_key);


--
-- Name: idx_msr_send_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_send_status ON df_etl.message_send_record USING btree (send_status);


--
-- Name: idx_msr_sent_time; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_sent_time ON df_etl.message_send_record USING btree (sent_time);


--
-- Name: idx_msr_task_batch; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_task_batch ON df_etl.message_send_record USING btree (task_id, batch_id);


--
-- Name: idx_msr_topic; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_msr_topic ON df_etl.message_send_record USING btree (topic);


--
-- Name: idx_notify_record_rule; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_notify_record_rule ON df_etl.notify_record USING btree (rule_id);


--
-- Name: idx_notify_record_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_notify_record_task ON df_etl.notify_record USING btree (task_id);


--
-- Name: idx_notify_record_triggered; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_notify_record_triggered ON df_etl.notify_record USING btree (triggered_at DESC);


--
-- Name: idx_qrtz_ft_inst_job_req_rcvry; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_inst_job_req_rcvry ON df_etl.qrtz_fired_triggers USING btree (sched_name, instance_name, requests_recovery);


--
-- Name: idx_qrtz_ft_j_g; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_j_g ON df_etl.qrtz_fired_triggers USING btree (sched_name, job_name, job_group);


--
-- Name: idx_qrtz_ft_jg; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_jg ON df_etl.qrtz_fired_triggers USING btree (sched_name, job_group);


--
-- Name: idx_qrtz_ft_t_g; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_t_g ON df_etl.qrtz_fired_triggers USING btree (sched_name, trigger_name, trigger_group);


--
-- Name: idx_qrtz_ft_tg; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_tg ON df_etl.qrtz_fired_triggers USING btree (sched_name, trigger_group);


--
-- Name: idx_qrtz_ft_trig_inst_name; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_ft_trig_inst_name ON df_etl.qrtz_fired_triggers USING btree (sched_name, instance_name);


--
-- Name: idx_qrtz_j_grp; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_j_grp ON df_etl.qrtz_job_details USING btree (sched_name, job_group);


--
-- Name: idx_qrtz_j_req_recovery; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_j_req_recovery ON df_etl.qrtz_job_details USING btree (sched_name, requests_recovery);


--
-- Name: idx_qrtz_t_c; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_c ON df_etl.qrtz_triggers USING btree (sched_name, calendar_name);


--
-- Name: idx_qrtz_t_g; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_g ON df_etl.qrtz_triggers USING btree (sched_name, trigger_group);


--
-- Name: idx_qrtz_t_j; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_j ON df_etl.qrtz_triggers USING btree (sched_name, job_name, job_group);


--
-- Name: idx_qrtz_t_jg; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_jg ON df_etl.qrtz_triggers USING btree (sched_name, job_group);


--
-- Name: idx_qrtz_t_n_g_state; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_n_g_state ON df_etl.qrtz_triggers USING btree (sched_name, trigger_group, trigger_state);


--
-- Name: idx_qrtz_t_n_state; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_n_state ON df_etl.qrtz_triggers USING btree (sched_name, trigger_name, trigger_group, trigger_state);


--
-- Name: idx_qrtz_t_next_fire_time; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_next_fire_time ON df_etl.qrtz_triggers USING btree (sched_name, next_fire_time);


--
-- Name: idx_qrtz_t_nft_misfire; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_nft_misfire ON df_etl.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time);


--
-- Name: idx_qrtz_t_nft_st; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_nft_st ON df_etl.qrtz_triggers USING btree (sched_name, trigger_state, next_fire_time);


--
-- Name: idx_qrtz_t_nft_st_misfire; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_nft_st_misfire ON df_etl.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time, trigger_state);


--
-- Name: idx_qrtz_t_nft_st_misfire_grp; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_nft_st_misfire_grp ON df_etl.qrtz_triggers USING btree (sched_name, misfire_instr, next_fire_time, trigger_group, trigger_state);


--
-- Name: idx_qrtz_t_state; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_qrtz_t_state ON df_etl.qrtz_triggers USING btree (sched_name, trigger_state);


--
-- Name: idx_sah_task_created; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sah_task_created ON df_etl.snapshot_apply_history USING btree (task_id, created_at);


--
-- Name: idx_source_datasource_code; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX idx_source_datasource_code ON df_etl.source_datasource USING btree (source_code) WHERE (source_code IS NOT NULL);


--
-- Name: idx_source_ds_institution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_source_ds_institution ON df_etl.source_datasource USING btree (institution_id) WHERE (institution_id IS NOT NULL);


--
-- Name: idx_sync_task_inst_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sync_task_inst_status ON df_etl.sync_task USING btree (institution_id, status) WHERE (institution_id IS NOT NULL);


--
-- Name: INDEX idx_sync_task_inst_status; Type: COMMENT; Schema: df_etl; Owner: -
--

COMMENT ON INDEX df_etl.idx_sync_task_inst_status IS '机构维度任务过滤/状态聚合：InstitutionQueryService.listTasksByInstitution、statusSummary';


--
-- Name: idx_sync_task_institution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sync_task_institution ON df_etl.sync_task USING btree (institution_id) WHERE (institution_id IS NOT NULL);


--
-- Name: idx_sync_task_source; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sync_task_source ON df_etl.sync_task USING btree (source_datasource_id);


--
-- Name: idx_sync_task_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sync_task_status ON df_etl.sync_task USING btree (status);


--
-- Name: idx_sync_task_target; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_sync_task_target ON df_etl.sync_task USING btree (target_datasource_id);


--
-- Name: idx_task_chunk_execution; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_chunk_execution ON df_etl.task_chunk USING btree (execution_id);


--
-- Name: idx_task_chunk_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_chunk_status ON df_etl.task_chunk USING btree (status);


--
-- Name: idx_task_execution_batchno; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_execution_batchno ON df_etl.task_execution USING btree (batch_no);


--
-- Name: idx_task_execution_reconcile; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_execution_reconcile ON df_etl.task_execution USING btree (status, reconcile_handled);


--
-- Name: idx_task_execution_started; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_execution_started ON df_etl.task_execution USING btree (started_at DESC);


--
-- Name: idx_task_execution_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_execution_status ON df_etl.task_execution USING btree (status);


--
-- Name: idx_task_execution_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_task_execution_task ON df_etl.task_execution USING btree (task_id);


--
-- Name: idx_tsk_task_exec; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_tsk_task_exec ON df_etl.task_snapshot_key USING btree (task_id, execution_id);


--
-- Name: idx_tsk_task_key; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_tsk_task_key ON df_etl.task_snapshot_key USING btree (task_id, key_value);


--
-- Name: idx_validation_run_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_validation_run_status ON df_etl.validation_run USING btree (status);


--
-- Name: idx_validation_run_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_validation_run_task ON df_etl.validation_run USING btree (task_id);


--
-- Name: idx_validation_run_task_status; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_validation_run_task_status ON df_etl.validation_run USING btree (task_id, status);


--
-- Name: idx_validation_run_trigger_created; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_validation_run_trigger_created ON df_etl.validation_run USING btree (trigger_type, created_at);


--
-- Name: idx_verify_chunk_run; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_chunk_run ON df_etl.etl_verify_chunk USING btree (validation_run_id);


--
-- Name: idx_verify_chunk_task; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_chunk_task ON df_etl.etl_verify_chunk USING btree (task_id, exec_id);


--
-- Name: idx_verify_diff_field_diff; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_diff_field_diff ON df_etl.etl_verify_diff_field USING btree (diff_id);


--
-- Name: idx_verify_diff_field_run; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_diff_field_run ON df_etl.etl_verify_diff_field USING btree (validation_run_id);


--
-- Name: idx_verify_diff_field_task_exec; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_diff_field_task_exec ON df_etl.etl_verify_diff_field USING btree (task_id, exec_id);


--
-- Name: idx_verify_diff_run; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_diff_run ON df_etl.etl_verify_diff USING btree (validation_run_id);


--
-- Name: idx_verify_diff_task_exec; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE INDEX idx_verify_diff_task_exec ON df_etl.etl_verify_diff USING btree (task_id, exec_id);


--
-- Name: uk_dfetl_dataset_code_ci; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_dfetl_dataset_code_ci ON df_etl.dfetl_dataset USING btree (lower((dataset_code)::text));


--
-- Name: uk_dfetl_field_active_code_ci; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_dfetl_field_active_code_ci ON df_etl.dfetl_field USING btree (dataset_id, lower((field_code)::text)) WHERE ((field_status)::text = 'ACTIVE'::text);


--
-- Name: uk_dfetl_precheck_export_request; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_dfetl_precheck_export_request ON df_etl.dfetl_precheck_export USING btree (request_key);


--
-- Name: uk_dfetl_precheck_run_active; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_dfetl_precheck_run_active ON df_etl.dfetl_precheck_run USING btree (route_id, contract_hash, route_revision) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'LOADING'::character varying, 'VALIDATING'::character varying])::text[]));


--
-- Name: uk_external_api_request_nonce; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_external_api_request_nonce ON df_etl.external_api_request_nonce USING btree (client_id, nonce);


--
-- Name: uk_external_task_request_batch_item; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_external_task_request_batch_item ON df_etl.external_task_request USING btree (external_batch_id, batch_item_key) WHERE ((external_batch_id IS NOT NULL) AND (batch_item_key IS NOT NULL));


--
-- Name: uk_institution_dataset_route_active; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_institution_dataset_route_active ON df_etl.institution_dataset_route USING btree (institution_id, dataset_id) WHERE (enabled = true);


--
-- Name: uk_message_send_record_message_id; Type: INDEX; Schema: df_etl; Owner: -
--

CREATE UNIQUE INDEX uk_message_send_record_message_id ON df_etl.message_send_record USING btree (message_id);


--
-- Name: batch_task_template_source batch_task_template_source_institution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template_source
    ADD CONSTRAINT batch_task_template_source_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES df_etl.institution(id);


--
-- Name: batch_task_template_source batch_task_template_source_template_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.batch_task_template_source
    ADD CONSTRAINT batch_task_template_source_template_id_fkey FOREIGN KEY (template_id) REFERENCES df_etl.batch_task_template(id);


--
-- Name: dfetl_field dfetl_field_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_field
    ADD CONSTRAINT dfetl_field_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id) ON DELETE CASCADE;


--
-- Name: dfetl_message_policy dfetl_message_policy_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_message_policy
    ADD CONSTRAINT dfetl_message_policy_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id) ON DELETE CASCADE;


--
-- Name: dfetl_precheck_export dfetl_precheck_export_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_export
    ADD CONSTRAINT dfetl_precheck_export_run_id_fkey FOREIGN KEY (run_id) REFERENCES df_etl.dfetl_precheck_run(id) ON DELETE CASCADE;


--
-- Name: dfetl_precheck_issue dfetl_precheck_issue_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_issue
    ADD CONSTRAINT dfetl_precheck_issue_run_id_fkey FOREIGN KEY (run_id) REFERENCES df_etl.dfetl_precheck_run(id) ON DELETE CASCADE;


--
-- Name: dfetl_precheck_run dfetl_precheck_run_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id);


--
-- Name: dfetl_precheck_run dfetl_precheck_run_execution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES df_etl.task_execution(id) ON DELETE SET NULL;


--
-- Name: dfetl_precheck_run dfetl_precheck_run_institution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES df_etl.institution(id);


--
-- Name: dfetl_precheck_run dfetl_precheck_run_retry_of_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_retry_of_run_id_fkey FOREIGN KEY (retry_of_run_id) REFERENCES df_etl.dfetl_precheck_run(id) ON DELETE SET NULL;


--
-- Name: dfetl_precheck_run dfetl_precheck_run_route_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_route_id_fkey FOREIGN KEY (route_id) REFERENCES df_etl.institution_dataset_route(id) ON DELETE CASCADE;


--
-- Name: dfetl_precheck_run dfetl_precheck_run_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_precheck_run
    ADD CONSTRAINT dfetl_precheck_run_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id) ON DELETE SET NULL;


--
-- Name: dfetl_sync_policy dfetl_sync_policy_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_sync_policy
    ADD CONSTRAINT dfetl_sync_policy_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id) ON DELETE CASCADE;


--
-- Name: dfetl_validation_policy dfetl_validation_policy_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dfetl_validation_policy
    ADD CONSTRAINT dfetl_validation_policy_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id) ON DELETE CASCADE;


--
-- Name: dirty_record dirty_record_chunk_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dirty_record
    ADD CONSTRAINT dirty_record_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES df_etl.task_chunk(id);


--
-- Name: dirty_record dirty_record_execution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dirty_record
    ADD CONSTRAINT dirty_record_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES df_etl.task_execution(id);


--
-- Name: dirty_record dirty_record_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.dirty_record
    ADD CONSTRAINT dirty_record_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id);


--
-- Name: etl_verify_chunk etl_verify_chunk_validation_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_chunk
    ADD CONSTRAINT etl_verify_chunk_validation_run_id_fkey FOREIGN KEY (validation_run_id) REFERENCES df_etl.validation_run(id) ON DELETE SET NULL;


--
-- Name: etl_verify_diff_field etl_verify_diff_field_diff_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff_field
    ADD CONSTRAINT etl_verify_diff_field_diff_id_fkey FOREIGN KEY (diff_id) REFERENCES df_etl.etl_verify_diff(id) ON DELETE CASCADE;


--
-- Name: etl_verify_diff_field etl_verify_diff_field_validation_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff_field
    ADD CONSTRAINT etl_verify_diff_field_validation_run_id_fkey FOREIGN KEY (validation_run_id) REFERENCES df_etl.validation_run(id) ON DELETE SET NULL;


--
-- Name: etl_verify_diff etl_verify_diff_validation_run_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.etl_verify_diff
    ADD CONSTRAINT etl_verify_diff_validation_run_id_fkey FOREIGN KEY (validation_run_id) REFERENCES df_etl.validation_run(id) ON DELETE SET NULL;


--
-- Name: external_task_request external_task_request_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.external_task_request
    ADD CONSTRAINT external_task_request_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id);


--
-- Name: institution_dataset_route institution_dataset_route_dataset_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route
    ADD CONSTRAINT institution_dataset_route_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES df_etl.dfetl_dataset(id);


--
-- Name: institution_dataset_route institution_dataset_route_institution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route
    ADD CONSTRAINT institution_dataset_route_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES df_etl.institution(id);


--
-- Name: institution_dataset_route institution_dataset_route_source_datasource_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route
    ADD CONSTRAINT institution_dataset_route_source_datasource_id_fkey FOREIGN KEY (source_datasource_id) REFERENCES df_etl.source_datasource(id);


--
-- Name: institution_dataset_route institution_dataset_route_target_datasource_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution_dataset_route
    ADD CONSTRAINT institution_dataset_route_target_datasource_id_fkey FOREIGN KEY (target_datasource_id) REFERENCES df_etl.target_datasource(id);


--
-- Name: institution institution_parent_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.institution
    ADD CONSTRAINT institution_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES df_etl.institution(id);


--
-- Name: medical_dirty_field medical_dirty_field_dirty_row_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_field
    ADD CONSTRAINT medical_dirty_field_dirty_row_id_fkey FOREIGN KEY (dirty_row_id) REFERENCES df_etl.medical_dirty_row(id) ON DELETE CASCADE;


--
-- Name: medical_dirty_row medical_dirty_row_execution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_row
    ADD CONSTRAINT medical_dirty_row_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES df_etl.task_execution(id);


--
-- Name: medical_dirty_row medical_dirty_row_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.medical_dirty_row
    ADD CONSTRAINT medical_dirty_row_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id);


--
-- Name: notify_record notify_record_channel_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.notify_record
    ADD CONSTRAINT notify_record_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES df_etl.webhook_endpoint(id);


--
-- Name: notify_record notify_record_rule_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.notify_record
    ADD CONSTRAINT notify_record_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES df_etl.alert_rule(id);


--
-- Name: notify_record notify_record_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.notify_record
    ADD CONSTRAINT notify_record_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id);


--
-- Name: qrtz_blob_triggers qrtz_blob_triggers_sched_name_trigger_name_trigger_group_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_cron_triggers qrtz_cron_triggers_sched_name_trigger_name_trigger_group_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simple_triggers qrtz_simple_triggers_sched_name_trigger_name_trigger_group_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_sched_name_trigger_name_trigger_group_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers qrtz_simprop_triggers_sched_name_trigger_name_trigger_grou_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_sched_name_trigger_name_trigger_grou_fkey FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers qrtz_triggers_sched_name_job_name_job_group_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_sched_name_job_name_job_group_fkey FOREIGN KEY (sched_name, job_name, job_group) REFERENCES df_etl.qrtz_job_details(sched_name, job_name, job_group);


--
-- Name: source_datasource source_datasource_institution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.source_datasource
    ADD CONSTRAINT source_datasource_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES df_etl.institution(id);


--
-- Name: sync_task sync_task_institution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.sync_task
    ADD CONSTRAINT sync_task_institution_id_fkey FOREIGN KEY (institution_id) REFERENCES df_etl.institution(id);


--
-- Name: sync_task sync_task_source_datasource_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.sync_task
    ADD CONSTRAINT sync_task_source_datasource_id_fkey FOREIGN KEY (source_datasource_id) REFERENCES df_etl.source_datasource(id);


--
-- Name: sync_task sync_task_target_datasource_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.sync_task
    ADD CONSTRAINT sync_task_target_datasource_id_fkey FOREIGN KEY (target_datasource_id) REFERENCES df_etl.target_datasource(id);


--
-- Name: task_chunk task_chunk_execution_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_chunk
    ADD CONSTRAINT task_chunk_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES df_etl.task_execution(id) ON DELETE CASCADE;


--
-- Name: task_execution task_execution_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_execution
    ADD CONSTRAINT task_execution_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id);


--
-- Name: task_view_config task_view_config_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.task_view_config
    ADD CONSTRAINT task_view_config_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id) ON DELETE CASCADE;


--
-- Name: validation_run validation_run_task_id_fkey; Type: FK CONSTRAINT; Schema: df_etl; Owner: -
--

ALTER TABLE ONLY df_etl.validation_run
    ADD CONSTRAINT validation_run_task_id_fkey FOREIGN KEY (task_id) REFERENCES df_etl.sync_task(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict YsdAaOIKlWSLcL0e1bSQ1uJ3nuNlMM95Co29UkUp3pVFmthVTAHeeY1YhgazWkZ


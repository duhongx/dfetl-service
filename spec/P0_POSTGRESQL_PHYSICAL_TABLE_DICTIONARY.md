# DFETL PostgreSQL V1 物理表字典

> 状态：`GENERATED_AND_FROZEN_FOR_D2`
> 生成日期：2026-08-18
> 签字基线：`938566a6659fbf445e00f472ba932fe446d1d886`
> OpenAPI 基线：`8b7db4610508d9381c5fe4510757f058c5917b44`
> 可执行来源：`server/src/main/resources/db/migration/V1__baseline.sql`
> 生成器：`scripts/generate_postgresql_v1_dictionary.py`
> 适用范围：新系统独立、空白 PostgreSQL 元数据库；禁止在老 `df_ygt/df_etl` 上执行。

## 1. 基线摘要

| 项目 | 数量/结论 |
| --- | --- |
| 逻辑表 | 77 张 |
| 分区接收表 | 2 张默认分区：`operation_audit_default`、`external_api_request_log_default` |
| 索引 | 135 个显式索引（不含主键/唯一约束自动索引） |
| PL/pgSQL 函数 | 5 个 |
| Trigger | 18 个 |
| Quartz | Quartz 2.5.2 官方 PostgreSQL JDBCJobStore 11 张表及官方索引 |
| 权限目录 | 106 个 `domain.action` 基础权限 |
| 初始账号 | 不创建；首次管理员必须通过独立的一次性安全引导创建 |
| Secret | V1 不包含密码、JWT/AES 主密钥、数据库凭据或 Client Secret |

## 2. 关键物理边界

1. 所有对象位于新数据库的 `df_etl` Schema；Flyway history 也配置在该 Schema。
2. Dataset、Route、Task 使用稳定身份与不可变版本；当前版本指针使用可延迟复合外键和提交时 Trigger 校验。
3. PostgreSQL 只保存预检 Run/Summary/Manifest；海量 RAW、问题记录和问题项位于 Doris。
4. 无主键范围替换使用 Doris LIST 正式分区、临时分区、备份和回滚控制面表，不使用整表 TRUNCATE。
5. `operation_audit` 和 `external_api_request_log` 按月 Range 分区，V1 创建 Default Partition，后续维护任务提前创建月分区。
6. 所有可编辑配置使用 `revision` 乐观锁；命令使用 `command_idempotency`；长外部操作使用带 Fencing Token 的 `operation_lock`。
7. RabbitMQ 是 P0 唯一业务消息通道；V1 不包含 Redis Stream、transport 切换或任务级消息覆盖。

## 3. 领域表清单

### 3.1 安全、会话与全局支撑

| 表 | 职责 |
| --- | --- |
| `user_account` | Local user identities; accounts are disabled rather than physically deleted |
| `security_role` | Named permission collections; built-in roles cannot be deleted |
| `security_permission` | Versioned domain.action permission catalog |
| `user_role` | Many-to-many user to role assignments |
| `role_permission` | Many-to-many role to permission assignments |
| `login_session` | Refresh-token/session identities; token values are stored as hashes only |
| `user_alert_preference` | Per-user notification preferences; does not alter system alert rules |
| `system_setting` | Non-secret low-frequency settings for one medical-community deployment |
| `registry_connection` | Singleton registry connection; password is AES-GCM ciphertext |
| `global_validation_policy` | Singleton default validation policy |
| `export_storage_config` | Singleton S3-compatible export storage configuration |
| `application_instance` | Application process heartbeat identities for lease ownership and diagnostics |
| `operation_lock` | Cross-component lease with fencing token for long external operations |
| `command_idempotency` | Command idempotency facts scoped by principal and endpoint |
| `export_job` | Shared asynchronous export jobs for precheck, execution, validation, logs and audit |
| `operation_audit` | Append-only security and business operation audit; secrets and sensitive raw values are forbidden |

### 3.2 接入资源与业务系统实例

| 表 | 职责 |
| --- | --- |
| `institution` | Flat medical-institution collection; no parent hierarchy and no tenant column |
| `source_datasource` | Pure logical JDBC source connection; institution ownership is expressed through system instances |
| `target_datasource` | Logical Doris deployment; FE endpoints are normalized in target_datasource_endpoint |
| `target_datasource_endpoint` | Doris FE endpoints; DFETL does not manage BE nodes |
| `business_system_instance` | One actually deployed business application system within the medical community |
| `business_system_instance_institution` | Many-to-many system-instance coverage of institutions |
| `business_system_instance_datasource` | Pure many-to-many association; no purpose, priority or automatic failover |

### 3.3 标准数据集、字段合同与 Doris 合同

| 表 | 职责 |
| --- | --- |
| `field_conversion_contract` | Immutable published medical-field conversion and normalization contract header |
| `field_conversion_rule` | Ordered immutable rules belonging to one conversion contract version |
| `generic_jdbc_type_mapping` | Mutable diagnostic JDBC-to-Doris suggestions; never override the medical contract |
| `dataset_definition_sync_run` | Manual registry-to-dataset definition synchronization history |
| `standard_dataset` | Stable standard-dataset identity imported only from the registry |
| `standard_dataset_version` | Immutable normalized dataset definition version |
| `standard_dataset_field` | Immutable fields belonging to one standard dataset version |
| `dataset_sync_policy` | Mutable dataset defaults copied into immutable task versions |
| `dataset_validation_policy` | Dataset validation override; service forbids checksum without a real business key |
| `dataset_message_policy` | Dataset-level RabbitMQ policy; no task override or transport switch |
| `doris_table_contract` | Expected ODS/RAW contract generated from one immutable dataset version; actual Doris metadata is read live |
| `doris_institution_partition` | Stable institution-to-formal-LIST-partition binding for shared ODS tables |
| `doris_table_operation` | Explicit Doris create/rebuild/partition-maintenance command history; normal sync never creates or alters tables |

### 3.4 采集链路与字段解析

| 表 | 职责 |
| --- | --- |
| `collection_route` | Stable shared collection-route identity; no enabled state and no independent structure gate |
| `collection_route_institution` | Current institution coverage of a shared collection route |
| `collection_route_version` | Immutable route contract and external-resource snapshot |
| `collection_route_version_institution` | Immutable route-version institution coverage snapshot |
| `route_field_resolution` | Immutable standard-field to actual-JDBC-field resolution; no aliases or edit expressions |

### 3.5 任务、执行、水位与校验

| 表 | 职责 |
| --- | --- |
| `sync_task` | Stable institution plus dataset task identity; execution contract lives in immutable versions |
| `sync_task_version` | Immutable task execution contract; schedule, route version, dataset version and validation are frozen |
| `task_governance_override` | Mutable task-level scheduling/validation overrides; message settings are intentionally absent |
| `sync_execution` | Immutable execution context plus mutable status; no cross-execution checkpoint or retry self-reference |
| `load_batch` | One execution batch with deterministic Doris Label and final probe state; not a future execution checkpoint |
| `task_watermark` | Single formal watermark per task; advanced only after all loads and blocking validation succeed |
| `validation_run` | Unified validation run; technical status and PASS/MISMATCH business result are separate |
| `delete_apply_run` | Auditable delete-reconciliation dry-run/apply command; apply must reference a successful dry run |
| `message_outbox` | One small RabbitMQ publish command per successful execution; payload rows are reread from Doris |
| `doris_scope_backup_snapshot` | Short-lived old formal institution range copied to an internal Doris backup table for rollback |
| `doris_scope_replace_run` | Execution-scoped LIST partition replacement state, backup, post-switch validation and rollback facts |

### 3.6 预检控制面

| 表 | 职责 |
| --- | --- |
| `precheck_run` | One immutable route precheck run fact; data issues are COMPLETED+ISSUES rather than technical failure |
| `precheck_issue_summary` | Long-lived field/composite/structure issue summaries; detailed problem rows remain in Doris |
| `precheck_detail_manifest` | Authoritative control-plane state for limited-life Doris precheck detail; missing Doris rows never imply zero issues |

### 3.7 告警与外部 API

| 表 | 职责 |
| --- | --- |
| `alert_channel` | Encrypted outbound alert endpoint and secret configuration |
| `alert_rule` | Whitelisted metric and condition alert rules; no arbitrary SQL or expression execution |
| `alert_rule_channel` | Many-to-many alert rule to delivery channel association |
| `alert_event` | Deduplicated alert event fact; payload must be sanitized |
| `alert_delivery` | One logical delivery per event and channel; attempts are separate immutable facts |
| `alert_delivery_attempt` | Immutable sanitized alert delivery attempt; secrets and raw payloads are forbidden |
| `external_client` | External API principal with ALL or SELECTED institution scope |
| `external_client_institution` | Institution scope rows for SELECTED external clients; ALL clients must have none |
| `external_client_secret` | One-time external client secret version; only an irreversible hash is stored |
| `external_api_request_identity` | Global request-id uniqueness anchor for the partitioned external API request log |
| `external_api_request_log` | Partitioned sanitized external API access fact; no Authorization, secret or medical payload |

### 3.8 Quartz JDBCJobStore

| 表 | 职责 |
| --- | --- |
| `qrtz_job_details` | 见字段与约束定义 |
| `qrtz_triggers` | 见字段与约束定义 |
| `qrtz_simple_triggers` | 见字段与约束定义 |
| `qrtz_cron_triggers` | 见字段与约束定义 |
| `qrtz_simprop_triggers` | 见字段与约束定义 |
| `qrtz_blob_triggers` | 见字段与约束定义 |
| `qrtz_calendars` | 见字段与约束定义 |
| `qrtz_paused_trigger_grps` | 见字段与约束定义 |
| `qrtz_fired_triggers` | 见字段与约束定义 |
| `qrtz_scheduler_state` | 见字段与约束定义 |
| `qrtz_locks` | 见字段与约束定义 |

## 4. 完整字段、约束和索引

### 4.1 安全、会话与全局支撑

#### `user_account`

Local user identities; accounts are disabled rather than physically deleted

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `username varchar(100) NOT NULL` |
| 3 | `username_normalized varchar(100) GENERATED ALWAYS AS (lower(btrim(username))) STORED` |
| 4 | `display_name varchar(200) NOT NULL` |
| 5 | `password_hash varchar(255) NOT NULL` |
| 6 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 7 | `failed_login_count integer NOT NULL DEFAULT 0` |
| 8 | `locked_until timestamptz` |
| 9 | `token_version integer NOT NULL DEFAULT 0` |
| 10 | `password_changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `last_login_at timestamptz` |
| 12 | `revision bigint NOT NULL DEFAULT 0` |
| 13 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `created_by bigint` |
| 15 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 16 | `updated_by bigint` |

**表内约束**

- `CONSTRAINT uq_user_account_username_normalized UNIQUE (username_normalized)`
- `CONSTRAINT ck_user_account_username_nonblank CHECK (btrim(username) <> '')`
- `CONSTRAINT ck_user_account_display_name_nonblank CHECK (btrim(display_name) <> '')`
- `CONSTRAINT ck_user_account_status CHECK (status IN ('ENABLED','DISABLED','LOCKED'))`
- `CONSTRAINT ck_user_account_failed_login_count CHECK (failed_login_count >= 0)`
- `CONSTRAINT ck_user_account_token_version CHECK (token_version >= 0)`
- `CONSTRAINT ck_user_account_revision CHECK (revision >= 0)`
- `CONSTRAINT fk_user_account_created_by FOREIGN KEY (created_by) REFERENCES df_etl.user_account(id) ON DELETE RESTRICT`
- `CONSTRAINT fk_user_account_updated_by FOREIGN KEY (updated_by) REFERENCES df_etl.user_account(id) ON DELETE RESTRICT`

**显式索引**

- `CREATE INDEX idx_user_account_status_username ON df_etl.user_account(status, username_normalized);`
- `CREATE INDEX idx_user_account_last_login ON df_etl.user_account(last_login_at DESC);`

#### `security_role`

Named permission collections; built-in roles cannot be deleted

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `code_normalized varchar(100) GENERATED ALWAYS AS (lower(btrim(code))) STORED` |
| 4 | `name varchar(200) NOT NULL` |
| 5 | `description text` |
| 6 | `built_in boolean NOT NULL DEFAULT false` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 8 | `revision bigint NOT NULL DEFAULT 0` |
| 9 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 11 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 12 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_security_role_code_normalized UNIQUE (code_normalized)`
- `CONSTRAINT ck_security_role_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_security_role_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_security_role_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_security_role_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_security_role_status_code ON df_etl.security_role(status, code_normalized);`

#### `security_permission`

Versioned domain.action permission catalog

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `code varchar(128) PRIMARY KEY` |
| 2 | `domain varchar(64) NOT NULL` |
| 3 | `action varchar(96) NOT NULL` |
| 4 | `description varchar(500) NOT NULL` |
| 5 | `confirmation_level varchar(8) NOT NULL DEFAULT 'NONE'` |
| 6 | `built_in boolean NOT NULL DEFAULT true` |
| 7 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT ck_security_permission_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_security_permission_domain_nonblank CHECK (btrim(domain) <> '')`
- `CONSTRAINT ck_security_permission_action_nonblank CHECK (btrim(action) <> '')`
- `CONSTRAINT ck_security_permission_code_shape CHECK (code = domain \|\| '.' \|\| action)`
- `CONSTRAINT ck_security_permission_confirmation CHECK (confirmation_level IN ('NONE','C1','C2','S1'))`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `user_role`

Many-to-many user to role assignments

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `user_id bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE CASCADE` |
| 2 | `role_id bigint NOT NULL REFERENCES df_etl.security_role(id) ON DELETE RESTRICT` |
| 3 | `assigned_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `assigned_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (user_id, role_id)`

**显式索引**

- `CREATE INDEX idx_user_role_role_user ON df_etl.user_role(role_id, user_id);`

#### `role_permission`

Many-to-many role to permission assignments

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `role_id bigint NOT NULL REFERENCES df_etl.security_role(id) ON DELETE CASCADE` |
| 2 | `permission_code varchar(128) NOT NULL REFERENCES df_etl.security_permission(code) ON DELETE RESTRICT` |
| 3 | `assigned_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `assigned_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (role_id, permission_code)`

**显式索引**

- `CREATE INDEX idx_role_permission_permission_role ON df_etl.role_permission(permission_code, role_id);`

#### `login_session`

Refresh-token/session identities; token values are stored as hashes only

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `user_id bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE CASCADE` |
| 3 | `refresh_token_hash varchar(255) NOT NULL` |
| 4 | `token_version integer NOT NULL` |
| 5 | `issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 6 | `expires_at timestamptz NOT NULL` |
| 7 | `last_seen_at timestamptz` |
| 8 | `revoked_at timestamptz` |
| 9 | `revoke_reason varchar(300)` |
| 10 | `client_ip inet` |
| 11 | `user_agent varchar(500)` |

**表内约束**

- `CONSTRAINT ck_login_session_token_version CHECK (token_version >= 0)`
- `CONSTRAINT ck_login_session_expiry CHECK (expires_at > issued_at)`
- `CONSTRAINT ck_login_session_revocation CHECK (revoked_at IS NULL OR revoked_at >= issued_at)`

**显式索引**

- `CREATE INDEX idx_login_session_user_expiry ON df_etl.login_session(user_id, expires_at DESC);`
- `CREATE INDEX idx_login_session_active_user ON df_etl.login_session(user_id, issued_at DESC) WHERE revoked_at IS NULL;`
- `CREATE INDEX idx_login_session_expiry ON df_etl.login_session(expires_at);`

#### `user_alert_preference`

Per-user notification preferences; does not alter system alert rules

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `user_id bigint PRIMARY KEY REFERENCES df_etl.user_account(id) ON DELETE CASCADE` |
| 2 | `in_app_enabled boolean NOT NULL DEFAULT true` |
| 3 | `email_enabled boolean NOT NULL DEFAULT false` |
| 4 | `critical_only boolean NOT NULL DEFAULT false` |
| 5 | `quiet_hours_json jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 6 | `revision bigint NOT NULL DEFAULT 0` |
| 7 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT ck_user_alert_preference_revision CHECK (revision >= 0)`
- `CONSTRAINT ck_user_alert_preference_quiet_hours CHECK (jsonb_typeof(quiet_hours_json) = 'object')`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `system_setting`

Non-secret low-frequency settings for one medical-community deployment

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `setting_key varchar(128) PRIMARY KEY` |
| 2 | `category varchar(64) NOT NULL` |
| 3 | `value_type varchar(16) NOT NULL` |
| 4 | `value_json jsonb NOT NULL` |
| 5 | `default_value_json jsonb` |
| 6 | `validation_rule_json jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 7 | `description varchar(500) NOT NULL` |
| 8 | `revision bigint NOT NULL DEFAULT 0` |
| 9 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_system_setting_key_nonblank CHECK (btrim(setting_key) <> '')`
- `CONSTRAINT ck_system_setting_category_nonblank CHECK (btrim(category) <> '')`
- `CONSTRAINT ck_system_setting_value_type CHECK (value_type IN ('INTEGER','BOOLEAN','STRING','JSON'))`
- `CONSTRAINT ck_system_setting_value_shape CHECK ( (value_type = 'INTEGER' AND jsonb_typeof(value_json) = 'number') OR (value_type = 'BOOLEAN' AND jsonb_typeof(value_json) = 'boolean') OR (value_type = 'STRING' AND jsonb_typeof(value_json) = 'string') OR value_type = 'JSON' )`
- `CONSTRAINT ck_system_setting_default_shape CHECK ( default_value_json IS NULL OR (value_type = 'INTEGER' AND jsonb_typeof(default_value_json) = 'number') OR (value_type = 'BOOLEAN' AND jsonb_typeof(default_value_json) = 'boolean') OR (value_type = 'STRING' AND jsonb_typeof(default_value_json) = 'string') OR value_type = 'JSON' )`
- `CONSTRAINT ck_system_setting_validation_rule CHECK (jsonb_typeof(validation_rule_json) = 'object')`
- `CONSTRAINT ck_system_setting_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_system_setting_category_key ON df_etl.system_setting(category, setting_key);`

#### `registry_connection`

Singleton registry connection; password is AES-GCM ciphertext

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id smallint PRIMARY KEY DEFAULT 1` |
| 2 | `host varchar(255)` |
| 3 | `port integer` |
| 4 | `database_name varchar(128)` |
| 5 | `schema_name varchar(128)` |
| 6 | `username varchar(128)` |
| 7 | `password_ciphertext bytea` |
| 8 | `password_nonce bytea` |
| 9 | `crypto_key_id varchar(128)` |
| 10 | `ssl_mode varchar(24) NOT NULL DEFAULT 'DISABLE'` |
| 11 | `connect_timeout_seconds integer NOT NULL DEFAULT 10` |
| 12 | `query_timeout_seconds integer NOT NULL DEFAULT 60` |
| 13 | `status varchar(20) NOT NULL DEFAULT 'UNCONFIGURED'` |
| 14 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 15 | `last_tested_at timestamptz` |
| 16 | `last_test_error text` |
| 17 | `revision bigint NOT NULL DEFAULT 0` |
| 18 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 19 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_registry_connection_singleton CHECK (id = 1)`
- `CONSTRAINT ck_registry_connection_port CHECK (port IS NULL OR port BETWEEN 1 AND 65535)`
- `CONSTRAINT ck_registry_connection_ssl_mode CHECK (ssl_mode IN ('DISABLE','ALLOW','PREFER','REQUIRE','VERIFY_CA','VERIFY_FULL'))`
- `CONSTRAINT ck_registry_connection_timeout CHECK (connect_timeout_seconds BETWEEN 1 AND 300 AND query_timeout_seconds BETWEEN 1 AND 86400)`
- `CONSTRAINT ck_registry_connection_status CHECK (status IN ('UNCONFIGURED','CONFIGURED'))`
- `CONSTRAINT ck_registry_connection_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`
- `CONSTRAINT ck_registry_connection_configured CHECK ( status = 'UNCONFIGURED' OR (host IS NOT NULL AND port IS NOT NULL AND database_name IS NOT NULL AND schema_name IS NOT NULL AND username IS NOT NULL AND password_ciphertext IS NOT NULL AND password_nonce IS NOT NULL AND crypto_key_id IS NOT NULL) )`
- `CONSTRAINT ck_registry_connection_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `global_validation_policy`

Singleton default validation policy

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id smallint PRIMARY KEY DEFAULT 1` |
| 2 | `method varchar(32) NOT NULL DEFAULT 'ROW_COUNT'` |
| 3 | `tolerance numeric(20,6) NOT NULL DEFAULT 0` |
| 4 | `lookback_seconds integer NOT NULL DEFAULT 0` |
| 5 | `auto_recheck_enabled boolean NOT NULL DEFAULT false` |
| 6 | `revision bigint NOT NULL DEFAULT 0` |
| 7 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 8 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_global_validation_policy_singleton CHECK (id = 1)`
- `CONSTRAINT ck_global_validation_policy_method CHECK (method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))`
- `CONSTRAINT ck_global_validation_policy_tolerance CHECK (tolerance = 0)`
- `CONSTRAINT ck_global_validation_policy_lookback CHECK (lookback_seconds = 0)`
- `CONSTRAINT ck_global_validation_policy_recheck CHECK (auto_recheck_enabled = false)`
- `CONSTRAINT ck_global_validation_policy_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `export_storage_config`

Singleton S3-compatible export storage configuration

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id smallint PRIMARY KEY DEFAULT 1` |
| 2 | `provider varchar(24) NOT NULL DEFAULT 'S3_COMPATIBLE'` |
| 3 | `endpoint varchar(500)` |
| 4 | `region varchar(128)` |
| 5 | `bucket varchar(128)` |
| 6 | `object_prefix varchar(300) NOT NULL DEFAULT 'dfetl-export'` |
| 7 | `access_key_id_ciphertext bytea` |
| 8 | `secret_access_key_ciphertext bytea` |
| 9 | `credential_nonce bytea` |
| 10 | `crypto_key_id varchar(128)` |
| 11 | `path_style_access boolean NOT NULL DEFAULT true` |
| 12 | `tls_enabled boolean NOT NULL DEFAULT true` |
| 13 | `lifecycle_managed boolean NOT NULL DEFAULT false` |
| 14 | `status varchar(20) NOT NULL DEFAULT 'UNCONFIGURED'` |
| 15 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 16 | `last_tested_at timestamptz` |
| 17 | `last_test_error text` |
| 18 | `revision bigint NOT NULL DEFAULT 0` |
| 19 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 20 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_export_storage_singleton CHECK (id = 1)`
- `CONSTRAINT ck_export_storage_provider CHECK (provider = 'S3_COMPATIBLE')`
- `CONSTRAINT ck_export_storage_status CHECK (status IN ('UNCONFIGURED','CONFIGURED','DISABLED'))`
- `CONSTRAINT ck_export_storage_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`
- `CONSTRAINT ck_export_storage_configured CHECK ( status <> 'CONFIGURED' OR (endpoint IS NOT NULL AND bucket IS NOT NULL AND access_key_id_ciphertext IS NOT NULL AND secret_access_key_ciphertext IS NOT NULL AND credential_nonce IS NOT NULL AND crypto_key_id IS NOT NULL) )`
- `CONSTRAINT ck_export_storage_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `application_instance`

Application process heartbeat identities for lease ownership and diagnostics

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `instance_id varchar(128) PRIMARY KEY` |
| 2 | `host_name varchar(255) NOT NULL` |
| 3 | `application_version varchar(100) NOT NULL` |
| 4 | `started_at timestamptz NOT NULL` |
| 5 | `last_heartbeat_at timestamptz NOT NULL` |
| 6 | `status varchar(20) NOT NULL` |

**表内约束**

- `CONSTRAINT ck_application_instance_id_nonblank CHECK (btrim(instance_id) <> '')`
- `CONSTRAINT ck_application_instance_status CHECK (status IN ('STARTING','RUNNING','DRAINING','STOPPED'))`
- `CONSTRAINT ck_application_instance_heartbeat CHECK (last_heartbeat_at >= started_at)`

**显式索引**

- `CREATE INDEX idx_application_instance_status_heartbeat ON df_etl.application_instance(status, last_heartbeat_at);`

#### `operation_lock`

Cross-component lease with fencing token for long external operations

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `lock_key varchar(300) PRIMARY KEY` |
| 2 | `owner_instance_id varchar(128) NOT NULL REFERENCES df_etl.application_instance(instance_id) ON DELETE RESTRICT` |
| 3 | `owner_resource_id varchar(128)` |
| 4 | `lease_until timestamptz NOT NULL` |
| 5 | `fencing_token bigint NOT NULL` |
| 6 | `revision bigint NOT NULL DEFAULT 0` |
| 7 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT ck_operation_lock_key_nonblank CHECK (btrim(lock_key) <> '')`
- `CONSTRAINT ck_operation_lock_fencing_token CHECK (fencing_token >= 0)`
- `CONSTRAINT ck_operation_lock_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_operation_lock_lease_until ON df_etl.operation_lock(lease_until);`
- `CREATE INDEX idx_operation_lock_owner ON df_etl.operation_lock(owner_instance_id, lease_until);`

#### `command_idempotency`

Command idempotency facts scoped by principal and endpoint

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `principal_type varchar(24) NOT NULL` |
| 3 | `principal_id varchar(128) NOT NULL` |
| 4 | `endpoint_key varchar(200) NOT NULL` |
| 5 | `idempotency_key varchar(200) NOT NULL` |
| 6 | `request_hash varchar(128) NOT NULL` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'IN_PROGRESS'` |
| 8 | `resource_type varchar(64)` |
| 9 | `resource_id varchar(128)` |
| 10 | `http_status integer` |
| 11 | `response_snapshot jsonb` |
| 12 | `failure_code varchar(128)` |
| 13 | `failure_message text` |
| 14 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 15 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 16 | `expires_at timestamptz NOT NULL` |

**表内约束**

- `CONSTRAINT uq_command_idempotency_scope UNIQUE (principal_type, principal_id, endpoint_key, idempotency_key)`
- `CONSTRAINT ck_command_idempotency_principal_type CHECK (principal_type IN ('USER','EXTERNAL_CLIENT','SCHEDULER','SYSTEM'))`
- `CONSTRAINT ck_command_idempotency_status CHECK (status IN ('IN_PROGRESS','SUCCEEDED','FAILED'))`
- `CONSTRAINT ck_command_idempotency_http_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)`
- `CONSTRAINT ck_command_idempotency_expiry CHECK (expires_at > created_at)`
- `CONSTRAINT ck_command_idempotency_response CHECK (response_snapshot IS NULL OR jsonb_typeof(response_snapshot) = 'object')`

**显式索引**

- `CREATE INDEX idx_command_idempotency_expiry ON df_etl.command_idempotency(expires_at);`
- `CREATE INDEX idx_command_idempotency_status_updated ON df_etl.command_idempotency(status, updated_at);`

#### `export_job`

Shared asynchronous export jobs for precheck, execution, validation, logs and audit

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `export_type varchar(48) NOT NULL` |
| 3 | `subject_type varchar(64) NOT NULL` |
| 4 | `subject_id varchar(128) NOT NULL` |
| 5 | `filter_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 6 | `format varchar(8) NOT NULL` |
| 7 | `contains_sensitive boolean NOT NULL DEFAULT false` |
| 8 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 9 | `object_manifest jsonb` |
| 10 | `row_count bigint` |
| 11 | `byte_count bigint` |
| 12 | `requested_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 13 | `requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `started_at timestamptz` |
| 15 | `finished_at timestamptz` |
| 16 | `expires_at timestamptz` |
| 17 | `failure_code varchar(128)` |
| 18 | `failure_message text` |
| 19 | `idempotency_key varchar(200) NOT NULL` |
| 20 | `request_hash varchar(128) NOT NULL` |
| 21 | `permission_code_snapshot varchar(128) NOT NULL` |
| 22 | `object_storage_config_revision bigint NOT NULL` |
| 23 | `content_sha256 varchar(64)` |
| 24 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_export_job_idempotency UNIQUE (requested_by, export_type, subject_type, subject_id, idempotency_key)`
- `CONSTRAINT ck_export_job_filter_snapshot CHECK (jsonb_typeof(filter_snapshot) = 'object')`
- `CONSTRAINT ck_export_job_format CHECK (format IN ('CSV','XLSX'))`
- `CONSTRAINT ck_export_job_status CHECK (status IN ('PENDING','GENERATING','SUCCEEDED','FAILED','EXPIRED'))`
- `CONSTRAINT ck_export_job_manifest CHECK (object_manifest IS NULL OR jsonb_typeof(object_manifest) IN ('object','array'))`
- `CONSTRAINT ck_export_job_counts CHECK ((row_count IS NULL OR row_count >= 0) AND (byte_count IS NULL OR byte_count >= 0))`
- `CONSTRAINT ck_export_job_time_order CHECK ( (started_at IS NULL OR started_at >= requested_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) AND (expires_at IS NULL OR expires_at >= requested_at) )`

**显式索引**

- `CREATE INDEX idx_export_job_requester_created ON df_etl.export_job(requested_by, created_at DESC);`
- `CREATE INDEX idx_export_job_status_created ON df_etl.export_job(status, created_at);`
- `CREATE INDEX idx_export_job_expiry ON df_etl.export_job(expires_at) WHERE status = 'SUCCEEDED';`

#### `operation_audit`

Append-only security and business operation audit; secrets and sensitive raw values are forbidden

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `occurred_at timestamptz NOT NULL` |
| 2 | `id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL` |
| 3 | `request_id varchar(64)` |
| 4 | `trace_id varchar(64)` |
| 5 | `command_id uuid` |
| 6 | `idempotency_key_hash varchar(64)` |
| 7 | `actor_type varchar(24) NOT NULL` |
| 8 | `actor_id varchar(128)` |
| 9 | `actor_name_snapshot varchar(200)` |
| 10 | `source varchar(24) NOT NULL` |
| 11 | `permission_code varchar(128)` |
| 12 | `operation_code varchar(128) NOT NULL` |
| 13 | `target_type varchar(64)` |
| 14 | `target_id varchar(128)` |
| 15 | `target_name_snapshot varchar(300)` |
| 16 | `institution_scope jsonb` |
| 17 | `result varchar(16) NOT NULL` |
| 18 | `http_status integer` |
| 19 | `reason text` |
| 20 | `before_snapshot jsonb` |
| 21 | `after_snapshot jsonb` |
| 22 | `detail jsonb` |
| 23 | `error_code varchar(128)` |
| 24 | `client_ip inet` |
| 25 | `user_agent varchar(500)` |

**表内约束**

- `PRIMARY KEY (occurred_at, id)`
- `CONSTRAINT ck_operation_audit_actor_type CHECK (actor_type IN ('USER','EXTERNAL_CLIENT','SCHEDULER','SYSTEM'))`
- `CONSTRAINT ck_operation_audit_source CHECK (source IN ('WEB','EXTERNAL_API','SCHEDULER','SYSTEM'))`
- `CONSTRAINT ck_operation_audit_result CHECK (result IN ('SUCCESS','FAILED','DENIED'))`
- `CONSTRAINT ck_operation_audit_http_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)`
- `CONSTRAINT ck_operation_audit_institution_scope CHECK (institution_scope IS NULL OR jsonb_typeof(institution_scope) IN ('array','object'))`
- `CONSTRAINT ck_operation_audit_before CHECK (before_snapshot IS NULL OR jsonb_typeof(before_snapshot) = 'object')`
- `CONSTRAINT ck_operation_audit_after CHECK (after_snapshot IS NULL OR jsonb_typeof(after_snapshot) = 'object')`
- `CONSTRAINT ck_operation_audit_detail CHECK (detail IS NULL OR jsonb_typeof(detail) = 'object')`

**显式索引**

- `CREATE INDEX idx_operation_audit_occurred ON df_etl.operation_audit(occurred_at DESC);`
- `CREATE INDEX idx_operation_audit_id ON df_etl.operation_audit(id);`
- `CREATE INDEX idx_operation_audit_actor ON df_etl.operation_audit(actor_type, actor_id, occurred_at DESC);`
- `CREATE INDEX idx_operation_audit_target ON df_etl.operation_audit(target_type, target_id, occurred_at DESC);`
- `CREATE INDEX idx_operation_audit_request ON df_etl.operation_audit(request_id);`
- `CREATE INDEX idx_operation_audit_operation ON df_etl.operation_audit(operation_code, occurred_at DESC);`
- `CREATE INDEX idx_operation_audit_result ON df_etl.operation_audit(result, occurred_at DESC);`

### 4.2 接入资源与业务系统实例

#### `institution`

Flat medical-institution collection; no parent hierarchy and no tenant column

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(64) NOT NULL` |
| 3 | `name varchar(300) NOT NULL` |
| 4 | `type varchar(100)` |
| 5 | `level varchar(100)` |
| 6 | `region varchar(200)` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 8 | `description text` |
| 9 | `revision bigint NOT NULL DEFAULT 0` |
| 10 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 13 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_institution_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_institution_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_institution_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_institution_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_institution_code_ci ON df_etl.institution(lower(code));`
- `CREATE INDEX idx_institution_status_id ON df_etl.institution(status, id);`

#### `source_datasource`

Pure logical JDBC source connection; institution ownership is expressed through system instances

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `name varchar(300) NOT NULL` |
| 4 | `db_type varchar(32) NOT NULL` |
| 5 | `connection_mode varchar(20) NOT NULL` |
| 6 | `host varchar(255)` |
| 7 | `port integer` |
| 8 | `database_name varchar(128)` |
| 9 | `default_schema varchar(128)` |
| 10 | `jdbc_url text` |
| 11 | `username varchar(128) NOT NULL` |
| 12 | `password_ciphertext bytea NOT NULL` |
| 13 | `password_nonce bytea NOT NULL` |
| 14 | `crypto_key_id varchar(128) NOT NULL` |
| 15 | `ssl_enabled boolean NOT NULL DEFAULT false` |
| 16 | `read_only boolean NOT NULL DEFAULT true` |
| 17 | `connect_timeout_seconds integer NOT NULL DEFAULT 10` |
| 18 | `query_timeout_seconds integer NOT NULL DEFAULT 60` |
| 19 | `socket_timeout_seconds integer NOT NULL DEFAULT 60` |
| 20 | `pool_max_size integer NOT NULL DEFAULT 4` |
| 21 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 22 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 23 | `last_tested_at timestamptz` |
| 24 | `last_test_error text` |
| 25 | `description text` |
| 26 | `revision bigint NOT NULL DEFAULT 0` |
| 27 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 28 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 29 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 30 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_source_datasource_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_source_datasource_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_source_datasource_db_type CHECK (db_type IN ('POSTGRESQL','MYSQL','ORACLE','SQLSERVER'))`
- `CONSTRAINT ck_source_datasource_mode CHECK (connection_mode IN ('HOST_PORT','JDBC_URL'))`
- `CONSTRAINT ck_source_datasource_connection CHECK ( (connection_mode = 'HOST_PORT' AND host IS NOT NULL AND port BETWEEN 1 AND 65535 AND database_name IS NOT NULL AND jdbc_url IS NULL) OR (connection_mode = 'JDBC_URL' AND jdbc_url IS NOT NULL AND btrim(jdbc_url) <> '' AND host IS NULL AND port IS NULL) )`
- `CONSTRAINT ck_source_datasource_jdbc_no_credentials CHECK (jdbc_url IS NULL OR (position('user=' IN lower(jdbc_url)) = 0 AND position('password=' IN lower(jdbc_url)) = 0))`
- `CONSTRAINT ck_source_datasource_timeouts CHECK ( connect_timeout_seconds BETWEEN 1 AND 300 AND query_timeout_seconds BETWEEN 1 AND 86400 AND socket_timeout_seconds BETWEEN 1 AND 86400 AND pool_max_size BETWEEN 1 AND 100 )`
- `CONSTRAINT ck_source_datasource_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_source_datasource_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`
- `CONSTRAINT ck_source_datasource_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_source_datasource_code_ci ON df_etl.source_datasource(lower(code));`
- `CREATE INDEX idx_source_datasource_status_type ON df_etl.source_datasource(status, db_type, id);`

#### `target_datasource`

Logical Doris deployment; FE endpoints are normalized in target_datasource_endpoint

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `name varchar(300) NOT NULL` |
| 4 | `deployment_mode varchar(20) NOT NULL DEFAULT 'CLUSTER'` |
| 5 | `database_name varchar(128) NOT NULL` |
| 6 | `username varchar(128) NOT NULL` |
| 7 | `password_ciphertext bytea NOT NULL` |
| 8 | `password_nonce bytea NOT NULL` |
| 9 | `crypto_key_id varchar(128) NOT NULL` |
| 10 | `ssl_enabled boolean NOT NULL DEFAULT false` |
| 11 | `connect_timeout_seconds integer NOT NULL DEFAULT 10` |
| 12 | `query_timeout_seconds integer NOT NULL DEFAULT 60` |
| 13 | `stream_load_timeout_seconds integer NOT NULL DEFAULT 600` |
| 14 | `pool_max_size integer NOT NULL DEFAULT 8` |
| 15 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 16 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 17 | `last_tested_at timestamptz` |
| 18 | `last_test_error text` |
| 19 | `description text` |
| 20 | `revision bigint NOT NULL DEFAULT 0` |
| 21 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 22 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 23 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 24 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_target_datasource_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_target_datasource_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_target_datasource_database_nonblank CHECK (btrim(database_name) <> '')`
- `CONSTRAINT ck_target_datasource_mode CHECK (deployment_mode IN ('STANDALONE','CLUSTER'))`
- `CONSTRAINT ck_target_datasource_timeouts CHECK ( connect_timeout_seconds BETWEEN 1 AND 300 AND query_timeout_seconds BETWEEN 1 AND 86400 AND stream_load_timeout_seconds BETWEEN 1 AND 86400 AND pool_max_size BETWEEN 1 AND 100 )`
- `CONSTRAINT ck_target_datasource_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_target_datasource_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`
- `CONSTRAINT ck_target_datasource_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_target_datasource_code_ci ON df_etl.target_datasource(lower(code));`
- `CREATE INDEX idx_target_datasource_status_id ON df_etl.target_datasource(status, id);`

#### `target_datasource_endpoint`

Doris FE endpoints; DFETL does not manage BE nodes

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `target_datasource_id bigint NOT NULL REFERENCES df_etl.target_datasource(id) ON DELETE CASCADE` |
| 3 | `host varchar(255) NOT NULL` |
| 4 | `query_port integer NOT NULL DEFAULT 9030` |
| 5 | `http_port integer NOT NULL DEFAULT 8030` |
| 6 | `enabled boolean NOT NULL DEFAULT true` |
| 7 | `ordinal_no integer NOT NULL` |
| 8 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 9 | `last_tested_at timestamptz` |
| 10 | `last_test_error text` |

**表内约束**

- `CONSTRAINT uq_target_endpoint_ordinal UNIQUE (target_datasource_id, ordinal_no)`
- `CONSTRAINT uq_target_endpoint_host_ports UNIQUE (target_datasource_id, host, query_port, http_port)`
- `CONSTRAINT ck_target_endpoint_host_nonblank CHECK (btrim(host) <> '')`
- `CONSTRAINT ck_target_endpoint_ports CHECK (query_port BETWEEN 1 AND 65535 AND http_port BETWEEN 1 AND 65535)`
- `CONSTRAINT ck_target_endpoint_ordinal CHECK (ordinal_no > 0)`
- `CONSTRAINT ck_target_endpoint_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`

**显式索引**

- `CREATE INDEX idx_target_endpoint_enabled ON df_etl.target_datasource_endpoint(target_datasource_id, enabled, ordinal_no);`

#### `business_system_instance`

One actually deployed business application system within the medical community

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `name varchar(300) NOT NULL` |
| 4 | `system_type varchar(32) NOT NULL` |
| 5 | `vendor varchar(200)` |
| 6 | `product_version varchar(100)` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 8 | `description text` |
| 9 | `revision bigint NOT NULL DEFAULT 0` |
| 10 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 13 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_business_system_instance_code_nonblank CHECK (btrim(code) <> '')`
- `CONSTRAINT ck_business_system_instance_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_business_system_instance_type CHECK (system_type IN ('HIS','LIS','PACS','EMR','OTHER'))`
- `CONSTRAINT ck_business_system_instance_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_business_system_instance_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_business_system_instance_code_ci ON df_etl.business_system_instance(lower(code));`
- `CREATE INDEX idx_business_system_instance_type_status ON df_etl.business_system_instance(system_type, status, id);`

#### `business_system_instance_institution`

Many-to-many system-instance coverage of institutions

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `instance_id bigint NOT NULL REFERENCES df_etl.business_system_instance(id) ON DELETE CASCADE` |
| 2 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 3 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (instance_id, institution_id)`

**显式索引**

- `CREATE INDEX idx_instance_institution_reverse ON df_etl.business_system_instance_institution(institution_id, instance_id);`

#### `business_system_instance_datasource`

Pure many-to-many association; no purpose, priority or automatic failover

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `instance_id bigint NOT NULL REFERENCES df_etl.business_system_instance(id) ON DELETE CASCADE` |
| 2 | `source_datasource_id bigint NOT NULL REFERENCES df_etl.source_datasource(id) ON DELETE RESTRICT` |
| 3 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (instance_id, source_datasource_id)`

**显式索引**

- `CREATE INDEX idx_instance_datasource_reverse ON df_etl.business_system_instance_datasource(source_datasource_id, instance_id);`

### 4.3 标准数据集、字段合同与 Doris 合同

#### `field_conversion_contract`

Immutable published medical-field conversion and normalization contract header

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `contract_version varchar(64) NOT NULL` |
| 3 | `name varchar(200) NOT NULL` |
| 4 | `status varchar(20) NOT NULL DEFAULT 'PUBLISHED'` |
| 5 | `normalization_protocol jsonb NOT NULL` |
| 6 | `checksum_protocol jsonb NOT NULL` |
| 7 | `description text` |
| 8 | `published_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 9 | `published_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_field_conversion_contract_version UNIQUE (contract_version)`
- `CONSTRAINT ck_field_conversion_contract_version_nonblank CHECK (btrim(contract_version) <> '')`
- `CONSTRAINT ck_field_conversion_contract_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_field_conversion_contract_status CHECK (status IN ('PUBLISHED','RETIRED'))`
- `CONSTRAINT ck_field_conversion_contract_normalization CHECK (jsonb_typeof(normalization_protocol) = 'object')`
- `CONSTRAINT ck_field_conversion_contract_checksum CHECK (jsonb_typeof(checksum_protocol) = 'object')`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `field_conversion_rule`

Ordered immutable rules belonging to one conversion contract version

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `contract_id bigint NOT NULL REFERENCES df_etl.field_conversion_contract(id) ON DELETE RESTRICT` |
| 3 | `priority integer NOT NULL` |
| 4 | `standard_type varchar(32) NOT NULL` |
| 5 | `standard_format varchar(64)` |
| 6 | `min_length integer` |
| 7 | `max_length integer` |
| 8 | `min_precision integer` |
| 9 | `max_precision integer` |
| 10 | `min_scale integer` |
| 11 | `max_scale integer` |
| 12 | `doris_type_template varchar(200) NOT NULL` |
| 13 | `validation_contract jsonb NOT NULL DEFAULT '{}'::jsonb` |

**表内约束**

- `CONSTRAINT uq_field_conversion_rule_priority UNIQUE (contract_id, priority)`
- `CONSTRAINT ck_field_conversion_rule_priority CHECK (priority > 0)`
- `CONSTRAINT ck_field_conversion_rule_length CHECK ( (min_length IS NULL OR min_length >= 0) AND (max_length IS NULL OR max_length >= 0) AND (min_length IS NULL OR max_length IS NULL OR min_length <= max_length) )`
- `CONSTRAINT ck_field_conversion_rule_precision CHECK ( (min_precision IS NULL OR min_precision > 0) AND (max_precision IS NULL OR max_precision > 0) AND (min_precision IS NULL OR max_precision IS NULL OR min_precision <= max_precision) )`
- `CONSTRAINT ck_field_conversion_rule_scale CHECK ( (min_scale IS NULL OR min_scale >= 0) AND (max_scale IS NULL OR max_scale >= 0) AND (min_scale IS NULL OR max_scale IS NULL OR min_scale <= max_scale) )`
- `CONSTRAINT ck_field_conversion_rule_validation CHECK (jsonb_typeof(validation_contract) = 'object')`

**显式索引**

- `CREATE INDEX idx_field_conversion_rule_selector ON df_etl.field_conversion_rule(contract_id, standard_type, standard_format, priority);`

#### `generic_jdbc_type_mapping`

Mutable diagnostic JDBC-to-Doris suggestions; never override the medical contract

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `db_type varchar(32) NOT NULL` |
| 3 | `jdbc_type_code integer` |
| 4 | `jdbc_type_name varchar(128)` |
| 5 | `doris_type_suggestion varchar(200) NOT NULL` |
| 6 | `priority integer NOT NULL DEFAULT 100` |
| 7 | `enabled boolean NOT NULL DEFAULT true` |
| 8 | `description text` |
| 9 | `revision bigint NOT NULL DEFAULT 0` |
| 10 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 13 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_generic_mapping_db_type CHECK (db_type IN ('POSTGRESQL','MYSQL','ORACLE','SQLSERVER'))`
- `CONSTRAINT ck_generic_mapping_selector CHECK (jdbc_type_code IS NOT NULL OR (jdbc_type_name IS NOT NULL AND btrim(jdbc_type_name) <> ''))`
- `CONSTRAINT ck_generic_mapping_doris_type CHECK (btrim(doris_type_suggestion) <> '')`
- `CONSTRAINT ck_generic_mapping_priority CHECK (priority > 0)`
- `CONSTRAINT ck_generic_mapping_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_generic_mapping_selector ON df_etl.generic_jdbc_type_mapping(db_type, coalesce(jdbc_type_code, -2147483648), lower(coalesce(jdbc_type_name, '')), priority);`
- `CREATE INDEX idx_generic_mapping_enabled ON df_etl.generic_jdbc_type_mapping(db_type, enabled, priority);`

#### `dataset_definition_sync_run`

Manual registry-to-dataset definition synchronization history

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `registry_config_revision bigint NOT NULL` |
| 3 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 4 | `dry_run boolean NOT NULL DEFAULT false` |
| 5 | `created_count integer NOT NULL DEFAULT 0` |
| 6 | `updated_count integer NOT NULL DEFAULT 0` |
| 7 | `unchanged_count integer NOT NULL DEFAULT 0` |
| 8 | `failed_count integer NOT NULL DEFAULT 0` |
| 9 | `result_summary jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 10 | `requested_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 11 | `requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 12 | `started_at timestamptz` |
| 13 | `finished_at timestamptz` |
| 14 | `failure_code varchar(128)` |
| 15 | `failure_message text` |

**表内约束**

- `CONSTRAINT ck_dataset_definition_sync_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'))`
- `CONSTRAINT ck_dataset_definition_sync_counts CHECK (created_count >= 0 AND updated_count >= 0 AND unchanged_count >= 0 AND failed_count >= 0)`
- `CONSTRAINT ck_dataset_definition_sync_summary CHECK (jsonb_typeof(result_summary) = 'object')`
- `CONSTRAINT ck_dataset_definition_sync_times CHECK ( (started_at IS NULL OR started_at >= requested_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE INDEX idx_dataset_definition_sync_requested ON df_etl.dataset_definition_sync_run(requested_at DESC);`
- `CREATE INDEX idx_dataset_definition_sync_status ON df_etl.dataset_definition_sync_run(status, requested_at);`

#### `standard_dataset`

Stable standard-dataset identity imported only from the registry

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `external_dataset_id varchar(128) NOT NULL` |
| 3 | `dataset_code varchar(100) NOT NULL` |
| 4 | `name varchar(300) NOT NULL` |
| 5 | `category varchar(100)` |
| 6 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 7 | `current_version_id bigint` |
| 8 | `first_imported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 9 | `last_synced_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `last_sync_result varchar(20) NOT NULL` |
| 11 | `description text` |
| 12 | `revision bigint NOT NULL DEFAULT 0` |
| 13 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 15 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 16 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_standard_dataset_external_id UNIQUE (external_dataset_id)`
- `CONSTRAINT uq_standard_dataset_id_self UNIQUE (id)`
- `CONSTRAINT ck_standard_dataset_external_id_nonblank CHECK (btrim(external_dataset_id) <> '')`
- `CONSTRAINT ck_standard_dataset_code_nonblank CHECK (btrim(dataset_code) <> '')`
- `CONSTRAINT ck_standard_dataset_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_standard_dataset_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_standard_dataset_sync_result CHECK (last_sync_result IN ('CREATED','UPDATED','UNCHANGED','FAILED'))`
- `CONSTRAINT ck_standard_dataset_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE UNIQUE INDEX uq_standard_dataset_code_ci ON df_etl.standard_dataset(lower(dataset_code));`
- `CREATE INDEX idx_standard_dataset_status_code ON df_etl.standard_dataset(status, lower(dataset_code));`
- `CREATE INDEX idx_standard_dataset_last_synced ON df_etl.standard_dataset(last_synced_at DESC);`

#### `standard_dataset_version`

Immutable normalized dataset definition version

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `dataset_id bigint NOT NULL REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 3 | `version_no integer NOT NULL` |
| 4 | `source_definition_version varchar(128)` |
| 5 | `definition_hash varchar(128) NOT NULL` |
| 6 | `conversion_contract_id bigint NOT NULL REFERENCES df_etl.field_conversion_contract(id) ON DELETE RESTRICT` |
| 7 | `institution_code_field_code varchar(100) NOT NULL` |
| 8 | `incremental_field_code varchar(100)` |
| 9 | `field_count integer NOT NULL` |
| 10 | `business_key_count integer NOT NULL DEFAULT 0` |
| 11 | `definition_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 12 | `source_sync_run_id uuid NOT NULL REFERENCES df_etl.dataset_definition_sync_run(id) ON DELETE RESTRICT` |
| 13 | `imported_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `imported_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_standard_dataset_version_no UNIQUE (dataset_id, version_no)`
- `CONSTRAINT uq_standard_dataset_version_hash UNIQUE (dataset_id, definition_hash)`
- `CONSTRAINT uq_standard_dataset_version_id_dataset UNIQUE (id, dataset_id)`
- `CONSTRAINT ck_standard_dataset_version_no CHECK (version_no > 0)`
- `CONSTRAINT ck_standard_dataset_version_hash_nonblank CHECK (btrim(definition_hash) <> '')`
- `CONSTRAINT ck_standard_dataset_version_institution_field CHECK (btrim(institution_code_field_code) <> '')`
- `CONSTRAINT ck_standard_dataset_version_counts CHECK (field_count > 0 AND business_key_count BETWEEN 0 AND field_count)`
- `CONSTRAINT ck_standard_dataset_version_snapshot CHECK (jsonb_typeof(definition_snapshot) = 'object')`

**显式索引**

- `CREATE INDEX idx_standard_dataset_version_dataset_imported ON df_etl.standard_dataset_version(dataset_id, imported_at DESC);`

#### `standard_dataset_field`

Immutable fields belonging to one standard dataset version

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `dataset_version_id bigint NOT NULL REFERENCES df_etl.standard_dataset_version(id) ON DELETE RESTRICT` |
| 3 | `external_field_id varchar(128)` |
| 4 | `field_code varchar(100) NOT NULL` |
| 5 | `field_name varchar(300) NOT NULL` |
| 6 | `ordinal_no integer NOT NULL` |
| 7 | `standard_type varchar(32) NOT NULL` |
| 8 | `standard_format varchar(64)` |
| 9 | `max_length integer` |
| 10 | `numeric_precision integer` |
| 11 | `numeric_scale integer` |
| 12 | `nullable boolean NOT NULL` |
| 13 | `business_key_ordinal integer` |
| 14 | `value_domain_json jsonb` |
| 15 | `format_contract_json jsonb` |
| 16 | `doris_type varchar(200) NOT NULL` |
| 17 | `doris_nullable boolean NOT NULL` |
| 18 | `sensitive boolean NOT NULL DEFAULT false` |
| 19 | `description text` |

**表内约束**

- `CONSTRAINT uq_standard_dataset_field_ordinal UNIQUE (dataset_version_id, ordinal_no)`
- `CONSTRAINT uq_standard_dataset_field_id_version UNIQUE (id, dataset_version_id)`
- `CONSTRAINT uq_standard_dataset_field_business_key UNIQUE (dataset_version_id, business_key_ordinal)`
- `CONSTRAINT ck_standard_dataset_field_code_nonblank CHECK (btrim(field_code) <> '')`
- `CONSTRAINT ck_standard_dataset_field_name_nonblank CHECK (btrim(field_name) <> '')`
- `CONSTRAINT ck_standard_dataset_field_ordinal CHECK (ordinal_no > 0)`
- `CONSTRAINT ck_standard_dataset_field_lengths CHECK (max_length IS NULL OR max_length > 0)`
- `CONSTRAINT ck_standard_dataset_field_numeric CHECK ( (numeric_precision IS NULL AND numeric_scale IS NULL) OR (numeric_precision IS NOT NULL AND numeric_precision > 0 AND numeric_scale IS NOT NULL AND numeric_scale BETWEEN 0 AND numeric_precision) )`
- `CONSTRAINT ck_standard_dataset_field_business_key CHECK (business_key_ordinal IS NULL OR business_key_ordinal > 0)`
- `CONSTRAINT ck_standard_dataset_field_value_domain CHECK (value_domain_json IS NULL OR jsonb_typeof(value_domain_json) IN ('array','object'))`
- `CONSTRAINT ck_standard_dataset_field_format_contract CHECK (format_contract_json IS NULL OR jsonb_typeof(format_contract_json) = 'object')`

**显式索引**

- `CREATE UNIQUE INDEX uq_standard_dataset_field_code_ci ON df_etl.standard_dataset_field(dataset_version_id, lower(field_code));`
- `CREATE INDEX idx_standard_dataset_field_business_key ON df_etl.standard_dataset_field(dataset_version_id, business_key_ordinal) WHERE business_key_ordinal IS NOT NULL;`

#### `dataset_sync_policy`

Mutable dataset defaults copied into immutable task versions

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `dataset_id bigint PRIMARY KEY REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 2 | `schedule_mode varchar(24) NOT NULL DEFAULT 'EVERY_N_HOURS'` |
| 3 | `interval_hours integer DEFAULT 4` |
| 4 | `cron_expression varchar(200)` |
| 5 | `timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai'` |
| 6 | `fetch_size integer NOT NULL DEFAULT 5000` |
| 7 | `upper_bound_delay_minutes integer NOT NULL DEFAULT 5` |
| 8 | `lookback_seconds integer NOT NULL DEFAULT 0` |
| 9 | `revision bigint NOT NULL DEFAULT 0` |
| 10 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_dataset_sync_policy_mode CHECK (schedule_mode IN ('EVERY_N_HOURS','CRON'))`
- `CONSTRAINT ck_dataset_sync_policy_schedule CHECK ( (schedule_mode = 'EVERY_N_HOURS' AND interval_hours BETWEEN 1 AND 168 AND cron_expression IS NULL) OR (schedule_mode = 'CRON' AND cron_expression IS NOT NULL AND btrim(cron_expression) <> '' AND interval_hours IS NULL) )`
- `CONSTRAINT ck_dataset_sync_policy_fetch_size CHECK (fetch_size BETWEEN 1 AND 1000000)`
- `CONSTRAINT ck_dataset_sync_policy_delay CHECK (upper_bound_delay_minutes BETWEEN 0 AND 1440)`
- `CONSTRAINT ck_dataset_sync_policy_lookback CHECK (lookback_seconds >= 0)`
- `CONSTRAINT ck_dataset_sync_policy_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `dataset_validation_policy`

Dataset validation override; service forbids checksum without a real business key

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `dataset_id bigint PRIMARY KEY REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 2 | `method varchar(32) NOT NULL DEFAULT 'ROW_COUNT'` |
| 3 | `tolerance numeric(20,6) NOT NULL DEFAULT 0` |
| 4 | `blocking_enabled boolean NOT NULL DEFAULT true` |
| 5 | `lookback_seconds integer NOT NULL DEFAULT 0` |
| 6 | `auto_recheck_enabled boolean NOT NULL DEFAULT false` |
| 7 | `revision bigint NOT NULL DEFAULT 0` |
| 8 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 9 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_dataset_validation_policy_method CHECK (method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))`
- `CONSTRAINT ck_dataset_validation_policy_tolerance CHECK (tolerance = 0)`
- `CONSTRAINT ck_dataset_validation_policy_lookback CHECK (lookback_seconds = 0)`
- `CONSTRAINT ck_dataset_validation_policy_recheck CHECK (auto_recheck_enabled = false)`
- `CONSTRAINT ck_dataset_validation_policy_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `dataset_message_policy`

Dataset-level RabbitMQ policy; no task override or transport switch

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `dataset_id bigint PRIMARY KEY REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 2 | `enabled boolean NOT NULL DEFAULT false` |
| 3 | `source_system varchar(128)` |
| 4 | `tenant_code varchar(128)` |
| 5 | `routing_key varchar(255)` |
| 6 | `topic varchar(255)` |
| 7 | `message_key_template varchar(1000)` |
| 8 | `rate_limit_per_second integer NOT NULL DEFAULT 1000` |
| 9 | `page_size integer NOT NULL DEFAULT 5000` |
| 10 | `revision bigint NOT NULL DEFAULT 0` |
| 11 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 12 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_dataset_message_policy_limits CHECK (rate_limit_per_second BETWEEN 1 AND 100000 AND page_size BETWEEN 1 AND 100000)`
- `CONSTRAINT ck_dataset_message_policy_enabled_fields CHECK ( enabled = false OR (source_system IS NOT NULL AND tenant_code IS NOT NULL AND routing_key IS NOT NULL AND topic IS NOT NULL AND message_key_template IS NOT NULL) )`
- `CONSTRAINT ck_dataset_message_policy_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `doris_table_contract`

Expected ODS/RAW contract generated from one immutable dataset version; actual Doris metadata is read live

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `target_datasource_id bigint NOT NULL REFERENCES df_etl.target_datasource(id) ON DELETE RESTRICT` |
| 3 | `dataset_version_id bigint NOT NULL REFERENCES df_etl.standard_dataset_version(id) ON DELETE RESTRICT` |
| 4 | `ods_database varchar(128) NOT NULL` |
| 5 | `ods_table varchar(128) NOT NULL` |
| 6 | `raw_database varchar(128) NOT NULL` |
| 7 | `raw_table varchar(128) NOT NULL` |
| 8 | `key_model varchar(24) NOT NULL` |
| 9 | `partition_model varchar(32) NOT NULL` |
| 10 | `distribution_model varchar(24) NOT NULL` |
| 11 | `expected_schema_hash varchar(128) NOT NULL` |
| 12 | `expected_ddl_hash varchar(128) NOT NULL` |
| 13 | `expected_ddl_snapshot jsonb NOT NULL` |
| 14 | `contract_status varchar(20) NOT NULL DEFAULT 'EXPECTED'` |
| 15 | `last_checked_at timestamptz` |
| 16 | `last_check_error text` |
| 17 | `revision bigint NOT NULL DEFAULT 0` |
| 18 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 19 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 20 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 21 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_doris_table_contract_target_version UNIQUE (target_datasource_id, dataset_version_id)`
- `CONSTRAINT ck_doris_table_contract_names CHECK (btrim(ods_database) <> '' AND btrim(ods_table) <> '' AND btrim(raw_database) <> '' AND btrim(raw_table) <> '')`
- `CONSTRAINT ck_doris_table_contract_key_model CHECK (key_model IN ('UNIQUE_KEY','DUPLICATE_KEY'))`
- `CONSTRAINT ck_doris_table_contract_partition CHECK (partition_model IN ('LIST_BY_INSTITUTION','NONE'))`
- `CONSTRAINT ck_doris_table_contract_distribution CHECK (distribution_model IN ('RANDOM','HASH_BUSINESS_KEY'))`
- `CONSTRAINT ck_doris_table_contract_status CHECK (contract_status IN ('EXPECTED','MATCHED','MISMATCH','MISSING'))`
- `CONSTRAINT ck_doris_table_contract_snapshot CHECK (jsonb_typeof(expected_ddl_snapshot) = 'object')`
- `CONSTRAINT ck_doris_table_contract_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_doris_table_contract_status ON df_etl.doris_table_contract(target_datasource_id, contract_status, id);`
- `CREATE INDEX idx_doris_table_contract_dataset ON df_etl.doris_table_contract(dataset_version_id, target_datasource_id);`

#### `doris_institution_partition`

Stable institution-to-formal-LIST-partition binding for shared ODS tables

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `doris_table_contract_id bigint NOT NULL REFERENCES df_etl.doris_table_contract(id) ON DELETE RESTRICT` |
| 3 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 4 | `institution_code_snapshot varchar(64) NOT NULL` |
| 5 | `formal_partition_name varchar(64) NOT NULL` |
| 6 | `partition_value varchar(128) NOT NULL` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'EXPECTED'` |
| 8 | `last_row_count bigint` |
| 9 | `last_visible_version varchar(128)` |
| 10 | `last_checked_at timestamptz` |
| 11 | `revision bigint NOT NULL DEFAULT 0` |

**表内约束**

- `CONSTRAINT uq_doris_partition_contract_institution UNIQUE (doris_table_contract_id, institution_id)`
- `CONSTRAINT uq_doris_partition_contract_name UNIQUE (doris_table_contract_id, formal_partition_name)`
- `CONSTRAINT uq_doris_partition_contract_value UNIQUE (doris_table_contract_id, partition_value)`
- `CONSTRAINT uq_doris_partition_id_contract UNIQUE (id, doris_table_contract_id)`
- `CONSTRAINT uq_doris_partition_replace_identity UNIQUE (id, doris_table_contract_id, formal_partition_name)`
- `CONSTRAINT ck_doris_partition_code_nonblank CHECK (btrim(institution_code_snapshot) <> '')`
- `CONSTRAINT ck_doris_partition_name_shape CHECK (formal_partition_name ~ '^p_i_[0-9a-f]{16}$')`
- `CONSTRAINT ck_doris_partition_value_nonblank CHECK (btrim(partition_value) <> '')`
- `CONSTRAINT ck_doris_partition_status CHECK (status IN ('EXPECTED','PRESENT','MISMATCH','MISSING'))`
- `CONSTRAINT ck_doris_partition_row_count CHECK (last_row_count IS NULL OR last_row_count >= 0)`
- `CONSTRAINT ck_doris_partition_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_doris_partition_institution ON df_etl.doris_institution_partition(institution_id, doris_table_contract_id);`
- `CREATE INDEX idx_doris_partition_status ON df_etl.doris_institution_partition(doris_table_contract_id, status);`

#### `doris_table_operation`

Explicit Doris create/rebuild/partition-maintenance command history; normal sync never creates or alters tables

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `doris_table_contract_id bigint NOT NULL REFERENCES df_etl.doris_table_contract(id) ON DELETE RESTRICT` |
| 3 | `operation varchar(24) NOT NULL` |
| 4 | `targets jsonb NOT NULL` |
| 5 | `expected_definition_hash varchar(128) NOT NULL` |
| 6 | `ddl_snapshot text NOT NULL` |
| 7 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 8 | `requested_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 9 | `requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `started_at timestamptz` |
| 11 | `finished_at timestamptz` |
| 12 | `result_summary jsonb` |
| 13 | `failure_code varchar(128)` |
| 14 | `failure_message text` |
| 15 | `reason text NOT NULL` |

**表内约束**

- `CONSTRAINT ck_doris_table_operation_type CHECK (operation IN ('REFRESH','CREATE','REBUILD','MAINTAIN_PARTITIONS'))`
- `CONSTRAINT ck_doris_table_operation_targets CHECK (jsonb_typeof(targets) = 'array' AND jsonb_array_length(targets) > 0)`
- `CONSTRAINT ck_doris_table_operation_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','STATE_UNKNOWN'))`
- `CONSTRAINT ck_doris_table_operation_result CHECK (result_summary IS NULL OR jsonb_typeof(result_summary) = 'object')`
- `CONSTRAINT ck_doris_table_operation_times CHECK ( (started_at IS NULL OR started_at >= requested_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE INDEX idx_doris_table_operation_contract_requested ON df_etl.doris_table_operation(doris_table_contract_id, requested_at DESC);`
- `CREATE INDEX idx_doris_table_operation_status ON df_etl.doris_table_operation(status, requested_at);`

### 4.4 采集链路与字段解析

#### `collection_route`

Stable shared collection-route identity; no enabled state and no independent structure gate

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `dataset_id bigint NOT NULL REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 3 | `business_system_instance_id bigint NOT NULL REFERENCES df_etl.business_system_instance(id) ON DELETE RESTRICT` |
| 4 | `source_datasource_id bigint NOT NULL REFERENCES df_etl.source_datasource(id) ON DELETE RESTRICT` |
| 5 | `source_schema varchar(128)` |
| 6 | `source_object varchar(256) NOT NULL` |
| 7 | `source_object_type varchar(32) NOT NULL` |
| 8 | `target_datasource_id bigint NOT NULL REFERENCES df_etl.target_datasource(id) ON DELETE RESTRICT` |
| 9 | `current_version_id bigint` |
| 10 | `deleted_at timestamptz` |
| 11 | `deleted_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 12 | `revision bigint NOT NULL DEFAULT 0` |
| 13 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `created_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 15 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 16 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_collection_route_id_dataset UNIQUE (id, dataset_id)`
- `CONSTRAINT uq_collection_route_id_instance UNIQUE (id, business_system_instance_id)`
- `CONSTRAINT fk_collection_route_instance_source FOREIGN KEY (business_system_instance_id, source_datasource_id) REFERENCES df_etl.business_system_instance_datasource(instance_id, source_datasource_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_collection_route_object_nonblank CHECK (btrim(source_object) <> '')`
- `CONSTRAINT ck_collection_route_object_type CHECK (source_object_type IN ('TABLE','VIEW','MATERIALIZED_VIEW'))`
- `CONSTRAINT ck_collection_route_revision CHECK (revision >= 0)`
- `CONSTRAINT ck_collection_route_delete_actor CHECK ((deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL))`

**显式索引**

- `CREATE UNIQUE INDEX uq_collection_route_business_key_active ON df_etl.collection_route( business_system_instance_id, source_datasource_id, dataset_id, lower(coalesce(source_schema, '')), lower(source_object) ) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_collection_route_dataset_active ON df_etl.collection_route(dataset_id, id) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_collection_route_instance_active ON df_etl.collection_route(business_system_instance_id, id) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_collection_route_source_active ON df_etl.collection_route(source_datasource_id, id) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_collection_route_target_active ON df_etl.collection_route(target_datasource_id, id) WHERE deleted_at IS NULL;`

#### `collection_route_institution`

Current institution coverage of a shared collection route

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `route_id bigint NOT NULL` |
| 2 | `business_system_instance_id bigint NOT NULL` |
| 3 | `institution_id bigint NOT NULL` |
| 4 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 5 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (route_id, institution_id)`
- `CONSTRAINT fk_route_institution_route_instance FOREIGN KEY (route_id, business_system_instance_id) REFERENCES df_etl.collection_route(id, business_system_instance_id) ON DELETE CASCADE`
- `CONSTRAINT fk_route_institution_instance_coverage FOREIGN KEY (business_system_instance_id, institution_id) REFERENCES df_etl.business_system_instance_institution(instance_id, institution_id) ON DELETE RESTRICT`

**显式索引**

- `CREATE INDEX idx_route_institution_reverse ON df_etl.collection_route_institution(institution_id, route_id);`
- `CREATE INDEX idx_route_institution_instance ON df_etl.collection_route_institution(business_system_instance_id, institution_id, route_id);`

#### `collection_route_version`

Immutable route contract and external-resource snapshot

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `route_id bigint NOT NULL REFERENCES df_etl.collection_route(id) ON DELETE RESTRICT` |
| 3 | `version_no integer NOT NULL` |
| 4 | `dataset_id bigint NOT NULL` |
| 5 | `dataset_version_id bigint NOT NULL` |
| 6 | `business_system_instance_id bigint NOT NULL REFERENCES df_etl.business_system_instance(id) ON DELETE RESTRICT` |
| 7 | `source_datasource_id bigint NOT NULL REFERENCES df_etl.source_datasource(id) ON DELETE RESTRICT` |
| 8 | `source_schema_snapshot varchar(128)` |
| 9 | `source_object_snapshot varchar(256) NOT NULL` |
| 10 | `source_object_type_snapshot varchar(32) NOT NULL` |
| 11 | `target_datasource_id bigint NOT NULL REFERENCES df_etl.target_datasource(id) ON DELETE RESTRICT` |
| 12 | `ods_database_snapshot varchar(128) NOT NULL` |
| 13 | `ods_table_snapshot varchar(128) NOT NULL` |
| 14 | `raw_database_snapshot varchar(128) NOT NULL` |
| 15 | `raw_table_snapshot varchar(128) NOT NULL` |
| 16 | `source_structure_hash varchar(128) NOT NULL` |
| 17 | `contract_hash varchar(128) NOT NULL` |
| 18 | `route_contract_snapshot jsonb NOT NULL` |
| 19 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 20 | `created_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_collection_route_version_no UNIQUE (route_id, version_no)`
- `CONSTRAINT uq_collection_route_version_hash UNIQUE (route_id, contract_hash)`
- `CONSTRAINT uq_collection_route_version_id_route UNIQUE (id, route_id)`
- `CONSTRAINT uq_collection_route_version_id_dataset UNIQUE (id, dataset_version_id)`
- `CONSTRAINT uq_collection_route_version_precheck_snapshot UNIQUE (id, route_id, dataset_version_id)`
- `CONSTRAINT fk_collection_route_version_route_dataset FOREIGN KEY (route_id, dataset_id) REFERENCES df_etl.collection_route(id, dataset_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_collection_route_version_dataset FOREIGN KEY (dataset_version_id, dataset_id) REFERENCES df_etl.standard_dataset_version(id, dataset_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_collection_route_version_no CHECK (version_no > 0)`
- `CONSTRAINT ck_collection_route_version_object CHECK (btrim(source_object_snapshot) <> '')`
- `CONSTRAINT ck_collection_route_version_object_type CHECK (source_object_type_snapshot IN ('TABLE','VIEW','MATERIALIZED_VIEW'))`
- `CONSTRAINT ck_collection_route_version_names CHECK ( btrim(ods_database_snapshot) <> '' AND btrim(ods_table_snapshot) <> '' AND btrim(raw_database_snapshot) <> '' AND btrim(raw_table_snapshot) <> '' )`
- `CONSTRAINT ck_collection_route_version_hashes CHECK (btrim(source_structure_hash) <> '' AND btrim(contract_hash) <> '')`
- `CONSTRAINT ck_collection_route_version_snapshot CHECK (jsonb_typeof(route_contract_snapshot) = 'object')`

**显式索引**

- `CREATE INDEX idx_collection_route_version_route_created ON df_etl.collection_route_version(route_id, created_at DESC);`
- `CREATE INDEX idx_collection_route_version_dataset ON df_etl.collection_route_version(dataset_version_id, id);`

#### `collection_route_version_institution`

Immutable route-version institution coverage snapshot

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `route_version_id bigint NOT NULL REFERENCES df_etl.collection_route_version(id) ON DELETE RESTRICT` |
| 2 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 3 | `institution_code_snapshot varchar(64) NOT NULL` |
| 4 | `institution_name_snapshot varchar(300) NOT NULL` |

**表内约束**

- `PRIMARY KEY (route_version_id, institution_id)`
- `CONSTRAINT ck_route_version_institution_code CHECK (btrim(institution_code_snapshot) <> '')`
- `CONSTRAINT ck_route_version_institution_name CHECK (btrim(institution_name_snapshot) <> '')`

**显式索引**

- `CREATE INDEX idx_route_version_institution_reverse ON df_etl.collection_route_version_institution(institution_id, route_version_id);`

#### `route_field_resolution`

Immutable standard-field to actual-JDBC-field resolution; no aliases or edit expressions

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `route_version_id bigint NOT NULL REFERENCES df_etl.collection_route_version(id) ON DELETE RESTRICT` |
| 3 | `dataset_field_id bigint` |
| 4 | `dataset_version_id bigint NOT NULL` |
| 5 | `standard_field_code varchar(100) NOT NULL` |
| 6 | `standard_field_name varchar(300)` |
| 7 | `standard_ordinal_no integer` |
| 8 | `jdbc_field_name varchar(256)` |
| 9 | `jdbc_type_code integer` |
| 10 | `jdbc_type_name varchar(128)` |
| 11 | `jdbc_nullable boolean` |
| 12 | `jdbc_ordinal_no integer` |
| 13 | `doris_field_name varchar(100)` |
| 14 | `conversion_contract_id bigint REFERENCES df_etl.field_conversion_contract(id) ON DELETE RESTRICT` |
| 15 | `match_status varchar(24) NOT NULL` |
| 16 | `diagnostic_code varchar(128)` |
| 17 | `diagnostic_message text` |

**表内约束**

- `CONSTRAINT fk_route_field_resolution_route_dataset FOREIGN KEY (route_version_id, dataset_version_id) REFERENCES df_etl.collection_route_version(id, dataset_version_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_route_field_resolution_dataset_field FOREIGN KEY (dataset_field_id, dataset_version_id) REFERENCES df_etl.standard_dataset_field(id, dataset_version_id) ON DELETE RESTRICT`
- `CONSTRAINT uq_route_field_resolution_standard UNIQUE (route_version_id, standard_field_code)`
- `CONSTRAINT ck_route_field_resolution_standard_code CHECK (btrim(standard_field_code) <> '')`
- `CONSTRAINT ck_route_field_resolution_status CHECK (match_status IN ('MATCHED','MISSING','AMBIGUOUS','EXTRA','TYPE_UNSUPPORTED'))`
- `CONSTRAINT ck_route_field_resolution_matched CHECK ( match_status <> 'MATCHED' OR (dataset_field_id IS NOT NULL AND jdbc_field_name IS NOT NULL AND jdbc_ordinal_no IS NOT NULL AND doris_field_name = lower(standard_field_code) AND conversion_contract_id IS NOT NULL) )`
- `CONSTRAINT ck_route_field_resolution_ordinals CHECK ( (standard_ordinal_no IS NULL OR standard_ordinal_no > 0) AND (jdbc_ordinal_no IS NULL OR jdbc_ordinal_no > 0) )`

**显式索引**

- `CREATE UNIQUE INDEX uq_route_field_resolution_standard_ci ON df_etl.route_field_resolution(route_version_id, lower(standard_field_code));`
- `CREATE UNIQUE INDEX uq_route_field_resolution_jdbc_ci ON df_etl.route_field_resolution(route_version_id, lower(jdbc_field_name)) WHERE match_status = 'MATCHED';`
- `CREATE INDEX idx_route_field_resolution_status ON df_etl.route_field_resolution(route_version_id, match_status, standard_ordinal_no);`

### 4.5 任务、执行、水位与校验

#### `sync_task`

Stable institution plus dataset task identity; execution contract lives in immutable versions

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 3 | `dataset_id bigint NOT NULL REFERENCES df_etl.standard_dataset(id) ON DELETE RESTRICT` |
| 4 | `route_id bigint NOT NULL` |
| 5 | `name varchar(300) NOT NULL` |
| 6 | `current_version_id bigint` |
| 7 | `schedule_enabled boolean NOT NULL DEFAULT true` |
| 8 | `deleted_at timestamptz` |
| 9 | `deleted_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 10 | `revision bigint NOT NULL DEFAULT 0` |
| 11 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 12 | `created_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 13 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_sync_task_id_dataset UNIQUE (id, dataset_id)`
- `CONSTRAINT uq_sync_task_id_route UNIQUE (id, route_id)`
- `CONSTRAINT uq_sync_task_id_institution UNIQUE (id, institution_id)`
- `CONSTRAINT fk_sync_task_route_institution FOREIGN KEY (route_id, institution_id) REFERENCES df_etl.collection_route_institution(route_id, institution_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_route_dataset FOREIGN KEY (route_id, dataset_id) REFERENCES df_etl.collection_route(id, dataset_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_sync_task_name_nonblank CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_sync_task_revision CHECK (revision >= 0)`
- `CONSTRAINT ck_sync_task_delete_actor CHECK ((deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL))`

**显式索引**

- `CREATE UNIQUE INDEX uq_sync_task_institution_dataset_active ON df_etl.sync_task(institution_id, dataset_id) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_sync_task_route_active ON df_etl.sync_task(route_id, institution_id) WHERE deleted_at IS NULL;`
- `CREATE INDEX idx_sync_task_schedule_active ON df_etl.sync_task(schedule_enabled, id) WHERE deleted_at IS NULL;`

#### `sync_task_version`

Immutable task execution contract; schedule, route version, dataset version and validation are frozen

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `task_id bigint NOT NULL REFERENCES df_etl.sync_task(id) ON DELETE RESTRICT` |
| 3 | `version_no integer NOT NULL` |
| 4 | `route_id bigint NOT NULL` |
| 5 | `route_version_id bigint NOT NULL` |
| 6 | `dataset_id bigint NOT NULL` |
| 7 | `dataset_version_id bigint NOT NULL` |
| 8 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 9 | `institution_code_snapshot varchar(64) NOT NULL` |
| 10 | `task_kind varchar(32) NOT NULL` |
| 11 | `write_mode varchar(40) NOT NULL` |
| 12 | `doris_key_model varchar(24) NOT NULL` |
| 13 | `incremental_field_code varchar(100)` |
| 14 | `fetch_size integer NOT NULL` |
| 15 | `upper_bound_delay_minutes integer NOT NULL DEFAULT 5` |
| 16 | `lookback_seconds integer NOT NULL DEFAULT 0` |
| 17 | `schedule_mode varchar(24) NOT NULL` |
| 18 | `interval_hours integer` |
| 19 | `cron_expression varchar(200)` |
| 20 | `timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai'` |
| 21 | `validation_method varchar(32) NOT NULL` |
| 22 | `validation_blocking boolean NOT NULL DEFAULT true` |
| 23 | `field_contract_hash varchar(128) NOT NULL` |
| 24 | `contract_hash varchar(128) NOT NULL` |
| 25 | `effective_contract_snapshot jsonb NOT NULL` |
| 26 | `change_summary varchar(1000) NOT NULL` |
| 27 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 28 | `created_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_sync_task_version_no UNIQUE (task_id, version_no)`
- `CONSTRAINT uq_sync_task_version_hash UNIQUE (task_id, contract_hash)`
- `CONSTRAINT uq_sync_task_version_id_task UNIQUE (id, task_id)`
- `CONSTRAINT uq_sync_task_version_execution_snapshot UNIQUE (id, task_id, route_version_id, dataset_version_id, institution_id)`
- `CONSTRAINT fk_sync_task_version_task_dataset FOREIGN KEY (task_id, dataset_id) REFERENCES df_etl.sync_task(id, dataset_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_version_task_route FOREIGN KEY (task_id, route_id) REFERENCES df_etl.sync_task(id, route_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_version_task_institution FOREIGN KEY (task_id, institution_id) REFERENCES df_etl.sync_task(id, institution_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_version_route_institution FOREIGN KEY (route_version_id, institution_id) REFERENCES df_etl.collection_route_version_institution(route_version_id, institution_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_version_route_version FOREIGN KEY (route_version_id, route_id) REFERENCES df_etl.collection_route_version(id, route_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_sync_task_version_dataset_version FOREIGN KEY (dataset_version_id, dataset_id) REFERENCES df_etl.standard_dataset_version(id, dataset_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_sync_task_version_no CHECK (version_no > 0)`
- `CONSTRAINT ck_sync_task_version_kind CHECK (task_kind IN ('FULL_ONLY','FULL_THEN_INCREMENTAL'))`
- `CONSTRAINT ck_sync_task_version_write_mode CHECK (write_mode IN ('UPSERT','REPLACE_INSTITUTION_SCOPE'))`
- `CONSTRAINT ck_sync_task_version_key_model CHECK (doris_key_model IN ('UNIQUE_KEY','DUPLICATE_KEY'))`
- `CONSTRAINT ck_sync_task_version_combination CHECK ( (task_kind = 'FULL_ONLY' AND write_mode = 'REPLACE_INSTITUTION_SCOPE' AND doris_key_model = 'DUPLICATE_KEY' AND incremental_field_code IS NULL) OR (task_kind = 'FULL_THEN_INCREMENTAL' AND write_mode = 'UPSERT' AND doris_key_model = 'UNIQUE_KEY' AND incremental_field_code IS NOT NULL) OR (task_kind = 'FULL_ONLY' AND write_mode = 'UPSERT' AND doris_key_model = 'UNIQUE_KEY' AND incremental_field_code IS NULL) )`
- `CONSTRAINT ck_sync_task_version_fetch CHECK (fetch_size BETWEEN 1 AND 1000000)`
- `CONSTRAINT ck_sync_task_version_delay CHECK (upper_bound_delay_minutes BETWEEN 0 AND 1440)`
- `CONSTRAINT ck_sync_task_version_lookback CHECK (lookback_seconds >= 0)`
- `CONSTRAINT ck_sync_task_version_schedule_mode CHECK (schedule_mode IN ('EVERY_N_HOURS','CRON'))`
- `CONSTRAINT ck_sync_task_version_schedule CHECK ( (schedule_mode = 'EVERY_N_HOURS' AND interval_hours BETWEEN 1 AND 168 AND cron_expression IS NULL) OR (schedule_mode = 'CRON' AND interval_hours IS NULL AND cron_expression IS NOT NULL AND btrim(cron_expression) <> '') )`
- `CONSTRAINT ck_sync_task_version_validation CHECK (validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))`
- `CONSTRAINT ck_sync_task_version_snapshot CHECK (jsonb_typeof(effective_contract_snapshot) = 'object')`
- `CONSTRAINT ck_sync_task_version_change_summary CHECK (btrim(change_summary) <> '')`

**显式索引**

- `CREATE INDEX idx_sync_task_version_task_created ON df_etl.sync_task_version(task_id, created_at DESC);`
- `CREATE INDEX idx_sync_task_version_route_version ON df_etl.sync_task_version(route_version_id, task_id);`

#### `task_governance_override`

Mutable task-level scheduling/validation overrides; message settings are intentionally absent

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `task_id bigint PRIMARY KEY REFERENCES df_etl.sync_task(id) ON DELETE CASCADE` |
| 2 | `validation_method_override varchar(32)` |
| 3 | `validation_blocking_override boolean` |
| 4 | `fetch_size_override integer` |
| 5 | `upper_bound_delay_minutes_override integer` |
| 6 | `lookback_seconds_override integer` |
| 7 | `schedule_mode_override varchar(24)` |
| 8 | `interval_hours_override integer` |
| 9 | `cron_expression_override varchar(200)` |
| 10 | `timezone_override varchar(64)` |
| 11 | `revision bigint NOT NULL DEFAULT 0` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 13 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT ck_task_governance_validation CHECK (validation_method_override IS NULL OR validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))`
- `CONSTRAINT ck_task_governance_fetch CHECK (fetch_size_override IS NULL OR fetch_size_override BETWEEN 1 AND 1000000)`
- `CONSTRAINT ck_task_governance_delay CHECK (upper_bound_delay_minutes_override IS NULL OR upper_bound_delay_minutes_override BETWEEN 0 AND 1440)`
- `CONSTRAINT ck_task_governance_lookback CHECK (lookback_seconds_override IS NULL OR lookback_seconds_override >= 0)`
- `CONSTRAINT ck_task_governance_schedule_mode CHECK (schedule_mode_override IS NULL OR schedule_mode_override IN ('EVERY_N_HOURS','CRON'))`
- `CONSTRAINT ck_task_governance_schedule CHECK ( schedule_mode_override IS NULL OR (schedule_mode_override = 'EVERY_N_HOURS' AND interval_hours_override BETWEEN 1 AND 168 AND cron_expression_override IS NULL) OR (schedule_mode_override = 'CRON' AND interval_hours_override IS NULL AND cron_expression_override IS NOT NULL AND btrim(cron_expression_override) <> '') )`
- `CONSTRAINT ck_task_governance_revision CHECK (revision >= 0)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `sync_execution`

Immutable execution context plus mutable status; no cross-execution checkpoint or retry self-reference

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `execution_uuid uuid NOT NULL` |
| 3 | `task_id bigint NOT NULL REFERENCES df_etl.sync_task(id) ON DELETE RESTRICT` |
| 4 | `task_version_id bigint NOT NULL` |
| 5 | `route_version_id bigint NOT NULL REFERENCES df_etl.collection_route_version(id) ON DELETE RESTRICT` |
| 6 | `dataset_version_id bigint NOT NULL REFERENCES df_etl.standard_dataset_version(id) ON DELETE RESTRICT` |
| 7 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 8 | `operation_type varchar(24) NOT NULL` |
| 9 | `trigger_type varchar(24) NOT NULL` |
| 10 | `status varchar(24) NOT NULL DEFAULT 'PENDING'` |
| 11 | `schedule_fire_time timestamptz` |
| 12 | `scope_type varchar(24) NOT NULL` |
| 13 | `window_lower timestamptz` |
| 14 | `window_upper timestamptz` |
| 15 | `key_lower jsonb` |
| 16 | `key_upper jsonb` |
| 17 | `source_snapshot jsonb NOT NULL` |
| 18 | `target_snapshot jsonb NOT NULL` |
| 19 | `range_snapshot jsonb NOT NULL` |
| 20 | `effective_config_snapshot jsonb NOT NULL` |
| 21 | `validation_policy_snapshot jsonb NOT NULL` |
| 22 | `message_policy_snapshot jsonb NOT NULL` |
| 23 | `source_row_count bigint` |
| 24 | `target_row_count bigint` |
| 25 | `loaded_row_count bigint` |
| 26 | `rejected_row_count bigint NOT NULL DEFAULT 0` |
| 27 | `batch_count integer NOT NULL DEFAULT 0` |
| 28 | `engine_job_id varchar(200)` |
| 29 | `current_stage varchar(64)` |
| 30 | `progress_percent numeric(5,2)` |
| 31 | `failure_code varchar(128)` |
| 32 | `failure_message text` |
| 33 | `cancel_requested_at timestamptz` |
| 34 | `cancel_requested_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 35 | `started_at timestamptz` |
| 36 | `finished_at timestamptz` |
| 37 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 38 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_sync_execution_uuid UNIQUE (execution_uuid)`
- `CONSTRAINT uq_sync_execution_id_task UNIQUE (id, task_id)`
- `CONSTRAINT uq_sync_execution_validation_snapshot UNIQUE (id, task_id, task_version_id)`
- `CONSTRAINT fk_sync_execution_task_version FOREIGN KEY (task_version_id, task_id, route_version_id, dataset_version_id, institution_id) REFERENCES df_etl.sync_task_version(id, task_id, route_version_id, dataset_version_id, institution_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_sync_execution_operation CHECK (operation_type IN ('NORMAL','RECOLLECT','BACKFILL_TIME','BACKFILL_KEY'))`
- `CONSTRAINT ck_sync_execution_trigger CHECK (trigger_type IN ('SCHEDULED','MANUAL','EXTERNAL_API','INITIAL_INCREMENT','SYSTEM_RECOVERY'))`
- `CONSTRAINT ck_sync_execution_status CHECK (status IN ('PENDING','RUNNING','LOADING','VALIDATING','SUCCEEDED','FAILED','CANCELLED','STATE_UNKNOWN'))`
- `CONSTRAINT ck_sync_execution_scope CHECK (scope_type IN ('FULL','INCREMENTAL_WINDOW','BACKFILL_TIME','BACKFILL_KEY'))`
- `CONSTRAINT ck_sync_execution_operation_scope CHECK ( (operation_type IN ('NORMAL','RECOLLECT') AND scope_type IN ('FULL','INCREMENTAL_WINDOW')) OR (operation_type = 'BACKFILL_TIME' AND scope_type = 'BACKFILL_TIME') OR (operation_type = 'BACKFILL_KEY' AND scope_type = 'BACKFILL_KEY') )`
- `CONSTRAINT ck_sync_execution_window CHECK ( (scope_type IN ('INCREMENTAL_WINDOW','BACKFILL_TIME') AND window_lower IS NOT NULL AND window_upper IS NOT NULL AND window_lower < window_upper) OR (scope_type NOT IN ('INCREMENTAL_WINDOW','BACKFILL_TIME') AND window_lower IS NULL AND window_upper IS NULL) )`
- `CONSTRAINT ck_sync_execution_key_range CHECK ( (scope_type = 'BACKFILL_KEY' AND key_lower IS NOT NULL AND key_upper IS NOT NULL) OR (scope_type <> 'BACKFILL_KEY' AND key_lower IS NULL AND key_upper IS NULL) )`
- `CONSTRAINT ck_sync_execution_snapshots CHECK ( jsonb_typeof(source_snapshot) = 'object' AND jsonb_typeof(target_snapshot) = 'object' AND jsonb_typeof(range_snapshot) = 'object' AND jsonb_typeof(effective_config_snapshot) = 'object' AND jsonb_typeof(validation_policy_snapshot) = 'object' AND jsonb_typeof(message_policy_snapshot) = 'object' )`
- `CONSTRAINT ck_sync_execution_counts CHECK ( (source_row_count IS NULL OR source_row_count >= 0) AND (target_row_count IS NULL OR target_row_count >= 0) AND (loaded_row_count IS NULL OR loaded_row_count >= 0) AND rejected_row_count >= 0 AND batch_count >= 0 )`
- `CONSTRAINT ck_sync_execution_progress CHECK (progress_percent IS NULL OR progress_percent BETWEEN 0 AND 100)`
- `CONSTRAINT ck_sync_execution_cancel_actor CHECK ((cancel_requested_at IS NULL AND cancel_requested_by IS NULL) OR (cancel_requested_at IS NOT NULL AND cancel_requested_by IS NOT NULL))`
- `CONSTRAINT ck_sync_execution_times CHECK ( (started_at IS NULL OR started_at >= created_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE UNIQUE INDEX uq_sync_execution_active_task ON df_etl.sync_execution(task_id) WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING','STATE_UNKNOWN');`
- `CREATE INDEX idx_sync_execution_task_created ON df_etl.sync_execution(task_id, created_at DESC);`
- `CREATE INDEX idx_sync_execution_status_created ON df_etl.sync_execution(status, created_at);`
- `CREATE INDEX idx_sync_execution_task_version ON df_etl.sync_execution(task_version_id, created_at DESC);`
- `CREATE INDEX idx_sync_execution_route_version ON df_etl.sync_execution(route_version_id, created_at DESC);`

#### `load_batch`

One execution batch with deterministic Doris Label and final probe state; not a future execution checkpoint

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `execution_id bigint NOT NULL REFERENCES df_etl.sync_execution(id) ON DELETE RESTRICT` |
| 3 | `batch_no integer NOT NULL` |
| 4 | `status varchar(24) NOT NULL DEFAULT 'PENDING'` |
| 5 | `cursor_lower jsonb` |
| 6 | `cursor_upper jsonb` |
| 7 | `time_lower timestamptz` |
| 8 | `time_upper timestamptz` |
| 9 | `institution_code_snapshot varchar(64) NOT NULL` |
| 10 | `range_snapshot jsonb NOT NULL` |
| 11 | `source_row_count bigint NOT NULL DEFAULT 0` |
| 12 | `loaded_row_count bigint NOT NULL DEFAULT 0` |
| 13 | `rejected_row_count bigint NOT NULL DEFAULT 0` |
| 14 | `payload_checksum varchar(128)` |
| 15 | `doris_label varchar(128) NOT NULL` |
| 16 | `doris_txn_id varchar(128)` |
| 17 | `doris_status varchar(32)` |
| 18 | `doris_probe_status varchar(32)` |
| 19 | `doris_probe_response jsonb` |
| 20 | `submitted_at timestamptz` |
| 21 | `committed_at timestamptz` |
| 22 | `failure_code varchar(128)` |
| 23 | `failure_message text` |
| 24 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 25 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_load_batch_execution_no UNIQUE (execution_id, batch_no)`
- `CONSTRAINT uq_load_batch_doris_label UNIQUE (doris_label)`
- `CONSTRAINT ck_load_batch_no CHECK (batch_no > 0)`
- `CONSTRAINT ck_load_batch_status CHECK (status IN ('PENDING','READING','LOADING','VISIBLE','FAILED','CANCELLED','STATE_UNKNOWN'))`
- `CONSTRAINT ck_load_batch_time_range CHECK ((time_lower IS NULL AND time_upper IS NULL) OR (time_lower IS NOT NULL AND time_upper IS NOT NULL AND time_lower < time_upper))`
- `CONSTRAINT ck_load_batch_institution CHECK (btrim(institution_code_snapshot) <> '')`
- `CONSTRAINT ck_load_batch_range_snapshot CHECK (jsonb_typeof(range_snapshot) = 'object')`
- `CONSTRAINT ck_load_batch_counts CHECK (source_row_count >= 0 AND loaded_row_count >= 0 AND rejected_row_count >= 0)`
- `CONSTRAINT ck_load_batch_probe_response CHECK (doris_probe_response IS NULL OR jsonb_typeof(doris_probe_response) = 'object')`
- `CONSTRAINT ck_load_batch_times CHECK (committed_at IS NULL OR submitted_at IS NULL OR committed_at >= submitted_at)`

**显式索引**

- `CREATE INDEX idx_load_batch_execution_status_no ON df_etl.load_batch(execution_id, status, batch_no);`
- `CREATE INDEX idx_load_batch_status_updated ON df_etl.load_batch(status, updated_at);`

#### `task_watermark`

Single formal watermark per task; advanced only after all loads and blocking validation succeed

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `task_id bigint PRIMARY KEY REFERENCES df_etl.sync_task(id) ON DELETE RESTRICT` |
| 2 | `watermark_value timestamptz` |
| 3 | `last_success_execution_id bigint` |
| 4 | `revision bigint NOT NULL DEFAULT 0` |
| 5 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 6 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT fk_task_watermark_execution FOREIGN KEY (last_success_execution_id, task_id) REFERENCES df_etl.sync_execution(id, task_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_task_watermark_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_task_watermark_last_execution ON df_etl.task_watermark(last_success_execution_id);`

#### `validation_run`

Unified validation run; technical status and PASS/MISMATCH business result are separate

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `run_uuid uuid NOT NULL` |
| 3 | `execution_id bigint REFERENCES df_etl.sync_execution(id) ON DELETE RESTRICT` |
| 4 | `task_id bigint NOT NULL REFERENCES df_etl.sync_task(id) ON DELETE RESTRICT` |
| 5 | `task_version_id bigint NOT NULL` |
| 6 | `validation_type varchar(32) NOT NULL` |
| 7 | `trigger_type varchar(24) NOT NULL` |
| 8 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 9 | `result varchar(16)` |
| 10 | `policy_snapshot jsonb NOT NULL` |
| 11 | `range_snapshot jsonb NOT NULL` |
| 12 | `protocol_snapshot jsonb NOT NULL` |
| 13 | `source_row_count bigint` |
| 14 | `target_row_count bigint` |
| 15 | `source_checksum varchar(256)` |
| 16 | `target_checksum varchar(256)` |
| 17 | `difference_summary jsonb NOT NULL DEFAULT '[]'::jsonb` |
| 18 | `failure_code varchar(128)` |
| 19 | `failure_message text` |
| 20 | `started_at timestamptz` |
| 21 | `finished_at timestamptz` |
| 22 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 23 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_validation_run_uuid UNIQUE (run_uuid)`
- `CONSTRAINT fk_validation_run_task_version FOREIGN KEY (task_version_id, task_id) REFERENCES df_etl.sync_task_version(id, task_id) ON DELETE RESTRICT`
- `CONSTRAINT fk_validation_run_execution_snapshot FOREIGN KEY (execution_id, task_id, task_version_id) REFERENCES df_etl.sync_execution(id, task_id, task_version_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_validation_run_type CHECK (validation_type IN ('ROW_COUNT','ROW_COUNT_CHECKSUM','DELETE_RECONCILIATION','DORIS_SCOPE_PRE_SWITCH','DORIS_SCOPE_POST_SWITCH'))`
- `CONSTRAINT ck_validation_run_trigger CHECK (trigger_type IN ('SYNC_GATE','MANUAL','MANUAL_RECHECK','SYSTEM'))`
- `CONSTRAINT ck_validation_run_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED'))`
- `CONSTRAINT ck_validation_run_result CHECK ( (status = 'COMPLETED' AND result IN ('PASS','MISMATCH')) OR (status <> 'COMPLETED' AND result IS NULL) )`
- `CONSTRAINT ck_validation_run_execution_gate CHECK (trigger_type <> 'SYNC_GATE' OR execution_id IS NOT NULL)`
- `CONSTRAINT ck_validation_run_snapshots CHECK ( jsonb_typeof(policy_snapshot) = 'object' AND jsonb_typeof(range_snapshot) = 'object' AND jsonb_typeof(protocol_snapshot) = 'object' AND jsonb_typeof(difference_summary) = 'array' )`
- `CONSTRAINT ck_validation_run_counts CHECK ((source_row_count IS NULL OR source_row_count >= 0) AND (target_row_count IS NULL OR target_row_count >= 0))`
- `CONSTRAINT ck_validation_run_times CHECK ( (started_at IS NULL OR started_at >= created_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE UNIQUE INDEX uq_validation_sync_gate ON df_etl.validation_run(execution_id, validation_type) WHERE trigger_type = 'SYNC_GATE';`
- `CREATE INDEX idx_validation_run_task_created ON df_etl.validation_run(task_id, created_at DESC);`
- `CREATE INDEX idx_validation_run_execution ON df_etl.validation_run(execution_id, created_at DESC) WHERE execution_id IS NOT NULL;`
- `CREATE INDEX idx_validation_run_status_created ON df_etl.validation_run(status, created_at);`

#### `delete_apply_run`

Auditable delete-reconciliation dry-run/apply command; apply must reference a successful dry run

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `validation_run_id bigint NOT NULL REFERENCES df_etl.validation_run(id) ON DELETE RESTRICT` |
| 3 | `operation varchar(16) NOT NULL` |
| 4 | `dry_run_id uuid REFERENCES df_etl.delete_apply_run(id) ON DELETE RESTRICT` |
| 5 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 6 | `plan_snapshot jsonb NOT NULL` |
| 7 | `affected_row_count bigint` |
| 8 | `requested_by bigint NOT NULL REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 9 | `requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `started_at timestamptz` |
| 11 | `finished_at timestamptz` |
| 12 | `reason text NOT NULL` |
| 13 | `failure_code varchar(128)` |
| 14 | `failure_message text` |

**表内约束**

- `CONSTRAINT uq_delete_apply_id_validation UNIQUE (id, validation_run_id)`
- `CONSTRAINT fk_delete_apply_dry_run FOREIGN KEY (dry_run_id, validation_run_id) REFERENCES df_etl.delete_apply_run(id, validation_run_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_delete_apply_operation CHECK (operation IN ('DRY_RUN','APPLY'))`
- `CONSTRAINT ck_delete_apply_dry_reference CHECK ( (operation = 'DRY_RUN' AND dry_run_id IS NULL) OR (operation = 'APPLY' AND dry_run_id IS NOT NULL AND dry_run_id <> id) )`
- `CONSTRAINT ck_delete_apply_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','STATE_UNKNOWN'))`
- `CONSTRAINT ck_delete_apply_plan CHECK (jsonb_typeof(plan_snapshot) = 'object')`
- `CONSTRAINT ck_delete_apply_count CHECK (affected_row_count IS NULL OR affected_row_count >= 0)`
- `CONSTRAINT ck_delete_apply_times CHECK ( (started_at IS NULL OR started_at >= requested_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE UNIQUE INDEX uq_delete_apply_effective_apply ON df_etl.delete_apply_run(validation_run_id) WHERE operation = 'APPLY' AND status IN ('PENDING','RUNNING','SUCCEEDED','STATE_UNKNOWN');`
- `CREATE INDEX idx_delete_apply_validation_requested ON df_etl.delete_apply_run(validation_run_id, requested_at DESC);`

#### `message_outbox`

One small RabbitMQ publish command per successful execution; payload rows are reread from Doris

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `event_id uuid NOT NULL` |
| 3 | `execution_id bigint NOT NULL REFERENCES df_etl.sync_execution(id) ON DELETE RESTRICT` |
| 4 | `publish_command jsonb NOT NULL` |
| 5 | `routing_key_snapshot varchar(255) NOT NULL` |
| 6 | `topic_snapshot varchar(255) NOT NULL` |
| 7 | `message_key_template_snapshot varchar(1000) NOT NULL` |
| 8 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 9 | `available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 10 | `attempt_count integer NOT NULL DEFAULT 0` |
| 11 | `last_attempt_at timestamptz` |
| 12 | `published_at timestamptz` |
| 13 | `last_error text` |
| 14 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 15 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_message_outbox_event UNIQUE (event_id)`
- `CONSTRAINT uq_message_outbox_execution UNIQUE (execution_id)`
- `CONSTRAINT ck_message_outbox_command CHECK (jsonb_typeof(publish_command) = 'object')`
- `CONSTRAINT ck_message_outbox_status CHECK (status IN ('PENDING','PUBLISHING','PUBLISHED','DEAD_LETTER'))`
- `CONSTRAINT ck_message_outbox_attempt_count CHECK (attempt_count >= 0)`
- `CONSTRAINT ck_message_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))`

**显式索引**

- `CREATE INDEX idx_message_outbox_pending ON df_etl.message_outbox(status, available_at, id);`
- `CREATE INDEX idx_message_outbox_publishing_timeout ON df_etl.message_outbox(status, last_attempt_at) WHERE status = 'PUBLISHING';`

#### `doris_scope_backup_snapshot`

Short-lived old formal institution range copied to an internal Doris backup table for rollback

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `execution_id bigint NOT NULL UNIQUE REFERENCES df_etl.sync_execution(id) ON DELETE RESTRICT` |
| 3 | `target_datasource_id bigint NOT NULL REFERENCES df_etl.target_datasource(id) ON DELETE RESTRICT` |
| 4 | `dataset_version_id bigint NOT NULL REFERENCES df_etl.standard_dataset_version(id) ON DELETE RESTRICT` |
| 5 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 6 | `backup_table_name varchar(128) NOT NULL` |
| 7 | `backup_execution_key varchar(128) NOT NULL` |
| 8 | `row_count bigint` |
| 9 | `checksum_method varchar(32)` |
| 10 | `checksum_value varchar(256)` |
| 11 | `status varchar(20) NOT NULL DEFAULT 'CREATING'` |
| 12 | `expires_at timestamptz NOT NULL` |
| 13 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 14 | `cleaned_at timestamptz` |
| 15 | `failure_message text` |

**表内约束**

- `CONSTRAINT uq_doris_scope_backup_key UNIQUE (target_datasource_id, dataset_version_id, backup_execution_key)`
- `CONSTRAINT uq_doris_scope_backup_id_execution UNIQUE (id, execution_id)`
- `CONSTRAINT ck_doris_scope_backup_name CHECK (backup_table_name ~ '^__dfetl_scope_backup_[a-z0-9_]+_v[0-9]+$')`
- `CONSTRAINT ck_doris_scope_backup_key_nonblank CHECK (btrim(backup_execution_key) <> '')`
- `CONSTRAINT ck_doris_scope_backup_count CHECK (row_count IS NULL OR row_count >= 0)`
- `CONSTRAINT ck_doris_scope_backup_status CHECK (status IN ('CREATING','AVAILABLE','RESTORING','RESTORED','CLEANED','FAILED'))`
- `CONSTRAINT ck_doris_scope_backup_expiry CHECK (expires_at > created_at)`
- `CONSTRAINT ck_doris_scope_backup_cleaned CHECK (cleaned_at IS NULL OR cleaned_at >= created_at)`

**显式索引**

- `CREATE INDEX idx_doris_scope_backup_status_expiry ON df_etl.doris_scope_backup_snapshot(status, expires_at);`

#### `doris_scope_replace_run`

Execution-scoped LIST partition replacement state, backup, post-switch validation and rollback facts

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `execution_id bigint NOT NULL UNIQUE REFERENCES df_etl.sync_execution(id) ON DELETE RESTRICT` |
| 3 | `table_contract_id bigint NOT NULL REFERENCES df_etl.doris_table_contract(id) ON DELETE RESTRICT` |
| 4 | `partition_binding_id bigint NOT NULL` |
| 5 | `formal_partition_name varchar(64) NOT NULL` |
| 6 | `new_temp_partition_name varchar(64) NOT NULL` |
| 7 | `rollback_temp_partition_name varchar(64)` |
| 8 | `backup_snapshot_id bigint NOT NULL UNIQUE` |
| 9 | `status varchar(32) NOT NULL DEFAULT 'PENDING'` |
| 10 | `fencing_token bigint` |
| 11 | `source_row_count bigint` |
| 12 | `staged_row_count bigint` |
| 13 | `backup_row_count bigint` |
| 14 | `formal_row_count_after_switch bigint` |
| 15 | `pre_switch_validation_id bigint REFERENCES df_etl.validation_run(id) ON DELETE RESTRICT` |
| 16 | `post_switch_validation_id bigint REFERENCES df_etl.validation_run(id) ON DELETE RESTRICT` |
| 17 | `switched_at timestamptz` |
| 18 | `rolled_back_at timestamptz` |
| 19 | `failure_code varchar(128)` |
| 20 | `failure_message text` |
| 21 | `revision bigint NOT NULL DEFAULT 0` |
| 22 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 23 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT fk_doris_scope_replace_partition FOREIGN KEY (partition_binding_id, table_contract_id, formal_partition_name) REFERENCES df_etl.doris_institution_partition(id, doris_table_contract_id, formal_partition_name) ON DELETE RESTRICT`
- `CONSTRAINT fk_doris_scope_replace_backup FOREIGN KEY (backup_snapshot_id, execution_id) REFERENCES df_etl.doris_scope_backup_snapshot(id, execution_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_doris_scope_replace_formal_name CHECK (formal_partition_name ~ '^p_i_[0-9a-f]{16}$')`
- `CONSTRAINT ck_doris_scope_replace_temp_name CHECK (new_temp_partition_name ~ '^tp_n_[0-9a-f]{16}$')`
- `CONSTRAINT ck_doris_scope_replace_rollback_name CHECK (rollback_temp_partition_name IS NULL OR rollback_temp_partition_name ~ '^tp_r_[0-9a-f]{16}$')`
- `CONSTRAINT ck_doris_scope_replace_status CHECK (status IN ( 'PENDING','LOCKING','CONTRACT_CHECKING','BACKING_UP','TEMP_PARTITION_CREATING','LOADING_TEMP', 'PRE_SWITCH_VALIDATING','SWITCHING','POST_SWITCH_VALIDATING','COMMITTING_METADATA','SUCCEEDED', 'CLEANING_TEMP','FAILED','ROLLBACK_PREPARING','ROLLBACK_LOADING','ROLLBACK_SWITCHING', 'ROLLED_BACK','STATE_UNKNOWN','ROLLBACK_FAILED' ))`
- `CONSTRAINT ck_doris_scope_replace_fencing CHECK (fencing_token IS NULL OR fencing_token >= 0)`
- `CONSTRAINT ck_doris_scope_replace_counts CHECK ( (source_row_count IS NULL OR source_row_count >= 0) AND (staged_row_count IS NULL OR staged_row_count >= 0) AND (backup_row_count IS NULL OR backup_row_count >= 0) AND (formal_row_count_after_switch IS NULL OR formal_row_count_after_switch >= 0) )`
- `CONSTRAINT ck_doris_scope_replace_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_doris_scope_replace_status ON df_etl.doris_scope_replace_run(status, updated_at);`
- `CREATE INDEX idx_doris_scope_replace_partition ON df_etl.doris_scope_replace_run(partition_binding_id, created_at DESC);`

### 4.6 预检控制面

#### `precheck_run`

One immutable route precheck run fact; data issues are COMPLETED+ISSUES rather than technical failure

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `run_uuid uuid NOT NULL` |
| 3 | `route_id bigint NOT NULL REFERENCES df_etl.collection_route(id) ON DELETE RESTRICT` |
| 4 | `route_version_id bigint NOT NULL` |
| 5 | `dataset_version_id bigint NOT NULL REFERENCES df_etl.standard_dataset_version(id) ON DELETE RESTRICT` |
| 6 | `status varchar(24) NOT NULL DEFAULT 'PENDING'` |
| 7 | `result varchar(16)` |
| 8 | `current_stage varchar(64)` |
| 9 | `progress_percent numeric(5,2)` |
| 10 | `source_structure_hash varchar(128)` |
| 11 | `extracted_rows bigint NOT NULL DEFAULT 0` |
| 12 | `checked_rows bigint NOT NULL DEFAULT 0` |
| 13 | `problem_record_count bigint NOT NULL DEFAULT 0` |
| 14 | `problem_item_count bigint NOT NULL DEFAULT 0` |
| 15 | `affected_institution_count integer NOT NULL DEFAULT 0` |
| 16 | `actor_type varchar(24) NOT NULL DEFAULT 'USER'` |
| 17 | `started_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 18 | `actor_name_snapshot varchar(200)` |
| 19 | `retention_policy_snapshot jsonb NOT NULL` |
| 20 | `started_at timestamptz` |
| 21 | `finished_at timestamptz` |
| 22 | `failure_code varchar(128)` |
| 23 | `failure_message text` |
| 24 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_precheck_run_uuid UNIQUE (run_uuid)`
- `CONSTRAINT fk_precheck_run_route_version FOREIGN KEY (route_version_id, route_id, dataset_version_id) REFERENCES df_etl.collection_route_version(id, route_id, dataset_version_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_precheck_run_status CHECK (status IN ('PENDING','EXTRACTING','VALIDATING','COMPLETED','FAILED','CANCELLED'))`
- `CONSTRAINT ck_precheck_run_result CHECK ( (status = 'COMPLETED' AND result IN ('PASS','ISSUES')) OR (status <> 'COMPLETED' AND result IS NULL) )`
- `CONSTRAINT ck_precheck_run_progress CHECK (progress_percent IS NULL OR progress_percent BETWEEN 0 AND 100)`
- `CONSTRAINT ck_precheck_run_counts CHECK ( extracted_rows >= 0 AND checked_rows >= 0 AND problem_record_count >= 0 AND problem_item_count >= 0 AND affected_institution_count >= 0 )`
- `CONSTRAINT ck_precheck_run_actor CHECK (actor_type IN ('USER','SYSTEM'))`
- `CONSTRAINT ck_precheck_run_actor_identity CHECK ((actor_type = 'USER' AND started_by IS NOT NULL) OR actor_type = 'SYSTEM')`
- `CONSTRAINT ck_precheck_run_retention_snapshot CHECK (jsonb_typeof(retention_policy_snapshot) = 'object')`
- `CONSTRAINT ck_precheck_run_times CHECK ( (started_at IS NULL OR started_at >= created_at) AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at) )`

**显式索引**

- `CREATE UNIQUE INDEX uq_precheck_run_active_route ON df_etl.precheck_run(route_id) WHERE status IN ('PENDING','EXTRACTING','VALIDATING');`
- `CREATE INDEX idx_precheck_run_route_started ON df_etl.precheck_run(route_id, started_at DESC NULLS LAST, created_at DESC);`
- `CREATE INDEX idx_precheck_run_status_started ON df_etl.precheck_run(status, started_at);`
- `CREATE INDEX idx_precheck_run_result_finished ON df_etl.precheck_run(result, finished_at DESC) WHERE result IS NOT NULL;`

#### `precheck_issue_summary`

Long-lived field/composite/structure issue summaries; detailed problem rows remain in Doris

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `precheck_run_id bigint NOT NULL REFERENCES df_etl.precheck_run(id) ON DELETE RESTRICT` |
| 3 | `institution_id bigint REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 4 | `institution_code_snapshot varchar(64) NOT NULL DEFAULT ''` |
| 5 | `scope varchar(16) NOT NULL` |
| 6 | `primary_field_code varchar(100) NOT NULL DEFAULT ''` |
| 7 | `field_codes_json jsonb NOT NULL DEFAULT '[]'::jsonb` |
| 8 | `rule_code varchar(128) NOT NULL` |
| 9 | `rule_version varchar(64) NOT NULL` |
| 10 | `checked_count bigint NOT NULL` |
| 11 | `affected_record_count bigint NOT NULL` |
| 12 | `problem_item_count bigint NOT NULL` |
| 13 | `deviation_summary_json jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 14 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_precheck_issue_summary_identity UNIQUE ( precheck_run_id, institution_code_snapshot, scope, primary_field_code, rule_code, rule_version )`
- `CONSTRAINT ck_precheck_issue_summary_scope CHECK (scope IN ('STRUCTURE','FIELD','COMPOSITE'))`
- `CONSTRAINT ck_precheck_issue_summary_rule CHECK (btrim(rule_code) <> '' AND btrim(rule_version) <> '')`
- `CONSTRAINT ck_precheck_issue_summary_fields CHECK (jsonb_typeof(field_codes_json) = 'array')`
- `CONSTRAINT ck_precheck_issue_summary_counts CHECK (checked_count >= 0 AND affected_record_count >= 0 AND problem_item_count >= 0)`
- `CONSTRAINT ck_precheck_issue_summary_deviation CHECK (jsonb_typeof(deviation_summary_json) = 'object')`

**显式索引**

- `CREATE INDEX idx_precheck_issue_summary_run_affected ON df_etl.precheck_issue_summary(precheck_run_id, affected_record_count DESC);`
- `CREATE INDEX idx_precheck_issue_summary_run_institution ON df_etl.precheck_issue_summary(precheck_run_id, institution_code_snapshot);`
- `CREATE INDEX idx_precheck_issue_summary_rule_created ON df_etl.precheck_issue_summary(rule_code, created_at DESC);`

#### `precheck_detail_manifest`

Authoritative control-plane state for limited-life Doris precheck detail; missing Doris rows never imply zero issues

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `precheck_run_id bigint NOT NULL UNIQUE REFERENCES df_etl.precheck_run(id) ON DELETE RESTRICT` |
| 3 | `storage_version smallint NOT NULL DEFAULT 1` |
| 4 | `raw_table_name varchar(128) NOT NULL` |
| 5 | `record_table_name varchar(128) NOT NULL DEFAULT 'dfetl_precheck_issue_record'` |
| 6 | `item_table_name varchar(128) NOT NULL DEFAULT 'dfetl_precheck_issue_item'` |
| 7 | `run_partition_date date NOT NULL` |
| 8 | `status varchar(24) NOT NULL DEFAULT 'AVAILABLE'` |
| 9 | `raw_expires_at timestamptz NOT NULL` |
| 10 | `detail_expires_at timestamptz NOT NULL` |
| 11 | `raw_row_count bigint NOT NULL DEFAULT 0` |
| 12 | `problem_record_count bigint NOT NULL DEFAULT 0` |
| 13 | `problem_item_count bigint NOT NULL DEFAULT 0` |
| 14 | `cleanup_attempt_count integer NOT NULL DEFAULT 0` |
| 15 | `cleanup_started_at timestamptz` |
| 16 | `cleanup_finished_at timestamptz` |
| 17 | `cleanup_error text` |
| 18 | `revision bigint NOT NULL DEFAULT 0` |
| 19 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 20 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT ck_precheck_manifest_storage_version CHECK (storage_version = 1)`
- `CONSTRAINT ck_precheck_manifest_raw_table CHECK (raw_table_name ~ '^raw_precheck_[a-z0-9_]+_v[0-9]+$')`
- `CONSTRAINT ck_precheck_manifest_record_table CHECK (record_table_name = 'dfetl_precheck_issue_record')`
- `CONSTRAINT ck_precheck_manifest_item_table CHECK (item_table_name = 'dfetl_precheck_issue_item')`
- `CONSTRAINT ck_precheck_manifest_status CHECK (status IN ('AVAILABLE','EXPIRING','CLEANING','EXPIRED','CLEAN_FAILED'))`
- `CONSTRAINT ck_precheck_manifest_expiry CHECK (detail_expires_at >= raw_expires_at)`
- `CONSTRAINT ck_precheck_manifest_counts CHECK (raw_row_count >= 0 AND problem_record_count >= 0 AND problem_item_count >= 0 AND cleanup_attempt_count >= 0)`
- `CONSTRAINT ck_precheck_manifest_cleanup_times CHECK (cleanup_finished_at IS NULL OR cleanup_started_at IS NULL OR cleanup_finished_at >= cleanup_started_at)`
- `CONSTRAINT ck_precheck_manifest_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_precheck_manifest_status_expiry ON df_etl.precheck_detail_manifest(status, detail_expires_at);`
- `CREATE INDEX idx_precheck_manifest_raw_expiry ON df_etl.precheck_detail_manifest(status, raw_expires_at);`

### 4.7 告警与外部 API

#### `alert_channel`

Encrypted outbound alert endpoint and secret configuration

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `code_normalized varchar(100) GENERATED ALWAYS AS (lower(btrim(code))) STORED` |
| 4 | `name varchar(200) NOT NULL` |
| 5 | `channel_type varchar(24) NOT NULL` |
| 6 | `message_format varchar(16) NOT NULL DEFAULT 'MARKDOWN'` |
| 7 | `endpoint_ciphertext bytea NOT NULL` |
| 8 | `secret_ciphertext bytea` |
| 9 | `credential_nonce bytea NOT NULL` |
| 10 | `crypto_key_id varchar(128) NOT NULL` |
| 11 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 12 | `last_test_status varchar(20) NOT NULL DEFAULT 'NOT_TESTED'` |
| 13 | `last_tested_at timestamptz` |
| 14 | `last_test_error text` |
| 15 | `revision bigint NOT NULL DEFAULT 0` |
| 16 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 17 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 18 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 19 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_alert_channel_code_normalized UNIQUE (code_normalized)`
- `CONSTRAINT ck_alert_channel_name CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_alert_channel_type CHECK (channel_type IN ('DINGTALK','WECOM','WEBHOOK'))`
- `CONSTRAINT ck_alert_channel_format CHECK (message_format IN ('TEXT','MARKDOWN'))`
- `CONSTRAINT ck_alert_channel_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_alert_channel_test_status CHECK (last_test_status IN ('NOT_TESTED','SUCCESS','FAILED'))`
- `CONSTRAINT ck_alert_channel_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_alert_channel_status_type ON df_etl.alert_channel(status, channel_type, id);`

#### `alert_rule`

Whitelisted metric and condition alert rules; no arbitrary SQL or expression execution

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `code varchar(100) NOT NULL` |
| 3 | `code_normalized varchar(100) GENERATED ALWAYS AS (lower(btrim(code))) STORED` |
| 4 | `name varchar(200) NOT NULL` |
| 5 | `scope_type varchar(20) NOT NULL DEFAULT 'GLOBAL'` |
| 6 | `scope_id varchar(128)` |
| 7 | `metric_code varchar(128) NOT NULL` |
| 8 | `condition_operator varchar(8) NOT NULL` |
| 9 | `condition_value jsonb NOT NULL` |
| 10 | `severity varchar(16) NOT NULL` |
| 11 | `cooldown_seconds integer NOT NULL DEFAULT 300` |
| 12 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 13 | `revision bigint NOT NULL DEFAULT 0` |
| 14 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 15 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 16 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 17 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_alert_rule_code_normalized UNIQUE (code_normalized)`
- `CONSTRAINT ck_alert_rule_name CHECK (btrim(name) <> '')`
- `CONSTRAINT ck_alert_rule_scope CHECK (scope_type IN ('GLOBAL','DATASET','TASK','ROUTE'))`
- `CONSTRAINT ck_alert_rule_scope_id CHECK ((scope_type = 'GLOBAL' AND scope_id IS NULL) OR (scope_type <> 'GLOBAL' AND scope_id IS NOT NULL))`
- `CONSTRAINT ck_alert_rule_metric CHECK (btrim(metric_code) <> '')`
- `CONSTRAINT ck_alert_rule_operator CHECK (condition_operator IN ('EQ','NE','GT','GE','LT','LE'))`
- `CONSTRAINT ck_alert_rule_condition CHECK (jsonb_typeof(condition_value) IN ('string','number','boolean','object','array'))`
- `CONSTRAINT ck_alert_rule_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))`
- `CONSTRAINT ck_alert_rule_cooldown CHECK (cooldown_seconds >= 0)`
- `CONSTRAINT ck_alert_rule_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_alert_rule_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_alert_rule_status_scope ON df_etl.alert_rule(status, scope_type, scope_id);`
- `CREATE INDEX idx_alert_rule_metric ON df_etl.alert_rule(metric_code, status);`

#### `alert_rule_channel`

Many-to-many alert rule to delivery channel association

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `alert_rule_id bigint NOT NULL REFERENCES df_etl.alert_rule(id) ON DELETE CASCADE` |
| 2 | `alert_channel_id bigint NOT NULL REFERENCES df_etl.alert_channel(id) ON DELETE RESTRICT` |
| 3 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (alert_rule_id, alert_channel_id)`

**显式索引**

- `CREATE INDEX idx_alert_rule_channel_reverse ON df_etl.alert_rule_channel(alert_channel_id, alert_rule_id);`

#### `alert_event`

Deduplicated alert event fact; payload must be sanitized

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `event_key varchar(200) NOT NULL UNIQUE` |
| 3 | `rule_id bigint REFERENCES df_etl.alert_rule(id) ON DELETE RESTRICT` |
| 4 | `source_type varchar(64) NOT NULL` |
| 5 | `source_id varchar(128)` |
| 6 | `severity varchar(16) NOT NULL` |
| 7 | `title varchar(500) NOT NULL` |
| 8 | `payload jsonb NOT NULL DEFAULT '{}'::jsonb` |
| 9 | `lifecycle_status varchar(20) NOT NULL DEFAULT 'OPEN'` |
| 10 | `occurred_at timestamptz NOT NULL` |
| 11 | `acknowledged_at timestamptz` |
| 12 | `acknowledged_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 13 | `resolved_at timestamptz` |
| 14 | `resolved_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 15 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT ck_alert_event_key CHECK (btrim(event_key) <> '')`
- `CONSTRAINT ck_alert_event_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))`
- `CONSTRAINT ck_alert_event_title CHECK (btrim(title) <> '')`
- `CONSTRAINT ck_alert_event_payload CHECK (jsonb_typeof(payload) = 'object')`
- `CONSTRAINT ck_alert_event_lifecycle CHECK (lifecycle_status IN ('OPEN','ACKNOWLEDGED','RESOLVED'))`
- `CONSTRAINT ck_alert_event_ack_pair CHECK ( (acknowledged_at IS NULL AND acknowledged_by IS NULL) OR (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL) )`
- `CONSTRAINT ck_alert_event_resolve_pair CHECK ( (resolved_at IS NULL AND resolved_by IS NULL) OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL) )`
- `CONSTRAINT ck_alert_event_lifecycle_fields CHECK ( (lifecycle_status = 'OPEN' AND acknowledged_at IS NULL AND resolved_at IS NULL) OR (lifecycle_status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL AND resolved_at IS NULL) OR (lifecycle_status = 'RESOLVED' AND resolved_at IS NOT NULL) )`
- `CONSTRAINT ck_alert_event_times CHECK ( (acknowledged_at IS NULL OR acknowledged_at >= occurred_at) AND (resolved_at IS NULL OR resolved_at >= occurred_at) )`

**显式索引**

- `CREATE INDEX idx_alert_event_lifecycle_occurred ON df_etl.alert_event(lifecycle_status, occurred_at DESC);`
- `CREATE INDEX idx_alert_event_severity_occurred ON df_etl.alert_event(severity, occurred_at DESC);`
- `CREATE INDEX idx_alert_event_source ON df_etl.alert_event(source_type, source_id, occurred_at DESC);`

#### `alert_delivery`

One logical delivery per event and channel; attempts are separate immutable facts

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `alert_event_id uuid NOT NULL REFERENCES df_etl.alert_event(id) ON DELETE RESTRICT` |
| 3 | `alert_channel_id bigint NOT NULL REFERENCES df_etl.alert_channel(id) ON DELETE RESTRICT` |
| 4 | `status varchar(20) NOT NULL DEFAULT 'PENDING'` |
| 5 | `attempt_count integer NOT NULL DEFAULT 0` |
| 6 | `max_attempts integer NOT NULL DEFAULT 3` |
| 7 | `next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 8 | `last_error text` |
| 9 | `sent_at timestamptz` |
| 10 | `revision bigint NOT NULL DEFAULT 0` |
| 11 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |

**表内约束**

- `CONSTRAINT uq_alert_delivery_event_channel UNIQUE (alert_event_id, alert_channel_id)`
- `CONSTRAINT ck_alert_delivery_status CHECK (status IN ('PENDING','SENDING','SUCCEEDED','FAILED','DEAD_LETTER'))`
- `CONSTRAINT ck_alert_delivery_attempts CHECK (attempt_count >= 0 AND max_attempts BETWEEN 1 AND 100 AND attempt_count <= max_attempts)`
- `CONSTRAINT ck_alert_delivery_sent CHECK ((status = 'SUCCEEDED') = (sent_at IS NOT NULL))`
- `CONSTRAINT ck_alert_delivery_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_alert_delivery_claim ON df_etl.alert_delivery(status, next_attempt_at, id);`
- `CREATE INDEX idx_alert_delivery_event ON df_etl.alert_delivery(alert_event_id, status);`

#### `alert_delivery_attempt`

Immutable sanitized alert delivery attempt; secrets and raw payloads are forbidden

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `alert_delivery_id uuid NOT NULL REFERENCES df_etl.alert_delivery(id) ON DELETE RESTRICT` |
| 3 | `attempt_no integer NOT NULL` |
| 4 | `started_at timestamptz NOT NULL` |
| 5 | `finished_at timestamptz` |
| 6 | `result varchar(16) NOT NULL` |
| 7 | `provider_status varchar(128)` |
| 8 | `provider_response_sanitized text` |
| 9 | `failure_code varchar(128)` |
| 10 | `failure_message text` |

**表内约束**

- `CONSTRAINT uq_alert_delivery_attempt_no UNIQUE (alert_delivery_id, attempt_no)`
- `CONSTRAINT ck_alert_delivery_attempt_no CHECK (attempt_no > 0)`
- `CONSTRAINT ck_alert_delivery_attempt_result CHECK (result IN ('SUCCEEDED','FAILED'))`
- `CONSTRAINT ck_alert_delivery_attempt_times CHECK (finished_at IS NULL OR finished_at >= started_at)`

**显式索引**

- `CREATE INDEX idx_alert_delivery_attempt_delivery ON df_etl.alert_delivery_attempt(alert_delivery_id, attempt_no DESC);`

#### `external_client`

External API principal with ALL or SELECTED institution scope

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 2 | `client_id varchar(128) NOT NULL` |
| 3 | `client_id_normalized varchar(128) GENERATED ALWAYS AS (lower(btrim(client_id))) STORED` |
| 4 | `display_name varchar(200) NOT NULL` |
| 5 | `status varchar(20) NOT NULL DEFAULT 'ENABLED'` |
| 6 | `authorization_mode varchar(16) NOT NULL DEFAULT 'SELECTED'` |
| 7 | `token_version integer NOT NULL DEFAULT 0` |
| 8 | `requests_per_minute integer` |
| 9 | `revision bigint NOT NULL DEFAULT 0` |
| 10 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 11 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 12 | `updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 13 | `updated_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |
| 14 | `last_used_at timestamptz` |

**表内约束**

- `CONSTRAINT uq_external_client_id_normalized UNIQUE (client_id_normalized)`
- `CONSTRAINT ck_external_client_id_nonblank CHECK (btrim(client_id) <> '')`
- `CONSTRAINT ck_external_client_display_name CHECK (btrim(display_name) <> '')`
- `CONSTRAINT ck_external_client_status CHECK (status IN ('ENABLED','DISABLED'))`
- `CONSTRAINT ck_external_client_auth_mode CHECK (authorization_mode IN ('ALL','SELECTED'))`
- `CONSTRAINT ck_external_client_token_version CHECK (token_version >= 0)`
- `CONSTRAINT ck_external_client_rate CHECK (requests_per_minute IS NULL OR requests_per_minute BETWEEN 1 AND 100000)`
- `CONSTRAINT ck_external_client_revision CHECK (revision >= 0)`

**显式索引**

- `CREATE INDEX idx_external_client_status ON df_etl.external_client(status, client_id_normalized);`

#### `external_client_institution`

Institution scope rows for SELECTED external clients; ALL clients must have none

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `external_client_id bigint NOT NULL REFERENCES df_etl.external_client(id) ON DELETE CASCADE` |
| 2 | `institution_id bigint NOT NULL REFERENCES df_etl.institution(id) ON DELETE RESTRICT` |
| 3 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 4 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `PRIMARY KEY (external_client_id, institution_id)`

**显式索引**

- `CREATE INDEX idx_external_client_institution_reverse ON df_etl.external_client_institution(institution_id, external_client_id);`

#### `external_client_secret`

One-time external client secret version; only an irreversible hash is stored

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `id uuid PRIMARY KEY` |
| 2 | `external_client_id bigint NOT NULL REFERENCES df_etl.external_client(id) ON DELETE RESTRICT` |
| 3 | `secret_version integer NOT NULL` |
| 4 | `secret_hash varchar(255) NOT NULL` |
| 5 | `created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| 6 | `expires_at timestamptz` |
| 7 | `revoked_at timestamptz` |
| 8 | `last_used_at timestamptz` |
| 9 | `created_by bigint REFERENCES df_etl.user_account(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_external_client_secret_version UNIQUE (external_client_id, secret_version)`
- `CONSTRAINT ck_external_client_secret_version CHECK (secret_version > 0)`
- `CONSTRAINT ck_external_client_secret_expiry CHECK (expires_at IS NULL OR expires_at > created_at)`
- `CONSTRAINT ck_external_client_secret_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)`

**显式索引**

- `CREATE UNIQUE INDEX uq_external_client_secret_active ON df_etl.external_client_secret(external_client_id) WHERE revoked_at IS NULL;`
- `CREATE INDEX idx_external_client_secret_active ON df_etl.external_client_secret(external_client_id, secret_version DESC) WHERE revoked_at IS NULL;`

#### `external_api_request_identity`

Global request-id uniqueness anchor for the partitioned external API request log

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `request_id varchar(64) PRIMARY KEY` |
| 2 | `occurred_at timestamptz NOT NULL` |
| 3 | `external_client_id bigint NOT NULL REFERENCES df_etl.external_client(id) ON DELETE RESTRICT` |

**表内约束**

- `CONSTRAINT uq_external_api_request_identity_time UNIQUE (request_id, occurred_at)`
- `CONSTRAINT uq_external_api_request_identity_client UNIQUE (request_id, occurred_at, external_client_id)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `external_api_request_log`

Partitioned sanitized external API access fact; no Authorization, secret or medical payload

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `occurred_at timestamptz NOT NULL` |
| 2 | `id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL` |
| 3 | `request_id varchar(64) NOT NULL` |
| 4 | `external_client_id bigint NOT NULL REFERENCES df_etl.external_client(id) ON DELETE RESTRICT` |
| 5 | `http_method varchar(10) NOT NULL` |
| 6 | `endpoint_key varchar(200) NOT NULL` |
| 7 | `institution_scope jsonb` |
| 8 | `idempotency_key_hash varchar(64)` |
| 9 | `http_status integer NOT NULL` |
| 10 | `duration_ms bigint NOT NULL` |
| 11 | `resource_type varchar(64)` |
| 12 | `resource_id varchar(128)` |
| 13 | `result varchar(16) NOT NULL` |
| 14 | `error_code varchar(128)` |
| 15 | `client_ip inet` |

**表内约束**

- `PRIMARY KEY (occurred_at, id)`
- `CONSTRAINT fk_external_api_request_identity FOREIGN KEY (request_id, occurred_at, external_client_id) REFERENCES df_etl.external_api_request_identity(request_id, occurred_at, external_client_id) ON DELETE RESTRICT`
- `CONSTRAINT ck_external_api_request_method CHECK (http_method IN ('GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS'))`
- `CONSTRAINT ck_external_api_request_scope CHECK (institution_scope IS NULL OR jsonb_typeof(institution_scope) IN ('array','object'))`
- `CONSTRAINT ck_external_api_request_http_status CHECK (http_status BETWEEN 100 AND 599)`
- `CONSTRAINT ck_external_api_request_duration CHECK (duration_ms >= 0)`
- `CONSTRAINT ck_external_api_request_result CHECK (result IN ('SUCCESS','FAILED','DENIED'))`

**显式索引**

- `CREATE INDEX idx_external_api_request_client_time ON df_etl.external_api_request_log(external_client_id, occurred_at DESC);`
- `CREATE INDEX idx_external_api_request_endpoint_time ON df_etl.external_api_request_log(endpoint_key, occurred_at DESC);`
- `CREATE INDEX idx_external_api_request_result_time ON df_etl.external_api_request_log(result, occurred_at DESC);`

### 4.8 Quartz JDBCJobStore

#### `qrtz_job_details`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `job_name varchar(200) NOT NULL` |
| 3 | `job_group varchar(200) NOT NULL` |
| 4 | `description varchar(250)` |
| 5 | `job_class_name varchar(250) NOT NULL` |
| 6 | `is_durable boolean NOT NULL` |
| 7 | `is_nonconcurrent boolean NOT NULL` |
| 8 | `is_update_data boolean NOT NULL` |
| 9 | `requests_recovery boolean NOT NULL` |
| 10 | `job_data bytea` |

**表内约束**

- `PRIMARY KEY (sched_name, job_name, job_group)`

**显式索引**

- `CREATE INDEX idx_qrtz_j_req_recovery ON df_etl.qrtz_job_details(sched_name, requests_recovery);`
- `CREATE INDEX idx_qrtz_j_grp ON df_etl.qrtz_job_details(sched_name, job_group);`

#### `qrtz_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_name varchar(200) NOT NULL` |
| 3 | `trigger_group varchar(200) NOT NULL` |
| 4 | `job_name varchar(200) NOT NULL` |
| 5 | `job_group varchar(200) NOT NULL` |
| 6 | `description varchar(250)` |
| 7 | `next_fire_time bigint` |
| 8 | `prev_fire_time bigint` |
| 9 | `priority integer` |
| 10 | `trigger_state varchar(16) NOT NULL` |
| 11 | `trigger_type varchar(8) NOT NULL` |
| 12 | `start_time bigint NOT NULL` |
| 13 | `end_time bigint` |
| 14 | `calendar_name varchar(200)` |
| 15 | `misfire_instr smallint` |
| 16 | `job_data bytea` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_name, trigger_group)`
- `FOREIGN KEY (sched_name, job_name, job_group) REFERENCES df_etl.qrtz_job_details(sched_name, job_name, job_group)`

**显式索引**

- `CREATE INDEX idx_qrtz_t_j ON df_etl.qrtz_triggers(sched_name, job_name, job_group);`
- `CREATE INDEX idx_qrtz_t_jg ON df_etl.qrtz_triggers(sched_name, job_group);`
- `CREATE INDEX idx_qrtz_t_c ON df_etl.qrtz_triggers(sched_name, calendar_name);`
- `CREATE INDEX idx_qrtz_t_g ON df_etl.qrtz_triggers(sched_name, trigger_group);`
- `CREATE INDEX idx_qrtz_t_state ON df_etl.qrtz_triggers(sched_name, trigger_state);`
- `CREATE INDEX idx_qrtz_t_n_state ON df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group, trigger_state);`
- `CREATE INDEX idx_qrtz_t_n_g_state ON df_etl.qrtz_triggers(sched_name, trigger_group, trigger_state);`
- `CREATE INDEX idx_qrtz_t_next_fire_time ON df_etl.qrtz_triggers(sched_name, next_fire_time);`
- `CREATE INDEX idx_qrtz_t_nft_st ON df_etl.qrtz_triggers(sched_name, trigger_state, next_fire_time);`
- `CREATE INDEX idx_qrtz_t_nft_misfire ON df_etl.qrtz_triggers(sched_name, misfire_instr, next_fire_time);`
- `CREATE INDEX idx_qrtz_t_nft_st_misfire ON df_etl.qrtz_triggers(sched_name, misfire_instr, next_fire_time, trigger_state);`
- `CREATE INDEX idx_qrtz_t_nft_st_misfire_grp ON df_etl.qrtz_triggers(sched_name, misfire_instr, next_fire_time, trigger_group, trigger_state);`

#### `qrtz_simple_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_name varchar(200) NOT NULL` |
| 3 | `trigger_group varchar(200) NOT NULL` |
| 4 | `repeat_count bigint NOT NULL` |
| 5 | `repeat_interval bigint NOT NULL` |
| 6 | `times_triggered bigint NOT NULL` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_name, trigger_group)`
- `FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_cron_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_name varchar(200) NOT NULL` |
| 3 | `trigger_group varchar(200) NOT NULL` |
| 4 | `cron_expression varchar(120) NOT NULL` |
| 5 | `time_zone_id varchar(80)` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_name, trigger_group)`
- `FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_simprop_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_name varchar(200) NOT NULL` |
| 3 | `trigger_group varchar(200) NOT NULL` |
| 4 | `str_prop_1 varchar(512)` |
| 5 | `str_prop_2 varchar(512)` |
| 6 | `str_prop_3 varchar(512)` |
| 7 | `int_prop_1 integer` |
| 8 | `int_prop_2 integer` |
| 9 | `long_prop_1 bigint` |
| 10 | `long_prop_2 bigint` |
| 11 | `dec_prop_1 numeric(13,4)` |
| 12 | `dec_prop_2 numeric(13,4)` |
| 13 | `bool_prop_1 boolean` |
| 14 | `bool_prop_2 boolean` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_name, trigger_group)`
- `FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_blob_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_name varchar(200) NOT NULL` |
| 3 | `trigger_group varchar(200) NOT NULL` |
| 4 | `blob_data bytea` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_name, trigger_group)`
- `FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES df_etl.qrtz_triggers(sched_name, trigger_name, trigger_group)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_calendars`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `calendar_name varchar(200) NOT NULL` |
| 3 | `calendar bytea NOT NULL` |

**表内约束**

- `PRIMARY KEY (sched_name, calendar_name)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_paused_trigger_grps`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `trigger_group varchar(200) NOT NULL` |

**表内约束**

- `PRIMARY KEY (sched_name, trigger_group)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_fired_triggers`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `entry_id varchar(95) NOT NULL` |
| 3 | `trigger_name varchar(200) NOT NULL` |
| 4 | `trigger_group varchar(200) NOT NULL` |
| 5 | `instance_name varchar(200) NOT NULL` |
| 6 | `fired_time bigint NOT NULL` |
| 7 | `sched_time bigint NOT NULL` |
| 8 | `priority integer NOT NULL` |
| 9 | `state varchar(16) NOT NULL` |
| 10 | `job_name varchar(200)` |
| 11 | `job_group varchar(200)` |
| 12 | `is_nonconcurrent boolean` |
| 13 | `requests_recovery boolean` |

**表内约束**

- `PRIMARY KEY (sched_name, entry_id)`

**显式索引**

- `CREATE INDEX idx_qrtz_ft_trig_inst_name ON df_etl.qrtz_fired_triggers(sched_name, instance_name);`
- `CREATE INDEX idx_qrtz_ft_inst_job_req_rcvry ON df_etl.qrtz_fired_triggers(sched_name, instance_name, requests_recovery);`
- `CREATE INDEX idx_qrtz_ft_j_g ON df_etl.qrtz_fired_triggers(sched_name, job_name, job_group);`
- `CREATE INDEX idx_qrtz_ft_jg ON df_etl.qrtz_fired_triggers(sched_name, job_group);`
- `CREATE INDEX idx_qrtz_ft_t_g ON df_etl.qrtz_fired_triggers(sched_name, trigger_name, trigger_group);`
- `CREATE INDEX idx_qrtz_ft_tg ON df_etl.qrtz_fired_triggers(sched_name, trigger_group);`

#### `qrtz_scheduler_state`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `instance_name varchar(200) NOT NULL` |
| 3 | `last_checkin_time bigint NOT NULL` |
| 4 | `checkin_interval bigint NOT NULL` |

**表内约束**

- `PRIMARY KEY (sched_name, instance_name)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

#### `qrtz_locks`

未设置表注释。

**字段**

| 序号 | 字段定义（与 V1 一致） |
| ---: | --- |
| 1 | `sched_name varchar(120) NOT NULL` |
| 2 | `lock_name varchar(40) NOT NULL` |

**表内约束**

- `PRIMARY KEY (sched_name, lock_name)`

**显式索引**

- 无额外显式索引；使用主键/唯一约束自动索引或仅由 Quartz 官方访问路径使用。

## 5. 表外约束、函数和 Trigger

### 5.1 当前版本指针

`standard_dataset`、`collection_route`、`sync_task` 的 `current_version_id`：

- 通过 `(current_version_id, parent_id)` 复合外键确保版本属于当前身份；
- 外键及 Constraint Trigger 均 `DEFERRABLE INITIALLY DEFERRED`；
- 创建身份、首个版本和切换当前指针必须在同一事务完成；
- 提交时当前版本仍为空则拒绝事务。

### 5.2 不可变对象

以下对象通过 `reject_immutable_change()` 禁止 UPDATE/DELETE：字段转换合同与规则、Dataset Version/Field、Route Version/机构快照/字段解析、Task Version、告警投递尝试、审计和外部请求日志。

### 5.3 基础函数

- `df_etl.assert_current_version_set()`
- `df_etl.assert_delete_apply_dry_run()`
- `df_etl.assert_sync_task_version_dataset_contract()`
- `df_etl.reject_immutable_change()`
- `df_etl.validate_known_system_setting()`

### 5.4 Trigger

- `trg_alert_delivery_attempt_immutable`
- `trg_collection_route_current_version`
- `trg_collection_route_version_immutable`
- `trg_collection_route_version_institution_immutable`
- `trg_delete_apply_requires_successful_dry_run`
- `trg_external_api_request_identity_immutable`
- `trg_external_api_request_log_append_only`
- `trg_field_conversion_contract_immutable`
- `trg_field_conversion_rule_immutable`
- `trg_operation_audit_append_only`
- `trg_route_field_resolution_immutable`
- `trg_standard_dataset_current_version`
- `trg_standard_dataset_field_immutable`
- `trg_standard_dataset_version_immutable`
- `trg_sync_task_current_version`
- `trg_sync_task_version_dataset_contract`
- `trg_sync_task_version_immutable`
- `trg_system_setting_validate`

## 6. 基础数据

V1 只写入不含秘密的基础数据：

- 医共体名称/编码空值占位及调度、预检、导出、Outbox、Doris 备份默认参数；
- `registry_connection`、`global_validation_policy`、`export_storage_config` 三个未配置单例；
- `MEDICAL_V1` 字段转换合同及 A/AN/N/D/DT/L/B/BY 规则；
- `ROLE_ADMIN`、`ROLE_OPERATOR`、`ROLE_AUDITOR`、`ROLE_VIEWER`；
- 与冻结 A3/OpenAPI 合同对应的权限目录和内置角色权限集合。

V1 明确不创建默认管理员账号，也不写入任何固定密码或 Secret。

## 7. D3 空库验证要求

1. PostgreSQL 16 隔离空库执行 Flyway `migrate` 和 `validate`。
2. 验证 77 张逻辑表、2 张 Default Partition、11 张 Quartz 表和基础数据数量。
3. 检查全部外键已验证、Constraint Trigger 可提交首版本事务、部分唯一索引能抵抗并发。
4. 验证旧 `server/src/main/resources/db/*.sql` 不在 Flyway 扫描路径。
5. 验证 V1 无管理员密码、JWT/AES Key、数据库密码、Webhook/MinIO/Client Secret。
6. D3 完成前，状态仍为 `GENERATED_NOT_MIGRATED`，不得标记数据库为 `VERIFIED`。

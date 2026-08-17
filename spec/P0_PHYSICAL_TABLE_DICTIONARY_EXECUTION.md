# P0 物理表字典：Execution、Batch、Precheck、Validation 与 Message Outbox

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete + Snapshot 最小充分性已确认并收口  
> 最近更新：2026-08-17  
> Task：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

统一原则：

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

以上六张 PostgreSQL 表均为长期运行/质量/消息历史，不提供普通 DELETE API 或自动 PostgreSQL retention。

## 2. `sync_execution`

职责：一次真实同步运行的稳定身份、实际接受配置、非 Secret Runtime Endpoint、固定范围、最终 Validation/Message Policy、状态与结果。

### 2.1 最终字段

```text
id bigint identity PK
execution_uuid uuid NOT NULL

task_id bigint NOT NULL
task_revision bigint NOT NULL
institution_id bigint NOT NULL
institution_code varchar(100) NOT NULL
dataset_id bigint NOT NULL
dataset_version_id bigint NOT NULL
route_version_id bigint NOT NULL

task_kind varchar(32) NOT NULL
write_mode varchar(40) NOT NULL
doris_key_model varchar(24) NOT NULL
incremental_field_code varchar(100) NULL
fetch_size integer NOT NULL
upper_bound_delay_minutes integer NOT NULL DEFAULT 0
lookback_seconds integer NOT NULL DEFAULT 0

source_runtime_snapshot jsonb NOT NULL
target_runtime_snapshot jsonb NOT NULL

operation_type varchar(20) NOT NULL
trigger_type varchar(24) NOT NULL
status varchar(20) NOT NULL DEFAULT 'PENDING'
execution_scope varchar(24) NOT NULL
target_prepare_mode varchar(40) NOT NULL DEFAULT 'NONE'

window_lower timestamptz NULL
window_upper timestamptz NULL
key_lower jsonb NULL
key_upper jsonb NULL
watermark_before timestamptz NULL
watermark_commit_expected boolean NOT NULL DEFAULT false

schedule_fire_time timestamptz NULL
external_client_id bigint NULL
external_request_id varchar(128) NULL
requested_by_user_id bigint NULL

validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
checksum_protocol_version varchar(64) NULL

message_policy_snapshot jsonb NOT NULL

source_row_count bigint NOT NULL DEFAULT 0
loaded_row_count bigint NOT NULL DEFAULT 0
rejected_row_count bigint NOT NULL DEFAULT 0
batch_count integer NOT NULL DEFAULT 0
engine_job_id varchar(128) NULL

cancel_requested_at timestamptz NULL
cancel_requested_by bigint NULL
cancel_reason varchar(1000) NULL
error_code varchar(100) NULL
error_message varchar(2000) NULL
started_at timestamptz NULL
finished_at timestamptz NULL
revision bigint NOT NULL DEFAULT 0
created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
```

明确删除：

```text
precheck_fact_snapshot
```

明确不建立：

```text
task_version_id
validation_policy_snapshot
execution_contract_hash
dataset_definition_hash snapshot
route/field contract full JSON snapshot
range_snapshot JSON
```

### 2.2 不可变定义只引用

Execution 通过：

```text
dataset_version_id
route_version_id
```

引用永久保留的 Dataset Version、Route Version、Field Resolution 与 Field Conversion Contract，不复制 Definition Hash、字段列表或完整合同。

`institution_code` 继续保存为小型业务身份快照，用于 Doris Institution Scope、执行详情和 Outbox 生成。

### 2.3 Task 实际执行配置

本次实际接受值固定：

```text
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
```

不复制 Task Name、Schedule 配置或 Validation Override 原始继承链；历史只记录本次真正执行的值。

### 2.4 Runtime Endpoint Snapshot

`source_runtime_snapshot` 仅保存本次实际使用的非 Secret Source 连接事实：

```text
datasourceId
revision
dbType
connectionMode
host/port/databaseName 或 jdbcUrl
username
sslEnabled/readOnly
query/connect/socket timeout
```

不重复 Source Schema/Object，它们属于不可变 Route Version。

`target_runtime_snapshot` 仅保存本次实际使用的非 Secret Target 事实：

```text
datasourceId
revision
databaseName
username
httpPort
实际可用/选定 FE Endpoints(host/queryPort/httpPort/ordinalNo)
```

两列：

```text
CHECK jsonb_typeof(source_runtime_snapshot)='object'
CHECK jsonb_typeof(target_runtime_snapshot)='object'
```

严禁 Password/Password Enc、RabbitMQ Credential、API Secret、JWT、Master Key、Authorization/HMAC 信息。

### 2.5 运行原因与范围

继续使用结构化列：

```text
operation_type
trigger_type
execution_scope
target_prepare_mode
window_lower/window_upper
key_lower/key_upper
watermark_before
watermark_commit_expected
schedule_fire_time
external_client_id/external_request_id
requested_by_user_id
```

Trigger/Operation/Range/Terminal/Cancel CHECK 以 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` 为基线。

### 2.6 Validation Snapshot

```text
validation_method: ROW_COUNT / ROW_COUNT_CHECKSUM
validation_source: GLOBAL / DATASET / TASK / CONTRACT
```

```text
validation_source='CONTRACT'
→ validation_contract_forced=true
→ validation_source_revision IS NULL

validation_source IN ('GLOBAL','DATASET','TASK')
→ validation_contract_forced=false
```

Checksum Protocol：

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method='ROW_COUNT'
→ checksum_protocol_version IS NULL
```

不复制 Global/Dataset/Task Override 原始配置；最终 Method/Source/Revision/Forced 即为本次解析结果。

### 2.7 Message Policy Snapshot

`message_policy_snapshot` 最小只保存：

```text
enabled
policyRevision
sourceSystem
tenantId
routingKey
topic
keyTemplate
rateLimitPerSecond
pageSize
```

固定 Exchange `YL` 不复制；RabbitMQ 连接与 Credential 不复制。

Execution 成功收尾是否创建 Outbox，依据本 Execution 已冻结的 `message_policy_snapshot.enabled`，不重新读取当前 Dataset Policy。

### 2.8 FK / Unique / 并发

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

(dataset_version_id,incremental_field_code)
→ standard_dataset_field(dataset_version_id,field_code) RESTRICT

(external_client_id,external_request_id)
→ external_api_request(client_id,request_id) RESTRICT
```

```text
UNIQUE(execution_uuid)
UNIQUE(id,task_id)
UNIQUE(id,task_id,dataset_id,institution_id)

UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING')
```

### 删除

Execution 永久保留；Task 后续编辑、暂停、逻辑删除都不得修改或删除历史 Execution。

## 3. `load_batch`

只保存 Execution 内游标、Doris Label/Txn/State、批次行数和错误事实，不承担跨 Execution Resume。

```text
status: PENDING / LOADING / PROBING / SUCCEEDED / FAILED / CANCELLED
doris_state: UNKNOWN / PREPARE / COMMITTED / VISIBLE / ABORTED
```

```text
execution_id → sync_execution(id) RESTRICT
UNIQUE(execution_id,batch_no)
UNIQUE(doris_label)
```

只有 Doris `VISIBLE` + rejected=0 才 SUCCEEDED；Batch 永久保留。

## 4. `precheck_run`

```text
execution_status: PENDING / EXTRACTING / VALIDATING / COMPLETED / FAILED / CANCELLED
result_status: PASS / ISSUES
raw_cleanup_status: NOT_READY / PENDING / CLEANED / FAILED
```

Precheck 独立于正式同步，不进入 Execution Snapshot；不存在 `sync_execution.precheck_fact_snapshot`。

PostgreSQL `precheck_run/precheck_issue_summary` 永久保留；Doris RAW 终态后按既有 1 天规则清理，并更新 Cleanup 状态。

## 5. `precheck_issue_summary`

只保存 STRUCTURE/FIELD/COMPOSITE 汇总；永久保留；不保存行级 Issue、业务键或样例。

## 6. `validation_run`

统一承载 SYNC_GATE、MANUAL_RECHECK、普通独立 Validation 和 DELETE_RECONCILIATION。

核心字段：

```text
id/run_uuid
task_id/task_revision
execution_id
context_snapshot jsonb NULL
range_snapshot jsonb NULL
validation_scope/trigger_type
validation_method/validation_source
validation_source_revision/validation_contract_forced
checksum_protocol_version NULL
status/result
source_row_count/target_row_count
source_checksum/target_checksum
difference_count/difference_ratio/difference_summary
baseline_snapshot_run_id/current_snapshot_run_id
requested_by/cancel_requested_at/cancel_requested_by
error_code/error_message
started_at/finished_at/created_at/updated_at
```

JSON 基础 CHECK：

```text
context_snapshot IS NULL OR jsonb_typeof(context_snapshot)='object'
range_snapshot IS NULL OR jsonb_typeof(range_snapshot)='object'
```

### 6.1 SYNC_GATE / MANUAL_RECHECK

```text
execution_id IS NOT NULL
context_snapshot IS NULL
range_snapshot IS NULL
```

全部运行上下文唯一来自父 `sync_execution`；Validation Row 只保存校验事实。

### 6.2 普通独立 Validation

```text
trigger_type IN ('MANUAL','SCHEDULED')
validation_scope IN ('FULL_DATASET','CHANGE_WINDOW')
execution_id IS NULL
context_snapshot IS NOT NULL
range_snapshot IS NOT NULL
```

`context_snapshot` 最小只保存：

```text
routeVersionId
sourceRuntimeSnapshot
targetRuntimeSnapshot
```

不复制 Institution/Dataset/Dataset Version、字段列表、Field Contract 或 Source Schema/Object；这些由 Task Identity + 永久 Route Version 解释。

`range_snapshot`：

```text
FULL_DATASET → {}
CHANGE_WINDOW → {windowLower, windowUpper}
```

### 6.3 DELETE_RECONCILIATION

```text
execution_id IS NULL
context_snapshot IS NULL
range_snapshot IS NULL
baseline_snapshot_run_id/current_snapshot_run_id 非空
validation_method='DELETE_KEY_DIFF'
validation_source='FIXED'
```

上下文唯一来自 Delete Snapshot Run FK。

### 6.4 Checksum Protocol

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method IN ('ROW_COUNT','DELETE_KEY_DIFF')
→ checksum_protocol_version IS NULL
```

Validation 永久保留；不提供 purge。

## 7. `message_outbox`

Outbox 是可独立恢复/扫描/发布的小型 RabbitMQ 指令，不是历史业务 Payload Snapshot。

显式字段：

```text
id/event_id
execution_id/task_id/dataset_id/institution_id
status/available_at/attempt_count/max_attempts
policy_revision
publish_scope
source_system
tenant_id
routing_key
topic
key_template
rate_limit_per_second
page_size
range_snapshot jsonb NOT NULL
last_attempt_at/published_at
last_error_code/last_error_message
created_at/updated_at
```

消息策略字段必须来自 Execution 已冻结的 `message_policy_snapshot`，不在创建 Outbox 时重新读取当前 Dataset Policy。

父身份：

```text
(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id) RESTRICT
```

```text
UNIQUE(event_id)
UNIQUE(execution_id)
```

### 7.1 最小 `range_snapshot`

显式列已有：

```text
execution_id
task_id
dataset_id
institution_id
```

JSON 不再重复：

```text
executionId
taskId
datasetId
institutionId
operationType
```

只保存 Publisher 独立工作所需：

```text
executionScope
institutionCode
datasetVersionId
routeVersionId
targetDatasourceId
```

时间范围增加：

```text
windowLower/windowUpper
```

主键范围增加：

```text
keyLower/keyUpper
```

全量不保存伪范围。

### 7.2 不复制 Target Runtime Endpoint

Execution 的 `target_runtime_snapshot` 解释原同步写入事实；Outbox 人工重发已经确认重新读取**当前 Doris**，因此 Outbox 只保存逻辑 `targetDatasourceId`，不复制原 Target Endpoint Snapshot。

不保存业务 Payload、分页进度、逐条 Message、逐次 Attempt、RabbitMQ Credential。

Outbox PUBLISHED/DEAD_LETTER 永久保留；人工重发沿用 Event ID。

## 8. 成功收尾事务

```text
全部 Batch SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE COMPLETED + PASS
→ Execution SUCCEEDED
→ Watermark 创建/推进
→ 使用 Execution.message_policy_snapshot 决定是否创建 Outbox
→ commit
```

事务内不访问 Doris/RabbitMQ。

## 9. 验收

- 不可变 Dataset/Route/Field Contract 只引用，不复制第二份定义事实。
- Execution 新增非 Secret `source_runtime_snapshot/target_runtime_snapshot`。
- `precheck_fact_snapshot` 不进入 V1。
- ROW_COUNT 不保存无意义 Checksum Protocol。
- SYNC_GATE/MANUAL_RECHECK 只用父 Execution 上下文。
- 普通独立 Validation 自持最小 Runtime Context/Range。
- Delete Reconciliation 只用 Snapshot Run FK。
- Outbox 保留 Message Policy Snapshot 与最小 Range，但不重复身份/Operation/Target Endpoint。
- 所有 Snapshot 严禁 Secret。
- Runtime PostgreSQL History 继续永久保留。

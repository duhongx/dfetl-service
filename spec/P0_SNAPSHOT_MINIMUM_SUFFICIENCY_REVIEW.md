# P0 Execution / Validation / Outbox Snapshot 最小充分性 Review

> 状态：阶段 1 第 6 项最终一致性 Review 已确认  
> 确认日期：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> Execution：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> Outbox：`spec/P0_OUTBOX_SCOPE_MAPPING_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> 限制：本文是 Flyway V1 的运行快照字段边界，不是 SQL；阶段 1 最终签字前不创建 `V1__baseline.sql`。

## 1. 已确认总原则

Snapshot 统一遵循：

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

当前永久不可变且不得删除的定义包括：

```text
standard_dataset_version
standard_dataset_field
field_conversion_contract / field_conversion_rule
collection_route_version
route_field_resolution
```

因此 Execution / Validation / Outbox 不再复制这些对象的完整 JSON、字段列表、Hash 或合同内容；通过稳定 FK/ID 读取即可。

真正需要快照的是：

- Task 在本次运行真正采用的可变执行参数；
- Source / Target 当前可编辑连接资源中，本次运行实际使用的**非 Secret Runtime Endpoint**；
- 本次运行原因、范围、水位提交语义；
- 最终解析出的 Validation 方法；
- Dataset Message Policy 的本次生效值；
- 独立 Validation 在 Task 可继续编辑情况下必须自持的最小运行上下文。

## 2. Secret Boundary

任何 Snapshot、Audit、Outbox 范围或 Validation Context 中都不得保存：

```text
数据库 password / password_enc
RabbitMQ username/password/credential
Webhook secret
External API client secret
JWT secret
Encryption Master Key
Authorization Header
HMAC signature
```

Snapshot 只冻结：

```text
业务执行语义
非 Secret Runtime Endpoint
不可变定义 ID
```

不建设 Credential History。

## 3. `sync_execution`：稳定身份只引用

保留：

```text
task_id
task_revision
institution_id
institution_code
dataset_id
dataset_version_id
route_version_id
```

数据库通过现有强 FK 保证：

```text
route_version_id + institution_id + dataset_id + dataset_version_id
```

属于同一不可变 Route Version。

`institution_code` 继续保留为小型业务身份快照，用于 Doris Institution Scope、执行详情和 Outbox 生成。

明确不复制：

```text
dataset_definition_hash
route_contract_hash
structure_hash
field_resolution_hash
完整 Standard Field List
完整 Dataset Version JSON
完整 Route Version JSON
完整 Field Contract JSON
```

这些均可由永久不可变定义引用解释。

## 4. `sync_execution`：Task 实际执行参数快照

继续保存本次 Execution 真正采用的：

```text
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
```

即使部分值可从 Dataset 合同重新推导，也不在历史读取时重新计算；这些列明确表示“本次 Execution 实际接受的执行合同”。

不保存与本次数据执行无关的 Task 当前管理配置：

```text
task name
schedule_mode
schedule_interval_hours
schedule_cron
schedule_timezone
schedule_source
schedule_enabled
validation_method_override
```

调度历史只需要：

```text
trigger_type
schedule_fire_time
```

最终 Validation 使用已解析 Snapshot，不保存 Override 链的原始配置副本。

## 5. `sync_execution`：新增 Runtime Endpoint Snapshot

当前 `source_datasource`、`target_datasource`、`target_datasource_fe_endpoint` 都是可编辑当前资源，而 Route Version 只冻结其稳定 ID 和 Source Object 定义。

为了保证历史 Execution 能回答“当时实际从哪里读、向哪里写”，新增：

```text
source_runtime_snapshot jsonb NOT NULL
target_runtime_snapshot jsonb NOT NULL
```

两列必须：

```text
CHECK jsonb_typeof(...)='object'
```

### 5.1 Source Runtime Snapshot

只保存本次实际使用的非 Secret 连接事实，例如：

```json
{
  "datasourceId": 10,
  "revision": 7,
  "dbType": "POSTGRESQL",
  "connectionMode": "HOST_PORT",
  "host": "10.10.1.20",
  "port": 5432,
  "databaseName": "df_his",
  "jdbcUrl": null,
  "username": "df_read",
  "sslEnabled": false,
  "readOnly": true,
  "queryTimeoutSeconds": 60,
  "connectTimeoutSeconds": 30,
  "socketTimeoutSeconds": 60
}
```

不重复：

```text
source_schema
source_object
source_object_type
```

这些属于不可变 `collection_route_version`。

### 5.2 Target Runtime Snapshot

只保存本次实际使用的非 Secret Target 运行事实，例如：

```json
{
  "datasourceId": 20,
  "revision": 4,
  "databaseName": "ods",
  "username": "df_load",
  "httpPort": 8030,
  "feEndpoints": [
    {
      "host": "10.20.1.10",
      "queryPort": 9030,
      "httpPort": 8030,
      "ordinalNo": 1
    }
  ]
}
```

FE 列表表示 Execution 接受时实际可用/选定的 Target Runtime Endpoint 集合，不保存 Password。

## 6. `sync_execution`：运行原因和范围

继续使用结构化列，不再增加第二份 `range_snapshot`：

```text
operation_type
trigger_type
execution_scope
target_prepare_mode
window_lower
window_upper
key_lower
key_upper
watermark_before
watermark_commit_expected
schedule_fire_time
external_client_id
external_request_id
requested_by_user_id
```

这些列共同解释：

```text
为什么执行
+ 本次处理哪一段数据
+ 是否允许推进正式 Watermark
+ 谁/什么触发了本次运行
```

范围 CHECK 继续以 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` 为准。

## 7. `sync_execution`：Validation Snapshot

保留：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
checksum_protocol_version
```

不增加：

```text
validation_policy_snapshot
Task Validation Override Snapshot
Dataset Validation Override Snapshot
Global Validation Setting Snapshot
```

最终 Method/Source/Source Revision/Contract Forced 已足够解释本次解析结果。

Checksum Protocol 最终约束：

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method='ROW_COUNT'
→ checksum_protocol_version IS NULL
```

## 8. `sync_execution`：Message Policy Snapshot

Execution 接受时读取当前 Dataset Message Policy 并冻结：

```text
message_policy_snapshot jsonb NOT NULL
```

最小内容：

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

固定 Exchange `YL` 不需要复制；RabbitMQ 连接参数/凭据属于部署 Secret，不进入 Snapshot。

Execution 成功收尾时是否创建 Outbox，必须依据本次 Execution 的 `message_policy_snapshot.enabled`，不得重新读取当前 Dataset Policy 覆盖本次已接受运行。

## 9. 删除 `precheck_fact_snapshot`

最终 V1 不建立：

```text
sync_execution.precheck_fact_snapshot
```

原因：

```text
Precheck != 正式 Sync Gate
Precheck 结果 != Execution 成功条件
Precheck 数据 != 正式同步业务数据源
```

把“最近 Precheck 事实”复制进 Execution 会误导为本次同步依赖该 Precheck。Precheck 历史由 `precheck_run` 独立查询即可。

## 10. `sync_execution` 最终 Snapshot 字段集合

```text
# stable identity/reference
task_id
task_revision
institution_id
institution_code
dataset_id
dataset_version_id
route_version_id

# actual mutable Task execution config
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds

# mutable non-secret datasource runtime facts
source_runtime_snapshot
target_runtime_snapshot

# actual execution intent/range
operation_type
trigger_type
execution_scope
target_prepare_mode
window_lower
window_upper
key_lower
key_upper
watermark_before
watermark_commit_expected

# trigger provenance
schedule_fire_time
external_client_id
external_request_id
requested_by_user_id

# final validation resolution
validation_method
validation_source
validation_source_revision
validation_contract_forced
checksum_protocol_version

# dataset message policy at acceptance
message_policy_snapshot
```

`status/count/error/timestamps/engine_job_id/cancel_*` 属于运行结果，不属于启动 Snapshot，但继续保存在 Execution。

## 11. `validation_run`：唯一上下文来源原则

统一 `validation_run` 不再让每种运行同时复制 Parent Context + `context_snapshot` + `range_snapshot`。

固定三种模式：

### 11.1 SYNC_GATE / MANUAL_RECHECK

```text
execution_id IS NOT NULL
context_snapshot IS NULL
range_snapshot IS NULL
```

所有运行上下文唯一来自父 `sync_execution`。

Validation Row 自身只保存本次校验事实：

```text
task_id
task_revision
execution_id
validation_scope
trigger_type
validation_method
validation_source
validation_source_revision
validation_contract_forced
checksum_protocol_version
status/result
source_row_count/target_row_count
source_checksum/target_checksum
difference_count/difference_summary
error/timestamps
```

Execution 是同步运行上下文的唯一真相；Validation 是该运行上下文上的校验事实。

### 11.2 普通独立 Validation

适用：

```text
trigger_type IN ('MANUAL','SCHEDULED')
AND validation_scope IN ('FULL_DATASET','CHANGE_WINDOW')
```

固定：

```text
execution_id IS NULL
context_snapshot IS NOT NULL
range_snapshot IS NOT NULL
```

因为独立 Validation 启动后 Task 可以继续编辑，本次运行必须自持最小 Runtime Context。

`context_snapshot` 最小内容只保存：

```text
routeVersionId
sourceRuntimeSnapshot
targetRuntimeSnapshot
```

不复制：

```text
institutionId
datasetId
datasetVersionId
完整字段列表
Field Contract
Source Schema/Object
```

这些均可由 `task_id + routeVersionId` 和永久不可变定义解释。

`range_snapshot`：

```text
FULL_DATASET → {}
CHANGE_WINDOW → {windowLower, windowUpper}
```

不把 Task/Dataset/Route 身份复制到 Range JSON。

### 11.3 DELETE_RECONCILIATION

固定：

```text
execution_id IS NULL
context_snapshot IS NULL
range_snapshot IS NULL
baseline_snapshot_run_id IS NOT NULL
current_snapshot_run_id IS NOT NULL
validation_method='DELETE_KEY_DIFF'
validation_source='FIXED'
```

上下文唯一来自两个 `delete_snapshot_run` FK，不再复制第三份 Delete Snapshot Context。

## 12. `validation_run` JSON Nullability / CHECK

最终字段：

```text
context_snapshot jsonb NULL
range_snapshot jsonb NULL
```

不再使用：

```text
NOT NULL DEFAULT '{}'
```

CHECK：

```text
context_snapshot IS NULL OR jsonb_typeof(context_snapshot)='object'
range_snapshot IS NULL OR jsonb_typeof(range_snapshot)='object'
```

运行类型的 NULL 组合以第 11 节为最终 V1 规则。

Checksum Protocol 同样：

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method IN ('ROW_COUNT','DELETE_KEY_DIFF')
→ checksum_protocol_version IS NULL
```

## 13. `message_outbox`：继续作为自包含小型发布指令

Outbox 不是历史 Payload Snapshot，也不是当前 Dataset Policy 的动态引用。

继续显式保存启动时已固化到 Execution 的 Message Policy 值：

```text
policy_revision
publish_scope
source_system
tenant_id
routing_key
topic
key_template
rate_limit_per_second
page_size
```

这样 Outbox 等待/重试期间 Dataset Message Policy 后续修改不会改变已接受发布指令。

不保存：

```text
RabbitMQ Exchange / Credential
业务 Payload
分页进度
逐条 Message
逐次 Attempt 明细
```

## 14. `message_outbox.range_snapshot` 最小化

Outbox 显式列已经保存：

```text
execution_id
task_id
dataset_id
institution_id
```

因此 `range_snapshot` 不再重复：

```text
executionId
taskId
datasetId
institutionId
operationType
```

最终仅保存发布器独立工作所需的原执行范围/不可变版本/逻辑 Target：

### FULL

```json
{
  "executionScope": "FULL",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9
}
```

### INCREMENTAL / BACKFILL_TIME

```json
{
  "executionScope": "INCREMENTAL",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9,
  "windowLower": "...",
  "windowUpper": "..."
}
```

### BACKFILL_KEY

```json
{
  "executionScope": "BACKFILL_KEY",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9,
  "keyLower": {},
  "keyUpper": {}
}
```

实际 `executionScope` 必须复制原 Execution 的真实值；`publish_scope` 继续只有 `FULL/INCREMENTAL`。

`operation_type` 永久保存在父 Execution，不是 Publisher 独立读取范围所需字段，因此不重复进 JSON。

## 15. Outbox 不保存 Target Runtime Endpoint Snapshot

Execution 的 `target_runtime_snapshot` 回答“原同步当时实际向哪里写”。

但消息人工重发已经确认：

```text
重新读取当前 Doris
```

不是历史 Payload Replay。

因此 Outbox 只保存 `targetDatasourceId` 作为逻辑 Target 身份；发布时使用该 Target 当前可用连接配置读取当前 Doris。

不把 Execution 的 `target_runtime_snapshot` 复制到 Outbox。

## 16. 最终删除/新增清单

### `sync_execution`

新增：

```text
source_runtime_snapshot jsonb NOT NULL
target_runtime_snapshot jsonb NOT NULL
```

删除：

```text
precheck_fact_snapshot
```

收紧：

```text
checksum_protocol_version
```

仅 ROW_COUNT_CHECKSUM 非空。

### `validation_run`

保留字段：

```text
context_snapshot
range_snapshot
```

但改为可空，并按运行类型严格 CHECK；Execution-bound Validation 和 Delete Reconciliation 不复制 Context/Range。

`checksum_protocol_version` 同样只在 ROW_COUNT_CHECKSUM 时非空。

### `message_outbox`

保持显式 Message Policy Snapshot；`range_snapshot` 删除重复身份/operation 字段，只保留发布所需最小范围。

## 17. 验收

- Task/Datasource 后续修改不能改变历史 Execution 的真实运行解释。
- Dataset/Route/Field Contract 等永久不可变定义不在运行表复制第二份完整事实。
- Execution 能回答本次实际使用的 Task 参数、Source/Target Runtime Endpoint、范围、Validation 和 Message Policy。
- `precheck_fact_snapshot` 不进入 V1。
- SYNC_GATE/MANUAL_RECHECK 只使用父 Execution 上下文。
- 独立 Validation 在 Task 可编辑期间仍有完整最小 Runtime Context。
- Delete Reconciliation 只依赖 Snapshot Run FK。
- Outbox 能独立恢复发布，但不保存业务 Payload、Target Runtime Endpoint 或重复身份字段。
- ROW_COUNT 不保存无意义 Checksum Protocol Version。
- 所有 Snapshot 严禁 Secret。

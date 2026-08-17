# DFETL 目标元数据模型

> 状态：阶段 1 最终物理矩阵收口版；Snapshot 最小充分性已确认  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`  
> 限制：本文定义目标逻辑关系，不是 Flyway SQL；阶段 1 最终签字前不得修改正式数据库。

## 1. 总体主线

```text
接入资源
→ Institution Route
→ Sync Task Current Config
→ Execution / Batch
→ Validation / Watermark / Message
```

固定原则：

- 一个部署只服务一个医共体，不增加 Tenant 表。
- Institution 扁平；HIS/LIS/PACS 仅为轻量 Business Catalog。
- Source 直接属于一家 Institution + 一个 Business Catalog。
- Route 固定单机构。
- Dataset Version、Route Version、Field Resolution、Field Conversion Contract 永久不可变。
- Task 不版本化，保存当前配置。
- Runtime Snapshot 遵循：**不可变定义只引用、可变运行事实才快照、Secret 永不快照**。
- FK 使用最强复合关系证明同一业务身份。

## 2. 顶层关系

```text
institution 1 ── N source_datasource N ── 1 business_catalog
       │
       └── 1 ── N collection_route N ── 1 standard_dataset
                         │
                         ├── current_version_id → collection_route_version
                         │                         └── route_field_resolution
                         │
                         └── 1 ── 0..1 sync_task
                                         ├── task_watermark
                                         ├── sync_execution
                                         │      └── load_batch
                                         ├── validation_run
                                         └── message_outbox（through execution）

target_datasource
  └── target_datasource_fe_endpoint

standard_dataset
  └── standard_dataset_version
       └── standard_dataset_field
```

## 3. 接入资源

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

- Source 保存当前可编辑数据库连接资源。
- Target 保存当前可编辑 Doris 逻辑连接和 FE Endpoint。
- Resource 无引用时可物理删除，有历史引用时只能停用。
- Password/Credential 使用密文或部署 Secret，不进入 Runtime Snapshot。

## 4. Dataset / Field Contract

```text
standard_dataset
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
generic_jdbc_type_mapping
dataset_sync_policy
dataset_message_policy
```

- Dataset 只允许管理员从规范库人工同步。
- 相同历史 Definition Hash 复用原不可变 Version。
- Version/Field/Contract 永久保留，不复制到 Execution/Validation JSON。
- Dataset Validation Override 直接保存在 `standard_dataset.validation_method_override`。
- Message Policy 只存在 Dataset 级。
- 不建立独立 Global/Dataset/Task Validation Policy 表。

## 5. Institution Route

### `collection_route`

一家 Institution + 一个 Dataset 的当前采集投影：

```text
institution_id
dataset_id
source_datasource_id
source_schema/source_object/source_object_type
target_datasource_id
status/structure_status
current_version_id
revision
deleted_at/deleted_by
```

Route 使用逻辑删除。

### `collection_route_version`

永久不可变 Route 定义：

```text
id/route_id/version_no
institution_id
dataset_id/dataset_version_id
source_datasource_id
source_schema/source_object/source_object_type
target_datasource_id
structure_hash/contract_hash
```

Route Version 不冻结 Source/Target 当前 Connection Endpoint；因此运行时由 Execution 另行冻结非 Secret Runtime Endpoint。

### `route_field_resolution`

```text
route_version_id
dataset_version_id
standard_field_id
source_column_name/source_ordinal/source_jdbc_type/source_type_name
```

不重复保存 `field_code`；只允许大小写差异，不支持别名、表达式或默认值改写身份。

## 6. Sync Task

固定业务身份：

```text
institution_id + dataset_id
```

创建后不可修改；同一 Institution + Dataset 一条未删除 Task。

当前配置：

```text
dataset_version_id
route_version_id
name
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size/upper_bound_delay_minutes/lookback_seconds
schedule_*
validation_method_override
revision
```

不建立 `sync_task_version/current_version_id/task_version_id`。

## 7. Watermark

```text
task_id
watermark_value
source_execution_id
revision
updated_at/updated_by
```

Watermark 是当前状态，不保存历史；显式“清除水位”可删除当前 Row，Task 逻辑删除不级联 Watermark。

## 8. Execution / Batch

### `sync_execution`

Execution 的启动上下文分三类。

#### 不可变定义引用

```text
task_id/task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
```

Dataset/Route/Field Contract 不复制第二份完整 JSON。

#### 可变运行事实快照

```text
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size/upper_bound_delay_minutes/lookback_seconds
source_runtime_snapshot
target_runtime_snapshot
operation_type/trigger_type/execution_scope/target_prepare_mode
固定 time/key range
watermark_before/watermark_commit_expected
validation_method/source/source_revision/contract_forced
checksum_protocol_version（仅 ROW_COUNT_CHECKSUM）
message_policy_snapshot
```

`source_runtime_snapshot/target_runtime_snapshot` 只保存非 Secret Endpoint/Revision/运行连接事实；严禁 Password/Credential。

不建立 `precheck_fact_snapshot`。

#### 运行结果

```text
status
counts
engine_job_id
cancel/error/timestamps
```

这些不是启动 Snapshot。

### `load_batch`

只保存本 Execution 内游标、Doris Label/Txn/State、批次行数和错误；不是跨 Execution Checkpoint。

## 9. Precheck

```text
precheck_run
precheck_issue_summary
```

Precheck 独立于正式同步；Execution 不复制 Precheck Fact。PostgreSQL Run/Summary 永久保留，Doris RAW 终态后按 1 天规则清理。

## 10. Validation

统一 `validation_run`，但每种运行只有**一个上下文真相来源**。

### SYNC_GATE / MANUAL_RECHECK

```text
execution_id NOT NULL
context_snapshot NULL
range_snapshot NULL
```

全部上下文读取父 `sync_execution`。

### 普通独立 Validation

```text
execution_id NULL
context_snapshot NOT NULL
range_snapshot NOT NULL
```

最小 Context 只保存：

```text
routeVersionId
sourceRuntimeSnapshot
targetRuntimeSnapshot
```

Range 只保存 FULL_DATASET 空对象或 CHANGE_WINDOW 的 lower/upper。

### DELETE_RECONCILIATION

```text
execution_id NULL
context_snapshot NULL
range_snapshot NULL
baseline_snapshot_run_id/current_snapshot_run_id NOT NULL
validation_method=DELETE_KEY_DIFF
validation_source=FIXED
```

上下文来自 Delete Snapshot Run FK。

## 11. Message Outbox

RabbitMQ Only，Dataset Message Policy 在 Execution 接受时冻结。

```text
sync_execution
  └── 0..1 message_outbox
```

Outbox 显式保存：

```text
execution_id/task_id/dataset_id/institution_id
publish_scope
Message Policy Snapshot fields
最小 range_snapshot
```

`range_snapshot` 不重复显式身份或 `operation_type`，只保存 Publisher 所需：

```text
executionScope
institutionCode
datasetVersionId
routeVersionId
targetDatasourceId
具体 time/key range
```

Outbox 不复制 Execution Target Runtime Endpoint；人工重发按已确认语义重新读取当前 Doris，不做历史 Payload Replay。

## 12. Delete Snapshot

PostgreSQL：

```text
delete_snapshot_run
task_delete_snapshot_state
validation_run(DELETE_RECONCILIATION)
delete_apply_run
```

Doris：

```text
_dfetl_key_snapshot
_dfetl_delete_diff
```

Delete Snapshot 使用 Task 复合身份 + Route Version 四元身份；PostgreSQL 历史永久保留，Doris Key/Diff 按生命周期清理。

## 13. Support Objects

```text
app_user
audit_log
system_setting
alert_channel
alert_rule
alert_rule_channel
alert_event
alert_delivery
external_api_client
external_api_client_institution
external_api_request_nonce
external_api_request
Quartz 官方 11 张 qrtz_* 表
```

普通审计用户 FK 使用 SET NULL；运行责任用户 RESTRICT。Nonce 1 小时清理；Quartz 是可重建调度投影。

## 14. 明确废止

不得重新引入：

- Business System Instance；
- Multi-Institution Route；
- `sync_task_version/task_version_id`；
- Task-level Message Policy；
- 独立 Validation Policy Table；
- Standard Task CUSTOM_SQL；
- Source/Task Group；
- Institution Tree；
- 行级 Precheck Issue；
- Execution Resume/Checkpoint；
- Runtime Snapshot 中的完整不可变定义副本；
- Credential/Secret History。

后续数据库、Java Entity/DTO、API 和前端类型必须以本模型及已冻结矩阵为准。

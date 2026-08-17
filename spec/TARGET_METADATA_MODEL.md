# DFETL 目标元数据模型

> 状态：阶段 1 FK Matrix 收口版  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> 限制：本文定义目标逻辑关系，不是 Flyway SQL；阶段 1 最终签字前不得修改正式数据库。

## 1. 总体主线

```text
接入资源
→ 机构采集 Route
→ Sync Task
→ Execution / Batch
→ Validation / Watermark / Message
```

固定原则：

- 一个部署只服务一个医共体，不增加 Tenant 表。
- Institution 扁平。
- HIS/LIS/PACS 只作为轻量 Business Catalog。
- Source Datasource 直接属于一家 Institution + 一个 Business Catalog。
- Route 固定单机构。
- Dataset Version、Route Version、Field Conversion Contract 保持不可变。
- Task 不版本化，保存当前配置。
- Execution/Validation 保存启动快照。
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

### `institution`

医共体内机构身份；不保存机构树、Datasource、Route、Dataset、调度或运行状态。

### `business_catalog`

HIS/LIS/PACS/EMR 等轻量分类，不表示真实部署实例，不与 Institution 建多对多覆盖关系。

### `source_datasource`

```text
institution_id      → institution
business_catalog_id → business_catalog
```

只保存数据库连接与测试状态；不保存 Dataset、Schema/Object 映射、Target Table。

### `target_datasource`

逻辑 Doris 部署；子表 `target_datasource_fe_endpoint`。不绑定 Institution/Business Catalog/Dataset。

## 4. 标准 Dataset 与 Field Contract

目标对象：

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
- 定义变化生成不可变 `standard_dataset_version`。
- Field 只属于某个 Dataset Version。
- Dataset 当前 Validation Override 直接保存在 `standard_dataset.validation_method_override`。
- Message Policy 只存在 Dataset 级。
- 不建立 Global/Dataset/Task Validation Policy 表。

## 5. 机构采集 Route

### 5.1 `collection_route`

职责：一家 Institution 对一个标准 Dataset 的当前采集映射。

当前字段：

```text
id
institution_id
dataset_id
source_datasource_id
source_schema
source_object
source_object_type
target_datasource_id
status
structure_status
structure_checked_at
structure_error_summary
current_version_id
revision
deleted_at/deleted_by
created_*/updated_*
```

不变量：

1. Route 单机构。
2. Source 必须属于 Route Institution。
3. Business Catalog 从 Source 推导，Route 不重复保存。
4. 一 Institution + Dataset 一条未删除 Route。
5. `current_version_id` 只能指向自身 Route 的 Version。
6. Route 状态与 Structure Status 独立。

### 5.2 `collection_route_version`

一次不可变 Route 配置快照：

```text
id
route_id
version_no
institution_id
dataset_id
dataset_version_id
source_datasource_id
source_schema
source_object
source_object_type
target_datasource_id
structure_hash
contract_hash
created_at/created_by
```

FK 强关系：

```text
(route_id,institution_id,dataset_id)
→ collection_route(id,institution_id,dataset_id)

(dataset_id,dataset_version_id)
→ standard_dataset_version(dataset_id,id)

(source_datasource_id,institution_id)
→ source_datasource(id,institution_id)
```

Task/Execution/Delete Snapshot 使用：

```text
(id,institution_id,dataset_id,dataset_version_id)
```

作为 Route Version 稳定业务身份父键。

### 5.3 `route_field_resolution`

职责：某个 Route Version 下 Standard Field → JDBC Actual Column 的不可变解析快照。

最终字段：

```text
route_version_id
dataset_version_id
standard_field_id
source_column_name
source_ordinal
source_jdbc_type
source_type_name
resolved_at
```

**不再保存 `field_code`。**

Standard Field Code 通过 `standard_field_id` 读取。

数据库关系：

```text
(route_version_id,dataset_version_id)
→ collection_route_version(id,dataset_version_id)

(dataset_version_id,standard_field_id)
→ standard_dataset_field(dataset_version_id,id)
```

因此数据库保证 Field 一定属于 Route Version 使用的 Dataset Version。

只允许大小写差异；不支持字段重命名、别名、表达式、默认值。

## 6. Sync Task

### 6.1 固定业务身份

```text
institution_id
dataset_id
```

创建后不可修改；同一 Institution + Dataset 一个未删除 Task。

### 6.2 当前配置

```text
dataset_version_id
route_version_id
name
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
schedule_*
validation_method_override
revision
```

Task 通过一条四元 FK 固定当前 Route/Dataset/Institution：

```text
(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id)
```

不建立 `sync_task_version/current_version_id/task_version_id`。

## 7. `task_watermark`

一 Task 最多一条当前正式 Watermark：

```text
task_id
watermark_value
source_execution_id
revision
updated_at/updated_by
```

自动推进来源通过：

```text
(source_execution_id,task_id)
→ sync_execution(id,task_id)
```

保证 Source Execution 属于同一个 Task。

不建立 Watermark History；历史范围从 Execution 查询。

## 8. Execution / Batch

### `sync_execution`

保存一次真实同步运行和启动快照：

```text
task_id + task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
task kind/write mode/key model
incremental field
fetch/read range
validation method/source/revision
message policy snapshot
operation/trigger/status/range
统计和错误
```

关系：

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id)

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(...)

(external_client_id,external_request_id)
→ external_api_request(client_id,request_id)
```

并提供：

```text
UNIQUE(id,task_id)
UNIQUE(id,task_id,dataset_id,institution_id)
```

分别供 Watermark/Validation 和 Outbox 使用。

### `load_batch`

只保存本 Execution 内游标、Doris Label/Txn/State、批次行数和错误；不是跨 Execution Checkpoint。

## 9. Precheck

目标对象：

```text
precheck_run
precheck_issue_summary
```

Precheck Run 固定 Route/Route Version/Institution/Dataset/Dataset Version；同 Route 最多一个活动 Run。

Issue Summary 只保存 STRUCTURE/FIELD/COMPOSITE 汇总，不保存行级问题。

## 10. Validation

统一 `validation_run`。

同步门禁/人工重新校验关联 Execution 时使用：

```text
(execution_id,task_id)
→ sync_execution(id,task_id)
```

因此不能引用其他 Task 的 Execution。

独立 Validation 使用 Task + Context/Range Snapshot。

校验方法解析：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
→ Dataset 合同能力
```

## 11. Message Outbox

RabbitMQ Only；Dataset 级 Policy。

```text
sync_execution
  └── 0..1 message_outbox
```

Outbox 通过：

```text
(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id)
```

保证所有冗余查询身份与原 Execution 一致。不保存 Task Version、逐条 Payload 或分页进度。

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

Delete Snapshot 使用 Task 复合身份 + Route Version 四元身份；历史全部 RESTRICT。删除差异不自动应用。

## 13. 支撑对象

继续保留：

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

普通审计用户 FK 使用 SET NULL；运行责任用户使用 RESTRICT。

## 14. 明确废止

不得重新引入：

- Business System Instance 及其多对多中间层；
- Multi-Institution Route；
- `sync_task_version`；
- Task-level Message Policy；
- Global/Dataset/Task Validation Policy 表；
- Standard Task CUSTOM_SQL；
- Source/Task Group；
- Institution Tree；
- 行级 Precheck Issue；
- Execution Resume/Checkpoint 状态表。

后续数据库、Java Entity/DTO、API 和前端类型必须以本模型及 FK Matrix 为准。

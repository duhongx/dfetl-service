# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL 最终表清单已确认；进入全量 FK Matrix Review  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 已完成的模型收口

### Resource / Route

```text
旧：Business System Instance ↔ Multi-Institution/Multi-Source → Shared Route
新：Institution + Business Catalog → Source → Single-Institution Route
```

### Task / Validation

```text
旧：sync_task → Task Version → Execution
新：sync_task Current Config → Execution/Validation Startup Snapshot
```

```text
旧：Global/Dataset/Task Validation Policy Tables
新：System Setting + Dataset Override + Task Override + Execution Validation Snapshot
```

当前唯一主线：

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Startup Snapshot
```

继续保留不可变 Version：

```text
standard_dataset_version
collection_route_version
field_conversion_contract / field_conversion_rule
```

不建立 Task Version。

## 2. P0 PostgreSQL 最终表清单：已确认

### 2.1 DFETL 领域/控制表：39 张

#### 接入资源：5

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

#### Dataset / 字段合同 / Dataset 配置：8

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

#### Route / Task / Watermark：5

```text
collection_route
collection_route_version
route_field_resolution
sync_task
task_watermark
```

#### Execution / Precheck / Validation / Message：6

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

#### 删除识别：3

```text
delete_snapshot_run
task_delete_snapshot_state
delete_apply_run
```

`DELETE_RECONCILIATION` 复用统一 `validation_run`。

#### 账号 / Audit / Setting / Alert / External API：12

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
```

`alert_rule_channel` 已确认保留，用于 Alert Rule 与 Channel 的结构化多对多关系。

### 2.2 Quartz JDBC JobStore：11 张

```text
qrtz_job_details
qrtz_triggers
qrtz_simple_triggers
qrtz_cron_triggers
qrtz_simprop_triggers
qrtz_blob_triggers
qrtz_calendars
qrtz_paused_trigger_grps
qrtz_fired_triggers
qrtz_scheduler_state
qrtz_locks
```

Quartz 11 张官方表单独统计，不计入 DFETL 39 张领域/控制表。

### 2.3 冻结数量口径

```text
DFETL P0 领域/控制表       39
Quartz JDBC JobStore       11
--------------------------------
Flyway V1 负责创建         50
```

`flyway_schema_history` 由 Flyway 自身创建和管理，不计入 P0 39、Quartz 11 或 V1 自己定义的 50 张表。

## 3. 当前明确废止对象

不得作为 Active Model 进入 V1/API/Entity/Frontend：

```text
business_system_instance
business_system_instance_institution
business_system_instance_datasource

collection_route_institution
collection_route_version_institution

sync_task_version
sync_task.current_version_id
task_version_id

global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode
```

同样不恢复：Validation Disable、Tolerance、Validation Lookback、Auto Revalidate/Fail Block、Task-level Message Policy、RBAC、Scheduler Reconciliation、External API Rate Limit/Quota 表。

## 4. 当前关键身份约束

### Source → Route

```text
source_datasource(id,institution_id) UNIQUE

collection_route(source_datasource_id,institution_id)
→ source_datasource(id,institution_id)
```

### Route Version 四元身份

```text
collection_route_version(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
) UNIQUE
```

统一被以下对象使用：

```text
sync_task
sync_execution
precheck_run
delete_snapshot_run
```

从而保证 Route Version/Institution/Dataset/Dataset Version 属于同一不可变 Route Snapshot。

### Task → Execution/Delete Snapshot

```text
sync_task(id,institution_id,dataset_id) UNIQUE
```

用于固定 Task Business Identity。

### Execution → Outbox

```text
sync_execution(id,task_id,dataset_id,institution_id) UNIQUE

message_outbox(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id)
```

Outbox 的冗余查询列因此不能和父 Execution 身份漂移。

## 5. 当前 Validation 配置

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

解析：

```text
Task
→ Dataset
→ Global Setting
→ Registered Default ROW_COUNT
→ Dataset Contract Capability
```

运行结果固定：

```text
sync_execution.validation_method
sync_execution.validation_source
sync_execution.validation_source_revision
sync_execution.validation_contract_forced
```

## 6. Precheck 单机构收口

`precheck_run` 固定：

```text
route_id
route_version_id
institution_id/institution_code
dataset_id/dataset_version_id
```

`precheck_issue_summary`：

```text
STRUCTURE
FIELD
COMPOSITE
```

父 Run 已唯一确定 Institution，所以 Issue Summary 不重复 Institution 维度，也不存在多机构 Route 下钻语义；不保存行级问题。

## 7. Active Spec 语义扫描结果

以下属于合法旧词引用：

```text
历史审计
旧模型说明
已废止
明确不建立
不得进入 V1
机械迁移映射
```

以下非法残留已经完成主要清理：

```text
Active Object List 仍包含旧表
FK 指向旧表
Task Create 仍创建 First Task Version
Quartz 仍读取 Current Task Version
Validation 当前值仍保存于独立 Policy Table
Current Flow 仍要求 Publish/Switch/Rollback Task Version
```

历史/Legacy Audit 文件可以保留旧对象名称，但不得被当成目标模型输入。

## 8. 当前 Review 顺序

用户已确认按以下顺序逐项讨论并完成：

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [ ] 全量 FK Matrix。
3. [ ] Business/Concurrency Unique Matrix。
4. [ ] Status / Enum / CHECK Matrix。
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

Doris ODS/RAW/Technical Table List、Sensitive Field/Secret Boundary 等内容在对应最终物理 Review 中同步核对，但不改变上述讨论顺序。

## 9. 第 1 项结论

第 1 项已经由用户明确确认并冻结：

> P0 PostgreSQL DFETL 业务/控制表冻结为 39 张，其中保留 `alert_rule_channel` 作为 Alert Rule 与 Channel 的多对多关系表。Quartz JDBC JobStore 11 张官方表单独统计。因此 Flyway V1 负责创建 50 张表；`flyway_schema_history` 由 Flyway 自身创建，不计入 P0/V1 业务表数量。

后续不得在没有新的明确业务需求和 Review 的情况下随意新增 P0 PostgreSQL 表。若技术实现需要额外持久化对象，必须先证明现有 39 张 DFETL 表和 11 张 Quartz 表无法表达该事实，再进入 Review。

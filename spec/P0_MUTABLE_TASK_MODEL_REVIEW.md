# P0 可变任务配置模型 Review

> 状态：阶段 1 Snapshot 最小充分性已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Task：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. Task = 固定身份 + 当前配置

```text
sync_task
= 固定 Institution + Dataset 身份
+ 当前可编辑执行配置
```

同一 Institution + Dataset 一个未删除 Task。普通编辑直接更新当前 `sync_task`，不创建 Task Version。

明确不建立：

```text
sync_task_version
sync_task.current_version_id
version_no
task contract_hash
Task 发布/切换/回退状态机
```

## 2. Task 身份不可修改

```text
institution_id
dataset_id
```

创建后不可修改。需要更换身份时：

```text
确认无活动 Sync Execution
→ 逻辑删除旧 Task
→ 创建新 Task
```

旧 Watermark、Execution、Batch、Validation、Outbox、Delete/Audit 历史全部继续归属旧 `task_id`，不迁移。

## 3. 当前配置

`sync_task` 当前保存：

```text
institution_id
dataset_id
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
schedule_mode
schedule_interval_hours
schedule_cron
schedule_timezone
schedule_source
schedule_source_revision
schedule_enabled
validation_method_override
revision
```

用户可修改 Route/Dataset Version、名称、读取参数、调度和 Validation Override；活动 Sync Execution 期间禁止编辑。

平台不因编辑自动：

```text
重置 Watermark
重新全量
补采
迁移 Delete Snapshot Baseline
```

## 4. Validation Override

Task 只保存：

```text
validation_method_override = NULL / ROW_COUNT / ROW_COUNT_CHECKSUM
```

NULL 即继承，不建立 `task_validation_policy/override_mode`。

最终解析：

```text
Task Override
→ Dataset Override
→ system_setting[validation.default_method]
→ Registered Default ROW_COUNT
→ Dataset Contract Capability
```

## 5. 活动 Sync Execution 期间禁止编辑

活动状态：

```text
PENDING
RUNNING
LOADING
VALIDATING
```

编辑固定流程：

```text
锁定 sync_task
→ 检查活动 sync_execution
→ 有则返回 TASK_EXECUTION_ACTIVE
→ 无则按 revision 更新当前配置
```

Execution 启动与 Task 编辑使用同一 Task 行锁或等效事务串行化。

## 6. 独立 Validation 期间允许编辑

普通独立 Validation：

```text
trigger_type IN ('MANUAL','SCHEDULED')
AND status IN ('PENDING','RUNNING')
```

不阻止 Task 普通编辑。

原因是独立 Validation 启动时已经冻结最小上下文：

```text
task_id + task_revision
routeVersionId
sourceRuntimeSnapshot
targetRuntimeSnapshot
range_snapshot
最终 Validation Method/Source
```

不可变 Dataset/Route/Field Contract 不复制，通过永久定义引用解释。

任务编辑与独立 Validation 启动同时发生时，通过锁定同一 `sync_task` 确定“旧配置快照”或“新配置快照”的清晰边界，不形成混合快照。

MANUAL_RECHECK 不属于“新独立上下文”：它关联原 `sync_execution`，完全复用原 Execution 上下文。

## 7. Snapshot 总原则

已确认：

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

永久不可变定义：

```text
standard_dataset_version
standard_dataset_field
field_conversion_contract / field_conversion_rule
collection_route_version
route_field_resolution
```

这些对象不在 Runtime JSON 中复制第二份完整事实。

## 8. `sync_execution` 最小充分 Snapshot

Execution 创建时固定：

### 稳定身份/定义引用

```text
task_id + task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
```

### 本次实际 Task 执行参数

```text
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size/upper_bound_delay_minutes/lookback_seconds
```

### 非 Secret Runtime Endpoint

```text
source_runtime_snapshot
target_runtime_snapshot
```

这两份 JSON 冻结本次实际 Source/Target 可编辑连接资源中的非 Secret Endpoint/Revision/运行参数；不保存 Password/Credential。

### 本次运行原因/范围

```text
operation_type/trigger_type/execution_scope/target_prepare_mode
window_lower/window_upper
key_lower/key_upper
watermark_before/watermark_commit_expected
schedule_fire_time/external request/requested user
```

### 最终 Validation

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
checksum_protocol_version（仅 ROW_COUNT_CHECKSUM 非空）
```

### Message Policy

```text
message_policy_snapshot
```

只保存 Dataset Message Policy 本次生效值，不保存 RabbitMQ Connection Secret。

明确不保存：

```text
完整 Dataset/Route/Field Contract JSON
Task Name/Schedule Config Snapshot
Validation Override 链完整 JSON
precheck_fact_snapshot
Secret/Credential
```

## 9. `validation_run` 上下文边界

### SYNC_GATE / MANUAL_RECHECK

```text
execution_id NOT NULL
context_snapshot NULL
range_snapshot NULL
```

所有上下文从父 Execution 读取，不复制第二份。

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

### DELETE_RECONCILIATION

```text
execution_id NULL
context_snapshot NULL
range_snapshot NULL
baseline/current delete_snapshot_run FK 非空
```

上下文唯一来自 Snapshot Run。

## 10. `message_outbox`

删除 `task_version_id`；Outbox 通过父 Execution 复合 FK 固定身份，并保存：

```text
显式 Message Policy Snapshot 字段
publish_scope
最小 range_snapshot
```

Range JSON 不重复 Execution/Task/Dataset/Institution ID 或 `operation_type`。

Outbox 不复制父 Execution Target Runtime Endpoint；人工重发按已确认语义重新读取当前 Doris。

## 11. Watermark

```text
task_id
watermark_value
source_execution_id
```

不保存 Task Version。Task 配置变化不自动修改 Watermark；显式“清除水位”可物理删除当前 Watermark Row。

## 12. 数据库与应用边界

数据库负责：

- 未删除 Task Institution + Dataset 唯一；
- Route/Dataset/Institution 强 FK 一致；
- 三种标准 Task 组合；
- 调度字段组合；
- Validation Override 受控值；
- 同 Task 活动 Sync/Independent Validation 并发兜底；
- Runtime Snapshot JSON 类型和各运行类型 NULL/非 NULL CHECK。

应用负责：

- Task Identity 不提供修改入口；
- 启动时在同一 Task 锁边界读取完整当前配置；
- 生成 Execution/Independent Validation 最小 Snapshot；
- Runtime Snapshot 严格剔除 Secret；
- 记录 Task 修改 Audit；
- 同步 Quartz Projection；
- 展示当前 Task 与历史 Runtime Snapshot，不用当前 Task 值覆盖历史。

## 13. 废止旧描述

不得进入 V1/API/Entity：

```text
sync_task_version/current_version_id/task_version_id
task_validation_policy/override_mode
Task 发布/迁移/回退
待生效配置
Task Identity 修改/历史搬迁
独立 Validation 运行期间禁止编辑普通 Task 配置
Execution/Validation 复制完整不可变定义 JSON
precheck_fact_snapshot
Credential History
```

## 14. 验收

- Task 保存固定身份 + 当前配置，不版本化。
- 活动 Sync 期间不可编辑，普通独立 Validation 期间可编辑。
- Execution 能解释本次实际 Task 参数、Runtime Endpoint、范围、Validation 和 Message Policy。
- Dataset/Route/Field Contract 只通过不可变引用解释。
- Execution-bound Validation 不复制父 Execution Context。
- 独立 Validation 有最小充分非 Secret Context。
- Outbox 是小型自包含发布指令，不是 Payload Replay。
- 所有历史不因 Task 后续修改而变化。

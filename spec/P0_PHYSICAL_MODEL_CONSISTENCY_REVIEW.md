# P0 物理模型一致性 Review

> 状态：2026-08-17 Active Spec 语义扫描完成；进入最终表/FK/Enum/Delete Matrix Review  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 本轮收口目标

本轮连续完成两类旧模型迁移：

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

## 2. 当前唯一主模型

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

同样不恢复：Validation Disable、Tolerance、Validation Lookback、Auto Revalidate/Fail Block、Task-level Message Policy。

## 4. 当前关键 FK 身份

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

## 7. 本轮 Active Spec 修改范围

### 第一阶段：Resource / Route

- [x] `PRODUCT_AND_BUSINESS_DECISIONS.md`
- [x] `TARGET_METADATA_MODEL.md`
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md`
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`
- [x] `DATABASE_MIGRATION_BASELINE.md`
- [x] `LEGACY_FUNCTION_ALIGNMENT.md`
- [x] `JAVA_PRODUCTION_MIGRATION_REVIEW.md`
- [x] Phase/TASKS/Pending Decision 入口文档

### 第二阶段：Task Version / Validation Policy

按用户确认顺序：

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`
- [x] `EXTERNAL_API_REVIEW.md`
- [x] `QUARTZ_JOBSTORE_REVIEW.md`

### Full Spec Scan 额外发现并修正

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`：Route Version 四元父 Unique。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`：Execution 四元 Route FK、Precheck 单机构、Execution→Outbox Composite Identity。
- [x] `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`：Delete Snapshot 四元 Route FK。
- [x] `P0_SUPPORT_OBJECT_REVIEW.md`：删除旧 `global_validation_policy` Current Semantics 和 Quartz Current Task Version。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY.md`：总索引/替代关系更新。
- [x] `P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md`：清理状态更新。
- [x] `P0_GLOBAL_VALIDATION_SETTING_REVIEW.md`：清理状态更新。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`：标记旧 Policy Table 清理完成。
- [x] `P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`：旧 Immediate Supplemental Increment 清理状态更新。
- [x] `TASKS.md` / `PHASE1_REVIEW_STATUS.md` / `PHASE1_REMAINING_AND_IMPLEMENTATION_PLAN.md` 同步完成状态。

## 8. Full Spec Scan 判断规则

目标不是旧字符串出现次数为 0。

以下属于**合法引用**：

```text
历史审计
旧模型说明
已废止
明确不建立
不得进入 V1
机械迁移映射
```

以下属于**非法残留**，本轮已经清理主要 Active Spec：

```text
Active Object List 仍包含旧表
FK 指向旧表
Task Create 仍创建 First Task Version
Quartz 仍读取 Current Task Version
Validation 当前值仍保存于独立 Policy Table
Current Flow 仍要求 Publish/Switch/Rollback Task Version
```

历史/Legacy Audit 文件可以保留旧对象名称，但不得被当成目标模型输入。

## 9. 当前尚未完成

这些是**技术一致性 Review**，不是新的业务问题：

- [ ] 最终 P0 PostgreSQL Table List + Count。
- [ ] Quartz Official Table List。
- [ ] Doris ODS/RAW/Technical Table List。
- [ ] 全量 FK Matrix：Child Columns / Parent Unique / ON DELETE / Child Index。
- [ ] 全量 Business Unique / Concurrency Unique Matrix。
- [ ] Status / Enum / CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Sensitive Field / Secret Boundary Final Check。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性 Final Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

## 10. 当前结论

截至本轮收口，Active Spec 的**业务语义层**已经不再依赖：

```text
Business System Instance
Multi-Institution Route
Task Version
Independent Validation Policy Tables
```

后续若再次出现这些概念，必须先证明是合法历史/废止引用；不得从旧 Review 或历史 SQL 恢复到目标模型。

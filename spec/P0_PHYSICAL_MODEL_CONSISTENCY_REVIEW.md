# P0 物理模型一致性 Review

> 状态：阶段 1 Active Spec 语义收口完成，继续做最终表/FK/枚举矩阵  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 已完成的两轮结构性收口

### 1.1 接入资源 / Route

```text
旧：业务系统实例 ↔ 多机构 ↔ 多数据源 → 共享 Route 覆盖机构
新：机构 + 业务目录 → Source datasource → 单机构 Route
```

已删除目标模型中的：

```text
business_system_instance
business_system_instance_institution
business_system_instance_datasource
collection_route_institution
collection_route_version_institution
```

### 1.2 Task Version / Validation Policy

```text
旧：sync_task → current Task Version → 运行
新：sync_task 当前配置 → Execution/Validation 启动快照
```

```text
旧：global/dataset/task validation policy 表
新：system_setting + Dataset override + Task override
```

当前明确：

```text
sync_task_version                 不建立
sync_task.current_version_id      不建立
全部 task_version_id              不建立

global_validation_policy         不建立
dataset_validation_policy        不建立
task_validation_policy           不建立
override_mode                     不建立
```

## 2. 当前 Resource 表

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

不变量：

- Source 必须直接保存 `institution_id + business_catalog_id`。
- `source_datasource(id,institution_id)` 提供 Route 机构一致性父唯一键。
- Target Doris 不绑定机构和业务目录。

## 3. 当前 Dataset / Route / Task 主链

```text
standard_dataset
  └── standard_dataset_version
       └── standard_dataset_field

collection_route
  └── collection_route_version
       └── route_field_resolution

sync_task
  └── task_watermark
```

### 3.1 Route

- Route 只有一个 Institution。
- 一 Institution + Dataset 一条未删除 Route。
- Route Source 必须属于同一 Institution。
- Route Version 保存 `institution_id/dataset_id/dataset_version_id` 不可变快照。

标准 Route Version 父唯一键：

```text
UNIQUE(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
)
```

### 3.2 Task

- 一 Institution + Dataset 一个未删除 Task。
- `institution_id/dataset_id` 是不可修改业务身份。
- `sync_task` 直接保存当前 Dataset Version、Route Version、读取/写入/调度/Validation Override。
- 普通编辑覆盖当前配置并增加 `revision`；活动同步 Execution 阻止编辑。
- 活动独立 Validation 不阻止普通编辑；该 Validation 使用启动快照。

Task 使用：

```text
FOREIGN KEY (
  route_version_id,
  institution_id,
  dataset_id,
  dataset_version_id
)
REFERENCES collection_route_version(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
)
```

## 4. 当前 Validation 配置模型

唯一持久化入口：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

允许最终方法：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

`NULL` Override = 继承。

解析：

```text
Task
→ Dataset
→ Global System Setting
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

Execution 固定：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
```

正式同步 Validation 不可关闭、不允许容差、不使用隐式 Validation Lookback。

## 5. 当前运行主链

```text
sync_task
  └── sync_execution
       ├── load_batch
       ├── validation_run (SYNC_GATE)
       └── message_outbox

sync_task
  ├── task_watermark
  ├── validation_run (MANUAL/SCHEDULED)
  └── delete_snapshot_run
       └── task_delete_snapshot_state

collection_route
  └── precheck_run
       └── precheck_issue_summary
```

### 5.1 Route Version 运行身份统一

以下对象都使用同一四元 Route Version 身份：

```text
sync_task
sync_execution
precheck_run
delete_snapshot_run
```

不再有任何运行模型依赖 `collection_route_version_institution`。

### 5.2 Precheck 单机构收口

- `precheck_run` 保存当前 Route 的 Institution/Dataset/Dataset Version 快照。
- `precheck_issue_summary` 不重复保存 Institution，不存在 `INSTITUTION` Rule Scope。
- Issue 只保存 STRUCTURE/FIELD/COMPOSITE 汇总，不保存行级问题。

## 6. 并发和业务唯一约束

必须继续保证：

```text
Source code/identity 唯一
Route active UNIQUE(institution_id,dataset_id)
Task active UNIQUE(institution_id,dataset_id)
同 Task 一个活动 sync_execution
同 Route 一个活动 precheck_run
同 Task 一个活动独立 validation_run
同 Task 一个活动 delete_snapshot_run
同 Execution 最多一个 message_outbox
External API UNIQUE(client_id,request_id)
External API UNIQUE(client_id,nonce)
```

跨表“同步 vs 独立 Validation”通过锁定同一 `sync_task` 进行事务检查。

## 7. Active Spec 扫描处置规则

全 `spec/` 扫描时，不要求旧关键词出现次数为 0。

### 合法保留

以下场景允许出现：

```text
sync_task_version
global_validation_policy
dataset_validation_policy
task_validation_policy
collection_route_version_institution
```

前提是语义明确为：

```text
历史对象
已废止
明确不建立
不得进入 V1
旧模型机械清理映射
```

### 非法残留

以下属于必须清理的错误：

- active object list 把旧表当目标表；
- FK 指向已删除旧表；
- 创建 Task 时仍要求创建第一个 Task Version；
- Quartz 仍读取 current Task Version；
- Validation 当前配置仍通过独立 Policy 表保存；
- 当前流程仍要求 Task Version 发布/切换/回退。

本轮已完成这些非法残留的主要 Active Spec 修正。

## 8. 本轮已完成文件

### Task Version / Validation Policy 直接清理

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`
- [x] `EXTERNAL_API_REVIEW.md`
- [x] `QUARTZ_JOBSTORE_REVIEW.md`

### 全 Spec 扫描发现的结构残留

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`：补 Route Version 四元父唯一键。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`：删除多机构 Route FK；Precheck 收敛为单机构。
- [x] `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`：删除多机构 Route FK。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY.md`：更新全局索引和最终替代关系。

此前已收口：

- [x] `PRODUCT_AND_BUSINESS_DECISIONS.md`
- [x] `TARGET_METADATA_MODEL.md`
- [x] `DATABASE_MIGRATION_BASELINE.md`
- [x] `LEGACY_FUNCTION_ALIGNMENT.md`
- [x] `JAVA_PRODUCTION_MIGRATION_REVIEW.md`
- [x] `PHASE1_REVIEW_STATUS.md`
- [x] `PHASE1_REMAINING_AND_IMPLEMENTATION_PLAN.md`

## 9. 仍待完成的阶段 1 技术一致性工作

以下不是新的业务确认问题：

- [ ] 形成最终 P0 PostgreSQL 表清单及总数。
- [ ] 对**全部** FK 建立“子列 → 父唯一键 → 删除行为 → 子索引”矩阵并逐项核对。
- [ ] 形成全部业务唯一性/并发唯一约束矩阵。
- [ ] 统一所有状态/CHECK 枚举名称。
- [ ] 形成删除行为矩阵。
- [ ] 最终核对 Execution/Validation/Outbox Snapshot 字段是否最小且足够。
- [ ] 完成 `PHASE1_FINAL_REVIEW.md`。

## 10. 当前结论

Active Spec 当前目标模型统一为：

```text
机构 + 业务目录
→ Source/Target
→ Dataset + immutable Dataset Version
→ 单机构 Route + immutable Route Version
→ 当前配置 Task
→ Execution/Validation 启动快照
```

Task Version 和三层 Validation Policy 已完成语义迁移；后续扫描若看到相关词，必须先判断它是否只是“历史/废止说明”，不得重新恢复旧模型。

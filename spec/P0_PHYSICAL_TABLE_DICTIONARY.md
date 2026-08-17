# P0 物理表字典总索引

> 状态：阶段 1 物理模型一致性收口中  
> 最近更新：2026-08-17  
> 适用数据库：新系统独立 PostgreSQL 元数据库 + Doris 技术表  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文是总索引和全局约定，不替代各拆分字典；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 全局约定

- PostgreSQL 业务表、Quartz 表和 Flyway History 使用新数据库 `df_etl` Schema。
- 表/列/索引使用小写 `snake_case`。
- 主键默认 `bigint identity`；跨系统运行标识可使用 UUID。
- 时间统一 `timestamptz`。
- 状态使用受控 `varchar + CHECK`，不创建 PostgreSQL ENUM。
- 可变配置使用 `revision` 乐观锁。
- Version/Execution/Validation/Outbox/Audit 历史不能因配置对象删除被级联破坏。
- Secret 不写 Audit 摘要；敏感凭据使用加密密文或部署 Secret。
- 阶段 1 只收敛文档模型，不修改旧 `df_ygt/df_etl` 数据库。

## 2. 当前权威物理字典

| 领域 | 权威文档 |
| --- | --- |
| 接入资源 | `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` |
| Dataset / Field Contract / Dataset 配置 | `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` |
| Route / Task 关系 | `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` |
| Task / Watermark | `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| Validation 配置/门禁 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| Execution / Batch / Precheck / Validation / Outbox | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |
| 删除识别 | `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md` |
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

发生冲突时，以用户最新确认及日期更晚的已确认专项 Review 为准。

## 3. 当前核心 PostgreSQL 主链

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint

standard_dataset
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
generic_jdbc_type_mapping
dataset_sync_policy
dataset_message_policy

collection_route
collection_route_version
route_field_resolution

sync_task
task_watermark

sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

以及支撑对象：

```text
app_user
audit_log
system_setting
alert_* 
external_api_*
delete_snapshot_run
task_delete_snapshot_state
delete_apply_run
Quartz 官方 qrtz_* 表
```

## 4. 当前模型主关系

```text
source_datasource
  → institution + business_catalog

collection_route
  → institution + standard_dataset
  → source_datasource + target_datasource

collection_route_version
  → collection_route
  → standard_dataset_version
  → immutable Source/Target/Field Resolution snapshot

sync_task
  → institution + standard_dataset
  → current dataset_version_id + route_version_id

sync_execution / precheck_run / delete_snapshot_run
  → immutable Route Version identity snapshot
```

### 4.1 Source 与 Route 机构一致性

父键：

```text
source_datasource(id,institution_id) UNIQUE
```

Route：

```text
FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
```

### 4.2 Route Version 四元身份

标准父键：

```text
collection_route_version(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
) UNIQUE
```

Task/Execution/Delete Snapshot 使用该四元身份，直接保证：

```text
Route Version
+ Institution
+ Dataset
+ Dataset Version
```

属于同一不可变 Route Snapshot。

不再建立/引用：

```text
collection_route_institution
collection_route_version_institution
```

## 5. Task Version 的最终替代关系

目标模型：

```text
sync_task
= 固定 Institution + Dataset 身份
+ 当前可编辑配置
+ revision
```

历史不可变性：

```text
sync_execution / validation_run 启动快照
```

明确不存在：

```text
sync_task_version
sync_task.current_version_id
task_version_id
Task Version 发布/切换/回退状态机
```

继续存在并保持不可变的 Version：

```text
standard_dataset_version
collection_route_version
field_conversion_contract / field_conversion_rule
```

## 6. Validation 配置最终存储

只存在：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

解析顺序：

```text
Task override
→ Dataset override
→ system setting
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

最终运行值固定到 `sync_execution`：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
```

明确不存在：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode
validation enabled/disabled
row tolerance
validation lookback
auto revalidate/fail_block
```

## 7. 明确不得进入 V1 的旧对象

- 独立业务系统实例及其机构/数据源多对多表；
- Route 多机构覆盖关系表；
- `sync_task_version` 和全部 `task_version_id`；
- 三张独立 Validation Policy 表；
- Task 级 Message Policy；
- 数据源组/任务组；
- 机构树；
- 标准 Task `CUSTOM_SQL`；
- Redis Stream P0 通道；
- 行级 Precheck/Validation Issue；
- 跨 Execution Resume/Checkpoint 状态表。

## 8. Doris 技术表

继续保留：

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

PostgreSQL 不重复维护 Doris 业务表结构登记表。

## 9. 阶段 1 最终验收重点

- 当前表清单与 `TARGET_METADATA_MODEL.md` 一致。
- Source 直接属于 Institution + Business Catalog。
- Route 为单机构模型。
- Route Version 四元身份 FK 在 Task/Execution/Delete Snapshot 统一使用。
- Task 为固定身份 + 当前配置模型，不存在 Task Version。
- Validation 只存在三层值，不存在 Policy 表。
- Execution/Validation 历史由启动快照解释。
- 所有 FK 父列有唯一约束，子列有必要索引。
- 状态、删除行为和并发唯一约束无冲突。
- 最终 `PHASE1_FINAL_REVIEW.md` 完成并由用户签字后，才能进入 Flyway V1。

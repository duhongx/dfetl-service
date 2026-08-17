# P0 物理表字典总索引

> 状态：阶段 1 P0 PostgreSQL 最终表清单已冻结；进入 FK Matrix Review  
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
| 支撑对象 | `P0_SUPPORT_OBJECT_REVIEW.md` |
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

发生冲突时，以用户最新确认及日期更晚的已确认专项 Review 为准。

## 3. P0 PostgreSQL 最终表清单

### 3.1 DFETL 领域/控制表：39 张

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

`DELETE_RECONCILIATION` 继续复用统一 `validation_run`，不重复增加删除校验表。

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

`alert_rule_channel` 明确保留，用于 Alert Rule 与 Channel 的结构化多对多关系，不使用 JSON Channel ID 数组替代。

### 3.2 Quartz JDBC JobStore：11 张

Quartz 官方 PostgreSQL JobStore 表单独统计：

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

这 11 张属于 Quartz 运行基础设施，不计入 DFETL 39 张领域/控制表；阶段 2 生成 V1 时按当前 Quartz 版本对应的官方 PostgreSQL Schema 建立，不自行发明业务字段。

### 3.3 数量口径

```text
DFETL P0 领域/控制表       39
Quartz JDBC JobStore       11
--------------------------------
Flyway V1 负责创建         50
```

`flyway_schema_history` 由 Flyway 自身创建和管理：

- 不计入 DFETL P0 39 张；
- 不计入 Quartz 11 张；
- 不计入 V1 自己定义的 50 张表；
- 新空库首次 migrate 后实际可看到该额外 Flyway 管理表。

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

Task/Execution/Precheck/Delete Snapshot 使用该四元身份，直接保证 Route Version + Institution + Dataset + Dataset Version 属于同一不可变 Route Snapshot。

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
- 跨 Execution Resume/Checkpoint 状态表；
- Scheduler Reconciliation 表；
- External API 应用层 Rate Limit/Quota 表；
- RBAC Role/Permission 表。

## 8. Doris 技术表

继续保留：

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

PostgreSQL 不重复维护 Doris 业务表结构登记表。

Doris 最终业务/技术表清单与数量将在后续最终物理一致性 Review 中单独核对，不改变本节已经冻结的 PostgreSQL `39 + 11` 口径。

## 9. 阶段 1 当前验收重点

已完成：

- [x] P0 DFETL PostgreSQL 领域/控制表清单冻结为 39 张。
- [x] Quartz PostgreSQL JobStore 清单冻结为 11 张。
- [x] V1 创建表数量口径冻结为 50 张。
- [x] `flyway_schema_history` 明确不计入 P0/V1 表数量。

下一项：

- [ ] 完整 FK Matrix：Child Columns / Parent Unique / ON DELETE / Child Index。

后续仍需：

- [ ] Business/Concurrency Unique Matrix。
- [ ] Status/Enum/CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性 Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

最终 `PHASE1_FINAL_REVIEW.md` 完成并由用户签字后，才能进入 Flyway V1。

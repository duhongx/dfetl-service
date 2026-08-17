# P0 物理表字典总索引

> 状态：阶段 1 P0 PostgreSQL 表清单 + FK Matrix 已冻结；进入 Unique Matrix Review  
> 最近更新：2026-08-17  
> 适用数据库：新系统独立 PostgreSQL 元数据库 + Doris 技术表  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> 限制：本文是总索引和全局约定，不替代各拆分字典；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 全局约定

- PostgreSQL 业务表、Quartz 表和 Flyway History 使用新数据库 `df_etl` Schema。
- 表/列/索引统一小写 `snake_case`。
- 主键默认 `bigint identity`；跨系统运行标识可用 UUID。
- 时间统一 `timestamptz`。
- 状态使用受控 `varchar + CHECK`，不创建 PostgreSQL ENUM。
- 可变配置使用 `revision` 乐观锁。
- Version/Execution/Validation/Outbox/Audit 历史不能被配置删除级联破坏。
- Secret 不写 Audit；敏感凭据使用密文或部署 Secret。
- 阶段 1 只收敛文档，不修改正式数据库。

## 2. 权威物理文档

| 领域 | 权威文档 |
| --- | --- |
| 接入资源 | `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` |
| Dataset / Field Contract | `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` |
| Route / Field Resolution | `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` |
| Task / Watermark | `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| Validation 配置/门禁 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| Execution / Batch / Precheck / Validation / Outbox | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |
| 删除识别 | `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md` |
| 支撑对象 | `P0_SUPPORT_OBJECT_REVIEW.md` |
| 外键矩阵 | `P0_FOREIGN_KEY_MATRIX_REVIEW.md` |
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

冲突时以用户最新确认和日期更晚的已确认专项 Review 为准。

## 3. PostgreSQL 最终表清单

### DFETL 领域/控制表：39

```text
# Resource 5
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint

# Dataset/Contract 8
standard_dataset
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
generic_jdbc_type_mapping
dataset_sync_policy
dataset_message_policy

# Route/Task/Watermark 5
collection_route
collection_route_version
route_field_resolution
sync_task
task_watermark

# Runtime 6
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox

# Delete 3
delete_snapshot_run
task_delete_snapshot_state
delete_apply_run

# Support 12
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

### Quartz 官方表：11

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

数量：

```text
DFETL 39 + Quartz 11 = V1 创建 50 张
```

`flyway_schema_history` 由 Flyway 自身创建，不计入 50 张。

## 4. FK 总原则

已确认：

```text
最强复合 FK 优先
历史对象 RESTRICT
纯配置子对象可 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列必须有可用索引
```

Quartz 使用官方 PostgreSQL Schema，不做 DFETL 自定义 FK 重设计。

## 5. 当前关键复合身份

### Source → Route

```text
source_datasource(id,institution_id) UNIQUE

collection_route(source_datasource_id,institution_id)
→ source_datasource(id,institution_id)
```

### Route → Route Version

```text
collection_route(id,institution_id,dataset_id) UNIQUE

collection_route_version(route_id,institution_id,dataset_id)
→ collection_route(id,institution_id,dataset_id)
```

Route 当前指针：

```text
collection_route(id,current_version_id)
→ collection_route_version(route_id,id)
DEFERRABLE INITIALLY DEFERRED
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

被 Task/Execution/Precheck/Delete Snapshot 使用。

### Field Resolution

```text
route_field_resolution(
  route_version_id,
  dataset_version_id,
  standard_field_id
)
```

不再保存重复 `field_code`。

```text
(route_version_id,dataset_version_id)
→ collection_route_version(id,dataset_version_id)

(dataset_version_id,standard_field_id)
→ standard_dataset_field(dataset_version_id,id)
```

### Task → Execution

```text
sync_task(id,institution_id,dataset_id) UNIQUE
sync_execution(id,task_id) UNIQUE
```

Watermark/Validation 通过 `(execution_id,task_id)` 证明同 Task。

### External Request → Execution

```text
external_api_request(client_id,request_id) UNIQUE

sync_execution(external_client_id,external_request_id)
→ external_api_request(client_id,request_id)
```

### Execution → Outbox

```text
sync_execution(id,task_id,dataset_id,institution_id) UNIQUE

message_outbox(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id)
```

## 6. Task / Validation 最终替代关系

Task：

```text
sync_task = 固定 Institution + Dataset + 当前配置 + revision
sync_execution / validation_run = 启动快照
```

Validation：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

不建立 Task Version 和三张独立 Validation Policy 表。

## 7. 明确不得进入 V1

- Business System Instance 及多对多关联；
- Multi-Institution Route 表；
- `sync_task_version/task_version_id`；
- Global/Dataset/Task Validation Policy 表；
- Task-level Message Policy；
- DataSource/Task Group；
- Institution Tree；
- Standard Task CUSTOM_SQL；
- Redis Stream P0；
- Row-level Precheck/Validation Issue；
- Execution Resume/Checkpoint；
- Scheduler Reconciliation；
- External API Rate Limit/Quota；
- RBAC 表。

## 8. Doris 技术表

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

Doris 数量后续单独核对，不改变 PostgreSQL 39+11 口径。

## 9. 阶段 1 当前状态

已完成：

- [x] P0 DFETL PostgreSQL 表清单：39。
- [x] Quartz 表清单：11。
- [x] V1 创建数量：50。
- [x] `flyway_schema_history` 数量口径。
- [x] 全量 FK Matrix：Child Columns / Parent Unique / ON DELETE / Child Index。
- [x] Route/Route Version/Task/Execution/Field Resolution 强复合 FK 收口。
- [x] Watermark/Validation 同 Task Execution FK。
- [x] External Request → Execution FK。
- [x] User FK：普通审计 SET NULL / 运行责任 RESTRICT。

下一项：

- [ ] Business / Concurrency Unique Matrix。

后续：

- [ ] Status / Enum / CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性 Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

用户最终签字后才能进入 Flyway V1。

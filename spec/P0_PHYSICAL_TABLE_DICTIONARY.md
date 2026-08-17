# P0 物理表字典总索引

> 状态：阶段 1 P0 PostgreSQL 表清单 + FK Matrix + Unique Matrix 已冻结；进入 Status/Enum/CHECK Matrix Review  
> 最近更新：2026-08-17  
> 适用数据库：新系统独立 PostgreSQL 元数据库 + Doris 技术表  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
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
| 唯一/并发矩阵 | `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md` |
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

```text
DFETL 39 + Quartz 11 = V1 创建 50 张
```

`flyway_schema_history` 由 Flyway 自身创建，不计入 50 张。

## 4. FK 总原则

```text
最强复合 FK 优先
历史对象 RESTRICT
纯配置子对象可 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列必须有可用索引
```

完整矩阵见 `P0_FOREIGN_KEY_MATRIX_REVIEW.md`。Quartz 使用官方 PostgreSQL Schema，不做 DFETL 自定义 FK 重设计。

## 5. Unique 总原则

唯一性严格分三类：

```text
Business Unique
Concurrency / Safety Partial Unique
FK Support Unique
```

其中 FK Support Unique 只用于复合 FK，不得被解释成新的产品业务身份。

Business Unique 主要用于：

```text
稳定 Code / ID
父对象内 Version No
不可变内容 Hash
业务关系 Pair
一次运行 UUID / Event ID
父对象内自然序号
```

Concurrency / Safety Partial Unique 主要用于：

```text
Active Field Conversion Contract
Active Sync Execution per Task
Active Precheck per Route
Active Independent Validation per Task
Active Delete Snapshot per Task
Effective Real Delete Apply per Validation
```

完整矩阵见 `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

## 6. Dataset / Route Version 内容复用规则

`standard_dataset_version.definition_hash` 和 `collection_route_version.contract_hash` 都是不可变内容身份。

固定规则：

```text
Hash 与当前相同
→ 不创建 Version

Hash 与当前不同，但历史已存在相同 Hash
→ 复用历史不可变 Version
→ 切换 current_version_id
→ 不创建重复内容 Version
→ 写 audit_log

Hash 在历史中也从未出现
→ 创建新的 Version No
```

因此早期字典中的“Hash 变化 → 插入 Version”只能解释为“**新 Hash 从未在该父对象历史中出现**”。若命中历史 Hash，以本节和 `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md` 为准。

Version 表记录内容版本；“用户又保存了一次”由 `audit_log` 记录，不靠重复 Version 行记录操作历史。

## 7. 当前关键复合身份

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

## 8. 关键 Business / Concurrency Unique

### 当前 Route / Task

```sql
UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL;

UNIQUE INDEX uk_sync_task_active_institution_dataset
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL;
```

### Runtime

```sql
UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING');

UNIQUE INDEX uk_precheck_run_active_route
ON precheck_run(route_id)
WHERE execution_status IN ('PENDING','EXTRACTING','VALIDATING');

UNIQUE INDEX uk_validation_run_active_independent_task
ON validation_run(task_id)
WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
  AND status IN ('PENDING','RUNNING');

UNIQUE INDEX uk_delete_snapshot_run_active_task
ON delete_snapshot_run(task_id)
WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING');
```

### Delete Apply Safety

```sql
UNIQUE INDEX uk_delete_apply_effective
ON delete_apply_run(validation_run_id)
WHERE dry_run=false
  AND status IN ('PENDING','RUNNING','SUCCEEDED');
```

Dry Run 可多次；FAILED/PARTIAL_FAILED/CANCELLED 后可重新发起真实 Apply；SUCCEEDED 后不能再次发起真实 Apply。

### External Client

```text
UNIQUE(client_id)
```

`client_name` 可重复，不建立 Name Unique。

Alert Channel/Rule 因没有独立稳定 Code，继续 `lower(name)` 唯一。

## 9. Task / Validation 最终替代关系

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

## 10. 明确不得进入 V1

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
- RBAC 表；
- External Client Name Unique；
- Delete Apply 旧 `uk_delete_apply_active/uk_delete_apply_success` 双索引。

## 11. Doris 技术表

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

Doris 数量后续单独核对，不改变 PostgreSQL 39+11 口径。

## 12. 阶段 1 当前状态

已完成：

- [x] P0 DFETL PostgreSQL 表清单：39。
- [x] Quartz 表清单：11。
- [x] V1 创建数量：50。
- [x] 全量 FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Dataset/Route 历史相同 Hash 复用不可变 Version。
- [x] External Client Name 不唯一。
- [x] Delete Apply 使用单一 Effective Partial Unique。

下一项：

- [ ] Status / Enum / CHECK Matrix。

后续：

- [ ] Delete Behavior Matrix。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性 Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

用户最终签字后才能进入 Flyway V1。

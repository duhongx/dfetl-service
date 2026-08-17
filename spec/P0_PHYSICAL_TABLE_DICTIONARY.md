# P0 物理表字典总索引

> 状态：阶段 1 PostgreSQL Table + FK + Unique + Status/CHECK + Delete + Snapshot Matrix 已冻结  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 权威物理文档

| 领域 | 权威文档 |
| --- | --- |
| Resource | `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` |
| Dataset / Field Contract | `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` |
| Route / Field Resolution | `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` |
| Task / Watermark | `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| Validation | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| Execution / Precheck / Outbox | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |
| Delete Snapshot | `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md` |
| Support Object | `P0_SUPPORT_OBJECT_REVIEW.md` |
| FK Matrix | `P0_FOREIGN_KEY_MATRIX_REVIEW.md` |
| Unique Matrix | `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md` |
| Status / Enum / CHECK Matrix | `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` |
| Delete Behavior Matrix | `P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md` |
| Snapshot 最小充分性 | `P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md` |
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

冲突时以用户最新确认及日期更晚的专项 Matrix/Review 为准；Snapshot 字段/NULL/Secret 边界以 `P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md` 为最终 V1 基线。

## 2. PostgreSQL 表数量

```text
DFETL P0 领域/控制表 39
Quartz 官方表          11
-------------------------
V1 创建                50
```

`flyway_schema_history` 由 Flyway 自身管理，不计入 50。

## 3. 当前模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Current Sync Task
→ Execution / Validation Runtime Snapshot
```

不建立 Business System Instance、Multi-Institution Route、Task Version、独立 Validation Policy 表。

## 4. FK 原则

```text
最强复合 FK 优先
历史 RESTRICT
纯配置子对象可 CASCADE
普通审计用户 SET NULL
运行责任用户 RESTRICT
FK 子列具备索引
```

完整见 `P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

## 5. Unique 原则

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

- Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique。
- Dataset/Route 相同历史 Hash 复用旧不可变 Version。
- Execution/Precheck/Validation/Delete Snapshot 使用 Partial Unique。
- External Client 只保证 `client_id`，Name 可重复。
- Delete Apply 使用单条 `PENDING/RUNNING/SUCCEEDED` Safety Partial Unique。

完整见 `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

## 6. Status / Enum / CHECK 原则

```text
SUCCEEDED = 业务动作真正成功
COMPLETED + result = 检查/分析技术完成，结果另行表达
```

- Resource Test Status + 时间/错误组合固定。
- Route `status` / `structure_status` 独立。
- Dataset Default 不保存 EVERY_N_HOURS 最终 Cron；Task 保存最终 Cron。
- Execution Trigger/Operation/Range/Terminal/Cancel 使用严格 CHECK。
- `validation_run.validation_source` 额外允许 `FIXED`，只用于 Delete Reconciliation。
- Outbox/Delete Apply/Audit/Alert/External Request 终态组合固定。

完整见 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`；Snapshot 专属 NULL/Checksum Protocol CHECK 以 Snapshot Review 和最新 Execution/Validation 字典为准。

## 7. Delete Behavior 原则

### Resource

```text
institution/business_catalog/source_datasource/target_datasource
→ 无引用可物理删除
→ 有引用只能停用
→ 不增加 deleted_at
```

### Definition History

```text
standard_dataset → VOID
field_conversion_contract → RETIRED
Dataset Version / Field / Contract History → 永久保留
```

### Route / Task / Watermark

```text
collection_route → LOGICAL_DELETE
sync_task        → LOGICAL_DELETE
```

Watermark 仅显式 Clear 删除当前 Row，Task 删除不级联。

### Runtime / Support

Execution/Batch/Precheck/Validation/Outbox/Delete/Audit/External Request/Alert History 无普通 DELETE/自动 PostgreSQL retention；Nonce 1 小时 TTL；Doris RAW/Snapshot/Diff 按生命周期清理；Quartz 为可重建投影。

完整见 `P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`。

## 8. Snapshot 最小充分性原则

统一：

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

### Execution

不可变定义只保存 ID/FK：

```text
dataset_version_id
route_version_id
```

不复制 Definition Hash、字段列表、Route/Field Contract JSON。

实际可变运行事实保存：

```text
Task 实际执行参数
operation/trigger/scope/range
最终 Validation Resolution
message_policy_snapshot
source_runtime_snapshot
target_runtime_snapshot
```

新增：

```text
source_runtime_snapshot jsonb NOT NULL
target_runtime_snapshot jsonb NOT NULL
```

两者只保存非 Secret Endpoint/Revision/运行连接事实。

删除：

```text
precheck_fact_snapshot
```

Checksum Protocol：仅 `ROW_COUNT_CHECKSUM` 非空。

### Validation

```text
SYNC_GATE / MANUAL_RECHECK
→ context_snapshot/range_snapshot = NULL
→ 唯一上下文来自父 Execution

普通独立 Validation
→ context_snapshot/range_snapshot 非空
→ 最小 Context = routeVersionId + Source/Target Runtime Snapshot

DELETE_RECONCILIATION
→ context_snapshot/range_snapshot = NULL
→ 唯一上下文来自 baseline/current Snapshot Run FK
```

### Outbox

Outbox 保留显式 Message Policy Snapshot 和最小 `range_snapshot`，但 Range JSON 不重复：

```text
executionId/taskId/datasetId/institutionId/operationType
```

不复制 Execution Target Runtime Endpoint；人工重发读取当前 Doris。

### Secret

Runtime/Validation/Outbox Snapshot 禁止保存 DB/RabbitMQ/API/Webhook/JWT/Master Key/Authorization/HMAC 等 Secret。

完整见 `P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`。

## 9. 明确不得进入 V1

- Business System Instance / Multi-Institution Route；
- `sync_task_version/task_version_id`；
- Global/Dataset/Task Validation Policy；
- Task-level Message Policy；
- DataSource/Task Group、Institution Tree；
- Standard Task CUSTOM_SQL；
- Redis Stream P0；
- Row-level Precheck/Validation Issue；
- Execution Resume/Checkpoint；
- Scheduler Reconciliation；
- External API Rate Limit/Quota；
- RBAC；
- Runtime 历史 Archive/Retention 状态机；
- Runtime Snapshot 中完整不可变定义副本；
- `precheck_fact_snapshot`；
- Credential/Secret History。

## 10. Doris 技术表

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

Doris 大数据生命周期不改变 PostgreSQL 39+11 口径。

## 11. 阶段 1 当前状态

已完成：

- [x] PostgreSQL / Quartz 表清单与数量。
- [x] FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。
- [x] Delete Behavior Matrix。
- [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。

下一项：

- [ ] **`PHASE1_FINAL_REVIEW.md`。**

前端产品模型仍优先完成；阶段 1 最终 Review 和用户明确签字后，才能进入 Flyway V1/Java 后端实施。

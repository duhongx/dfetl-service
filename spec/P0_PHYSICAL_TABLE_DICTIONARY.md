# P0 物理表字典总索引

> 状态：阶段 1 PostgreSQL Table + FK + Unique + Status/CHECK + Delete Behavior Matrix 已冻结  
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
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

冲突时以用户最新确认及日期更晚的专项 Matrix/Review 为准。

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
→ Execution / Validation Startup Snapshot
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

完整见 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

## 7. Delete Behavior 原则

业务删除能力与 FK `ON DELETE` 分开解释。

### Resource

```text
institution/business_catalog/source_datasource/target_datasource
→ 无引用可物理删除
→ 有引用只能停用
→ 不增加 deleted_at
```

`target_datasource_fe_endpoint` 是纯当前配置，可物理删除。

### Definition History

```text
standard_dataset → VOID
field_conversion_contract → RETIRED
standard_dataset_version/field/contract history → 永久保留
```

`generic_jdbc_type_mapping` 是当前诊断配置，可物理删除。

### Route / Task / Watermark

```text
collection_route → LOGICAL_DELETE
sync_task        → LOGICAL_DELETE
```

Version/Field Resolution/Runtime History 永久保留。

```text
task_watermark
→ Task 删除不级联
→ 只有显式“清除水位”操作允许删除当前行
```

### Runtime / Audit / Idempotency / Alert History

以下 PostgreSQL 元数据无普通 DELETE/自动 retention：

```text
sync_execution/load_batch
precheck_run/precheck_issue_summary
validation_run/message_outbox
delete_snapshot_run/task_delete_snapshot_state/delete_apply_run
audit_log/external_api_request
alert_event/alert_delivery
```

### Support / Temporary / Projection

- Alert Rule/Channel 可物理删除，历史靠 Snapshot + SET NULL。
- User/External Client 只停用。
- System Setting 无通用 DELETE。
- External Nonce 1 小时 TTL。
- Client-Institution/Rule-Channel 等当前关系可物理增删。
- Doris RAW/Snapshot/Diff 按生命周期清理，但 PostgreSQL Run 保留。
- Quartz Job/Trigger 是可重建投影，随 Task 当前调度状态删除/重建。

完整见 `P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`。

## 8. 明确不得进入 V1

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
- 为历史对象预埋 Archive/Retention 状态机。

## 9. Doris 技术表

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

Doris 大数据生命周期不改变 PostgreSQL 39+11 口径。

## 10. 阶段 1 当前状态

已完成：

- [x] PostgreSQL / Quartz 表清单与数量。
- [x] FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。
- [x] Delete Behavior Matrix。

下一项：

- [ ] **Execution / Validation / Outbox Snapshot 最小充分性 Review。**

后续：

- [ ] `PHASE1_FINAL_REVIEW.md`。

前端产品模型仍优先完成；用户最终签字后才能进入 Flyway V1/Java 后端实施。

# P0 物理表字典总索引

> 状态：阶段 1 PostgreSQL Table + FK + Unique + Status/Enum/CHECK Matrix 已冻结  
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
| External API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

发生 Status/Enum/CHECK 冲突时，以 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` 和日期更晚的已确认专项 Review 为准。

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

三类明确区分：

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

- Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique。
- Execution/Precheck/Validation/Delete Snapshot 等活动对象使用 Partial Unique。
- FK Support Unique 不重复解释为业务唯一。
- Dataset/Route 相同历史 Hash 复用旧不可变 Version。
- External Client 只保证 `client_id` 唯一，`client_name` 可重复。
- Delete Apply 使用一条 `PENDING/RUNNING/SUCCEEDED` Safety Partial Unique。

完整见 `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

## 6. Status / Enum / CHECK 原则

状态分三类：

```text
生命周期 Status
完成 Result
配置 State
```

固定术语：

```text
SUCCEEDED
= 同步/批次/投递/删除应用等业务动作真正成功

COMPLETED + result
= Precheck/Validation/Delete Snapshot 技术完成，结果另行表达
```

关键结论：

- Resource Test：单点 `UNTESTED/SUCCESS/FAILED`；Target 聚合额外 `PARTIAL`。
- Route `status` 与 `structure_status` 独立，不做数据库强耦合。
- Dataset Sync Policy：INHERIT/MANUAL 不带 Cron/Timezone；EVERY_N_HOURS 只带 Interval+Timezone；Task 保存最终 Cron。
- Execution Trigger/Operation/Range/Terminal/Cancel 使用严格跨字段 CHECK。
- `sync_execution.validation_source = GLOBAL/DATASET/TASK/CONTRACT`。
- `validation_run.validation_source` 额外允许 `FIXED`，且只用于 `DELETE_RECONCILIATION + DELETE_KEY_DIFF`。
- Outbox/Delete Apply/Audit/Alert/External Request 的终态时间、错误和结果组合由 CHECK 保证。
- Count/Ratio/Port/Hash/JSON 类型执行统一基础 CHECK。

完整见 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

## 7. 明确不得进入 V1

- Business System Instance 及其多对多关联；
- Multi-Institution Route 关系表；
- `sync_task_version/task_version_id`；
- Global/Dataset/Task Validation Policy；
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

继续保留：

```text
每个 Dataset 固定 ODS
每个 Dataset 固定 RAW
_dfetl_key_snapshot
_dfetl_delete_diff
```

Doris 数量不改变 PostgreSQL 39+11 口径。

## 9. 阶段 1 当前状态

已完成：

- [x] PostgreSQL / Quartz 表清单与数量。
- [x] FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。

下一项：

- [ ] **Delete Behavior Matrix。**

后续：

- [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

前端产品模型仍优先完成；用户最终签字后才能进入 Flyway V1/Java 后端实施。

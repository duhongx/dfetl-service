# P0 物理表字典总索引

> 状态：P0 技术模型 Review 已通过；Phase 1 总体等待前端验收与 G-001 最终签字  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`  
> 限制：用户最终签字前不得创建/固化 `V1__baseline.sql`。

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
| Phase 1 Final Gate | `PHASE1_FINAL_REVIEW.md` |
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

完整见 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

## 7. Delete Behavior 原则

- Resource：无引用可物理删除，有引用只能停用，不增加逻辑删除；
- Dataset/Version/Field/Contract：永久定义历史，通过 VOID/RETIRED 表达失效；
- Route/Task：逻辑删除；
- Watermark：Task 删除不级联，仅显式 Clear 删除当前 Row；
- Runtime/Audit/External Request/Alert History：无普通 DELETE/自动 PostgreSQL retention；
- External Nonce：1 小时 TTL；
- Doris RAW/Snapshot/Diff：按生命周期清理，PostgreSQL Run 保留；
- Quartz：可重建投影。

完整见 `P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`。

## 8. Snapshot 最小充分性原则

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

### Execution

- `dataset_version_id/route_version_id` 只引用永久不可变定义；
- 保存实际 Task 执行参数、运行原因/范围、最终 Validation、Message Policy；
- 新增非 Secret `source_runtime_snapshot/target_runtime_snapshot`；
- 删除 `precheck_fact_snapshot`；
- Checksum Protocol 仅 `ROW_COUNT_CHECKSUM` 非空。

### Validation

```text
SYNC_GATE / MANUAL_RECHECK → 父 Execution 是唯一 Context
普通独立 Validation       → 最小 Context/Range
DELETE_RECONCILIATION      → Snapshot Run FK 是唯一 Context
```

### Outbox

保留显式 Message Policy Snapshot + 最小 `range_snapshot`，不重复显式身份/`operationType`，不复制 Target Runtime Endpoint；人工重发读取当前 Doris。

所有 Runtime/Validation/Outbox Snapshot 禁止 DB/RabbitMQ/API/Webhook/JWT/Master Key/Authorization/HMAC Secret。

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

## 11. Phase 1 Final Review 状态

`spec/PHASE1_FINAL_REVIEW.md` 已完成。

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

技术模型已冻结，不再继续新增 Table/FK/Unique/Status/Delete/Snapshot 设计问题。

当前只剩：

```text
Frontend 与 Spec 100% 对齐
+ P-002
+ P-003
+ G-001 用户最终签字
```

在用户明确确认：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

之前，不得创建/固化 Flyway V1，也不得按最终模型推进 Java 后端实施。

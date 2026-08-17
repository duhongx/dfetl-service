# P0 物理模型一致性 Review

> 状态：P0 技术模型 Review 已通过；Phase 1 总体等待前端验收与最终签字  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`

## 1. 当前唯一主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Runtime Snapshot
```

不建立 Business System Instance、Multi-Institution Route、Task Version、独立 Validation Policy Table。

## 2. 技术模型最终状态

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

PostgreSQL 数量口径：

```text
DFETL 领域/控制表 39
Quartz 官方表       11
----------------------
V1 创建             50
```

`flyway_schema_history` 不计入 50。

## 3. 六项最终矩阵：全部完成

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。

权威文件：

```text
P0_FOREIGN_KEY_MATRIX_REVIEW.md
P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md
P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md
P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md
P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md
```

## 4. 已冻结技术原则

### FK

```text
最强复合 FK
历史 RESTRICT
纯配置 CASCADE
普通审计用户 SET NULL
运行责任用户 RESTRICT
FK 子列索引
```

### Unique

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

### Status

```text
SUCCEEDED = 真正业务成功
COMPLETED + result = 检查/分析技术完成
```

### Delete

- Resource 无引用可物理删除，有引用只能停用；
- Dataset/Version/Field/Contract 是永久定义历史；
- Route/Task 逻辑删除；Watermark 仅显式 Clear；
- Runtime/Audit/Request/Alert History 永久保留 PostgreSQL 元数据；
- External Nonce 1 小时 TTL；
- Doris 大数据按生命周期清理；Quartz 为可重建投影。

### Snapshot

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

- Execution 保存非 Secret Source/Target Runtime Snapshot；
- 删除 `precheck_fact_snapshot`；
- SYNC_GATE/MANUAL_RECHECK 使用父 Execution Context；
- 普通独立 Validation 保存最小 Context/Range；
- Delete Reconciliation 使用 Snapshot Run FK；
- Outbox 保留 Message Policy Snapshot + 最小 Range。

## 5. 已废止对象：不得因普通实现问题重新打开

```text
business_system_instance*
collection_route_institution
collection_route_version_institution
sync_task_version
task_version_id
global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode
Task-level Message Policy
Redis Stream P0
Standard Task CUSTOM_SQL
Institution Tree
RBAC
Scheduler Reconciliation
External API Rate Limit/Quota Table
Row-level Precheck/Validation Issue Table
Execution Resume/Checkpoint Table
```

若未来确有新的明确业务需求，必须重新进入专项 Review；不得在 Flyway/Entity/API 实现时自行恢复。

## 6. Final Review：完成，但总体未放行

`spec/PHASE1_FINAL_REVIEW.md` 已完成。

最终结论：

> 阶段 1 的目标元数据/物理模型技术 Review 已经通过，不再继续新增表/FK/Unique/Status/Delete/Snapshot 设计问题；但 Phase 1 总体尚未最终签字，因为前端产品模型与 Spec 的 100% 对齐尚未完成。当前仍不得创建 Flyway V1 或推进 Java 后端实施。待前端完成并收口 P-002/P-003 后，再执行 G-001 最终签字。

## 7. 当前唯一阶段阻塞

```text
Frontend 与已冻结 Spec 100% 对齐
+ P-002 管理员账号管理入口
+ P-003 Help/Docs 入口
+ G-001 用户最终签字
```

P-004/P-005/P-006/P-007 不阻塞当前技术模型冻结，也不得据此新增 P0 表或恢复废止模型。

## 8. 实施授权边界

当前：

```text
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

只有用户在前端收口后明确确认：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

或等价明确授权，才允许将状态改为：

```text
PHASE1_OVERALL = PASS
DATABASE_BACKEND_IMPLEMENTATION = AUTHORIZED
```

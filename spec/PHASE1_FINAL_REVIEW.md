# Phase 1 Final Review

> 状态：技术模型 Review 通过；Phase 1 总体等待前端验收与最终签字  
> 日期：2026-08-17  
> 分支：`duhongx/dfetl-service/main`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 最终状态

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

本文件明确区分：

1. **P0 目标元数据/物理模型技术 Review 是否完成**；
2. **Phase 1 总体是否已经满足进入数据库/后端实施的门槛**。

当前结论：技术模型已经完成并冻结；Phase 1 总体尚未最终签字。

## 2. 技术模型 Review：PASS

以下最终一致性工作已经完成：

1. P0 PostgreSQL 最终表清单与数量；
2. 全量 FK Matrix；
3. Business / Concurrency Unique Matrix；
4. Status / Enum / CHECK Matrix；
5. Delete Behavior Matrix；
6. Execution / Validation / Outbox Snapshot 最小充分性 Review。

最终 PostgreSQL 数量口径：

```text
DFETL P0 领域/控制表       39
Quartz JDBC JobStore       11
--------------------------------
Flyway V1 负责创建         50
```

`flyway_schema_history` 由 Flyway 自身创建和管理，不计入上述 50 张。

当前主模型固定为：

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Runtime Snapshot
```

## 3. 已冻结的结构原则

### 3.1 FK

```text
最强复合 FK
历史 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列具备可用索引
```

### 3.2 Unique

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

- Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique；
- Dataset/Route 相同历史 Hash 复用旧不可变 Version；
- Execution/Precheck/Validation/Delete Snapshot 等活动对象使用 Partial Unique；
- FK Support Unique 不解释成业务唯一。

### 3.3 Status / CHECK

```text
SUCCEEDED
= 同步/批次/消息/删除应用等真正业务动作成功

COMPLETED + result
= Precheck/Validation/Delete Snapshot 等检查/分析技术完成，结果另行表达
```

### 3.4 Delete Behavior

- Resource：无引用可物理删除，有引用只能停用，不增加 `deleted_at`；
- Dataset/Version/Field/Conversion Contract：永久定义历史，通过 `VOID/RETIRED` 表达失效；
- Route/Task：逻辑删除；
- Watermark：Task 删除不级联，仅显式 Clear 删除当前 Row；
- Runtime/Audit/External Request/Alert History：不提供普通 DELETE/自动 PostgreSQL retention；
- External Nonce：1 小时 TTL；
- Doris RAW/Snapshot/Diff：按生命周期清理，PostgreSQL Run 元数据保留；
- Quartz：可重建调度投影。

### 3.5 Snapshot

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

- Execution 通过 Dataset Version / Route Version 引用永久不可变定义；
- Execution 保存实际 Task 执行参数、运行范围、最终 Validation、Message Policy；
- Execution 保存不含凭据的 Source/Target Runtime Endpoint Snapshot；
- 不保存 `precheck_fact_snapshot`；
- SYNC_GATE/MANUAL_RECHECK 使用父 Execution 上下文；
- 普通独立 Validation 才保存最小 Context/Range；
- Delete Reconciliation 使用 Snapshot Run FK；
- Outbox 保存显式 Message Policy Snapshot + 最小 Range；
- 所有 Snapshot 禁止保存数据库/RabbitMQ/API/Webhook/JWT/Master Key/Authorization/HMAC Secret。

## 4. 明确废止且不得重新引入

除非出现新的明确业务需求并重新进入专项 Review，否则不得恢复：

```text
business_system_instance
business_system_instance_institution
business_system_instance_datasource

collection_route_institution
collection_route_version_institution

sync_task_version
sync_task.current_version_id
task_version_id

global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode

Task-level Message Policy
Redis Stream P0 通道
Standard Task CUSTOM_SQL
Institution Tree
RBAC Role/Permission
Scheduler Reconciliation
External API Application Rate Limit/Quota Table
Row-level Precheck/Validation Issue Table
Execution Resume/Checkpoint Table
```

后续普通实现问题不得借机重新打开上述已冻结模型。

## 5. 当前未放行原因：Frontend Acceptance

技术模型已完成，但当前 Phase 1 总体仍未满足最终签字条件。

当前前端仍需完成并逐页验收：

```text
Navigation / Information Architecture
Resource Pages
Institution Route Pages
Task Current Config UI
Precheck / Validation / Operations
Alert / Log / Audit / System Settings
所有菜单真实 URL
lint / build
逐页原型、状态、空态、错误态、危险确认
```

前端必须与已冻结 Spec 一致，尤其不得重新出现：

```text
Business System Instance
Multi-Institution Route
Task Version UI
独立 Validation Policy / Override Mode / Validation Disable / Tolerance
Task-level Message Policy
```

## 6. G-001 当前状态

```text
G-001 = PENDING_FINAL_SIGNOFF
```

当前**不能**使用以下签字语句：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

只有完成前端产品模型 100% 对齐并收口当前产品入口决策后，用户再次明确确认该签字语句，才能：

```text
PHASE1_OVERALL = PASS
DATABASE_BACKEND_IMPLEMENTATION = AUTHORIZED
```

在此之前：

- 不创建/固化 Flyway `V1__baseline.sql`；
- 不按最终模型批量整改 Java Entity/Repository/Service；
- 不把“技术模型 PASS”解释成数据库/后端实施授权。

## 7. 当前必须在最终签字前收口的产品事项

### P-002：管理员账号管理前端入口

当前推荐：

```text
系统设置 → 账号管理
```

能力固定为：列表、新增、启停、重置密码；不建设 RBAC。

### P-003：使用文档/帮助入口

当前推荐：

```text
顶部右侧 Help → /docs
```

不占用核心业务左侧导航。

P-002/P-003 属于当前前端产品模型收口项，必须在 G-001 最终签字前确认并落到页面。

## 8. 不阻塞当前技术模型冻结的 Pending Decisions

以下事项保留到各自实施阶段，不反向打开已经通过的技术模型 Review：

- `P-004` 删除差异人工应用安全阈值：在 Delete Apply 实现前确认；现有 `risk_threshold_snapshot` 可承载最终规则；
- `P-005` 首个管理员初始化方式：后端实施期确认；
- `P-006` 新旧系统配置/Watermark/切换策略：上线切换期确认；
- `P-007` GraalVM Native Image：已暂缓。

这些事项如只影响运行参数、部署方式或上线流程，不得据此新增 P0 表或恢复已废止模型。

## 9. 下一步

当前唯一正确推进顺序：

```text
完成前端页面/导航/交互/文案 100%
→ 收口 P-002 / P-003
→ 核对 Frontend 与已冻结 Spec 一致
→ 执行 G-001 最终签字
→ 才允许冻结 API Contract / Flyway V1 / Java 后端实施
```

## 10. Final Review 结论

> 阶段 1 的目标元数据/物理模型技术 Review 已经通过，不再继续新增表/FK/Unique/Status/Delete/Snapshot 设计问题；但 Phase 1 总体尚未最终签字，因为前端产品模型与 Spec 的 100% 对齐尚未完成。当前仍不得创建 Flyway V1 或推进 Java 后端实施。待前端完成并收口 P-002/P-003 后，再执行 G-001 最终签字。

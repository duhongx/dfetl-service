# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：技术模型 Review 已通过；P-002/P-003 已确认；等待前端 100% 对齐与 G-001 最终签字  
> 分支：`duhongx/dfetl-service/main`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`

## 1. 当前状态

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

阶段 1 尚未最终签字：

- 不创建/固化 Flyway `V1__baseline.sql`；
- 不按最终模型批量修改 Java Entity/Repository/Service；
- 不修改正式 PostgreSQL 结构；
- 新系统不认领老 `df_ygt/df_etl`；
- 前端产品模型继续优先完成。

## 2. 技术 Review 已完成

- [x] Java / Legacy SQL Audit。
- [x] Dataset / Field Contract / Doris ODS-RAW Review。
- [x] Resource + Single-Institution Route 收口。
- [x] Mutable Task / Execution / Batch / Validation / Outbox / Delete / External API / Quartz Review。
- [x] Business System Instance / Multi-Institution Route 清理。
- [x] Task Version / Validation Policy 清理。
- [x] PostgreSQL DFETL 39 + Quartz 11 = V1 50。
- [x] FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。
- [x] Delete Behavior Matrix。
- [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。
- [x] `PHASE1_FINAL_REVIEW.md` 技术总验收。
- [x] P-002 产品入口决策：`系统设置 → 账号管理`。
- [x] P-003 产品入口决策：`顶部右侧 Help → /docs`。

## 3. 当前主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Runtime Snapshot
```

六项技术矩阵均已冻结，不再因普通实现问题继续新增 Table/FK/Unique/Status/Delete/Snapshot 设计问题。

## 4. 当前唯一阻塞项

G-001 最终签字前只剩：

```text
Frontend 与已冻结 Spec 100% 对齐
```

产品入口决策已全部完成，前端必须实际落地：

```text
系统设置 → 账号管理
顶部右侧 Help → /docs
```

并且左侧业务导航不增加“使用文档”。

前端仍需完成：

- [ ] Navigation / Information Architecture。
- [ ] Resource Pages。
- [ ] Institution Route Pages。
- [ ] Task Current Config UI，不出现 Task Version。
- [ ] Precheck / Validation / Operations。
- [ ] Alert / Log / Audit / System Settings。
- [ ] 账号管理页面实际位于 `系统设置 → 账号管理`。
- [ ] 顶部右侧 Help 实际进入 `/docs`，且左侧不增加“使用文档”。
- [ ] 所有菜单真实 URL。
- [ ] lint / build。
- [ ] 逐页原型、状态、空态、错误态、危险确认验收。

## 5. 不阻塞技术模型冻结的 Pending Decisions

```text
P-004 Delete Apply Safety Threshold → 实现前确认
P-005 First Admin Bootstrap          → 后端实施期确认
P-006 Old/New Cutover & Watermark    → 上线切换期确认
P-007 GraalVM Native Image           → 暂缓
```

这些事项不得反向新增 P0 表或恢复已废止模型，除非出现新的明确业务需求并重新进入专项 Review。

## 6. G-001 最终签字

当前：

```text
G-001 = PENDING_FINAL_SIGNOFF
```

只有前端 100% 收口完成后，用户明确确认：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

或等价明确授权，才能进入：

```text
PHASE1_OVERALL = PASS
DATABASE_BACKEND_IMPLEMENTATION = AUTHORIZED
```

在此之前继续保持数据库/后端实施未授权。

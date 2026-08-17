# 阶段 1 剩余 Review 与后续实施规划

> 状态：技术模型 Review 已通过；P-002 已确认；当前只剩 P-003、前端 100% 对齐与 G-001 最终签字  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`

## 1. 当前状态

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

已确认业务规则和六项技术矩阵不再重复讨论；旧残留只做机械清理。

## 2. 已完成

- [x] Java / Legacy SQL 审计。
- [x] Dataset / Field Contract / Doris ODS-RAW Review。
- [x] Mutable Task / Execution / Batch / Validation / Outbox / Delete / External API / Quartz Review。
- [x] Business System Instance / Multi-Institution Route 收口。
- [x] Task Version / Validation Policy 收口。
- [x] Active Spec 语义扫描。
- [x] PostgreSQL DFETL 39 + Quartz 11 = V1 50。
- [x] 全量 FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。
- [x] Delete Behavior Matrix。
- [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。
- [x] `PHASE1_FINAL_REVIEW.md` 技术总验收。
- [x] P-002 管理员账号管理入口：`系统设置 → 账号管理`。

## 3. 已冻结物理基线

```text
FK            → P0_FOREIGN_KEY_MATRIX_REVIEW.md
Unique        → P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md
Status/CHECK  → P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md
Delete        → P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md
Snapshot      → P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md
Final Gate    → PHASE1_FINAL_REVIEW.md
```

普通实现问题不得重新打开已冻结的 Table/FK/Unique/Status/Delete/Snapshot 模型。

## 4. 当前剩余工作：Frontend 100%

这是 G-001 最终签字前的唯一阶段阻塞。

### 4.1 信息架构

- [ ] Navigation 与最新模型一致。
- [ ] Resource：Institution / Business Catalog / Source / Target / 医共体标准。
- [ ] 独立 Institution Route。
- [ ] 不出现 Business System Instance。
- [ ] Task / Precheck / Validation / Operations 分工清晰。
- [x] P-002 产品入口决策：`系统设置 → 账号管理`。
- [ ] 账号管理页面按该入口实际落页并验收。
- [ ] P-003 Help/Docs 入口确认并落页。

### 4.2 页面/交互

- [ ] Resource CRUD/启停/Test/引用保护。
- [ ] Route：Dataset → Source → Schema → Object → Target。
- [ ] Route Structure Check / Enable / OUTDATED。
- [ ] Task Current Config UI，不出现 Task Version。
- [ ] Validation UI 不出现旧 Policy/Override Mode/关闭/容差。
- [ ] Precheck 与正式同步分离。
- [ ] Watermark / Backfill / Recollect / Cancel 文案正确。
- [ ] Alert / Log / Audit / System Settings 完整。
- [ ] 账号管理：列表、新增、启停、重置密码；不建设 RBAC。

### 4.3 前端验收

- [ ] 所有菜单真实 URL，可刷新/回退。
- [ ] `npm run lint` 通过。
- [ ] `npm run build` 通过。
- [ ] 逐页核对原型、状态、空态、错误态、处理中和危险确认。
- [ ] 页面字段与已冻结 Spec 一致。

## 5. 当前入口决策状态

```text
P-002: 已确认 → 系统设置 → 账号管理
P-003: 待确认 → 当前建议：顶部右侧 Help → /docs
```

P-002 的产品决策已经收口，但实际页面仍需完成。

当前唯一尚未确认的前端入口决策是 P-003。

## 6. 不阻塞当前技术模型冻结的事项

- P-004 Delete Apply Safety Threshold：实现前确认；
- P-005 First Admin Bootstrap：后端实施期确认；
- P-006 Old/New Cutover & Watermark：上线切换期确认；
- P-007 GraalVM Native Image：暂缓。

它们不得反向新增 P0 表或恢复已经废止的模型。

## 7. G-001 最终签字

P-003 和前端全部完成后再次核对：

```text
Frontend 与 Spec 100% 一致
+ P-002 已按确认入口落页
+ P-003 已确认并落页
+ PHASE1_FINAL_REVIEW 仍无新增阻塞
```

然后由用户明确确认：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

只有此时才允许进入数据库/后端实施。

## 8. G-001 之后的实施顺序

```text
前端确认
→ G-001 最终签字
→ API Contract 最终冻结
→ Flyway V1
→ Java Entity / Repository / Service
→ PostgreSQL / External Integration Test
→ SeaTunnel / Doris / Quartz / RabbitMQ
→ E2E
→ Multi-instance / Large Batch Reliability
→ Production Acceptance / Cutover
```

在 G-001 之前不得提前创建 Flyway V1 或批量整改 Java 后端。

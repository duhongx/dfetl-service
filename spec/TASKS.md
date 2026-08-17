# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：P0 技术模型 Review 已通过；P-002 已确认，Phase 1 总体等待前端验收、P-003 与 G-001 最终签字  
> 最近更新：2026-08-17  
> 产品基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`

## 1. 当前核心模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Current Config
→ Execution / Validation Runtime Snapshot
```

- [x] 不建设 Business System Instance。
- [x] Route 单机构。
- [x] Task 固定 Institution + Dataset，不建立 Task Version。
- [x] Validation 不建立 Global/Dataset/Task Policy Table。
- [x] Message Policy 只在 Dataset 级。
- [x] RabbitMQ Only。

## 2. PostgreSQL 表清单：完成

```text
DFETL 39 + Quartz 11 = V1 创建 50 张
```

- [x] `alert_rule_channel` 计入 39 张。
- [x] Quartz 使用 11 张官方表。
- [x] `flyway_schema_history` 不计入 50。

## 3. 六项技术矩阵：全部完成

### FK

权威：`P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

- [x] 最强复合 FK。
- [x] 历史 RESTRICT。
- [x] 纯配置 CASCADE。
- [x] 普通审计 User SET NULL。
- [x] 运行责任 User RESTRICT。
- [x] FK 子列索引。

### Business / Concurrency Unique

权威：`P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

- [x] Business / Concurrency / FK Support Unique 分离。
- [x] Dataset/Route 相同历史 Hash 复用旧 Version。
- [x] External Client Name 可重复。
- [x] Delete Apply Safety Partial Unique。

### Status / Enum / CHECK

权威：`P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

- [x] `SUCCEEDED` 与 `COMPLETED + result` 分离。
- [x] Resource/Route/Schedule/Execution/Validation/Outbox/Delete/Audit/Alert/External Request CHECK 收口。
- [x] `validation_run.validation_source=FIXED` 只用于 Delete Reconciliation。

### Delete Behavior

权威：`P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`。

- [x] Resource 无引用可物理删除，有引用只能停用。
- [x] Dataset/Version/Field/Contract 历史永久保留；Dataset=VOID，Contract=RETIRED。
- [x] Route/Task 逻辑删除；Watermark 仅显式 Clear。
- [x] Runtime/Audit/External Request/Alert History 无普通 DELETE/自动 PostgreSQL retention。
- [x] App User/External Client 只停用；System Setting 无通用 DELETE；External Nonce 1 小时 TTL。
- [x] Doris RAW/Snapshot/Diff 按生命周期清理，Quartz 为可重建投影。

### Snapshot 最小充分性

权威：`P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`。

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

- [x] Execution 保存非 Secret Source/Target Runtime Snapshot。
- [x] 删除 `precheck_fact_snapshot`。
- [x] SYNC_GATE/MANUAL_RECHECK 只使用父 Execution Context。
- [x] 普通独立 Validation 才保存最小 Context/Range。
- [x] Delete Reconciliation 只使用 Snapshot Run FK。
- [x] Outbox 保留 Message Policy Snapshot + 最小 Range。
- [x] Checksum Protocol 只在 ROW_COUNT_CHECKSUM 时保存。

## 4. PHASE1_FINAL_REVIEW：已完成

权威：`spec/PHASE1_FINAL_REVIEW.md`。

当前三态：

```text
TECHNICAL_MODEL_REVIEW = PASS
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

技术模型不再继续新增 Table/FK/Unique/Status/Delete/Snapshot 设计问题。

## 5. 当前最高优先级：前端 100%

G-001 最终签字前必须完成：

- [ ] Navigation 与最新产品模型一致。
- [ ] Resource 页面完整。
- [ ] Institution Route 页面完整。
- [ ] Task UI 不出现 Task Version。
- [ ] Validation UI 不出现旧 Policy/Override Mode/关闭/容差。
- [ ] Precheck 与正式同步分离。
- [ ] Watermark/Backfill/Recollect/Cancel 文案正确。
- [ ] Alert/Log/Audit/System Settings 完整。
- [ ] 所有菜单真实 URL。
- [ ] lint/build/逐页原型验收。
- [x] **P-002 管理员账号管理入口决策：`系统设置 → 账号管理`。**
- [ ] 账号管理页面按已确认入口实际落页并验收。
- [ ] P-003 Help/Docs 入口确认并落页。

前端必须与已冻结 Spec 100% 对齐。

## 6. 阶段 1 最终签字门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [x] Unique Matrix。
- [x] Status/Enum/CHECK Matrix。
- [x] Delete Behavior Matrix。
- [x] Snapshot 最小充分性 Review。
- [x] `PHASE1_FINAL_REVIEW.md` 完成。
- [x] P-002 产品入口决策收口。
- [ ] P-003 产品入口决策收口。
- [ ] Frontend 与 Spec 100% 一致，包括账号管理实际落页。
- [ ] 用户 G-001 最终签字。

最终授权语句：

```text
目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。
```

在用户明确授权前，不创建/固化 Flyway V1，不按最终模型推进 Java 后端整改。

## 7. 不阻塞当前技术模型冻结的后续事项

- P-004：Delete Apply 安全阈值，在实现前确认；
- P-005：首个管理员初始化方式，后端实施期确认；
- P-006：新旧系统配置/水位/切换策略，上线期确认；
- P-007：GraalVM Native Image，已暂缓。

上述事项不得反向新增 P0 表或恢复已废止模型，除非出现新的明确业务需求并重新进入专项 Review。

## 8. G-001 之后的实施顺序

只有最终签字后才进入：

1. API Contract 最终冻结；
2. Final P0 Flyway V1；
3. Java Entity/Repository/Service；
4. PostgreSQL/External Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ；
6. E2E；
7. Multi-instance/Large Batch Reliability；
8. Production Acceptance/Cutover。

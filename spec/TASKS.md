# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：P0 Table + FK + Unique + Status/CHECK + Delete Behavior 已冻结；前端优先  
> 最近更新：2026-08-17  
> 产品基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 当前核心模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Dataset Version
→ Single-Institution Route + Route Version
→ Sync Task Current Config
→ Execution / Validation Startup Snapshot
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

## 3. FK Matrix：完成

权威：`P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

- [x] 最强复合 FK。
- [x] 历史 RESTRICT。
- [x] 纯配置 CASCADE。
- [x] 普通审计 User SET NULL。
- [x] 运行责任 User RESTRICT。
- [x] FK 子列索引。

## 4. Business / Concurrency Unique Matrix：完成

权威：`P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

- [x] Business / Concurrency / FK Support Unique 分离。
- [x] Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique。
- [x] Dataset/Route 相同历史 Hash 复用旧 Version。
- [x] External Client Name 可重复。
- [x] Delete Apply Safety Partial Unique。

## 5. Status / Enum / CHECK Matrix：完成

权威：`P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

- [x] `SUCCEEDED` 与 `COMPLETED + result` 分离。
- [x] Resource Test、Route 双状态、Dataset/Task Schedule、Execution、Validation、Outbox/Delete Apply/Audit/Alert/External Request CHECK 收口。
- [x] `validation_run.validation_source=FIXED` 只用于 Delete Reconciliation。

## 6. Delete Behavior Matrix：完成

权威：`P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`。

- [x] Resource：无引用可物理删除，有引用只能停用，不增加逻辑删除。
- [x] Dataset/Version/Field/Contract 历史永久保留；Dataset 用 VOID，Contract 用 RETIRED。
- [x] Route/Task 使用逻辑删除。
- [x] Task 删除不级联 Watermark；只有显式“清除水位”才 DELETE 当前 Watermark Row。
- [x] Execution/Batch/Precheck/Validation/Outbox/Delete/Audit/External Request/Alert History 无普通 DELETE/自动 PostgreSQL retention。
- [x] FE Endpoint、Rule-Channel、Client-Institution、Generic JDBC Mapping 等当前配置可物理删除。
- [x] Alert Rule/Channel 可物理删除，历史依赖 Snapshot + SET NULL。
- [x] App User/External Client 只停用。
- [x] System Setting 无通用 DELETE。
- [x] External Nonce 1 小时 TTL。
- [x] Doris RAW/Snapshot/Diff 按生命周期清理但保留 PostgreSQL Run。
- [x] Quartz Job/Trigger 是可重建投影。

## 7. 阶段 1 技术一致性 Review 顺序

严格一次讨论一个：

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [ ] **Execution / Validation / Outbox Snapshot 最小充分性 Review。**
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论 Snapshot 最小充分性 Review。

## 8. 当前最高开发优先级：前端 100%

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

前端模型确认后再冻结最终 API Contract。

## 9. 前端之后

1. API Contract；
2. Flyway V1；
3. Java Entity/Repository/Service；
4. PostgreSQL/External Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ；
6. E2E；
7. Multi-instance/Large Batch Reliability；
8. Production Acceptance/Cutover。

## 10. 阶段 1 最终签字门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [x] Unique Matrix。
- [x] Status/Enum/CHECK Matrix。
- [x] Delete Behavior Matrix。
- [ ] Snapshot 最小充分性 Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

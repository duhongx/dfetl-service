# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：P0 Table + FK + Unique + Status/CHECK 已冻结；前端优先  
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

权威文档：`P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

- [x] 最强复合 FK。
- [x] 历史 RESTRICT。
- [x] 纯配置 CASCADE。
- [x] 普通审计 User SET NULL。
- [x] 运行责任 User RESTRICT。
- [x] FK 子列索引。
- [x] Route/Version/Task/Execution/Field Resolution 强复合身份闭环。
- [x] Watermark/Validation → 同 Task Execution。
- [x] External Request → Execution。
- [x] Execution → Outbox。

## 4. Business / Concurrency Unique Matrix：完成

权威文档：`P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

- [x] Business Unique、Concurrency Partial Unique、FK Support Unique 分离。
- [x] Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique。
- [x] Dataset/Route 相同历史 Hash 复用旧 Version。
- [x] External Client Name 可重复，只保证 `client_id`。
- [x] Delete Apply 使用单条 `PENDING/RUNNING/SUCCEEDED` Safety Partial Unique。

## 5. Status / Enum / CHECK Matrix：完成

权威文档：`P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`。

- [x] `SUCCEEDED` 与 `COMPLETED + result` 语义分离。
- [x] Resource Test Status + 时间/错误组合统一。
- [x] Route Business Status / Structure Status 保持独立。
- [x] Dataset Sync Policy 不保存 EVERY_N_HOURS 最终 Cron；Task 保存最终 Cron。
- [x] Execution Trigger/Operation/Range/Terminal/Cancel CHECK 收紧。
- [x] `sync_execution.validation_source` 只有 `GLOBAL/DATASET/TASK/CONTRACT`。
- [x] `validation_run.validation_source` 增加 `FIXED`，只用于 Delete Reconciliation。
- [x] Precheck/Validation/Delete Snapshot 使用 `COMPLETED + result`。
- [x] Outbox/Delete Apply/Audit/Alert/External Request 终态组合固定。
- [x] Count/Ratio/Port/Hash/JSON 基础 CHECK 统一。

## 6. 阶段 1 技术一致性 Review 顺序

严格一次讨论一个：

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [ ] **Delete Behavior Matrix。**
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论 Delete Behavior Matrix。

## 7. 当前最高开发优先级：前端 100%

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

## 8. 前端之后

1. API Contract；
2. Flyway V1；
3. Java Entity/Repository/Service；
4. PostgreSQL/External Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ；
6. E2E；
7. Multi-instance/Large Batch Reliability；
8. Production Acceptance/Cutover。

## 9. 阶段 1 最终签字门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [x] Unique Matrix。
- [x] Status/Enum/CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Snapshot 最小充分性 Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

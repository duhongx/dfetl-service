# 阶段 1 剩余 Review 与后续实施规划

> 状态：Table + FK + Unique + Status/CHECK + Delete + Snapshot 已确认；前端优先 + Phase 1 Final Review 待完成  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 执行原则

1. 已确认业务规则不重复讨论；旧残留机械清理。
2. 主线：Resource + Single-Institution Route + Current Task + Minimal Runtime Snapshot。
3. 前端页面/交互仍优先；技术一致性 Review 只收 Spec。
4. 阶段 1 最终签字前不创建 Flyway V1、不修改正式数据库。
5. 新系统只使用独立 PostgreSQL。

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

## 3. 已冻结物理基线

```text
FK            → P0_FOREIGN_KEY_MATRIX_REVIEW.md
Unique        → P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md
Status/CHECK  → P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md
Delete        → P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md
Snapshot      → P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md
```

### Snapshot 关键规则

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

- Execution 新增非 Secret Source/Target Runtime Snapshot。
- Execution 删除 `precheck_fact_snapshot`。
- Dataset/Route/Field Contract 不复制完整定义 JSON。
- SYNC_GATE/MANUAL_RECHECK 只使用父 Execution Context。
- 普通独立 Validation 才保存最小 Context/Range。
- Delete Reconciliation 只依赖 Snapshot Run FK。
- Outbox 保留 Message Policy Snapshot + 最小 Range，不复制 Target Endpoint/Payload。
- Checksum Protocol 仅 `ROW_COUNT_CHECKSUM` 保存。

## 4. 最终物理一致性 Review 顺序

按用户确认顺序严格一次一个：

1. [x] PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] **`PHASE1_FINAL_REVIEW.md`。**

下一项只处理 Phase 1 Final Review。

## 5. Phase 1 Final Review 目标

Final Review 只做**总验收与阻塞项确认**，不重新设计已经冻结的六项矩阵。

需要核对：

```text
1. 当前业务主模型是否只有一套且无旧模型 Current Semantics
2. 39 + 11 = 50 表口径是否与所有字典一致
3. FK / Unique / Status / Delete / Snapshot 五类矩阵是否互相无冲突
4. 敏感字段/Secret 是否不存在运行快照与 Audit 泄漏
5. 前端产品模型与当前 Spec 是否一致
6. 是否仍存在必须在进入 Flyway/后端前解决的阻塞项
```

最终只有在 `PHASE1_FINAL_REVIEW.md` 完成并由用户明确签字后，才能进入数据库/后端实施。

## 6. 前端产品完成 100%

当前最高开发优先级：

- [ ] Navigation 与最新模型一致。
- [ ] Resource：Institution/Business Catalog/Source/Target/医共体标准。
- [ ] 独立 Institution Route。
- [ ] 不出现 Business System Instance。
- [ ] Task Current Config，不出现 Task Version。
- [ ] Validation UI 不出现旧 Policy/Override Mode/关闭/容差。
- [ ] Precheck 与正式同步分离。
- [ ] Watermark/Backfill/Recollect/Cancel 文案正确。
- [ ] Alert/Log/Audit/System Settings 完整。
- [ ] URL/lint/build/逐页原型验收。

前端验收后再冻结 API Contract。

## 7. 前端之后

1. API Contract；
2. Final P0 Flyway V1；
3. Java Entity/Repository/Service；
4. PostgreSQL/External Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ；
6. E2E；
7. Multi-instance/Large Batch Reliability；
8. Production Acceptance/Cutover。

## 8. 阶段门槛

```text
Active Spec 无冲突
+ Table/FK/Unique/Status/Delete/Snapshot Matrix 完成
+ Frontend Model 确认
+ PHASE1_FINAL_REVIEW
+ 用户明确签字
```

在此之前不进入数据库/Java 实施。

# 阶段 1 剩余 Review 与后续实施规划

> 状态：PostgreSQL 表清单 + FK Matrix + Unique Matrix 已确认；前端优先 + Status/Enum/CHECK Matrix 待 Review  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 执行原则

1. 已确认业务规则不重复讨论；旧残留机械清理。
2. 主线：Resource + Single-Institution Route + Current Task + Runtime Snapshot。
3. 前端页面/交互仍优先完成；技术一致性 Review 只收 Spec。
4. 阶段 1 最终签字前不创建 Flyway V1、不修改正式数据库。
5. 新系统只使用独立 PostgreSQL。

## 2. 已完成

- [x] Java/Legacy SQL 审计。
- [x] Dataset/Field Contract/Doris ODS-RAW Review。
- [x] Mutable Task / Execution / Batch / Validation / Outbox / Delete / External API / Quartz Review。
- [x] Business System Instance / Multi-Institution Route 收口。
- [x] Task Version / Validation Policy 收口。
- [x] Active Spec 语义扫描。
- [x] PostgreSQL DFETL 表清单：39。
- [x] Quartz 官方表：11。
- [x] V1 表数量口径：50；Flyway History 不计。
- [x] 全量 FK Matrix。
- [x] Business / Concurrency Unique Matrix。

## 3. FK Matrix 已确认

权威文件：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

```text
最强复合 FK
历史 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列具备索引
```

关键闭环：

```text
Source(id,institution)
→ Route(source,institution)

Route(id,institution,dataset)
→ RouteVersion(route,institution,dataset)

Route(id,current_version)
→ RouteVersion(route,id) Deferred

RouteVersion(id,institution,dataset,dataset_version)
→ Task/Execution/Precheck/DeleteSnapshot

FieldResolution(route_version,dataset_version)
→ RouteVersion(id,dataset_version)

FieldResolution(dataset_version,standard_field)
→ DatasetField(dataset_version,id)

Execution(id,task)
→ Watermark/Validation

ExternalRequest(client,request)
→ External Execution

Execution(id,task,dataset,institution)
→ Outbox
```

## 4. Unique Matrix 已确认

权威文件：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

分类：

```text
Business Unique
Concurrency / Safety Partial Unique
FK Support Unique
```

固定结论：

- 稳定 Code/ID、父内 Version No、不可变内容 Hash、业务关系 Pair 使用 Business Unique。
- FK Support Unique 不重复算产品业务唯一。
- Dataset/Route 历史相同 Hash 直接复用旧不可变 Version；新 Hash 才新增 Version No。
- Current Route / Current Task 使用 `deleted_at IS NULL` Partial Business Unique。
- 活动 Execution/Precheck/Independent Validation/Delete Snapshot 使用 Partial Unique。
- 一个 Execution 最多一个 SYNC_GATE/Outbox；一个 Candidate 最多一个 Delete Reconciliation。
- External Client 只 `client_id` 唯一，`client_name` 可重复。
- Alert Channel/Rule 因没有独立 Code，Name 继续大小写不敏感唯一。
- Delete Apply 使用一条 `uk_delete_apply_effective` 覆盖 PENDING/RUNNING/SUCCEEDED；成功后不可再次真实 Apply。
- Sync vs Independent Validation 跨表互斥不新增 Lock/Slot 表。

## 5. 工作包 B：最终物理一致性矩阵

按用户确认顺序：

1. [x] PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [ ] **Status / Enum / CHECK Matrix。**
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

Doris Table List、Sensitive Field/Secret Boundary 在最终物理 Review 中同步核对，但不打乱上述讨论顺序。

## 6. 前端产品完成 100%

当前最高开发优先级。

### 信息架构

- [ ] Navigation 与最新模型一致。
- [ ] Resource：Institution/Business Catalog/Source/Target/医共体标准。
- [ ] 独立 Institution Route。
- [ ] 不出现 Business System Instance。
- [ ] Task/Precheck/Validation/Operations 分工清晰。
- [ ] Account/Help 入口按 Pending Decisions 确认。

### 页面/交互

- [ ] Resource CRUD/启停/Test/引用保护。
- [ ] Route：Dataset → Source → Schema → Object → Target。
- [ ] Route Structure Check/Enable/OUTDATED。
- [ ] Task Current Config UI，不出现 Task Version。
- [ ] Validation UI 不出现旧 Policy/Override Mode/关闭/容差。
- [ ] Precheck 与正式同步分离。
- [ ] Watermark/Backfill/Recollect/Cancel 文案正确。
- [ ] Alert/Log/Audit/System Settings 完整。
- [ ] URL/lint/build/逐页原型验收。

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
+ Table List 完成
+ FK Matrix 完成
+ Unique Matrix 完成
+ Status/Delete/Snapshot Matrix 完成
+ Frontend Model 确认
+ PHASE1_FINAL_REVIEW
+ 用户签字
```

在此之前不进入数据库/Java 实施。

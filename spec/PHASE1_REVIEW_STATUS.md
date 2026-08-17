# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：PostgreSQL 表清单 + FK Matrix + Unique Matrix 已确认；进入 Status/Enum/CHECK Matrix Review  
> 分支：`duhongx/dfetl-service/main`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 阶段约束

阶段 1 尚未最终签字：

- 不创建/固化 Flyway `V1__baseline.sql`。
- 不修改正式 PostgreSQL 结构。
- 新系统不认领老 `df_ygt/df_etl`。
- 新旧 PostgreSQL/Quartz/Execution/Watermark/Validation/Message 完全隔离。
- 前端产品模型仍优先完成；当前技术一致性 Review 只收敛 Spec。

## 2. 已完成

- [x] 老 Java 迁移完整性核对。
- [x] Legacy SQL Audit。
- [x] Dataset/Field Contract/Doris ODS-RAW Review。
- [x] Resource/Single-Institution Route 收口。
- [x] Mutable Task / Execution / Batch / Validation / Outbox / Delete / External API / Quartz Review。
- [x] Business System Instance / Multi-Institution Route 旧模型清理。
- [x] Task Version / Validation Policy Active Spec 清理。
- [x] PostgreSQL P0 表清单：DFETL 39。
- [x] Quartz PostgreSQL JobStore 表：11。
- [x] V1 数量口径：50；Flyway History 不计入。
- [x] `alert_rule_channel` 保留。
- [x] 全量 FK Matrix。
- [x] Business / Concurrency Unique Matrix。

## 3. 当前主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Dataset Version
→ Single-Institution Route + Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Startup Snapshot
```

## 4. FK 结论

```text
最强复合 FK
历史 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列具备索引
```

关键关系：

```text
Source(id,institution)
→ Route(source,institution)

Route(id,institution,dataset)
→ RouteVersion(route,institution,dataset)

Route(id,current_version)
→ RouteVersion(route,id) Deferred

RouteVersion(id,institution,dataset,dataset_version)
→ Task/Execution/Precheck/DeleteSnapshot

RouteFieldResolution(route_version,dataset_version)
→ RouteVersion(id,dataset_version)

RouteFieldResolution(dataset_version,standard_field)
→ StandardDatasetField(dataset_version,id)

Execution(id,task)
→ Watermark/Validation

ExternalRequest(client,request)
→ Execution(external_client,external_request)

Execution(id,task,dataset,institution)
→ MessageOutbox(...)
```

## 5. Unique 结论

唯一性分为：

```text
Business Unique
Concurrency / Safety Partial Unique
FK Support Unique
```

固定规则：

- 稳定 Code/ID、父内 Version No、不可变内容 Hash、关系 Pair 使用 Business Unique。
- FK Support Unique 不解释成业务身份。
- Dataset/Route 历史相同 Hash 复用历史不可变 Version，不创建重复内容 Version。
- Current Route / Current Task 使用逻辑删除条件 Partial Business Unique。
- 活动 Execution/Precheck/Independent Validation/Delete Snapshot 使用 Partial Unique 做并发兜底。
- External Client 只保证 `client_id` 唯一；`client_name` 可重复。
- Alert Channel/Rule Name 继续大小写不敏感唯一。
- Delete Apply 使用单一 `uk_delete_apply_effective` 覆盖 `PENDING/RUNNING/SUCCEEDED`。
- Sync vs Independent Validation 跨表互斥不新增 Lock/Slot 表。

权威文档：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

## 6. 当前 Validation

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
→ Dataset Contract Capability
```

不存在三张独立 Policy 表。

## 7. 当前 Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [ ] **Status / Enum / CHECK Matrix。**
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 4 项。

## 8. 前端仍为开发优先级

- [ ] Navigation/IA。
- [ ] Resource Pages。
- [ ] Institution Route Pages。
- [ ] Task/Precheck/Validation/Operations。
- [ ] Alert/Log/Audit/System Settings。
- [ ] lint/build/URL/逐页原型验收。

前端模型确认后再冻结最终 API Contract 和进入后端实现。

## 9. 最终门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [x] Unique Matrix。
- [ ] Status/CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Snapshot Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

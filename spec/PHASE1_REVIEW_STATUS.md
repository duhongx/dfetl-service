# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：PostgreSQL 表清单 + FK Matrix 已确认；进入 Unique Matrix Review  
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

## 3. 当前主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Dataset Version
→ Single-Institution Route + Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Startup Snapshot
```

## 4. 当前关键 FK 结论

已确认原则：

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

`route_field_resolution.field_code` 已删除，Field Code 从 Standard Field 读取。

## 5. 当前 Validation

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
→ Dataset Contract Capability
```

不存在三张独立 Policy 表。

## 6. 当前 Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [ ] **Business / Concurrency Unique Matrix。**
4. [ ] Status / Enum / CHECK Matrix。
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 3 项。

## 7. 前端仍为开发优先级

- [ ] Navigation/IA。
- [ ] Resource Pages。
- [ ] Institution Route Pages。
- [ ] Task/Precheck/Validation/Operations。
- [ ] Alert/Log/Audit/System Settings。
- [ ] lint/build/URL/逐页原型验收。

前端模型确认后再冻结最终 API Contract 和进入后端实现。

## 8. 最终门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [ ] Unique Matrix。
- [ ] Status/CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Snapshot Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

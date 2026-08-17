# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：PostgreSQL Table + FK + Unique + Status/CHECK + Delete Behavior 已确认；进入 Snapshot 最小充分性 Review  
> 分支：`duhongx/dfetl-service/main`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 阶段约束

阶段 1 尚未最终签字：

- 不创建/固化 Flyway `V1__baseline.sql`。
- 不修改正式 PostgreSQL 结构。
- 新系统不认领老 `df_ygt/df_etl`。
- 新旧 PostgreSQL/Quartz/Execution/Watermark/Validation/Message 完全隔离。
- 前端产品模型仍优先；当前技术一致性 Review 只收敛 Spec。

## 2. 已完成

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

## 3. 当前主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Startup Snapshot
```

## 4. 已确认物理矩阵

### FK

```text
最强复合 FK
历史 RESTRICT
纯配置 CASCADE
普通审计 User SET NULL
运行责任 User RESTRICT
FK 子列索引
```

### Unique

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

### Status / CHECK

```text
SUCCEEDED = 真正业务动作成功
COMPLETED + result = 检查/分析技术完成
```

### Delete Behavior

```text
Resource: 无引用可物理删，有引用只能停用
Dataset/Version/Field/Contract: 永久定义历史，VOID/RETIRED 表达失效
Route/Task: LOGICAL_DELETE
Watermark: Task 删除不级联，仅显式 Clear 删除当前 Row
Runtime/Audit/Request/Alert History: 永久 PostgreSQL 元数据
Alert Rule/Channel: 可物理删，Snapshot + SET NULL 保历史
User/External Client: 只停用
System Setting: 无通用 DELETE
External Nonce: 1 小时 TTL
Doris RAW/Snapshot/Diff: 按生命周期清理，PostgreSQL Run 保留
Quartz: 可重建投影
```

## 5. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [ ] **Execution / Validation / Outbox Snapshot 最小充分性 Review。**
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论 Snapshot 最小充分性 Review。

## 6. 前端仍为开发优先级

- [ ] Navigation/IA。
- [ ] Resource Pages。
- [ ] Institution Route Pages。
- [ ] Task/Precheck/Validation/Operations。
- [ ] Alert/Log/Audit/System Settings。
- [ ] lint/build/URL/逐页原型验收。

前端确认后再冻结最终 API Contract 和进入后端实施。

## 7. 最终门槛

- [x] Active Spec 业务语义收口。
- [x] PostgreSQL/Quartz 表清单。
- [x] FK Matrix。
- [x] Unique Matrix。
- [x] Status/CHECK Matrix。
- [x] Delete Behavior Matrix。
- [ ] Snapshot Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

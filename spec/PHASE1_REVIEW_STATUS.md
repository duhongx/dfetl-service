# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：PostgreSQL Table + FK + Unique + Status/CHECK 已确认；进入 Delete Behavior Matrix Review  
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

- [x] Java/Legacy SQL Audit。
- [x] Dataset/Field Contract/Doris ODS-RAW Review。
- [x] Resource + Single-Institution Route 收口。
- [x] Mutable Task / Execution / Batch / Validation / Outbox / Delete / External API / Quartz Review。
- [x] Business System Instance / Multi-Institution Route 旧模型清理。
- [x] Task Version / Validation Policy 清理。
- [x] PostgreSQL DFETL 表 39 + Quartz 11 = V1 50。
- [x] `alert_rule_channel` 保留。
- [x] 全量 FK Matrix。
- [x] Business / Concurrency Unique Matrix。
- [x] Status / Enum / CHECK Matrix。

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

- Dataset/Route 历史相同 Hash 复用原 Version。
- External Client Name 可重复。
- Delete Apply 成功后数据库层禁止再次真实 Apply。

### Status / CHECK

```text
SUCCEEDED
= 真正业务动作成功

COMPLETED + result
= 检查/分析技术完成，Result 单独表达
```

- Resource Test Status 与时间/错误组合固定。
- Route `status` / `structure_status` 独立。
- Dataset Sync Policy 不保存 EVERY_N_HOURS 最终 Cron；Task 保存最终 Cron。
- Execution Trigger/Operation/Range/Terminal/Cancel CHECK 严格。
- `sync_execution.validation_source = GLOBAL/DATASET/TASK/CONTRACT`。
- `validation_run.validation_source` 额外允许 `FIXED`，且只用于 Delete Reconciliation。
- Precheck/Validation/Delete Snapshot 使用 `COMPLETED + result`。
- Outbox/Delete Apply/Audit/Alert/External Request 终态 CHECK 固定。

## 5. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [ ] **Delete Behavior Matrix。**
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论 Delete Behavior Matrix。

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
- [ ] Delete Behavior Matrix。
- [ ] Snapshot Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：PostgreSQL Table + FK + Unique + Status/CHECK + Delete + Snapshot 已确认；进入 Phase 1 Final Review  
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
- [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。

## 3. 当前主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Runtime Snapshot
```

## 4. 已冻结物理矩阵

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
Definition History: 永久保留，VOID/RETIRED 表达失效
Route/Task: LOGICAL_DELETE
Watermark: 仅显式 Clear 删除当前 Row
Runtime/Audit/Request/Alert History: 永久 PostgreSQL 元数据
Nonce: 1 小时 TTL
Doris RAW/Snapshot/Diff: 清理大数据，保留 PostgreSQL Run
Quartz: 可重建投影
```

### Snapshot

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

Execution：

```text
新增 source_runtime_snapshot / target_runtime_snapshot
删除 precheck_fact_snapshot
Checksum Protocol 仅 ROW_COUNT_CHECKSUM 保存
```

Validation：

```text
SYNC_GATE / MANUAL_RECHECK → 只使用父 Execution Context
普通独立 Validation → 最小 Context/Range
DELETE_RECONCILIATION → 只使用 Snapshot Run FK
```

Outbox：

```text
显式 Message Policy Snapshot + 最小 Range
不重复身份/operationType
不复制 Target Runtime Endpoint
人工重发读取当前 Doris
```

所有 Runtime Snapshot 禁止 Secret。

## 5. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] **`PHASE1_FINAL_REVIEW.md`。**

下一项只进行 Phase 1 Final Review。

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
- [x] Snapshot Review。
- [ ] Frontend 与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户明确签字后才进入数据库/后端实施。

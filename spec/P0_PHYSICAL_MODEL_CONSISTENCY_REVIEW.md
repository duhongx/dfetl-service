# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL Table + FK + Unique + Status/Enum/CHECK Matrix 已确认  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`

## 1. 当前唯一主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Startup Snapshot
```

不建立 Business System Instance、Multi-Institution Route、Task Version、独立 Validation Policy Table。

## 2. PostgreSQL 表清单

```text
DFETL 领域/控制表 39
Quartz 官方表       11
----------------------
V1 创建             50
```

`flyway_schema_history` 不计入 50。

## 3. FK Matrix：完成

已确认：

```text
最强复合 FK
历史 RESTRICT
纯配置 CASCADE
普通审计用户 SET NULL
运行责任用户 RESTRICT
FK 子列索引
```

关键闭环包括 Route→Version 同父、Route Version 四元身份、Field Resolution Dataset Version/Field 归属、Watermark/Validation→同 Task Execution、External Request→Execution、Execution→Outbox。

## 4. Unique Matrix：完成

三类严格区分：

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

关键规则：

- Stable Code/ID、Parent Version No、Content Hash、Business Pair 使用 Business Unique。
- Dataset/Route 相同历史 Hash 复用原不可变 Version。
- Execution/Precheck/Validation/Delete Snapshot 使用 Partial Unique 做活动并发兜底。
- External Client Name 可重复，只保证 `client_id`。
- Delete Apply 使用单条 `PENDING/RUNNING/SUCCEEDED` Safety Partial Unique。

## 5. Status / Enum / CHECK Matrix：完成

### 5.1 状态语义

```text
SUCCEEDED
= 同步/批次/投递/删除应用等真正业务成功

COMPLETED + result
= Precheck/Validation/Delete Snapshot 技术完成，业务结果另行表达
```

### 5.2 Resource

- Source/FE Test：`UNTESTED/SUCCESS/FAILED`。
- Target Aggregate Test：`UNTESTED/SUCCESS/PARTIAL/FAILED`。
- Test Status 与 `last_tested_at/last_test_error` 组合由 CHECK 保证。
- Resource Enable/Disable 与 Test Result 保持独立。

### 5.3 Dataset / Task Schedule

Dataset Policy：

```text
INHERIT/MANUAL → interval/cron/timezone 均空
EVERY_N_HOURS → interval + timezone，cron 为空
CRON → cron + timezone
```

Task：

```text
MANUAL → interval/cron 空
EVERY_N_HOURS → interval + 最终错峰 cron + timezone
CRON → cron + timezone
```

Dataset Default 不保存最终 Quartz Cron。

### 5.4 Route

`status` 与 `structure_status` 独立，不做数据库耦合 CHECK；允许 `ENABLED + OUTDATED`。

### 5.5 Runtime

- Execution Trigger/Operation/Range/Terminal/Cancel CHECK 收紧。
- INCREMENTAL/BACKFILL_TIME 必须同时有 lower + upper。
- `CANCELLED` 只表示明确取消。
- Load Batch 只有 Doris `VISIBLE` 才 `SUCCEEDED`。
- Precheck 使用 `COMPLETED + PASS/ISSUES`。
- Validation 使用 `COMPLETED + PASS/MISMATCH`。

### 5.6 Validation Source

```text
sync_execution.validation_source:
GLOBAL / DATASET / TASK / CONTRACT
```

```text
validation_run.validation_source:
GLOBAL / DATASET / TASK / CONTRACT / FIXED
```

`FIXED` 只允许：

```text
DELETE_RECONCILIATION + DELETE_KEY_DIFF
```

### 5.7 Support / Delete

- Outbox `PUBLISHED/DEAD_LETTER` 的时间/错误组合已固定。
- Delete Snapshot 使用 `COMPLETED + result_type`。
- Delete Apply `SUCCEEDED/PARTIAL_FAILED/FAILED/CANCELLED` 的数量/时间/错误组合已固定。
- Audit Actor/Source 一一对应。
- Alert Delivery 与 External Request 终态组合已固定。

## 6. Active Spec 同步范围

本轮已新增/更新：

```text
P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md
P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md
P0_SUPPORT_OBJECT_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY.md
```

状态/CHECK 冲突时以新 Matrix 为最终 V1 基线。

## 7. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [ ] **Delete Behavior Matrix。**
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 5 项 Delete Behavior Matrix。

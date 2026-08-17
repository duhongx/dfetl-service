# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL Table + FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`

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

```text
最强复合 FK
历史 RESTRICT
纯配置 CASCADE
普通审计用户 SET NULL
运行责任用户 RESTRICT
FK 子列索引
```

Route/Version/Field Resolution/Task/Execution/Watermark/Validation/External Request/Outbox 的复合身份闭环已完成。

## 4. Unique Matrix：完成

```text
Business Unique
Concurrency Partial Unique
FK Support Unique
```

- Dataset/Route 相同历史 Hash 复用旧 Version。
- External Client Name 可重复。
- Delete Apply 使用单条 Safety Partial Unique。

## 5. Status / Enum / CHECK Matrix：完成

```text
SUCCEEDED = 真正业务成功
COMPLETED + result = 检查/分析技术完成
```

- Resource Test Status、Route 双状态、Dataset/Task Schedule、Execution Range/Trigger/Terminal/Cancel、Validation FIXED 来源、Outbox/Delete Apply/Audit/Alert/External Request CHECK 已收口。

## 6. Delete Behavior Matrix：完成

### 6.1 Resource

```text
Institution / Business Catalog / Source / Target
→ 无引用可物理删除
→ 有引用只能停用
→ 不增加 deleted_at
```

FE Endpoint 是当前配置，可物理删除。

### 6.2 Definition History

```text
standard_dataset → VOID
field_conversion_contract → RETIRED
Dataset Version / Field / Contract History → 永久保留
```

Generic JDBC Mapping 是当前诊断配置，可物理删除。

### 6.3 Route / Task / Watermark

```text
collection_route → LOGICAL_DELETE
sync_task → LOGICAL_DELETE
```

Version/Field Resolution/Runtime History 不删除。

```text
task_watermark
→ Task 删除不级联
→ 只有显式 Clear 才删除当前 Row
```

### 6.4 Runtime / Audit / Request / Alert History

以下 PostgreSQL 元数据不提供普通 DELETE/自动 retention：

```text
Execution/Batch/Precheck/Validation/Outbox
Delete Snapshot/State/Apply
Audit
External Request
Alert Event/Delivery
```

### 6.5 Support / Temporary / Projection

- Alert Rule/Channel 可物理删除，历史通过 Snapshot + SET NULL 解释。
- User/External Client 只停用。
- System Setting 无通用 DELETE。
- External Nonce 1 小时 TTL。
- Rule-Channel、Client-Institution 等当前关系可物理增删。
- Doris RAW/Snapshot/Diff 按生命周期清理，PostgreSQL Run 保留。
- Quartz Job/Trigger 是可重建投影。

## 7. Active Spec 同步范围

本轮新增/更新：

```text
P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md
P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md
P0_SUPPORT_OBJECT_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY.md
```

删除行为冲突时以 `P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md` 和日期更晚的已确认专项 Review 为最终基线。

## 8. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [ ] **Execution / Validation / Outbox Snapshot 最小充分性 Review。**
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 6 项 Snapshot 最小充分性 Review。

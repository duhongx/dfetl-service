# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL Table + FK + Unique + Status/CHECK + Delete + Snapshot Matrix 已确认  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`

## 1. 当前唯一主模型

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Fixed Identity + Current Config
→ Execution / Validation Runtime Snapshot
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

Route/Version/Field Resolution/Task/Execution/Watermark/Validation/External Request/Outbox 复合身份闭环已完成。

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

Resource Test、Route 双状态、Dataset/Task Schedule、Execution Range/Trigger/Terminal/Cancel、Validation FIXED 来源、Outbox/Delete Apply/Audit/Alert/External Request CHECK 已收口。

## 6. Delete Behavior Matrix：完成

- Resource 无引用可物理删除，有引用只能停用。
- Dataset/Version/Field/Contract 是永久定义历史，使用 VOID/RETIRED 表达失效。
- Route/Task 使用逻辑删除；Watermark 仅显式 Clear 删除当前 Row。
- Runtime/Audit/External Request/Alert History 永久保留 PostgreSQL 元数据。
- Alert Rule/Channel 可物理删除，历史通过 Snapshot + SET NULL 解释。
- User/External Client 只停用；Setting 无通用 DELETE；Nonce 1 小时 TTL。
- Doris RAW/Snapshot/Diff 按生命周期清理，PostgreSQL Run 保留；Quartz 是可重建投影。

## 7. Snapshot 最小充分性：完成

统一原则：

```text
不可变定义只引用
可变运行事实才快照
Secret 永不快照
```

### 7.1 Execution

新增：

```text
source_runtime_snapshot jsonb NOT NULL
target_runtime_snapshot jsonb NOT NULL
```

只保存非 Secret Runtime Endpoint/Revision/连接事实。

保留实际 Task 执行参数、Operation/Trigger/Scope/Range、最终 Validation Resolution 与 `message_policy_snapshot`。

删除：

```text
precheck_fact_snapshot
```

不复制 Dataset/Route/Field Contract 完整定义或 Hash；通过永久 Version/Resolution 引用解释。

Checksum Protocol 只在 `ROW_COUNT_CHECKSUM` 时非空。

### 7.2 Validation

```text
SYNC_GATE / MANUAL_RECHECK
→ context_snapshot/range_snapshot NULL
→ 父 Execution 是唯一上下文来源

普通独立 Validation
→ context_snapshot/range_snapshot 非空
→ 最小 Context = routeVersionId + Source/Target Runtime Snapshot

DELETE_RECONCILIATION
→ context_snapshot/range_snapshot NULL
→ baseline/current Snapshot Run FK 是唯一上下文来源
```

### 7.3 Outbox

保留显式 Message Policy Snapshot + 最小 `range_snapshot`；Range JSON 不重复：

```text
executionId
taskId
datasetId
institutionId
operationType
```

不复制父 Execution Target Runtime Endpoint；人工重发读取当前 Doris，不做历史 Payload Replay。

### 7.4 Secret

Runtime/Validation/Outbox Snapshot 均禁止 DB/RabbitMQ/API/Webhook/JWT/Master Key/Authorization/HMAC Secret。

## 8. Active Spec 本轮同步

新增/更新：

```text
P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
P0_OUTBOX_SCOPE_MAPPING_REVIEW.md
P0_MUTABLE_TASK_MODEL_REVIEW.md
TARGET_METADATA_MODEL.md
P0_PHYSICAL_TABLE_DICTIONARY.md
```

Snapshot 字段、NULL 组合、Checksum Protocol、Secret Boundary 冲突时，以 `P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md` 和日期更晚的 Execution/Validation 字典为准。

## 9. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [x] Status / Enum / CHECK Matrix。
5. [x] Delete Behavior Matrix。
6. [x] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] **`PHASE1_FINAL_REVIEW.md`。**

下一项只处理阶段 1 Final Review，不进入 Flyway/Java 实施。

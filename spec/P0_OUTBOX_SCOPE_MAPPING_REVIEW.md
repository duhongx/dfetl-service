# P0 消息 Outbox 发布范围映射 Review

> 状态：阶段 1 Snapshot 最小充分性已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Execution：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 固定边界

消息只使用 RabbitMQ，Message Policy 只存在 Dataset 级。每次成功同步 Execution 最多创建一条小型 `message_outbox` 指令。

Outbox 不保存：

```text
业务数据 Payload
分页进度
逐条 Message
逐次 Attempt 明细
完成通知
TRUNCATE Signal
SKIP/NOTIFY_ONLY
RabbitMQ Credential
Target Runtime Endpoint Snapshot
```

创建条件：

```text
sync_execution = SUCCEEDED
AND 唯一 SYNC_GATE = COMPLETED + PASS
AND rejected_row_count = 0
AND 本 Execution.message_policy_snapshot.enabled = true
```

**创建 Outbox 时不重新读取当前 Dataset Message Policy。**

## 2. 发布范围映射

`publish_scope` 只保留：

```text
FULL
INCREMENTAL
```

| operation_type | execution_scope | publish_scope | 发布内容 |
| --- | --- | --- | --- |
| NORMAL | INITIAL_FULL | FULL | 当前机构当前 Dataset 的当前全量数据 |
| NORMAL | FULL | FULL | 当前机构当前 Dataset 的当前全量数据 |
| NORMAL | INCREMENTAL | INCREMENTAL | 原 Execution 固定时间窗口全部数据 |
| RECOLLECT | INITIAL_FULL/FULL | FULL | 重采成功后的当前机构全量 |
| RECOLLECT | INCREMENTAL | INCREMENTAL | 原重采 Execution 固定窗口全部数据 |
| BACKFILL | BACKFILL_TIME | INCREMENTAL | 用户指定历史时间范围全部补采数据 |
| BACKFILL | BACKFILL_KEY | INCREMENTAL | 用户指定联合主键范围全部补采数据 |

Backfill 不推进正式 Watermark，但成功且本 Execution Message Policy 启用时仍创建 Outbox。

## 3. Outbox 身份

显式列继续保存：

```text
execution_id
task_id
dataset_id
institution_id
```

父 Execution：

```text
UNIQUE(id,task_id,dataset_id,institution_id)

FOREIGN KEY (execution_id,task_id,dataset_id,institution_id)
REFERENCES sync_execution(id,task_id,dataset_id,institution_id)
ON DELETE RESTRICT
```

因此 Outbox 的 Task/Dataset/Institution 不可能与父 Execution 漂移。

```text
UNIQUE(execution_id)
UNIQUE(event_id)
```

## 4. Message Policy Snapshot

Outbox 显式保存：

```text
policy_revision
publish_scope
source_system
tenant_id
routing_key
topic
key_template
rate_limit_per_second
page_size
```

这些值必须从父 Execution 已冻结的 `message_policy_snapshot` 复制，不能读取当前 Dataset Policy 替换历史已接受发布指令。

固定 Exchange `YL` 不重复存储；RabbitMQ Host/Port/User/Password 属于部署通道配置，不进入 Outbox。

## 5. 最小 `range_snapshot`

显式列已经保存：

```text
execution_id
task_id
dataset_id
institution_id
```

父 Execution 永久保存：

```text
operation_type
```

因此 `range_snapshot` **不再重复**：

```text
executionId
taskId
datasetId
institutionId
operationType
```

只保存 Publisher 独立读取数据所需的原 Execution 范围、不可变定义和逻辑 Target。

### FULL / INITIAL_FULL

```json
{
  "executionScope": "FULL",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9
}
```

若原 Execution Scope 为 `INITIAL_FULL`，`executionScope` 保存真实 `INITIAL_FULL`，不机械改写为 `FULL`；`publish_scope` 才是 `FULL`。

### INCREMENTAL / BACKFILL_TIME

```json
{
  "executionScope": "INCREMENTAL",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9,
  "windowLower": "2026-08-15T08:00:00+08:00",
  "windowUpper": "2026-08-15T12:00:00+08:00"
}
```

`BACKFILL_TIME` 保存真实 `executionScope=BACKFILL_TIME`。

### BACKFILL_KEY

```json
{
  "executionScope": "BACKFILL_KEY",
  "institutionCode": "330106001",
  "datasetVersionId": 31,
  "routeVersionId": 45,
  "targetDatasourceId": 9,
  "keyLower": {"...": "..."},
  "keyUpper": {"...": "..."}
}
```

全量不保存伪时间窗口；`range_snapshot` 不保存业务记录或发送分页位置。

## 6. 为什么保留这些 Range 字段

Publisher 必须能够在 Task、Route 当前指针、Dataset Current Version 后续变化后，仍按**原 Execution 的不可变定义和固定范围**读取当前 Doris。

因此保留：

```text
institutionCode
datasetVersionId
routeVersionId
targetDatasourceId
executionScope
具体 time/key range
```

但不复制已经由 Outbox 显式列或父 Execution 永久保存的身份事实。

## 7. Target Runtime Endpoint 不进入 Outbox

父 Execution 的：

```text
target_runtime_snapshot
```

用于解释“原同步当时实际向哪里写”。

消息人工重发的已确认语义是：

```text
重新读取当前 Doris
```

不是历史 Payload Replay。

因此 Outbox 只保存 `targetDatasourceId` 逻辑身份；发布时使用该 Target **当前可用连接配置**读取当前 Doris，不复制原 Execution Target Endpoint Snapshot。

## 8. 发布数据读取

### FULL

按 Outbox 的 Institution Code、Dataset/Route Version、Target Datasource 从当前 Doris 读取当前机构当前全量数据。

### INCREMENTAL / BACKFILL_TIME

按原 Execution 固定：

```text
[windowLower, windowUpper)
```

读取当前 Doris 中仍落在该范围的全部数据。

### BACKFILL_KEY

按原 Execution 固定联合业务主键范围读取当前 Doris 中对应数据。

人工重发时后续 UPSERT 可能使实际业务内容与原同步成功时不同，这是已确认取舍；Outbox 不保存历史 Payload。

## 9. 成功收尾事务

```text
锁定 sync_execution
→ 全部 load_batch = SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE = COMPLETED + PASS
→ rejected_row_count = 0
→ execution = SUCCEEDED
→ 创建/推进 watermark
→ 从 execution.message_policy_snapshot 生成唯一 message_outbox
→ commit
```

事务内不访问 RabbitMQ，也不重新读取 Doris。

## 10. 重试和人工重发

```text
PENDING
→ PUBLISHING
→ PUBLISHED / DEAD_LETTER
```

- 临时失败回 PENDING 并更新 `available_at`。
- 自动尝试耗尽进入 DEAD_LETTER。
- 人工重发沿用 `event_id`，重置 Attempt Count。
- 每次真实发送重新生成 27 位 Message ID。
- 不保存分页进度；失败后从该发布范围开头重新读取当前 Doris。
- 重发不改变原 Execution、Watermark 或 Task 调度。

## 11. 不发布对象

```text
precheck_run
独立 validation_run
DELETE_RECONCILIATION
delete_snapshot_run
delete_apply_run
FAILED/CANCELLED sync_execution
未通过 SYNC_GATE 的 Execution
```

## 12. 删除/历史

Outbox 是消息发布历史，PUBLISHED/DEAD_LETTER 都永久保留；不自动 purge。人工重发修改同一 Outbox 当前发布状态并写 Audit，不删除重建 Row。

## 13. 验收

- 一次 Execution 最多一个 Outbox。
- Outbox 身份与父 Execution 完全一致。
- Outbox Message Policy 来自父 Execution Snapshot，不回读当前 Dataset Policy。
- `range_snapshot` 不重复 Execution/Task/Dataset/Institution ID 或 operationType。
- Publisher 仍能基于最小 Range 独立恢复发布。
- Outbox 不复制原 Target Endpoint Snapshot。
- 重发读取当前 Doris，不保存历史业务 Payload/分页进度。
- 所有 Snapshot 严禁 Secret。

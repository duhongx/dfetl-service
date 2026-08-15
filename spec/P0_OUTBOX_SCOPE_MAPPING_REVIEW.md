# P0 消息 Outbox 发布范围映射 Review

> 状态：阶段 1 工作包 3 批量复核完成  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 固定边界

消息只使用 RabbitMQ，消息策略只存在于数据集级。每次成功同步执行最多创建一条小型 `message_outbox` 指令。

Outbox 不保存：

```text
业务数据 payload
分页进度
逐条消息明细
逐次发送尝试明细
完成通知
TRUNCATE 信号
SKIP/NOTIFY_ONLY 模式
```

只有满足以下条件才创建 Outbox：

```text
sync_execution = SUCCEEDED
AND 唯一 SYNC_GATE validation_run = COMPLETED + PASS
AND rejected_row_count = 0
AND 数据集消息策略 enabled = true
```

失败、取消、技术前检失败、校验失败、预检、独立校验、删除快照和删除应用均不创建消息 Outbox。

## 2. 发布范围映射

`publish_scope` 只保留：

```text
FULL
INCREMENTAL
```

实际映射：

| `operation_type` | `execution_scope` | `publish_scope` | 发布内容 |
| --- | --- | --- | --- |
| `NORMAL` | `INITIAL_FULL` | `FULL` | 当前机构、当前数据集在 Doris 中的全部当前数据。 |
| `NORMAL` | `FULL` | `FULL` | 当前机构、当前数据集在 Doris 中的全部当前数据。 |
| `NORMAL` | `INCREMENTAL` | `INCREMENTAL` | 本次固定增量窗口内的全部当前数据。 |
| `RECOLLECT` | `INITIAL_FULL/FULL` | `FULL` | 重新采集成功后，发送当前机构全量。 |
| `RECOLLECT` | `INCREMENTAL` | `INCREMENTAL` | 重新采集成功后，发送本次重新采集窗口内全部数据。 |
| `BACKFILL` | `BACKFILL_TIME` | `INCREMENTAL` | 发送用户指定历史时间范围内全部补采数据。 |
| `BACKFILL` | `BACKFILL_KEY` | `INCREMENTAL` | 发送用户指定主键范围内全部补采数据。 |

固定原则：

1. `operation_type` 表示为什么运行；`execution_scope` 表示本次实际数据范围。
2. 重新采集不增加第三种消息类型，按本次实际执行范围映射。
3. 数据补采是局部范围，不冒充全量；使用 `INCREMENTAL` 消息语义发送本次补采全部数据。
4. 数据补采不推进正式水位，但成功且消息策略启用时仍创建 Outbox，保证下游能够收到补采结果。
5. 首次全量与后续定时增量是两次独立执行，因此分别创建各自的 Outbox。

## 3. `range_snapshot`

Outbox 必须从原执行快照复制发布所需的最小范围，不回读当前任务配置替换历史。

通用字段：

```json
{
  "executionId": 1001,
  "operationType": "NORMAL",
  "executionScope": "INCREMENTAL",
  "institutionId": 10,
  "institutionCode": "330106001",
  "datasetId": 20,
  "datasetVersionId": 31,
  "routeVersionId": 45
}
```

时间范围执行增加：

```json
{
  "windowLower": "2026-08-15T08:00:00+08:00",
  "windowUpper": "2026-08-15T12:00:00+08:00"
}
```

主键范围补采增加：

```json
{
  "keyLower": {"...": "..."},
  "keyUpper": {"...": "..."}
}
```

全量不保存伪时间窗口。`range_snapshot` 不保存发送分页位置或业务记录。

## 4. 发布数据读取

### 4.1 全量

`publish_scope=FULL` 时，按 Outbox 保存的：

```text
目标 Doris
institution_id/institution_code
dataset_id/dataset_version_id
```

读取当前机构的当前全量数据。

人工重发时重新读取当前 Doris；后续 UPSERT 可能使内容与原执行成功时不同，这是已经确认的取舍。

### 4.2 日常增量和增量重新采集

按原执行固定：

```text
[window_lower, window_upper)
```

读取当前 Doris 中仍落在该范围内的全部数据。范围字段使用原执行的数据集合同和真实增量字段，不读取当前任务新配置。

### 4.3 时间补采

按原 `BACKFILL_TIME` 执行的固定历史时间范围读取全部数据，内部发布语义仍为 `INCREMENTAL`。

### 4.4 主键补采

按原 `BACKFILL_KEY` 执行固定的联合业务主键范围读取全部数据，内部发布语义仍为 `INCREMENTAL`。

## 5. `message_outbox` 身份一致性

目标字段继续保存：

```text
execution_id
task_id
dataset_id
institution_id
publish_scope
range_snapshot
消息策略快照字段
```

删除：

```text
task_version_id
```

父执行增加或保留：

```text
UNIQUE (id, task_id, dataset_id, institution_id)
```

Outbox 使用复合外键保证身份完全来自同一执行：

```text
FOREIGN KEY (execution_id, task_id, dataset_id, institution_id)
REFERENCES sync_execution(id, task_id, dataset_id, institution_id)
ON DELETE RESTRICT
```

因此不再分别依赖四条互相独立的外键来解释同一事实。`task_id/dataset_id/institution_id` 仍保留为高频查询和审计列。

每次执行最多一条：

```text
UNIQUE (execution_id)
UNIQUE (event_id)
```

## 6. 成功收尾事务

Outbox 与执行成功、水位提交在同一个短 PostgreSQL 事务完成：

```text
锁定 sync_execution
→ 确认全部 load_batch = SUCCEEDED + VISIBLE
→ 确认唯一 SYNC_GATE = COMPLETED + PASS
→ 确认 rejected_row_count = 0
→ 更新 execution = SUCCEEDED
→ 按规则创建或推进 watermark
→ 按本文件映射生成唯一 message_outbox
→ 提交
```

事务内不访问 RabbitMQ，也不重新读取 Doris。

## 7. 重试和人工重发

```text
PENDING
→ PUBLISHING
→ PUBLISHED
→ DEAD_LETTER
```

- 临时失败回到 `PENDING` 并更新 `available_at`。
- 自动尝试耗尽进入 `DEAD_LETTER`。
- 人工重发沿用 `event_id`，重置本轮尝试次数。
- 每次真实发送重新生成 27 位 `messageId`。
- 中途失败不保存分页进度；下一次从该发布范围开头重新读取当前 Doris。
- 重发不改变原同步执行、水位和任务调度。

## 8. 明确不发布的对象

```text
precheck_run
独立 validation_run
DELETE_RECONCILIATION
delete_snapshot_run
delete_apply_run
FAILED/CANCELLED sync_execution
未通过 SYNC_GATE 的执行
```

## 9. 验收

- 首次全量和普通全量生成 `FULL` Outbox。
- 日常增量生成 `INCREMENTAL` Outbox。
- 重新采集按实际执行范围生成 `FULL` 或 `INCREMENTAL`。
- 时间补采和主键补采都生成 `INCREMENTAL`，且发送本次补采全部数据。
- 补采不推进水位。
- 一次执行不能创建两条 Outbox。
- Outbox 的任务、机构和数据集必须与父执行完全一致。
- 重发从当前 Doris 重新读取，不保存历史业务快照和分页进度。

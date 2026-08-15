# P0 `load_batch` 模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 首次全量与增量边界：`spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`  
> Label 探测：`spec/P0_DORIS_LABEL_PROBE_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

从 `load_batch` 删除：

```text
phase
time_lower
time_upper
probe_result
institution_code
checksum_protocol_version
```

并将：

```text
committed_at
```

更名为：

```text
visible_at
```

原因是 Doris 的 `COMMITTED` 不等于数据已经可见；该时间字段只在 DFETL 确认事务达到 `VISIBLE` 时写入。

批次类型、整次范围、机构身份和 Checksum 协议统一从父执行取得：

```text
load_batch.execution_id
→ sync_execution
```

批次只保存实际分页游标、Doris Label、批次行数、载荷摘要和事务探测结果。

## 2. 父执行保存的事实

```text
sync_execution.execution_scope
sync_execution.window_lower/window_upper
sync_execution.key_lower/key_upper
sync_execution.institution_id/institution_code
sync_execution.checksum_protocol_version
```

这些值在一次执行的所有批次中完全相同，不在 `load_batch` 重复保存。

当前执行范围：

```text
FULL
INITIAL_FULL
INCREMENTAL
BACKFILL_TIME
BACKFILL_KEY
```

同一执行不混合不同范围。

## 3. 批次游标合同

### 3.1 有业务主键的全量和首次全量

```json
{
  "businessKey": {
    "YILIAOJGDM": "330106001",
    "JIUZHENLSH": "A000001"
  }
}
```

### 3.2 增量和按时间补采

```json
{
  "incrementalValue": "2026-08-15T08:36:20.123Z",
  "businessKey": {
    "YILIAOJGDM": "330106001",
    "JIUZHENLSH": "A000001"
  }
}
```

### 3.3 按主键范围补采

父执行保存整次 `key_lower/key_upper`；批次游标保存范围内本批实际联合业务键边界。

### 3.4 无业务主键全量

单 Reader 流式读取且没有稳定 Keyset 游标时，`cursor_lower/cursor_upper` 可以为空，不生成假主键。

### 3.5 固定边界

- 游标使用版本化、确定性的规范 JSON；
- 字段顺序按数据集合同固定；
- 游标用于诊断、日志、Label 定位和追溯；
- 游标不是跨执行恢复检查点；
- 重新采集从新执行范围起点和第 1 批开始。

## 4. Doris Label 状态模型

### 4.1 DFETL 批次状态

```text
PENDING
LOADING
PROBING
SUCCEEDED
FAILED
CANCELLED
```

### 4.2 Doris 原始事务状态

```text
UNKNOWN
PREPARE
COMMITTED
VISIBLE
ABORTED
```

只有：

```text
doris_state = VISIBLE
AND rejected_row_count = 0
```

批次才能进入 `SUCCEEDED`。

### 4.3 处理规则

- 明确成功并确认可见：`SUCCEEDED + VISIBLE`；
- `Publish Timeout`：`PROBING + COMMITTED`，继续查询原 Label；
- 客户端超时、空响应、连接中断或解析失败：进入 `PROBING`；
- `PREPARE/COMMITTED`：继续探测；
- `VISIBLE`：写入 `visible_at` 并成功；
- `ABORTED`：失败；
- `UNKNOWN` 或查询接口持续异常：限定时间后失败；
- 任何不明确结果均不自动重新提交该批数据。

失败后不推进水位、不进入正式校验、不创建 Outbox。人工重新采集前必须再次核实旧 Label。

## 5. 当前目标字段

```text
id
execution_id
batch_no
status
cursor_lower
cursor_upper
source_row_count
loaded_row_count
rejected_row_count
payload_checksum
doris_label
doris_txn_id
doris_state
probe_count
last_probed_at
submitted_at
visible_at
error_code
error_message
created_at
updated_at
```

明确不保存：

```text
phase
time_lower
time_upper
probe_result
institution_code
checksum_protocol_version
batch_type
stage
resume_from_batch_id
next_cursor
committed_at
```

## 6. 约束

```text
CHECK (batch_no > 0)
CHECK (status IN
       ('PENDING','LOADING','PROBING','SUCCEEDED','FAILED','CANCELLED'))
CHECK (source_row_count >= 0)
CHECK (loaded_row_count >= 0)
CHECK (rejected_row_count >= 0)
CHECK (probe_count >= 0)
CHECK (cursor_lower IS NULL OR jsonb_typeof(cursor_lower) = 'object')
CHECK (cursor_upper IS NULL OR jsonb_typeof(cursor_upper) = 'object')
CHECK (doris_state IS NULL OR
       doris_state IN ('UNKNOWN','PREPARE','COMMITTED','VISIBLE','ABORTED'))
CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR doris_state = 'VISIBLE')
CHECK (status <> 'SUCCEEDED' OR visible_at IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR rejected_row_count = 0)
CHECK (doris_state <> 'ABORTED' OR status = 'FAILED')
```

唯一和索引：

```text
UNIQUE (execution_id, batch_no)
UNIQUE (doris_label)

INDEX idx_load_batch_execution_status
    ON load_batch (execution_id, status, batch_no)

INDEX idx_load_batch_probe
    ON load_batch (status, updated_at, id)
    WHERE status IN ('LOADING','PROBING')
```

## 7. 查询方式

批次详情需要的类型、范围、机构和协议统一连接父执行：

```sql
SELECT
    b.*,
    e.execution_scope,
    e.window_lower,
    e.window_upper,
    e.key_lower,
    e.key_upper,
    e.institution_id,
    e.institution_code,
    e.checksum_protocol_version
FROM load_batch b
JOIN sync_execution e ON e.id = b.execution_id
WHERE b.execution_id = :executionId
ORDER BY b.batch_no;
```

这不是额外业务查询链路；批次本来就不能脱离父执行解释。

## 8. 清晰错误信息

至少支持：

```text
DORIS_STREAM_LOAD_FAILED
DORIS_FILTERED_ROWS
DORIS_LABEL_ABORTED
DORIS_LABEL_UNKNOWN_TIMEOUT
DORIS_LABEL_QUERY_TIMEOUT
DORIS_VISIBILITY_TIMEOUT
```

错误信息至少包含：

```text
batch_no
doris_label
最后 doris_state
probe_count
submitted_at
last_probed_at
Doris 脱敏错误摘要或最后查询异常
系统未自动重投的说明
核实旧 Label 后再重新采集的建议
```

执行因批次失败而失败时沿用底层错误码，并附失败批次号和 Label。

## 9. 历史兼容和验收

- 旧 `task_chunk` 字段不迁移。
- Flyway V1 不创建已删除字段。
- Java、OpenAPI 和 Vue 类型统一使用父执行事实、`status + dorisState + visibleAt`。
- 只有 `VISIBLE` 批次才能成功。
- `Publish Timeout` 和响应不明确只探测原 Label。
- `UNKNOWN` 超时后明确失败。
- 批次查询、日志、校验和 Label 探测不依赖被删除字段。
- 本结论只删除重复数据，不改变首次全量、正常增量、重新采集或数据补采语义。

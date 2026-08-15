# P0 `load_batch` 模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 首次全量与增量边界：`spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`  
> Label 探测：`spec/P0_DORIS_LABEL_PROBE_REVIEW.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

从 `load_batch` 删除：

```text
phase
time_lower
time_upper
probe_result
```

批次属于全量、首次全量、增量还是数据补采，统一通过父执行取得：

```text
load_batch.execution_id
→ sync_execution.execution_scope
```

整次执行的业务时间或主键范围也只保存在父执行：

```text
sync_execution.window_lower
sync_execution.window_upper
sync_execution.key_lower
sync_execution.key_upper
```

批次只保存实际分页使用的边界：

```text
load_batch.cursor_lower
load_batch.cursor_upper
```

Doris 状态只使用两层事实：

```text
load_batch.status
= DFETL 批次生命周期

load_batch.doris_state
= Doris 原始事务状态
```

不再建立第三套 `probe_result`。

当前执行范围枚举为：

```text
FULL
INITIAL_FULL
INCREMENTAL
BACKFILL_TIME
BACKFILL_KEY
```

同一 `sync_execution` 不混合不同执行范围，也不把一个固定业务时间窗口再次拆成多套独立业务窗口，因此子表没有必要重复保存类型或时间范围。

## 2. 删除 `phase` 的原因

保留 `load_batch.phase` 会重复表达父执行已经保存的事实，并允许出现不可能组合：

```text
sync_execution.execution_scope = INCREMENTAL
load_batch.phase = FULL
```

为了防止这种组合，还需要增加无法由普通外键表达的跨表校验，但该字段本身没有提供新的业务信息。

删除后：

- `INITIAL_FULL` 执行下的全部批次天然属于首次全量；
- `INCREMENTAL` 执行下的全部批次天然属于增量；
- `BACKFILL_TIME/BACKFILL_KEY` 执行下的全部批次天然属于数据补采；
- 普通 `FULL` 执行下的全部批次天然属于全量；
- 页面和 API 从父执行展示批次类型；
- 不建立批次阶段转换或子阶段状态机。

## 3. 删除批次级时间上下界的原因

父执行已经固定整次业务时间范围。例如一次增量执行固定为：

```text
[2026-08-15 08:00:00, 2026-08-15 12:00:00)
```

执行内的各个载入批次都属于这一范围。各批次之间的差异是 Keyset 分页游标，而不是重新定义一组业务时间窗口。

继续保存：

```text
load_batch.time_lower
load_batch.time_upper
```

只会产生两种结果：

1. 每个批次重复保存父执行相同的时间窗口；
2. 误导实现把一次增量执行拆成多个具有独立业务语义的时间子窗口。

因此：

- 整次同步范围只由 `sync_execution` 保存；
- `load_batch` 不保存业务时间上下界；
- 批次只保存实际分页游标、Doris Label、行数和提交结果；
- 校验、消息发布和水位推进都以父执行固定范围为准，不从批次重新拼接另一套时间范围。

## 4. 批次游标合同

### 4.1 有业务主键的全量和首次全量

游标保存标准数据集合同中的联合业务主键：

```json
{
  "businessKey": {
    "YILIAOJGDM": "330106001",
    "JIUZHENLSH": "A000001"
  }
}
```

### 4.2 增量和按时间补采

游标保存“增量时间值 + 联合业务主键”的完整有序元组：

```json
{
  "incrementalValue": "2026-08-15T08:36:20.123Z",
  "businessKey": {
    "YILIAOJGDM": "330106001",
    "JIUZHENLSH": "A000001"
  }
}
```

父执行仍保存整次固定时间范围；批次游标只说明本批实际分页边界。

### 4.3 按主键范围补采

父执行的 `key_lower/key_upper` 保存用户指定的整次补采范围；批次游标保存该范围内本批实际使用的联合业务键边界。

### 4.4 无业务主键全量

无业务主键任务使用单 Reader 流式全量读取，不生成假主键。若底层 Reader 无稳定 Keyset 游标，`cursor_lower/cursor_upper` 可以为空。

### 4.5 固定边界

- 游标 JSON 使用版本化、确定性的规范结构；
- 字段顺序按数据集合同固定；
- 游标用于批次诊断、日志、Doris Label 定位和结果追溯；
- 游标不是跨执行恢复检查点；
- 新执行不会读取旧批次游标继续运行；
- 重新采集仍从任务范围起点和第 1 批开始。

## 5. Doris Label 状态模型

### 5.1 `load_batch.status`

固定为：

```text
PENDING
LOADING
PROBING
SUCCEEDED
FAILED
CANCELLED
```

只有确认原 Label 对应 Doris 事务达到 `VISIBLE`，且本批 `rejected_row_count=0` 时，批次才能进入 `SUCCEEDED`。

不再使用 `COMMITTED` 作为 DFETL 批次终态。Doris 的 `COMMITTED` 只保存在 `doris_state`，表示事务已提交但数据尚未确认可见。

### 5.2 `load_batch.doris_state`

固定为：

```text
UNKNOWN
PREPARE
COMMITTED
VISIBLE
ABORTED
```

尚未取得有效 Doris 状态时允许为空。

### 5.3 删除 `probe_result`

`probe_result` 与 `status/doris_state` 重复，容易产生：

```text
status = SUCCEEDED
probe_result = ABORTED
```

等矛盾组合，因此直接删除。

保留：

```text
probe_count
last_probed_at
```

用于记录探测次数和最近探测时间。

### 5.4 处理规则

- 明确成功并确认可见：`SUCCEEDED + VISIBLE`；
- `Publish Timeout`：`PROBING + COMMITTED`，继续查询原 Label，不重新提交；
- 客户端超时、空响应、连接中断或响应解析失败：进入 `PROBING`，查询原 Label；
- `PREPARE/COMMITTED`：保持 `PROBING`；
- `VISIBLE`：更新为 `SUCCEEDED`；
- `ABORTED`：更新为 `FAILED`；
- `UNKNOWN`：在限定探测时间内继续查询；截止后失败；
- Label 查询接口持续失败：在限定时间内继续查询；截止后失败。

Label 在限定时间内始终为 `UNKNOWN` 时：

```text
load_batch = FAILED
sync_execution = FAILED
不推进水位
不进入正式校验
不创建 Outbox
不自动重新提交该批数据
```

人工重新采集前必须再次核实旧 Label，避免旧事务延迟出现后发生重复写入。

详细规则见：

```text
spec/P0_DORIS_LABEL_PROBE_REVIEW.md
```

## 6. `load_batch` 当前目标字段

```text
id
execution_id
batch_no
status
cursor_lower
cursor_upper
institution_code
source_row_count
loaded_row_count
rejected_row_count
payload_checksum
checksum_protocol_version
doris_label
doris_txn_id
doris_state
probe_count
last_probed_at
submitted_at
committed_at
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
batch_type
stage
resume_from_batch_id
next_cursor
```

其中 `committed_at` 当前表示 DFETL 最终确认该批次 `VISIBLE` 的时间；不以 Doris 处于 `COMMITTED` 作为写入该字段的条件。

## 7. 约束和查询影响

删除：

```text
CHECK (phase IN ('FULL','INCREMENTAL','BACKFILL'))
CHECK (time_lower IS NULL OR time_upper IS NULL OR time_lower < time_upper)
CHECK (probe_result IN (...))
```

目标约束：

```text
CHECK (status IN
       ('PENDING','LOADING','PROBING','SUCCEEDED','FAILED','CANCELLED'))

CHECK (doris_state IS NULL OR
       doris_state IN ('UNKNOWN','PREPARE','COMMITTED','VISIBLE','ABORTED'))

CHECK (probe_count >= 0)
CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR doris_state = 'VISIBLE')
CHECK (status <> 'SUCCEEDED' OR committed_at IS NOT NULL)
CHECK (status <> 'SUCCEEDED' OR rejected_row_count = 0)
CHECK (doris_state <> 'ABORTED' OR status = 'FAILED')
```

父执行继续约束整次时间或主键范围；批次游标只校验为 JSON 对象或 `NULL`。

查询批次类型和整次范围时使用父表：

```sql
SELECT
    b.*,
    e.execution_scope,
    e.window_lower,
    e.window_upper,
    e.key_lower,
    e.key_upper
FROM load_batch b
JOIN sync_execution e ON e.id = b.execution_id
WHERE b.execution_id = :executionId
ORDER BY b.batch_no;
```

批次列表和执行详情本来就需要读取父执行，不会为此增加一条额外业务查询链路。

## 8. 清晰错误信息

Label 相关失败必须保存稳定 `error_code` 和可直接用于排查的脱敏 `error_message`。

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
Doris 脱敏错误摘要或最后一次查询异常
系统未自动重投的处理说明
核实旧 Label 后再重新采集的建议动作
```

`sync_execution` 因批次失败而失败时，沿用底层稳定错误码，并在执行错误摘要中包含失败批次号和 Label。

不得保存密码、完整认证头、业务数据或未经脱敏的完整 Doris 响应体。

## 9. 历史兼容和迁移边界

- 老系统 `task_chunk` 没有稳定的目标 `phase`、批次时间范围或 Label 探测合同，旧字段不迁移。
- 新系统 Flyway V1 不创建 `load_batch.phase/time_lower/time_upper/probe_result`。
- Java 实体、DTO、OpenAPI 和 Vue 类型不得增加或回填这些字段。
- `spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` 已同步删除这些字段、约束和旧处理说明。
- 不为兼容旧页面保留只读重复列；需要展示时由服务端从父执行和 `doris_state` 返回统一信息。

## 10. 验收

- 数据库中不存在 `load_batch.phase/time_lower/time_upper/probe_result`。
- `INITIAL_FULL`、`INCREMENTAL`、`FULL` 和两类 `BACKFILL` 执行的批次均能从父执行准确识别类型及整次范围。
- 增量和按时间补采的批次只保存实际复合游标，不重复保存业务时间窗口。
- 只有 `VISIBLE` 批次才能进入 `SUCCEEDED`。
- `Publish Timeout`、客户端超时和响应不明确时只探测原 Label，不自动重投。
- Label 在限定时间内始终 `UNKNOWN` 时，批次和执行明确失败。
- 失败信息能够直接展示批次号、Label、最后状态、探测次数、失败原因和建议动作。
- 批次查询、日志、校验和 Doris Label 探测不依赖被删除字段。
- 本结论只简化并明确模型，不改变首次全量、正常增量、重新采集和数据补采的既有业务语义。

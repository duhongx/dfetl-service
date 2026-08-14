# P0 `load_batch` 模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 首次全量与增量边界：`spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

从 `load_batch` 删除：

```text
phase
time_lower
time_upper
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

## 5. `load_batch` 当前目标字段

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
probe_result
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
batch_type
stage
resume_from_batch_id
next_cursor
```

Doris Label 状态及探测结果组合仍在下一项一致性 Review 中继续收口。

## 6. 约束和查询影响

删除以下约束：

```text
CHECK (phase IN ('FULL','INCREMENTAL','BACKFILL'))
CHECK (time_lower IS NULL OR time_upper IS NULL OR time_lower < time_upper)
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

## 7. 历史兼容和迁移边界

- 老系统 `task_chunk` 没有稳定的目标 `phase` 或批次时间范围合同，旧字段不迁移。
- 新系统 Flyway V1 不创建 `load_batch.phase/time_lower/time_upper`。
- Java 实体、DTO、OpenAPI 和 Vue 类型不得增加或回填这些字段。
- `spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` 已同步删除这些字段、约束和旧处理说明。
- 不为兼容旧页面保留只读重复列；需要展示时由服务端从父执行返回执行范围文案和固定范围。

## 8. 验收

- 数据库中不存在 `load_batch.phase/time_lower/time_upper`。
- `INITIAL_FULL`、`INCREMENTAL`、`FULL` 和两类 `BACKFILL` 执行的批次均能从父执行准确识别类型及整次范围。
- 增量和按时间补采的批次只保存实际复合游标，不重复保存业务时间窗口。
- 不可能保存“增量执行 + 全量批次”或“批次时间范围超出父执行范围”等重复事实冲突。
- 批次查询、日志、校验和 Doris Label 探测不依赖被删除字段。
- 本结论只简化模型，不改变首次全量、正常增量、重新采集和数据补采的既有业务语义。

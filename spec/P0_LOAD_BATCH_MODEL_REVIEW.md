# P0 `load_batch` 模型 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 首次全量与增量边界：`spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 已确认结论

删除：

```text
load_batch.phase
```

批次属于全量、首次全量、增量还是数据补采，统一通过父执行取得：

```text
load_batch.execution_id
→ sync_execution.execution_scope
```

当前执行范围枚举为：

```text
FULL
INITIAL_FULL
INCREMENTAL
BACKFILL_TIME
BACKFILL_KEY
```

同一 `sync_execution` 不混合不同执行范围，因此子表没有必要重复保存 `FULL/INCREMENTAL/BACKFILL`。

## 2. 删除原因

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

## 3. `load_batch` 当前目标字段

`load_batch` 继续保存：

```text
id
execution_id
batch_no
status
cursor_lower
cursor_upper
time_lower
time_upper
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

其中游标、时间范围和 Doris Label 状态组合仍需在下一项一致性 Review 中收口；本次只删除重复的 `phase`。

## 4. 约束和查询影响

删除以下约束：

```text
CHECK (phase IN ('FULL','INCREMENTAL','BACKFILL'))
```

不增加替代字段或表达式索引。

查询批次类型时使用已有父表关联：

```sql
SELECT b.*, e.execution_scope
FROM load_batch b
JOIN sync_execution e ON e.id = b.execution_id
WHERE b.execution_id = :executionId
ORDER BY b.batch_no;
```

批次列表和执行详情本来就需要读取父执行，不会为此增加一条额外业务查询链路。

## 5. 历史兼容和迁移边界

- 老系统 `task_chunk` 没有稳定的目标 `phase` 合同，旧字段不迁移。
- 新系统 Flyway V1 不创建 `load_batch.phase`。
- Java 实体、DTO、OpenAPI 和 Vue 类型不得增加或回填该字段。
- 旧物理字典 `spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` 中残留的 `phase` 字段、枚举和说明，在阶段 1 最终机械清理时删除。
- 不为兼容旧页面保留只读 `phase` 列；需要展示时由服务端根据 `execution_scope` 返回统一的执行范围文案。

## 6. 验收

- 数据库中不存在 `load_batch.phase`。
- `INITIAL_FULL`、`INCREMENTAL`、`FULL` 和两类 `BACKFILL` 执行的批次均能从父执行准确识别类型。
- 不可能保存“增量执行 + 全量批次”之类的冲突组合。
- 批次查询、日志、校验和 Doris Label 探测不依赖重复字段。
- 本结论只简化模型，不改变首次全量、正常增量、重新采集和数据补采的既有业务语义。

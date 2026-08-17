# P0 首次全量与后续增量执行边界 Review

> 状态：已确认；相关旧“立即补充增量”文案清理已完成  
> 首次确认：2026-08-15  
> 最近收口：2026-08-17  
> Execution 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> Watermark 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Batch：`spec/P0_LOAD_BATCH_MODEL_REVIEW.md`

## 1. Task 与 Execution 边界

```text
sync_task      = 长期当前配置
sync_execution = Task 的一次真实运行
```

`FULL_THEN_INCREMENTAL` 的首次全量和后续增量是**同一 Task 的两次独立 Execution**，不是一条复合 Execution 的两个 Stage。

## 2. 最终规则

没有正式 Watermark 时，下一次正常运行创建：

```text
execution_scope = INITIAL_FULL
```

INITIAL_FULL：

- 只读取当前 Institution 全量；
- 只产生本次 Full Load Batch；
- 独立执行自己的 SYNC_GATE；
- Gate PASS 后独立 SUCCEEDED；
- Message Policy 启用时创建 FULL Outbox；
- 不在同一 Execution 内继续增量阶段。

开始时捕获：

```text
T0 = INITIAL_FULL started_at / fixed initial watermark
```

完整成功后：

```text
task_watermark.watermark_value = T0
source_execution_id = INITIAL_FULL execution id
```

失败/取消：

- 不创建 Watermark；
- 不创建成功 Outbox；
- 下一次正常运行仍 INITIAL_FULL；
- 不创建“待补充增量”状态。

## 3. 后续增量

INITIAL_FULL 成功后**等待下一次正常 Schedule/Manual Run**，再创建新的：

```text
execution_scope = INCREMENTAL
```

流程：

```text
读取当前 Watermark
→ 固定本次 Upper
→ [watermark,upper) 增量读取
→ Doris
→ 本次 SYNC_GATE
→ PASS 后 Watermark=upper
→ 按需创建 INCREMENTAL Outbox
```

INITIAL_FULL 和 INCREMENTAL 分别拥有独立：

```text
sync_execution id/uuid
status/time
load_batch 集合
validation_run
log/error
message_outbox
```

UI 运行历史按两条 Execution 展示，不拼成一条“Full Stage + Supplemental Increment Stage”。

## 4. Schedule 行为

1. 无 Watermark：正常 Trigger 创建 INITIAL_FULL。
2. INITIAL_FULL 活动期间到达的新 Trigger 按并发规则跳过。
3. INITIAL_FULL 结束后不追赶刚才跳过的 Trigger。
4. 不立即启动 Incremental。
5. 等下一次正常 Trigger 创建 INCREMENTAL。
6. INITIAL_FULL Failed 不自动暂停 Task；下一次正常 Trigger 仍可重新 INITIAL_FULL。

## 5. Physical Model

`sync_execution.execution_scope` 保留：

```text
INITIAL_FULL
INCREMENTAL
```

不建立：

```text
parent_execution_id
child_execution_id
supplemental_increment_execution_id
execution_stage table
INITIAL_FULL_AND_INCREMENTAL composite scope
```

`load_batch` 不保存 `phase`；Batch Scope 从父 Execution 推导。

`task_watermark`：

- INITIAL_FULL 成功建立 T0；
- INCREMENTAL 成功推进到固定 Upper；
- Failed/Cancelled/Backfill/Independent Validation 不推进；
- 不建立 Supplemental Watermark 或双 Watermark。

## 6. 旧描述清理状态

以下旧描述全部废止：

```text
首次全量完成后立即执行补充增量
补充增量属于 INITIAL_FULL 同一 Execution
一条 Execution 同时包含 FULL + INCREMENTAL Batch
load_batch.phase 重复保存父范围
```

2026-08-17 已完成产品基线、Target Model、Task/Execution 字典和 TASKS 中对应旧文案清理，本项不再是待办。

## 7. 验收

- 无 Watermark 时只有 INITIAL_FULL。
- INITIAL_FULL 成功后 Watermark=T0。
- 同一 Execution 不混合 Full/Incremental Batch。
- INITIAL_FULL 后不立即追加 Incremental。
- 下一次正常触发才创建独立 INCREMENTAL。
- 两次 Execution 分别拥有独立 Gate/Outbox/History。

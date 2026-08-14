# P0 首次全量与后续增量执行边界 Review

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 水位字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 批次模型：`spec/P0_LOAD_BATCH_MODEL_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 术语边界

必须区分长期任务和一次运行：

```text
sync_task
= 一条长期同步任务配置

sync_execution
= 该任务的一次实际运行
```

因此，“首次全量”和“后续增量”不是两条 `sync_task`，而是同一条 `sync_task` 的两次独立 `sync_execution`。

## 2. 已确认的最终规则

对于：

```text
FULL_THEN_INCREMENTAL
```

固定执行语义为：

```text
第一次运行：首次全量执行
后续运行：按照任务正常调度间隔产生的增量执行
```

不采用以下设计：

```text
首次全量完成后，立即在同一 sync_execution 内继续执行补充增量
```

也不采用：

```text
为首次全量和后续增量建立父子执行、组合执行或子阶段执行状态机
```

## 3. 首次全量执行

当 `FULL_THEN_INCREMENTAL` 任务不存在正式水位时，下一次正常运行创建一条独立执行：

```text
sync_execution.execution_scope = INITIAL_FULL
```

该执行只负责当前机构的首次全量：

- 只读取当前机构全量数据；
- 只产生首次全量执行的载入批次；
- 执行自己的同步门禁校验；
- 校验通过后独立进入 `SUCCEEDED`；
- 消息策略启用时，为该全量执行创建一条全量 `message_outbox`；
- 不在该执行内继续创建增量载入阶段。

首次全量开始时固定记录：

```text
initial_watermark = 首次全量开始时间 T0
```

首次全量完整写入并通过同步门禁校验后，创建正式水位：

```text
task_watermark.watermark_value = T0
source_execution_id = 本次首次全量执行 ID
```

这样下一次增量可以覆盖首次全量运行期间发生的修改，但不需要在全量完成后立即追加一次增量执行。

首次全量失败或取消时：

- 不创建正式水位；
- 不创建成功消息 Outbox；
- 下一次正常运行仍按首次全量处理；
- 不创建“补充增量待执行”状态。

## 4. 后续增量执行

首次全量成功后，等待任务下一次正常计划时间。

下一次 Quartz 调度触发，或者用户在之后人工运行该任务时，创建新的独立执行：

```text
sync_execution.execution_scope = INCREMENTAL
```

该执行：

```text
读取当前正式 watermark
→ 计算本次固定 upper
→ 按 [watermark, upper) 读取增量
→ 写入 Doris
→ 执行自己的同步门禁校验
→ 成功后推进 watermark = upper
→ 按需创建本次增量的 message_outbox
```

首次全量执行和后续增量执行分别拥有：

- 独立 `sync_execution.id/execution_uuid`；
- 独立状态、开始时间和完成时间；
- 独立 `load_batch` 集合；
- 独立 `validation_run`；
- 独立日志和错误；
- 独立消息 Outbox。

页面任务运行历史按两次独立执行展示，不在一个执行详情中拼成“全量阶段 + 补充增量阶段”。

## 5. 调度行为

`FULL_THEN_INCREMENTAL` 描述的是同一长期任务在不同执行之间的运行方式，不表示一个复合执行。

调度规则保持简单：

1. 没有正式水位时，计划触发创建首次全量执行。
2. 首次全量运行期间到达的新计划触发，按既有并发规则直接跳过。
3. 首次全量结束后不补跑刚才被跳过的计划，也不立即启动增量。
4. 等待下一次正常 Cron/间隔触发，再创建增量执行。
5. 首次全量失败不会自动暂停任务；下一次正常调度仍可重新发起首次全量。

## 6. 目标物理模型影响

### 6.1 `sync_execution`

继续保留：

```text
execution_scope = INITIAL_FULL
execution_scope = INCREMENTAL
```

两者永远属于不同的执行记录。

不建立：

```text
parent_execution_id
child_execution_id
supplemental_increment_execution_id
execution_stage 表
INITIAL_FULL_AND_INCREMENTAL 组合范围
```

### 6.2 `load_batch`

一个执行内的全部批次必须与该执行范围一致：

```text
INITIAL_FULL execution
→ 全部批次都属于首次全量

INCREMENTAL execution
→ 全部批次都属于增量
```

已经确认删除：

```text
load_batch.phase
```

批次类型统一通过父执行推导：

```text
load_batch.execution_id
→ sync_execution.execution_scope
```

不允许同一执行混合全量和增量批次，也不在子表复制父级执行范围。详细结论见 `spec/P0_LOAD_BATCH_MODEL_REVIEW.md`。

### 6.3 `task_watermark`

- 首次全量成功后创建水位，值为首次全量开始时刻 `T0`；
- 后续增量成功后推进到该执行固定 `upper`；
- 失败、取消、数据补采和独立治理校验不推进水位；
- 不建立“补充增量水位”或双水位。

## 7. 被本结论修正的旧描述

以下旧描述废止：

```text
首次全量完成后立即执行一次补充增量
补充增量属于首次全量同一执行流程
一条 INITIAL_FULL 执行同时包含 FULL 和 INCREMENTAL load_batch
load_batch 通过 phase 重复保存父执行范围
```

阶段 1 最终一致性清理时，必须从以下文档机械删除或改写这些旧描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/TARGET_METADATA_MODEL.md
spec/TASKS.md
```

本文件和 `spec/P0_LOAD_BATCH_MODEL_REVIEW.md` 在上述清理完成前作为该问题的权威 Review 结论。

# P0 物理模型一致性 Review

> 状态：阶段 1 工作包 3 进行中  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文只记录目标模型一致性结论；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. Review 范围

本工作包逐项核对：

```text
全部 P0 PostgreSQL 目标表
Quartz JDBC JobStore 标准表
Doris 平台技术表
业务基线
目标逻辑模型
各批物理表字典
历史 SQL 审计
旧 Java 查询路径
spec/TASKS.md
```

重点检查：

- 同一事实是否重复保存；
- 同一枚举是否存在多套名称；
- 外键父子关系和删除行为是否一致；
- 唯一性及并发约束是否完整；
- 已废止旧功能是否仍残留；
- 物理字典能否无歧义转换为后续 Flyway V1。

每次只讨论一个真实业务冲突。能直接判断的技术字段、外键和索引直接修正。

## 2. 已确认的一致性修正

| 编号 | 修正内容 | 权威文档 |
| --- | --- | --- |
| C-001 | 正式同步校验不能关闭，最低为 `ROW_COUNT`。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-002 | 行数校验严格相等，删除全部容差字段。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-003 | 删除正式校验 `lookback_hours`，只校验本次执行精确范围。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-004 | 删除预检三级策略，只保存和展示预检事实。 | `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| C-005 | 任务允许用户更换链路，系统不建设自动迁移状态机。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-006 | 首次全量和后续定时增量为两次独立执行。 | `P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md` |
| C-007 | 删除 `load_batch.phase`。 | `P0_LOAD_BATCH_MODEL_REVIEW.md` |
| C-008 | 删除 `load_batch.time_lower/time_upper`，整次范围保存在父执行。 | `P0_LOAD_BATCH_MODEL_REVIEW.md` |
| C-009 | Doris 返回不明确时只探测原 Label；`UNKNOWN` 超时后失败、不自动重投。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-010 | 删除 `load_batch.probe_result`，统一为 DFETL 批次状态和 Doris 原始状态。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-011 | 任务修改直接覆盖 `sync_task`，删除 `sync_task_version` 和所有 `task_version_id` 引用。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |

## 3. 已确认：可变任务配置模型

### 3.1 冲突

早期目标模型把任务拆成：

```text
sync_task
sync_task_version
sync_task.current_version_id
```

并要求任务配置变化生成不可变版本。

实际业务规则已重新确认：

> 修改任务就是覆盖原任务当前配置，不需要任务配置版本、发布、切换或回退流程。

### 3.2 最终规则

```text
sync_task
= 当前有效任务配置
```

删除：

```text
sync_task_version
sync_task.current_version_id
sync_execution.task_version_id
validation_run.task_version_id
message_outbox.task_version_id
task_watermark.task_version_id
```

保留：

```text
standard_dataset_version
collection_route_version
字段转换合同版本
```

因为这些表示外部数据合同和链路解析合同，不是任务日常修改历史。

### 3.3 历史追溯

创建执行时，把本次实际使用的任务配置复制到 `sync_execution` 身份字段和配置快照。之后任务修改只影响后续新执行。

任务修改历史写入 `audit_log`，不通过任务版本表保存。

### 3.4 物理模型影响

- `sync_task` 直接保存当前 `dataset_version_id`、`route_version_id`、任务类型、读取参数和调度配置；
- `task_validation_policy` 继续只保存校验方法继承/覆盖；
- `task_watermark` 只保存任务当前正式水位；
- `validation_run` 关联执行或任务，并使用运行快照；
- `message_outbox` 关联执行并保存消息发布快照；
- 此前提出的 `validation_run` 三列任务版本复合外键取消。

专项 Review：

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
```

## 4. 当前已同步修正的文档

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
spec/TASKS.md
```

## 5. 阶段 1 最终机械清理清单

仍需从以下文档删除旧的任务版本描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
spec/PHASE1_REVIEW_STATUS.md
其他引用 sync_task_version/current_version_id/task_version_id 的文档
```

同时继续清理已经确认废止的：

```text
validation enabled
row_tolerance
validation lookback_hours
首次全量立即补充增量
load_batch.phase/time_lower/time_upper/probe_result
```

这些均属于已确认结论的机械同步，不重新讨论。

## 6. 后续检查顺序

下一项讨论：

```text
任务存在活动 sync_execution 时，是否允许编辑当前任务配置？
```

确认后继续：

1. 唯一 `SYNC_GATE validation_run` 与执行快照一致性；
2. Outbox、执行、数据集和机构身份一致性；
3. 删除快照控制对象外键闭环；
4. P0 表清单、外键矩阵、索引矩阵和枚举统一；
5. 阶段 1 最终 Review 与签字。

# P0 物理模型一致性 Review

> 状态：阶段 1 工作包 3 进行中  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文只记录目标模型一致性结论；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. Review 范围

逐项核对：

```text
全部 P0 PostgreSQL 目标表
Quartz JDBC JobStore 标准表
Doris 平台技术表
业务基线和目标模型
各批物理表字典
历史 SQL 审计
旧 Java 查询路径
spec/TASKS.md
```

重点检查：

- 同一事实是否重复保存；
- 枚举是否存在多套名称；
- 外键父子关系和删除行为是否一致；
- 唯一性和并发约束是否完整；
- 已废止旧功能是否仍残留；
- 物理字典能否无歧义转换为 Flyway V1。

每次只讨论一个真实业务冲突。能直接判断的字段、外键、约束和索引直接修正。

## 2. 已确认的一致性修正

| 编号 | 修正内容 | 权威文档 |
| --- | --- | --- |
| C-001 | 正式同步校验不能关闭，最低为 `ROW_COUNT`。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-002 | 行数严格相等，删除全部容差字段。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-003 | 删除校验 `lookback_hours`，只校验本次执行精确范围。 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` |
| C-004 | 删除预检三级策略，只保存和展示预检事实。 | `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| C-005 | 任务允许用户更换链路，不建设自动迁移状态机。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-006 | 首次全量和后续定时增量为两次独立执行。 | `P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md` |
| C-007 | 删除 `load_batch.phase`。 | `P0_LOAD_BATCH_MODEL_REVIEW.md` |
| C-008 | 删除 `load_batch.time_lower/time_upper`，整次范围保存在父执行。 | `P0_LOAD_BATCH_MODEL_REVIEW.md` |
| C-009 | Doris 返回不明确时只探测原 Label，`UNKNOWN` 超时后失败、不自动重投。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-010 | 删除 `load_batch.probe_result`，统一为 DFETL 批次状态和 Doris 原始状态。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-011 | 任务修改直接覆盖 `sync_task`，删除 `sync_task_version` 和全部 `task_version_id`。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-012 | 活动执行期间禁止编辑任务配置，不建立待生效配置。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-013 | 删除 `task_validation_policy`；任务级校验覆盖合并为 `sync_task.validation_method_override`。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-014 | `institution_id/dataset_id` 是稳定任务身份，创建后不可修改；更换身份时逻辑删除旧任务并新建。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |

## 3. 可变任务配置模型

最终任务模型：

```text
sync_task
= 固定业务身份 + 当前有效配置
```

稳定业务身份：

```text
institution_id
dataset_id
```

普通可编辑配置：

```text
dataset_version_id
route_version_id
name
task_kind
write_mode
doris_key_model
incremental_field_code
读取参数
调度配置
validation_method_override
```

明确删除：

```text
sync_task_version
sync_task.current_version_id
task_validation_policy
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

历史执行通过 `sync_execution` 启动快照追溯；任务修改历史通过 `audit_log` 追溯。

## 4. 任务业务身份不可修改

一个 `task_id` 在整个生命周期内始终表示同一个：

```text
医疗机构 + 标准数据集
```

因此：

- 编辑 DTO 不提供 `institution_id/dataset_id` 修改能力；
- 旧调用方或通用接口提交不同值时返回 `TASK_IDENTITY_IMMUTABLE`；
- 更换机构或数据集必须先确认没有活动执行，再逻辑删除旧任务并创建新任务；
- 旧任务的水位、执行、批次、校验、Outbox 和审计历史继续归属旧任务；
- 不建设任务身份迁移、历史搬迁、水位迁移或删除快照基线迁移。

未删除任务仍由以下部分唯一索引保证：

```text
(institution_id, dataset_id)
WHERE deleted_at IS NULL
```

## 5. 任务级校验覆盖存储

任务级覆盖直接保存：

```text
sync_task.validation_method_override
```

允许值：

```text
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

语义：

- `NULL`：继承数据集级覆盖或全局默认；
- 非空：任务明确覆盖；
- 不保存 `override_mode`；
- 不维护独立策略 revision、时间和一对一策略行；
- 来源为任务时，执行快照的 `validation_source_revision` 使用 `sync_task.revision`；
- 活动执行期间禁止修改；
- 无真实业务主键时不能保存 `ROW_COUNT_CHECKSUM`。

最终解析：

```text
任务字段
→ 数据集策略
→ 全局默认
→ 数据集合同强制
```

## 6. 当前已同步修正的文档

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
spec/TASKS.md
```

## 7. 阶段 1 最终机械清理

仍需从以下文档删除旧的任务版本、任务策略表和任务身份可修改描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/PHASE1_REVIEW_STATUS.md
其他引用 sync_task_version/task_validation_policy 的文档
```

同时继续清理：

```text
validation enabled
row_tolerance
validation lookback_hours
首次全量立即补充增量
load_batch.phase/time_lower/time_upper/probe_result
```

这些属于已确认结论的机械同步，不重新讨论。

## 8. 后续检查顺序

下一项讨论：

```text
是否继续保留独立 dataset_validation_policy，
还是把数据集级校验覆盖合并为 standard_dataset.validation_method_override？
```

确认后继续：

1. 唯一 `SYNC_GATE validation_run` 与执行快照一致性；
2. Outbox、执行、数据集和机构身份一致性；
3. 删除快照控制对象外键闭环；
4. P0 表清单、外键矩阵、索引矩阵和枚举统一；
5. 阶段 1 最终 Review 与签字。

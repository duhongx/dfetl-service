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

每次只讨论一个真实业务冲突。能直接判断的字段、外键、约束、索引和运行边界直接修正。

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
| C-009 | Doris 返回不明确时只探测原 Label；`UNKNOWN` 超时后失败、不自动重投。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-010 | 删除 `load_batch.probe_result`，统一为 DFETL 批次状态和 Doris 原始状态。 | `P0_DORIS_LABEL_PROBE_REVIEW.md` |
| C-011 | 任务修改直接覆盖 `sync_task`，删除 `sync_task_version` 和全部 `task_version_id`。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-012 | 活动同步执行期间禁止编辑任务配置，不建立待生效配置。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-013 | 删除 `task_validation_policy`；任务级校验覆盖合并到 `sync_task`。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-014 | `institution_id/dataset_id` 是任务固定身份，创建后不可修改。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-015 | 删除 `dataset_validation_policy`；数据集级覆盖合并到 `standard_dataset`。 | `P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md` |
| C-016 | 删除 `global_validation_policy`；全局默认使用 `validation.default_method`。 | `P0_GLOBAL_VALIDATION_SETTING_REVIEW.md` |
| C-017 | 同一任务同步执行与独立校验互斥；`SYNC_GATE` 除外。 | `P0_TASK_OPERATION_EXCLUSION_REVIEW.md` |
| C-018 | 同一任务最多一条活动独立校验；使用部分唯一索引兜底。 | `P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md` |
| C-019 | 活动独立校验不阻止任务普通配置编辑；当前校验使用启动快照。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-020 | `sync_execution/validation_run/message_outbox` 删除 `task_version_id`，改用启动快照。 | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |
| C-021 | `load_batch` 删除重复机构和协议字段，`committed_at` 更名为 `visible_at`。 | `P0_LOAD_BATCH_MODEL_REVIEW.md` |
| C-022 | `validation_run.difference_count` 在无法由整次 Checksum 推导差异行数时允许为空。 | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |

## 3. 当前任务与校验配置模型

### 3.1 任务

```text
sync_task
= 固定机构和数据集身份 + 当前有效执行配置
```

明确删除：

```text
sync_task_version
sync_task.current_version_id
task_validation_policy
全部 task_version_id
```

历史执行通过 `sync_execution` 启动快照追溯；历史独立校验通过 `validation_run` 启动快照追溯；任务修改通过 `audit_log` 追溯。

### 3.2 校验覆盖

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

明确删除：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
```

解析顺序：

```text
任务覆盖
→ 数据集覆盖
→ 系统设置中的全局默认
→ 注册默认值 ROW_COUNT
→ 数据集合同能力强制
```

## 4. 全局默认校验方式

注册设置：

```text
validation.default_method
```

允许：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

默认：

```text
ROW_COUNT
```

设置行缺失时使用注册默认值；运行中的执行和校验继续使用启动快照。

## 5. 同任务运行操作互斥

同一任务只允许：

```text
一条活动同步执行（包含自身 SYNC_GATE）
或
一条活动独立人工/治理校验
```

活动同步：

```text
PENDING/RUNNING/LOADING/VALIDATING
```

活动独立校验：

```text
trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
AND status IN ('PENDING','RUNNING')
```

固定规则：

- 同步与独立校验互斥；
- 同任务第二条独立校验直接拒绝；
- `SYNC_GATE` 不受独立校验唯一索引限制；
- 冲突不排队、不等待、不补跑；
- 定期治理冲突只跳过本次触发；
- 使用 `uk_sync_execution_active_task` 和 `uk_validation_run_active_independent_task` 分别兜底单表并发；
- 跨表互斥通过锁定同一 `sync_task` 后检查保证。

## 6. 用户控制与结果保证边界

系统只限制会使数据写入、校验范围或最终结果不确定的冲突：

```text
同步 vs 同步
同步 vs 独立校验
独立校验 vs 独立校验
同步 vs 任务编辑
```

独立校验启动时已经固定任务、数据集版本、链路版本、范围和校验方式快照，因此活动独立校验不阻止任务普通配置编辑。

任务编辑与独立校验启动通过短事务锁确定本次校验使用编辑前或编辑后的完整配置；校验启动后用户可以继续编辑，当前校验不热更新。

平台提供操作能力并保证每次运行输入和结果可追溯；不会替用户决定无关结果的操作顺序，也不建设待生效、自动迁移或过程编排状态。

## 7. 执行、校验和 Outbox 快照模型

### 7.1 `sync_execution`

直接保存：

```text
task_id + task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
task_kind/write_mode/doris_key_model
增量和读取参数
固定范围
最终校验方法和来源
消息策略快照
```

不保存 `task_version_id`、重复校验策略 JSON 或额外执行合同 Hash。

### 7.2 `validation_run`

- `SYNC_GATE/MANUAL_RECHECK` 关联原执行；
- 独立校验保存 `context_snapshot/range_snapshot`；
- 后续任务编辑不改变本次校验；
- `difference_count` 只在能够准确计算时保存；整次 Checksum 不一致但无法推导差异行数时允许为空。

### 7.3 `message_outbox`

不保存任务版本。Outbox 关联原执行，并复制任务、数据集、机构身份及消息策略和范围快照；人工重发继续按已确认规则读取当前 Doris。

### 7.4 `load_batch`

批次只保存分页游标、行数、载荷摘要、Label 和 Doris 状态。类型、范围、机构和协议从父执行取得。确认可见时间统一使用 `visible_at`。

## 8. 当前已同步修正的文档

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md
spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_LOAD_BATCH_MODEL_REVIEW.md
spec/P0_DORIS_LABEL_PROBE_REVIEW.md
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
spec/TASKS.md
```

## 9. 阶段 1 最终机械清理

仍需从以下旧文档删除已废止描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/PHASE1_REVIEW_STATUS.md
其他仍引用旧任务版本或三张校验策略表的文档
```

继续清理：

```text
validation enabled/row_tolerance/lookback_hours
首次全量立即补充增量
load_batch.phase/time_lower/time_upper/probe_result
load_batch.institution_code/checksum_protocol_version/committed_at
任务身份可修改
独立校验期间禁止编辑任务
```

这些属于已确认结论的机械同步，不重新讨论。

## 10. 后续检查顺序

不再为不影响运行结果的过程限制单独提出确认。下一步直接完成：

1. 删除快照控制对象与 `validation_run` 的外键闭环；
2. 复核 Outbox 的发布范围与各种执行类型映射；
3. 形成唯一 P0 PostgreSQL 表清单；
4. 形成完整外键、删除行为、唯一性、索引和枚举矩阵；
5. 清理业务基线、目标模型和早期字典残留；
6. 形成 `PHASE1_FINAL_REVIEW.md` 并进入最终签字。

只有发现会改变业务结果、现有文档无法判断的真实选择时，才继续提出单项确认。

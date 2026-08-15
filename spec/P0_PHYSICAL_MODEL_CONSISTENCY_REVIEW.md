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
| C-013 | 删除 `task_validation_policy`；任务级校验覆盖合并为 `sync_task.validation_method_override`。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-014 | `institution_id/dataset_id` 是任务固定身份，创建后不可修改。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |
| C-015 | 删除 `dataset_validation_policy`；数据集级校验覆盖合并为 `standard_dataset.validation_method_override`。 | `P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md` |
| C-016 | 删除 `global_validation_policy`；全局默认使用注册系统设置 `validation.default_method`。 | `P0_GLOBAL_VALIDATION_SETTING_REVIEW.md` |
| C-017 | 同一任务的同步执行与独立人工/治理校验互斥；`SYNC_GATE` 除外。 | `P0_TASK_OPERATION_EXCLUSION_REVIEW.md` |
| C-018 | 同一任务最多一条活动独立校验；使用部分唯一索引兜底。 | `P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md` |
| C-019 | 活动独立校验不阻止任务普通配置编辑；当前校验继续使用启动快照。 | `P0_MUTABLE_TASK_MODEL_REVIEW.md` |

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

任务当前配置包括：

```text
dataset_version_id
route_version_id
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
调度配置
validation_method_override
```

历史执行通过 `sync_execution` 启动快照追溯；历史独立校验通过 `validation_run` 启动快照追溯；任务修改通过 `audit_log` 追溯。

### 3.2 校验覆盖

当前校验配置层级为：

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

数据集和任务使用 `NULL` 表示继承，不再保存独立 `override_mode`。

## 4. 全局默认校验方式的已确认边界

注册系统设置：

```text
validation.default_method
```

允许值：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认值：

```text
ROW_COUNT
```

固定规则：

- `system_setting` 中没有设置行时使用注册默认值；
- 第一次保存时插入行，后续使用 `system_setting.revision`；
- Flyway V1 不依赖固定单例策略行存在；
- 只允许注册枚举，不允许任意文本或未注册 key；
- 修改成功和失败都进入 `audit_log`；
- 运行中的执行和校验继续使用启动快照，后续新运行重新解析；
- 无真实业务主键时最终由合同强制为 `ROW_COUNT`。

专项 Review：

```text
spec/P0_GLOBAL_VALIDATION_SETTING_REVIEW.md
```

## 5. 同任务运行操作互斥

同一任务固定只允许一种业务运行形态：

```text
一条活动同步执行（包含自身 SYNC_GATE）
或
一条活动独立人工/治理校验
```

活动同步状态：

```text
PENDING
RUNNING
LOADING
VALIDATING
```

活动独立校验：

```text
trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
AND status IN ('PENDING','RUNNING')
```

固定规则：

- 活动同步存在时，独立校验直接拒绝；
- 活动独立校验存在时，计划、人工和外部 API 同步直接拒绝；
- 同一任务存在活动独立校验时，第二条独立校验直接拒绝；
- `SYNC_GATE` 属于父同步执行内部流程，不受独立校验唯一约束限制；
- 不同任务可以并行；
- 冲突不排队、不等待、不补跑；
- 统一返回 `TASK_OPERATION_ACTIVE`，包含占用对象 ID、类型、状态和处理建议；
- 定期治理冲突只跳过本次触发，不创建 `SKIPPED validation_run`；
- 同步启动和独立校验启动锁定同一条 `sync_task`，避免运行并发穿透。

数据库并发兜底：

```sql
CREATE UNIQUE INDEX uk_validation_run_active_independent_task
    ON validation_run (task_id)
    WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
      AND status IN ('PENDING','RUNNING');
```

专项 Review：

```text
spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md
spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md
```

## 6. 用户控制与结果保证边界

系统只限制会使数据写入、校验范围或最终结果失去确定性的冲突：

```text
同步 vs 同步
同步 vs 独立校验
独立校验 vs 独立校验
同步 vs 任务编辑
```

独立校验启动时已经固定任务、数据集版本、链路版本、范围和校验方式快照，因此：

```text
活动独立校验
vs
任务普通配置编辑
```

不构成结果冲突，不增加限制。

任务编辑与独立校验启动通过短事务锁确定本次校验使用编辑前或编辑后的完整配置。校验启动后用户可以继续编辑，当前校验不热更新，后续运行使用新配置。

平台提供操作能力并保证每次运行输入和结果可追溯；不会替用户决定无关结果的操作顺序，也不建设待生效、自动迁移或过程编排状态。

专项 Review：

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md
spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md
```

## 7. 当前已同步修正的文档

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md
spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
spec/TASKS.md
```

## 8. 阶段 1 最终机械清理

仍需从以下文档删除旧任务版本和三张独立校验策略表描述：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/PHASE1_REVIEW_STATUS.md
其他引用 sync_task_version/global_validation_policy/dataset_validation_policy/task_validation_policy 的文档
```

同时继续清理：

```text
validation enabled
row_tolerance
validation lookback_hours
首次全量立即补充增量
load_batch.phase/time_lower/time_upper/probe_result
任务身份可修改描述
允许同步和独立校验同时运行的旧描述
允许同任务多条独立校验并发的旧描述
独立校验期间禁止编辑任务普通配置的旧描述
```

这些属于已确认结论的机械同步，不重新讨论。

## 9. 后续检查顺序

不再为不影响运行结果的过程限制单独提出确认。下一步直接执行技术一致性清理：

1. 从 `sync_execution`、`validation_run`、`message_outbox` 删除 `task_version_id`，补齐执行和校验启动快照；
2. 完成唯一 `SYNC_GATE validation_run` 与父执行的任务、范围和校验快照一致性；
3. 核对 Outbox、执行、数据集和机构身份一致性；
4. 完成删除快照控制对象外键闭环；
5. 形成 P0 表清单、外键矩阵、索引矩阵和枚举矩阵；
6. 完成阶段 1 最终 Review 与签字。

只有发现会改变业务结果、现有文档无法判断的真实选择时，才继续提出单项确认。

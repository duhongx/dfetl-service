# P0 物理表字典：正式同步校验策略与严格门禁

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 任务模型：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> 数据集覆盖：`spec/P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 文档定位

本文是正式同步校验策略的当前权威物理字典，覆盖：

```text
global_validation_policy
standard_dataset.validation_method_override
sync_task.validation_method_override
sync_execution 校验快照
SYNC_GATE validation_run
```

明确不建立：

```text
dataset_validation_policy
task_validation_policy
校验开关表
校验容差表
校验回看策略表
自动复检策略表
```

以下旧字段不能进入 Flyway V1：

```text
enabled
row_tolerance
tolerance_rows
tolerance_percent
lookback_hours
dataset/task validation override_mode
```

## 2. 固定业务语义

每次正式同步都必须执行同步后门禁校验：

```text
全部 load_batch 已 SUCCEEDED + VISIBLE
→ 创建或完成唯一 SYNC_GATE validation_run
→ 至少执行 ROW_COUNT
→ 必须 COMPLETED + PASS
→ sync_execution 才能进入 SUCCEEDED
→ 按需推进 task_watermark
→ 按需创建 message_outbox
```

固定规则：

1. 正式同步校验不能关闭。
2. 最低方式始终为 `ROW_COUNT`。
3. `ROW_COUNT` 要求源端和目标端行数严格相等，差异 1 行也失败。
4. 不支持绝对容差、百分比容差或动态放宽。
5. `ROW_COUNT_CHECKSUM` 必须同时满足行数严格相等和业务字段 Checksum 一致。
6. 正式门禁只校验本次执行精确机构范围和数据范围。
7. 不保存、不展示、不接受 `lookback_hours`。
8. 无真实业务主键的数据集最终只能使用 `ROW_COUNT`。
9. 有真实业务主键的数据集可以选择 `ROW_COUNT_CHECKSUM`，但不能在执行中静默降级。
10. 默认不自动复检；人工重新校验创建独立 `validation_run`，不覆盖原门禁记录和原执行结果。

## 3. 校验回看与增量读取回看

正式同步校验回看字段已删除：

```text
validation lookback_hours
```

增量读取回看继续保存在：

```text
dataset_sync_policy.lookback_seconds
sync_task.lookback_seconds
sync_execution 执行快照
```

`lookback_seconds` 会改变本次实际读取范围；校验严格使用执行最终固定的真实范围，不再额外向历史范围扩展。

## 4. `global_validation_policy`

职责：保存正式同步校验的全局默认方法。当前 P0 设计为单例一行；是否继续独立成表在下一项一致性 Review 中确认。

### 4.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `smallint` | PK，固定为 `1` | 单例行 |
| `validation_method` | `varchar(32)` | NOT NULL DEFAULT `'ROW_COUNT'` | `ROW_COUNT/ROW_COUNT_CHECKSUM` |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 创建时间 |
| `created_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 更新时间 |
| `updated_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |

约束：

```text
CHECK (id = 1)
CHECK (validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (revision >= 0)
```

不保存：

```text
enabled
row_tolerance
lookback_hours
revalidate_enabled
revalidate_delay
fail_block
auto_repair
```

无业务主键任务解析到全局 `ROW_COUNT_CHECKSUM` 时，根据数据集合同明确强制为唯一支持的 `ROW_COUNT`，并在执行快照中记录 `CONTRACT` 来源。这是合同能力解析，不是执行中静默降级。

## 5. `standard_dataset.validation_method_override`

数据集级覆盖直接合并到数据集身份行：

```text
validation_method_override varchar(32) NULL
```

语义：

| 值 | 含义 |
| --- | --- |
| `NULL` | 数据集不覆盖，使用全局默认。 |
| `ROW_COUNT` | 数据集默认使用严格行数校验。 |
| `ROW_COUNT_CHECKSUM` | 数据集默认使用严格行数和内容 Checksum。 |

约束：

```text
CHECK (validation_method_override IS NULL OR
       validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
```

固定规则：

- 不保存 `override_mode`；`NULL` 就是继承。
- 不维护独立策略 revision、时间和一对一策略行。
- 使用 `standard_dataset.revision/updated_at/updated_by` 和通用 `audit_log`。
- 数据集定义同步不得覆盖该管理员配置。
- 当前数据集合同无真实业务主键时，拒绝保存 `ROW_COUNT_CHECKSUM`。
- 不为该低选择度字段单独建立索引。

## 6. `sync_task.validation_method_override`

任务级覆盖直接合并到当前任务：

```text
validation_method_override varchar(32) NULL
```

语义：

| 值 | 含义 |
| --- | --- |
| `NULL` | 任务不覆盖，继续解析数据集覆盖和全局默认。 |
| `ROW_COUNT` | 任务明确使用严格行数校验。 |
| `ROW_COUNT_CHECKSUM` | 任务明确使用严格行数和内容 Checksum。 |

约束：

```text
CHECK (validation_method_override IS NULL OR
       validation_method_override IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
```

固定规则：

- 不保存 `override_mode`。
- 不维护独立策略 revision、时间和一对一策略行。
- 使用 `sync_task.revision/updated_at/updated_by` 和通用 `audit_log`。
- 活动执行期间禁止修改。
- 无真实业务主键时拒绝保存 `ROW_COUNT_CHECKSUM`。
- 任务创建时不插入额外策略行。

## 7. 最终策略解析

每次执行启动前按以下顺序解析：

```text
sync_task.validation_method_override 非空
→ standard_dataset.validation_method_override 非空
→ global_validation_policy
→ 数据集合同能力强制
```

解析结果包含：

```text
validation_method
source_level
source_revision
contract_forced
```

其中：

- `source_level`：`GLOBAL/DATASET/TASK/CONTRACT`；
- 来源为 `GLOBAL`：`source_revision=global_validation_policy.revision`；
- 来源为 `DATASET`：`source_revision=standard_dataset.revision`；
- 来源为 `TASK`：`source_revision=sync_task.revision`；
- 来源为 `CONTRACT`：revision 为空且 `contract_forced=true`。

运行中执行始终使用启动时快照，不受后续设置修改影响。

## 8. `sync_execution` 校验快照

`sync_execution` 保存本次实际采用的最小校验快照：

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `validation_method` | `varchar(32)` | NOT NULL | `ROW_COUNT/ROW_COUNT_CHECKSUM` |
| `validation_source` | `varchar(16)` | NOT NULL | `GLOBAL/DATASET/TASK/CONTRACT` |
| `validation_source_revision` | `bigint` | NULL | 来源 revision；合同强制时为空 |
| `validation_contract_forced` | `boolean` | NOT NULL DEFAULT `false` | 是否由数据集合同强制收敛 |

约束：

```text
CHECK (validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (validation_source IN ('GLOBAL','DATASET','TASK','CONTRACT'))
CHECK (validation_source_revision IS NULL OR validation_source_revision >= 0)
CHECK (
  (validation_source = 'CONTRACT'
    AND validation_contract_forced = true
    AND validation_source_revision IS NULL)
  OR
  (validation_source <> 'CONTRACT'
    AND validation_contract_forced = false)
)
```

不保存：

```text
validation_enabled
row_tolerance
lookback_hours
dataset_policy_id
task_policy_id
```

本次校验范围来自执行固定快照：

```text
execution_scope
window_lower/window_upper
key_lower/key_upper
institution_id
dataset_id/dataset_version_id
route_version_id
```

## 9. `SYNC_GATE validation_run`

每次正式同步执行必须且只能有一条同步门禁校验：

```text
UNIQUE INDEX uk_validation_sync_gate_execution
    ON validation_run (execution_id)
    WHERE trigger_type = 'SYNC_GATE'
```

门禁结果固定为：

```text
ROW_COUNT:
source_row_count = target_row_count
AND difference_count = 0

ROW_COUNT_CHECKSUM:
source_row_count = target_row_count
AND difference_count = 0
AND source_checksum = target_checksum
```

范围规则：

| 执行类型 | 门禁校验范围 |
| --- | --- |
| `INITIAL_FULL` | 当前机构本次全量 |
| `INCREMENTAL` | 本次固定 `[watermark, upper)` |
| `FULL` | 当前机构本次全量 |
| `BACKFILL_TIME` | 用户明确指定的历史时间范围 |
| `BACKFILL_KEY` | 用户明确指定的主键范围 |
| `RECOLLECT` | 本次重新采集的实际范围 |

人工重新校验和定期治理校验需要历史范围时，在运行请求和 `validation_run.range_snapshot` 中显式保存，不通过策略中的隐式回看窗口表达。

## 10. 成功收尾

执行成功收尾短事务固定检查：

```text
全部 load_batch = SUCCEEDED
AND 全部 doris_state = VISIBLE
AND rejected_row_count = 0
AND 唯一 SYNC_GATE validation_run = COMPLETED + PASS
```

满足后才允许：

```text
sync_execution → SUCCEEDED
按规则推进 task_watermark
按数据集消息策略插入唯一 message_outbox
```

该事务内不调用 Doris、RabbitMQ 或其他远程服务。

## 11. 旧模型处置

以下对象、字段和能力不迁移到新系统：

```text
dataset_validation_policy
task_validation_policy
dataset/task override_mode
validation enabled/disabled
tolerance_rows
tolerance_percent
row_tolerance
lookback_hours
revalidate_enabled
revalidate_delay
auto_repair
fail_block
SAMPLE
ALL
独立 CHECKSUM 方法
```

`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 中仍残留的 `dataset_validation_policy` 章节，以及其他文档中的 `task_validation_policy` 描述，均由本文覆盖；阶段 1 最终一致性清理时机械删除，不再重新讨论。

# P0 物理表字典：正式同步校验策略与严格门禁

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-14  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 文档定位

本文是正式同步校验策略的当前权威物理字典，覆盖：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
sync_execution 中的校验策略快照
SYNC_GATE validation_run
```

本文覆盖以下旧字典中与校验开关、行数容差和校验回看窗口有关的旧字段定义：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
```

旧文档中残留的以下字段不能进入 Flyway V1：

```text
enabled
row_tolerance
tolerance_rows
tolerance_percent
lookback_hours
```

## 2. 固定业务语义

每次正式同步都必须执行同步后门禁校验：

```text
写入完成
→ 创建唯一 SYNC_GATE validation_run
→ 至少执行 ROW_COUNT
→ 必须 COMPLETED + PASS
→ sync_execution 才能进入 SUCCEEDED
→ 按需推进 task_watermark
→ 按需创建 message_outbox
```

固定规则：

1. 正式同步校验不能关闭。
2. 最低校验方式始终为 `ROW_COUNT`。
3. `ROW_COUNT` 要求源端和目标端行数严格相等，差异 1 行也失败。
4. 不支持绝对行数容差、百分比容差或动态放宽。
5. `ROW_COUNT_CHECKSUM` 必须同时满足行数严格相等和业务字段 Checksum 一致。
6. 正式同步门禁只校验本次执行的精确机构范围和数据范围，不向历史范围回看。
7. 不保存、不展示、不接受 `lookback_hours`。
8. 无真实业务主键的数据集固定使用 `ROW_COUNT`。
9. 有真实业务主键的数据集可以配置 `ROW_COUNT_CHECKSUM`，但不能静默降级。
10. 默认不自动复检；人工重新校验创建独立 `validation_run`，不覆盖原门禁结果。

## 3. 校验回看与增量读取回看必须区分

本文件删除的是正式同步校验的回看窗口：

```text
validation lookback_hours
```

它原本表示把校验范围向本次同步窗口之前扩展若干小时。该行为与“校验必须使用本次执行实际范围”冲突，因此直接删除，而不是保留一个固定为 0 的无效字段。

以下字段属于另一项已经确认的执行合同，继续保留：

```text
sync_task_version.lookback_seconds
dataset_sync_policy.lookback_seconds
```

`lookback_seconds` 是增量读取回看窗口，用于特殊数据源处理迟到更新；默认值为 0，用户明确配置后会改变实际读取范围，并固化到任务版本。它不是校验回看窗口，不能与本文件删除的 `lookback_hours` 混用。

## 4. `global_validation_policy`

职责：保存正式同步校验的全局默认方法。P0 只有一行。

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

明确不保存：

```text
enabled
row_tolerance
tolerance_rows
tolerance_percent
lookback_hours
trigger_mode
fail_block
revalidate_enabled
revalidate_delay
```

### 4.2 约束

```text
CHECK (id = 1)
CHECK (validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (revision >= 0)
```

无业务主键任务解析到全局 `ROW_COUNT_CHECKSUM` 时，在执行启动前根据数据集合同明确收敛为其唯一支持的 `ROW_COUNT`，并把来源记录为合同强制值。这属于合同解析，不是执行中静默降级。

## 5. `dataset_validation_policy`

职责：保存数据集级继承或覆盖的正式同步校验方法。不能关闭校验，也不能配置容差或回看窗口。

### 5.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `dataset_id` | `bigint` | PK/FK | FK `standard_dataset(id)`，`ON DELETE CASCADE` |
| `override_mode` | `varchar(16)` | NOT NULL DEFAULT `'INHERIT'` | `INHERIT/OVERRIDE` |
| `validation_method` | `varchar(32)` | NULL | 覆盖时必填 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 创建时间 |
| `created_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 更新时间 |
| `updated_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |

### 5.2 约束

```text
CHECK (override_mode IN ('INHERIT','OVERRIDE'))
CHECK (validation_method IS NULL OR
       validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (revision >= 0)

CHECK (
  (override_mode = 'INHERIT' AND validation_method IS NULL)
  OR
  (override_mode = 'OVERRIDE' AND validation_method IS NOT NULL)
)
```

应用保存时必须：

- 当前数据集版本没有真实业务主键时，拒绝保存 `ROW_COUNT_CHECKSUM`；
- 拒绝 `enabled`、`rowTolerance`、`toleranceRows`、`tolerancePercent`、`lookbackHours` 等旧字段；
- 成功和失败均写操作审计。

## 6. `task_validation_policy`

职责：保存任务级继承或覆盖的正式同步校验方法。不能关闭校验，也不能配置容差或回看窗口。

### 6.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `task_id` | `bigint` | PK/FK | FK `sync_task(id)`，`ON DELETE RESTRICT` |
| `override_mode` | `varchar(16)` | NOT NULL DEFAULT `'INHERIT'` | `INHERIT/OVERRIDE` |
| `validation_method` | `varchar(32)` | NULL | 覆盖时必填 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 创建时间 |
| `created_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 更新时间 |
| `updated_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |

### 6.2 约束

```text
CHECK (override_mode IN ('INHERIT','OVERRIDE'))
CHECK (validation_method IS NULL OR
       validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (revision >= 0)

CHECK (
  (override_mode = 'INHERIT' AND validation_method IS NULL)
  OR
  (override_mode = 'OVERRIDE' AND validation_method IS NOT NULL)
)
```

任务创建时同步创建一行 `INHERIT`。任务覆盖只决定校验方法，不保存开关、容差、回看、自动复检、自动修复或失败动作。

## 7. 最终策略解析

每次新执行启动前按以下优先级解析：

```text
任务 OVERRIDE
→ 数据集 OVERRIDE
→ 全局默认
→ 数据集合同能力强制
```

解析结果固定包含：

```text
validation_method
source_level
source_revision
contract_forced
```

其中：

- `source_level`：`GLOBAL/DATASET/TASK/CONTRACT`；
- `source_revision`：来自全局、数据集或任务策略的 revision；合同强制时为空；
- `contract_forced=true`：表示无业务主键数据集被明确限制为 `ROW_COUNT`。

运行中执行不受策略修改影响；下一次新执行重新解析。

## 8. `sync_execution` 校验快照

`sync_execution` 保存本次实际采用的最小校验快照：

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `validation_method` | `varchar(32)` | NOT NULL | `ROW_COUNT/ROW_COUNT_CHECKSUM` |
| `validation_source` | `varchar(16)` | NOT NULL | `GLOBAL/DATASET/TASK/CONTRACT` |
| `validation_source_revision` | `bigint` | NULL | 策略 revision；合同强制时为空 |
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
```

本次校验范围来自执行自身的固定范围快照，而不是来自校验策略：

```text
execution_scope
window_start
window_end
institution_id
任务版本和链路版本
```

## 9. `SYNC_GATE validation_run`

每次正式同步执行必须且只能有一条同步门禁校验：

```text
UNIQUE INDEX uk_validation_run_sync_gate_execution
    ON validation_run (execution_id)
    WHERE validation_type = 'SYNC_GATE'
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
| 首次全量 | 当前机构本次全量 |
| 首次全量补充增量 | 补充增量实际固定窗口 |
| 日常增量 | 本次固定 `[watermark, upper)` |
| 有主键全量 UPSERT | 当前机构本次全量 |
| 无主键机构范围清理重载 | 当前机构清理并重载后的全量 |
| 重新采集 | 本次重新采集实际范围 |
| 数据补采 | 用户明确指定的历史时间或主键范围 |

人工重新校验和定期治理校验需要历史范围时，在运行请求和 `validation_run` 中显式保存 `scope/window_start/window_end`，不通过策略中的隐式回看窗口表达。

## 10. 成功收尾

执行成功收尾短事务固定检查：

```text
全部 load_batch 已确认 COMMITTED
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

以下旧字段和能力不迁移到新系统：

```text
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
独立 CHECKSUM 方法（对外统一为 ROW_COUNT_CHECKSUM）
```

旧数据中的开关、容差和校验回看配置不进入新系统配置迁移。新系统所有正式同步至少执行严格 `ROW_COUNT`。

# P0 物理表字典：正式同步校验策略与严格行数门禁

> 状态：阶段 1 工作包 3 一致性 Review 已确认  
> 日期：2026-08-14  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 一致性 Review：`spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 文档定位

本文是正式同步校验策略的当前权威物理字典，覆盖以下对象：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
sync_execution 中的校验策略快照
SYNC_GATE validation_run
```

本文对以下旧字典中与校验开关、行数容差有关的字段定义具有覆盖效力：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
```

最终一致性检查时，旧文档中残留的 `enabled`、`row_tolerance` 及相应约束必须机械清理，不能进入 Flyway V1。

## 2. 已确认的固定业务语义

每次正式同步都必须执行同步后门禁校验，不允许关闭：

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

1. 正式同步校验没有启用/关闭开关。
2. 最低校验方式始终为 `ROW_COUNT`。
3. `ROW_COUNT` 要求源端和目标端行数严格相等。
4. 不支持绝对行数容差、百分比容差或按数据量动态放宽。
5. `ROW_COUNT_CHECKSUM` 必须同时满足行数严格相等和业务字段 Checksum 一致。
6. 任何不一致、无法完成或技术异常都阻止执行成功和水位推进。
7. 不创建 `SKIPPED` 门禁校验记录，也不通过空策略绕过校验。
8. 无真实业务主键的数据集固定使用 `ROW_COUNT`。
9. 有真实业务主键的数据集可以配置 `ROW_COUNT_CHECKSUM`，但不能静默降级。
10. 默认不自动复检；人工重新校验生成独立 `validation_run`，不覆盖原门禁结果。

## 3. `global_validation_policy`

职责：保存正式同步校验的全局默认方法和当前仍允许继承的参数。P0 只有一行。

### 3.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `smallint` | PK，固定为 `1` | 单例行 |
| `validation_method` | `varchar(32)` | NOT NULL DEFAULT `'ROW_COUNT'` | `ROW_COUNT/ROW_COUNT_CHECKSUM` |
| `lookback_hours` | `integer` | NOT NULL DEFAULT `0` | 当前保留字段，是否继续支持由下一项一致性 Review 确认 |
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
trigger_mode
fail_block
revalidate_enabled
revalidate_delay
```

### 3.2 约束

```text
CHECK (id = 1)
CHECK (validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (lookback_hours BETWEEN 0 AND 8760)
CHECK (revision >= 0)
```

无业务主键任务解析全局 `ROW_COUNT_CHECKSUM` 时，必须在执行启动前根据数据集合同明确收敛为其唯一支持的 `ROW_COUNT`，并记录来源为合同强制值；这不是执行中静默降级。

## 4. `dataset_validation_policy`

职责：数据集级继承或覆盖正式同步校验方法及当前仍允许覆盖的参数。不能关闭校验，也不能配置行数容差。

### 4.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `dataset_id` | `bigint` | PK/FK | FK `standard_dataset(id)`，`ON DELETE CASCADE` |
| `override_mode` | `varchar(16)` | NOT NULL DEFAULT `'INHERIT'` | `INHERIT/OVERRIDE` |
| `validation_method` | `varchar(32)` | NULL | 覆盖时必填 |
| `lookback_hours` | `integer` | NULL | 覆盖时必填；是否继续支持由下一项一致性 Review 确认 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁版本 |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 创建时间 |
| `created_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 更新时间 |
| `updated_by` | `bigint` | NULL | FK `app_user(id)`，`ON DELETE SET NULL` |

### 4.2 约束

```text
CHECK (override_mode IN ('INHERIT','OVERRIDE'))
CHECK (validation_method IS NULL OR
       validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM'))
CHECK (lookback_hours IS NULL OR lookback_hours BETWEEN 0 AND 8760)
CHECK (revision >= 0)

CHECK (
  (override_mode = 'INHERIT'
    AND validation_method IS NULL
    AND lookback_hours IS NULL)
  OR
  (override_mode = 'OVERRIDE'
    AND validation_method IS NOT NULL
    AND lookback_hours IS NOT NULL)
)
```

应用保存时验证：

- 当前数据集版本没有真实业务主键时，拒绝保存 `ROW_COUNT_CHECKSUM`；
- 不接受 `enabled`、`rowTolerance`、`toleranceRows`、`tolerancePercent` 等旧字段；
- 成功和失败均写操作审计。

## 5. `task_validation_policy`

职责：任务级继承或覆盖正式同步校验方法及当前仍允许覆盖的参数。不能关闭校验，也不能配置行数容差。

### 5.1 字段

| 列 | PostgreSQL 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `task_id` | `bigint` | PK/FK | FK `sync_task(id)`，`ON DELETE RESTRICT` |
| `override_mode` | `varchar(16)` | NOT NULL DEFAULT `'INHERIT'` | `INHERIT/OVERRIDE` |
| `validation_method` | `varchar(32)` | NULL | 覆盖时必填 |
| `lookback_hours` | `integer` | NULL | 覆盖时必填；是否继续支持由下一项一致性 Review 确认 |
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
CHECK (lookback_hours IS NULL OR lookback_hours BETWEEN 0 AND 8760)
CHECK (revision >= 0)

CHECK (
  (override_mode = 'INHERIT'
    AND validation_method IS NULL
    AND lookback_hours IS NULL)
  OR
  (override_mode = 'OVERRIDE'
    AND validation_method IS NOT NULL
    AND lookback_hours IS NOT NULL)
)
```

应用保存时根据任务当前数据集版本校验 Checksum 能力。任务级覆盖只影响后续新执行；运行中的执行继续使用启动时快照。

## 6. 执行快照

`sync_execution` 的有效校验配置快照至少保存：

```text
validation_method
validation_lookback_hours
validation_policy_source
validation_policy_revision
checksum_contract_version
```

明确不保存：

```text
validation_enabled
row_tolerance
tolerance_rows
tolerance_percent
```

其中 `validation_policy_source` 使用受控值：

```text
GLOBAL
DATASET
TASK
CONTRACT_FORCED
```

`CONTRACT_FORCED` 仅用于无业务主键数据集把上级 `ROW_COUNT_CHECKSUM` 在执行前明确收敛为 `ROW_COUNT` 的场景。

## 7. `SYNC_GATE validation_run`

每个正式同步执行必须且只能存在一条门禁校验：

```text
UNIQUE INDEX uk_validation_run_sync_gate_execution
    ON validation_run (sync_execution_id)
    WHERE run_type = 'SYNC_GATE'
```

门禁结果要求：

### `ROW_COUNT`

```text
source_row_count = target_row_count
AND difference_count = 0
```

### `ROW_COUNT_CHECKSUM`

```text
source_row_count = target_row_count
AND difference_count = 0
AND source_checksum = target_checksum
```

执行成功收尾必须检查：

```text
execution_status = COMPLETED
result_status = PASS
```

行数差异为 1 行也必须判定为 `FAIL`。校验技术异常使用执行失败状态和稳定错误码，不能把未知结果视为通过。

## 8. API、前端和兼容边界

- 前端不展示校验启用开关和任何行数容差输入框。
- 数据集和任务配置只显示继承/覆盖、校验方法和当前仍保留的其他参数。
- API 遇到旧 `enabled` 或容差字段时明确返回不支持错误，不静默忽略。
- 老数据库和旧实体中的容差配置不迁移到新系统。
- 旧代码的“绝对容差、全局百分比容差、任务百分比容差取最大值”逻辑全部废止。
- 独立人工治理校验如需比较特定范围，应通过明确的运行范围表达，不复用正式同步门禁的行数容差。

## 9. 验收场景

1. 源端 1000 行、目标端 1000 行：`ROW_COUNT` 通过。
2. 源端 1000 行、目标端 999 行：即使只差 1 行也失败，水位不推进。
3. 源端和目标端行数相同但业务字段不同：`ROW_COUNT_CHECKSUM` 失败。
4. 调用保存接口传 `enabled=false`：明确拒绝。
5. 调用保存接口传任意容差字段：明确拒绝。
6. 无业务主键数据集请求 `ROW_COUNT_CHECKSUM`：保存阶段拒绝；继承上级该方法时，执行前明确使用 `ROW_COUNT` 并记录 `CONTRACT_FORCED`。
7. 校验失败后人工重新校验：生成独立历史，不改写原 `SYNC_GATE` 记录，也不追溯推进原执行水位。

## 10. 当前未决项

当前仅剩一个与本字典直接相关的业务选择：

> 正式同步门禁是否继续允许 `lookback_hours` 扩大校验范围，还是固定只校验本次同步的精确范围。

该问题在下一次一致性 Review 中单独确认。除该字段外，校验不可关闭和行数严格相等已经确认，不再重新讨论。

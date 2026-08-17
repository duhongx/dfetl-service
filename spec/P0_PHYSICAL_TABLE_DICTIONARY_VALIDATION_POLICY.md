# P0 物理表字典：正式同步 Validation 配置与严格 Gate

> 状态：阶段 1 Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Task：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> Dataset Override：`spec/P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md`  
> Global Default：`spec/P0_GLOBAL_VALIDATION_SETTING_REVIEW.md`

## 1. 当前唯一 Validation 配置模型

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
sync_execution Validation Snapshot
validation_run
```

明确不建立：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
Validation Enable/Tolerance/Lookback/Auto Revalidate Policy
```

## 2. 正式同步 Gate

每次正式同步：

```text
全部 load_batch = SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE validation_run
→ 最低严格 ROW_COUNT
→ 必须 COMPLETED + PASS
→ sync_execution 才能 SUCCEEDED
→ 按规则推进 task_watermark
→ 按 Dataset Message Policy 创建 message_outbox
```

固定规则：

1. Validation 不能关闭。
2. ROW_COUNT 严格相等，无容差。
3. ROW_COUNT_CHECKSUM 同时要求 Row Count + Checksum 一致。
4. Gate 只验证 Execution 固定范围。
5. 无真实业务主键 Dataset 只能 ROW_COUNT。
6. 有真实业务主键可选择 ROW_COUNT_CHECKSUM，不允许运行中静默降级。
7. 不自动复检；人工 Recheck 创建独立 `validation_run`。

## 3. Global / Dataset / Task 配置

Global：

```text
system_setting[validation.default_method]
= ROW_COUNT / ROW_COUNT_CHECKSUM
注册默认 ROW_COUNT
```

Dataset：

```text
standard_dataset.validation_method_override
= NULL / ROW_COUNT / ROW_COUNT_CHECKSUM
```

Task：

```text
sync_task.validation_method_override
= NULL / ROW_COUNT / ROW_COUNT_CHECKSUM
```

`NULL` 直接表示继承，不保存 `override_mode`。

解析：

```text
Task Override
→ Dataset Override
→ Global Setting
→ Registered Default ROW_COUNT
→ Dataset Contract Capability
```

## 4. `sync_execution` Validation Snapshot

```text
validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
```

只允许：

```text
validation_method:
ROW_COUNT
ROW_COUNT_CHECKSUM

validation_source:
GLOBAL
DATASET
TASK
CONTRACT
```

**`sync_execution.validation_source` 不增加 `FIXED`。**

CHECK：

```text
validation_source='CONTRACT'
→ validation_contract_forced=true
→ validation_source_revision IS NULL

validation_source IN ('GLOBAL','DATASET','TASK')
→ validation_contract_forced=false

validation_source_revision IS NULL OR validation_source_revision >= 0
```

运行中只使用 Execution Snapshot，不重新读取当前配置。

## 5. `validation_run` 枚举

```text
validation_scope:
SYNC_WINDOW
FULL_DATASET
CHANGE_WINDOW
DELETE_RECONCILIATION

trigger_type:
SYNC_GATE
MANUAL
MANUAL_RECHECK
SCHEDULED

validation_method:
ROW_COUNT
ROW_COUNT_CHECKSUM
DELETE_KEY_DIFF

validation_source:
GLOBAL
DATASET
TASK
CONTRACT
FIXED

status:
PENDING
RUNNING
COMPLETED
FAILED
CANCELLED

result:
PASS
MISMATCH
```

其中 `FIXED` 只用于：

```text
DELETE_RECONCILIATION + DELETE_KEY_DIFF
```

## 6. `validation_run` 组合 CHECK

### 6.1 固定 Delete Reconciliation

```text
validation_scope='DELETE_RECONCILIATION'
→ execution_id IS NULL
→ validation_method='DELETE_KEY_DIFF'
→ validation_source='FIXED'
→ validation_source_revision IS NULL
→ validation_contract_forced=false
→ baseline_snapshot_run_id IS NOT NULL
→ current_snapshot_run_id IS NOT NULL
→ difference_count IS NOT NULL
→ difference_ratio IS NOT NULL
```

反向：

```text
validation_source='FIXED'
→ validation_scope='DELETE_RECONCILIATION'
→ validation_method='DELETE_KEY_DIFF'
```

### 6.2 Sync Gate

```text
trigger_type='SYNC_GATE'
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ validation_source<>'FIXED'
```

### 6.3 Manual Recheck

```text
trigger_type='MANUAL_RECHECK'
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
```

### 6.4 Independent Validation

```text
trigger_type IN ('MANUAL','SCHEDULED')
→ execution_id IS NULL
→ validation_scope IN ('FULL_DATASET','CHANGE_WINDOW','DELETE_RECONCILIATION')
```

非 Delete Reconciliation：

```text
validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
baseline_snapshot_run_id/current_snapshot_run_id/difference_ratio 均为空
```

`ROW_COUNT_CHECKSUM` 必须有 `checksum_protocol_version`。

## 7. 技术状态与 Result

```text
PENDING/RUNNING
→ result IS NULL
→ finished_at IS NULL

COMPLETED
→ result IN ('PASS','MISMATCH')
→ finished_at IS NOT NULL
→ error_code/error_message IS NULL

FAILED
→ result IS NULL
→ finished_at IS NOT NULL
→ error_code IS NOT NULL

CANCELLED
→ result IS NULL
→ finished_at IS NOT NULL
```

固定语义：

```text
COMPLETED + PASS      = 技术完成且一致
COMPLETED + MISMATCH  = 技术完成但发现差异
FAILED                = 查询/连接/计算/协议技术失败
```

## 8. SYNC_GATE 唯一性与成功条件

```sql
CREATE UNIQUE INDEX uk_validation_sync_gate_execution
ON validation_run(execution_id)
WHERE trigger_type='SYNC_GATE';
```

PASS：

```text
ROW_COUNT:
source_row_count = target_row_count
AND difference_count = 0

ROW_COUNT_CHECKSUM:
source_row_count = target_row_count
AND difference_count = 0
AND source_checksum = target_checksum
```

短事务只有在：

```text
全部 Batch SUCCEEDED + VISIBLE
AND rejected_row_count=0
AND SYNC_GATE COMPLETED + PASS
```

后才允许 Execution `SUCCEEDED`、Watermark 提交和 Outbox 创建。

## 9. 不进入目标模型

```text
Validation enabled/disabled
tolerance_rows/tolerance_percent/row_tolerance
Validation lookback_hours
revalidate_enabled/revalidate_delay
auto_repair/fail_block
SAMPLE/ALL
独立 CHECKSUM 方法
Policy Table / override_mode
```

## 10. 验收

- Validation 配置只存在 Global Setting + Dataset Override + Task Override。
- `sync_execution.validation_source` 只有 `GLOBAL/DATASET/TASK/CONTRACT`。
- `validation_run.validation_source` 额外允许 `FIXED`，且只用于 Delete Reconciliation。
- `COMPLETED + result` 与 `FAILED/CANCELLED` 终态无歧义。
- 正式同步最低严格 ROW_COUNT，不能关闭、无容差、无 Validation Lookback。
- SYNC_GATE PASS 才能成功收尾。

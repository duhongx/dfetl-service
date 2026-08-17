# P0 物理表字典：正式同步 Validation 配置与严格 Gate

> 状态：阶段 1 Status/CHECK + Snapshot 最小充分性已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Snapshot：`spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md`  
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

不建立 Global/Dataset/Task Validation Policy Table、Enable/Tolerance/Lookback/Auto Revalidate Policy。

## 2. 正式同步 Gate

```text
全部 load_batch = SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE validation_run
→ 最低严格 ROW_COUNT
→ 必须 COMPLETED + PASS
→ sync_execution 才能 SUCCEEDED
→ 按规则推进 task_watermark
→ 按 Execution 已冻结 Message Policy 创建 message_outbox
```

固定规则：

1. Validation 不能关闭。
2. ROW_COUNT 严格相等，无容差。
3. ROW_COUNT_CHECKSUM 同时要求 Row Count + Checksum 一致。
4. Gate 只验证父 Execution 固定范围。
5. 无真实业务主键 Dataset 只能 ROW_COUNT。
6. 不自动复检；人工 Recheck 创建独立 `validation_run`，但使用原 Execution 上下文。

## 3. Global / Dataset / Task 解析

```text
Task Override
→ Dataset Override
→ Global Setting
→ Registered Default ROW_COUNT
→ Dataset Contract Capability
```

Override 只允许：

```text
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

NULL 即继承，不保存 `override_mode`。

## 4. `sync_execution` Validation Snapshot

```text
validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
checksum_protocol_version varchar(64) NULL
```

只允许：

```text
validation_method: ROW_COUNT / ROW_COUNT_CHECKSUM
validation_source: GLOBAL / DATASET / TASK / CONTRACT
```

`sync_execution.validation_source` 不允许 `FIXED`。

```text
validation_source='CONTRACT'
→ validation_contract_forced=true
→ validation_source_revision IS NULL

validation_source IN ('GLOBAL','DATASET','TASK')
→ validation_contract_forced=false
```

Checksum Protocol：

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method='ROW_COUNT'
→ checksum_protocol_version IS NULL
```

不保存 Validation Policy/Override 链完整 JSON；最终 Method/Source/Revision/Forced 即本次解析结果。

## 5. `validation_run` 最终字段边界

核心字段：

```text
id/run_uuid
task_id/task_revision
execution_id
context_snapshot jsonb NULL
range_snapshot jsonb NULL
validation_scope
trigger_type
validation_method
validation_source
validation_source_revision
validation_contract_forced
status/result
checksum_protocol_version varchar(64) NULL
source_row_count/target_row_count
source_checksum/target_checksum
difference_count/difference_ratio/difference_summary
baseline_snapshot_run_id/current_snapshot_run_id
requested_by
cancel_requested_at/cancel_requested_by
error_code/error_message
started_at/finished_at/created_at/updated_at
```

JSON：

```text
context_snapshot IS NULL OR jsonb_typeof(context_snapshot)='object'
range_snapshot IS NULL OR jsonb_typeof(range_snapshot)='object'
```

不再使用 `NOT NULL DEFAULT '{}'` 强迫每种 Validation 都复制上下文。

## 6. Enum

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

`FIXED` 只用于 `DELETE_RECONCILIATION + DELETE_KEY_DIFF`。

## 7. 唯一上下文来源 CHECK

### 7.1 SYNC_GATE

```text
trigger_type='SYNC_GATE'
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ validation_source<>'FIXED'
→ context_snapshot IS NULL
→ range_snapshot IS NULL
```

父 `sync_execution` 是上下文唯一真相。

### 7.2 MANUAL_RECHECK

```text
trigger_type='MANUAL_RECHECK'
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ validation_source<>'FIXED'
→ context_snapshot IS NULL
→ range_snapshot IS NULL
```

Recheck 复用原 Execution 的身份、Runtime Endpoint、范围和不可变定义引用，不复制第二份运行 Context。

### 7.3 普通独立 Validation

```text
trigger_type IN ('MANUAL','SCHEDULED')
AND validation_scope IN ('FULL_DATASET','CHANGE_WINDOW')
→ execution_id IS NULL
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ validation_source<>'FIXED'
→ context_snapshot IS NOT NULL
→ range_snapshot IS NOT NULL
```

`context_snapshot` 最小只保存：

```text
routeVersionId
sourceRuntimeSnapshot
targetRuntimeSnapshot
```

所有 Runtime Snapshot 严禁 Secret。

`range_snapshot`：

```text
FULL_DATASET → {}
CHANGE_WINDOW → {windowLower, windowUpper}
```

不复制 Institution/Dataset/Dataset Version/字段列表/Field Contract/Source Schema/Object。

### 7.4 DELETE_RECONCILIATION

```text
validation_scope='DELETE_RECONCILIATION'
→ execution_id IS NULL
→ validation_method='DELETE_KEY_DIFF'
→ validation_source='FIXED'
→ validation_source_revision IS NULL
→ validation_contract_forced=false
→ context_snapshot IS NULL
→ range_snapshot IS NULL
→ baseline_snapshot_run_id/current_snapshot_run_id IS NOT NULL
→ difference_count/difference_ratio IS NOT NULL
```

上下文唯一来自两个 `delete_snapshot_run` FK。

反向：

```text
validation_source='FIXED'
→ validation_scope='DELETE_RECONCILIATION'
→ validation_method='DELETE_KEY_DIFF'
```

## 8. Checksum Protocol

```text
validation_method='ROW_COUNT_CHECKSUM'
→ checksum_protocol_version IS NOT NULL

validation_method IN ('ROW_COUNT','DELETE_KEY_DIFF')
→ checksum_protocol_version IS NULL
```

不为不执行 Checksum 的校验记录无意义 Protocol Version。

## 9. 技术状态与 Result

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

## 10. FK / Unique / Concurrency

```text
task_id → sync_task(id) RESTRICT
(execution_id,task_id) → sync_execution(id,task_id) RESTRICT
(baseline_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
(current_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
```

```text
UNIQUE(run_uuid)
UNIQUE(id,task_id)

UNIQUE INDEX uk_validation_sync_gate_execution
ON validation_run(execution_id)
WHERE trigger_type='SYNC_GATE'

UNIQUE INDEX uk_validation_run_active_independent_task
ON validation_run(task_id)
WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
  AND status IN ('PENDING','RUNNING')

UNIQUE INDEX uk_validation_delete_current_snapshot
ON validation_run(current_snapshot_run_id)
WHERE validation_scope='DELETE_RECONCILIATION'
```

## 11. PASS

ROW_COUNT：

```text
source_row_count = target_row_count
AND difference_count = 0
```

ROW_COUNT_CHECKSUM：

```text
source_row_count = target_row_count
AND difference_count = 0
AND source_checksum = target_checksum
```

短事务只有在全部 Batch `SUCCEEDED + VISIBLE`、rejected=0、SYNC_GATE `COMPLETED + PASS` 后，才允许 Execution SUCCEEDED、Watermark 提交和 Outbox 创建。

## 12. 删除与历史

Validation 永久保留；不提供普通 DELETE/Purge/Retention。Task 后续编辑不改变已创建 Validation 的唯一上下文来源。

## 13. 不进入目标模型

```text
Validation enabled/disabled
Tolerance
Validation Lookback Policy
Auto Revalidate
Policy Table / override_mode
task_version_id
所有 Validation 强制 context_snapshot/range_snapshot='{}'
Execution-bound Validation 重复运行 Context
Delete Reconciliation 重复 Context/Range JSON
Secret Snapshot
```

## 14. 验收

- `sync_execution.validation_source` 只有 GLOBAL/DATASET/TASK/CONTRACT。
- `validation_run.validation_source` 的 FIXED 只用于 Delete Reconciliation。
- SYNC_GATE/MANUAL_RECHECK 只使用父 Execution Context。
- 普通独立 Validation 自持最小非 Secret Runtime Context/Range。
- Delete Reconciliation 只使用 Snapshot Run FK。
- Checksum Protocol 只在 ROW_COUNT_CHECKSUM 时存在。
- 正式同步最低严格 ROW_COUNT，不能关闭、无容差、无 Validation Lookback。

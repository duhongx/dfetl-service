# P0 物理表字典：正式同步 Validation 配置与严格 Gate

> 状态：当前权威 Validation 物理模型；旧 Policy Table 清理已完成  
> 首次 Review：2026-08-15  
> 最近收口：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Task：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> Dataset Override：`spec/P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md`  
> Global Default：`spec/P0_GLOBAL_VALIDATION_SETTING_REVIEW.md`

## 1. 当前唯一 Validation 配置模型

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
sync_execution Validation Snapshot
SYNC_GATE validation_run
```

明确不建立：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
Validation Enable Table
Tolerance Table
Validation Lookback Policy
Auto Revalidate Policy
```

## 2. 固定正式同步 Gate

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
2. 最低方法 ROW_COUNT。
3. ROW_COUNT 必须严格相等，差 1 行也 MISMATCH。
4. 不支持绝对/百分比容差。
5. ROW_COUNT_CHECKSUM 同时要求 Row Count + Checksum 一致。
6. Gate 只验证本次 Execution 固定范围。
7. 不保存/接受 Validation `lookback_hours`。
8. 无真实业务主键 Dataset 只能 ROW_COUNT。
9. 有真实业务主键可选择 ROW_COUNT_CHECKSUM，不允许运行中静默降级。
10. 不自动复检；人工 Recheck 创建独立 `validation_run`。

## 3. 增量读取 Lookback 与 Validation Lookback 分离

保留：

```text
dataset_sync_policy.lookback_seconds
sync_task.lookback_seconds
sync_execution.lookback_seconds
```

它们只改变 Source 实际读取范围。

删除：

```text
validation lookback_hours
```

Gate 严格按 Execution 最终固定范围校验。

## 4. Global Default

```text
setting_key = validation.default_method
```

允许：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认：

```text
ROW_COUNT
```

Setting Row 不存在时使用注册默认，不建立固定单例 Global Policy Row。

## 5. Dataset Override

```text
standard_dataset.validation_method_override varchar(32) NULL
```

```text
NULL                = 继承 Global
ROW_COUNT           = 严格行数
ROW_COUNT_CHECKSUM  = 严格行数 + Checksum
```

- NULL 就是 INHERIT，不保存 `override_mode`。
- 共用 `standard_dataset.revision/updated_*` 和 Audit。
- Dataset Definition Sync 不覆盖管理员 Override。
- 无真实业务主键时拒绝保存 ROW_COUNT_CHECKSUM。

## 6. Task Override

```text
sync_task.validation_method_override varchar(32) NULL
```

语义与 Dataset Override 相同。

- 共用 `sync_task.revision/updated_*` 和 Audit。
- 活动同步 Execution 期间禁止修改。
- 无真实业务主键时拒绝 ROW_COUNT_CHECKSUM。
- Task 创建不插入任何 Policy Row。

## 7. 最终解析

```text
Task Override 非空
→ Dataset Override 非空
→ system_setting[validation.default_method]
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

解析结果：

```text
validation_method
validation_source
validation_source_revision
validation_contract_forced
```

来源：

```text
GLOBAL
DATASET
TASK
CONTRACT
```

运行中只使用 Execution Snapshot，不重新动态读取当前配置。

## 8. `sync_execution` Snapshot

```text
validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
```

约束：

```text
validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
validation_source IN ('GLOBAL','DATASET','TASK','CONTRACT')
validation_source_revision IS NULL OR validation_source_revision >= 0

CONTRACT → forced=true + revision=NULL
非 CONTRACT → forced=false
```

不保存：

```text
validation_enabled
row_tolerance
lookback_hours
global_policy_id
dataset_policy_id
task_policy_id
```

## 9. `SYNC_GATE validation_run`

每个正式 Execution 必须且只能有一条：

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

Execution Scope 对应本次实际范围，不再通过隐式 Policy Lookback 扩展。

## 10. 成功收尾

短事务检查：

```text
全部 Batch SUCCEEDED + VISIBLE
AND rejected_row_count=0
AND SYNC_GATE COMPLETED + PASS
```

之后才允许：

```text
Execution → SUCCEEDED
推进 Watermark
创建 Outbox
```

事务内不调用 Doris/RabbitMQ。

## 11. 旧模型清理完成状态

以下对象/字段/能力不进入新系统：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
global/dataset/task override_mode
Validation enabled/disabled
tolerance_rows/tolerance_percent/row_tolerance
Validation lookback_hours
revalidate_enabled/revalidate_delay
auto_repair/fail_block
SAMPLE/ALL
独立 CHECKSUM 方法
```

2026-08-17 已完成 Active Spec 的机械迁移；这些名称后续只允许出现在“历史/已废止/明确不建立”语境。

## 12. 验收

- P0 无三张 Validation Policy Table。
- 配置只存在 System Setting + Dataset Override + Task Override。
- NULL 表示继承。
- 正式同步最低严格 ROW_COUNT，不能关闭、无容差、无 Validation Lookback。
- Execution 保存实际最终 Method/Source Snapshot。
- SYNC_GATE PASS 才能成功收尾。

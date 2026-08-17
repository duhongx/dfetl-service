# P0 删除快照控制对象与校验外键闭环

> 状态：阶段 1 Delete Snapshot FK + Unique + Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Execution/Validation 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 最终对象

```text
PostgreSQL
├── delete_snapshot_run
├── task_delete_snapshot_state
├── validation_run（DELETE_RECONCILIATION）
└── delete_apply_run

Doris
├── _dfetl_key_snapshot
└── _dfetl_delete_diff
```

不建立 Task Version、Multi-Institution Route、逐键 PostgreSQL Snapshot/Diff、自动删除 ODS 状态机。

## 2. `delete_snapshot_run`

核心字段：

```text
id/run_uuid
task_id/task_revision
institution_id/institution_code
dataset_id/dataset_version_id/route_version_id
target_datasource_id
baseline_snapshot_run_id
status/result_type
三个 Hash + key_protocol_version
source_row_count/key_row_count/null_key_count/duplicate_key_count/difference_count
trigger_type/triggered_by
started_at/finished_at
candidate_cleanup_after/cleaned_at
error_code/error_message
created_at/updated_at
```

枚举：

```text
status:
PENDING
EXTRACTING
WRITING
COMPARING
COMPLETED
FAILED
CANCELLED

result_type:
BASELINE_CREATED
DIFF_GENERATED

trigger_type:
MANUAL
SCHEDULED
```

FK：

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

target_datasource_id → target_datasource(id) RESTRICT
(baseline_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
triggered_by → app_user(id) RESTRICT
```

Unique：

```text
UNIQUE(run_uuid)
UNIQUE(id,task_id) # FK Support

UNIQUE INDEX uk_delete_snapshot_run_active_task
ON delete_snapshot_run(task_id)
WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING')
```

CHECK：

```text
所有 count >= 0
baseline_snapshot_run_id IS NULL OR baseline_snapshot_run_id <> id
```

生命周期：

```text
PENDING/EXTRACTING/WRITING/COMPARING
→ result_type IS NULL
→ finished_at IS NULL

COMPLETED
→ result_type IS NOT NULL
→ finished_at IS NOT NULL
→ error_code/error_message IS NULL
→ null_key_count=0
→ duplicate_key_count=0
→ source_row_count=key_row_count

FAILED
→ result_type IS NULL
→ finished_at IS NOT NULL
→ error_code IS NOT NULL

CANCELLED
→ result_type IS NULL
→ finished_at IS NOT NULL
```

Result：

```text
BASELINE_CREATED
→ baseline_snapshot_run_id IS NULL
→ difference_count=0

DIFF_GENERATED
→ baseline_snapshot_run_id IS NOT NULL
```

`COMPLETED + result_type` 表示快照流程技术完成；不使用 `SUCCEEDED`。

## 3. `task_delete_snapshot_state`

```text
task_id PK
current_baseline_snapshot_run_id
last_reconciliation_validation_run_id
revision
updated_at
updated_by
```

FK：

```text
task_id → sync_task(id) RESTRICT
(current_baseline_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
(last_reconciliation_validation_run_id,task_id) → validation_run(id,task_id) RESTRICT
updated_by → app_user(id) SET NULL
```

基线切换时锁定本行并验证 Candidate `COMPLETED`、属于同 Task、Doris 数据未清理；历史由 Run + Validation 保存，不建立 State History 表。

## 4. Delete Reconciliation Validation

统一使用 `validation_run`：

```text
validation_scope='DELETE_RECONCILIATION'
validation_method='DELETE_KEY_DIFF'
validation_source='FIXED'
validation_source_revision IS NULL
validation_contract_forced=false
execution_id IS NULL
baseline_snapshot_run_id/current_snapshot_run_id 非空
difference_count/difference_ratio 非空
```

外键：

```text
(baseline_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
(current_snapshot_run_id,task_id)  → delete_snapshot_run(id,task_id) RESTRICT
```

同一 Candidate 只生成一条：

```sql
CREATE UNIQUE INDEX uk_validation_delete_current_snapshot
ON validation_run(current_snapshot_run_id)
WHERE validation_scope='DELETE_RECONCILIATION';
```

结果：

```text
COMPLETED + PASS      = 无删除差异
COMPLETED + MISMATCH  = 发现删除键
```

两者都不自动删除 ODS。

## 5. `delete_apply_run`

核心字段：

```text
id/run_uuid
validation_run_id/task_id/institution_id/dataset_id
dry_run
status
planned_count/applied_count/failed_count
risk_threshold_snapshot
requested_by/requested_at
confirmed_by/confirmed_at
started_at/finished_at
doris_label_prefix
error_code/error_message
created_at/updated_at
```

状态：

```text
PENDING
RUNNING
SUCCEEDED
PARTIAL_FAILED
FAILED
CANCELLED
```

FK：

```text
(validation_run_id,task_id) → validation_run(id,task_id) RESTRICT
(task_id,institution_id,dataset_id) → sync_task(id,institution_id,dataset_id) RESTRICT
requested_by/confirmed_by → app_user(id) RESTRICT
```

业务唯一：

```text
UNIQUE(run_uuid)
```

真实 Apply 安全唯一：

```sql
CREATE UNIQUE INDEX uk_delete_apply_effective
ON delete_apply_run(validation_run_id)
WHERE dry_run=false
  AND status IN ('PENDING','RUNNING','SUCCEEDED');
```

基础 CHECK：

```text
planned_count/applied_count/failed_count >= 0
applied_count + failed_count <= planned_count
jsonb_typeof(risk_threshold_snapshot)='object'
```

Dry Run：

```text
dry_run=true
→ confirmed_by/confirmed_at/doris_label_prefix IS NULL
→ applied_count=0
→ failed_count=0
→ status<>'PARTIAL_FAILED'
```

真实 Apply：

```text
dry_run=false
→ 进入 RUNNING/SUCCEEDED/PARTIAL_FAILED 前 confirmed_by/confirmed_at 非空
```

终态 CHECK：

```text
SUCCEEDED
→ finished_at IS NOT NULL
→ applied_count=planned_count
→ failed_count=0
→ error_code/error_message IS NULL

PARTIAL_FAILED
→ finished_at IS NOT NULL
→ applied_count>0
→ failed_count>0
→ applied_count+failed_count=planned_count
→ error_code IS NOT NULL

FAILED
→ finished_at IS NOT NULL
→ error_code IS NOT NULL

CANCELLED
→ finished_at IS NOT NULL
```

Dry Run 可多次；真实 Apply 已有 `PENDING/RUNNING/SUCCEEDED` 时不能再发起。

## 6. 基线切换

首次：

```text
delete_snapshot_run = COMPLETED + BASELINE_CREATED
→ 建立 current_baseline_snapshot_run_id
```

后续：

```text
Candidate 完整写 Doris
→ Baseline vs Candidate anti join
→ 写 _dfetl_delete_diff
→ validation_run = COMPLETED + PASS/MISMATCH
→ 锁定 State
→ 确认原 Baseline 未变化
→ 切换 Baseline
```

发现差异不阻止 Candidate 成为下一 Baseline；失败/取消/不完整 Candidate 永不切换。

## 7. Doris 技术表

继续保留：

```text
_dfetl_key_snapshot
_dfetl_delete_diff
```

百万级 Key/Diff 不回灌 PostgreSQL；Institution/Dataset/Route 上下文由 PostgreSQL Run 解释。

## 8. 验收

- Delete Snapshot 使用 `COMPLETED + result_type`，不是 `SUCCEEDED`。
- Delete Reconciliation 的 `validation_source` 固定 `FIXED`。
- Snapshot/Validation/Delete Apply 都能证明属于同一 Task。
- 一个 Task 一个活动 Delete Snapshot。
- 一个 Candidate 一个 Delete Reconciliation。
- 已成功真实 Apply 后禁止再次真实 Apply；Dry Run 可重复。
- Delete Apply 各终态的数量、时间和错误字段组合无歧义。
- Delete Diff 只生成，不自动应用。

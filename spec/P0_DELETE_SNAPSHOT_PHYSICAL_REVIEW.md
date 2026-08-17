# P0 删除快照控制对象与校验外键闭环

> 状态：阶段 1 Delete Snapshot FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
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

```text
status:
PENDING / EXTRACTING / WRITING / COMPARING / COMPLETED / FAILED / CANCELLED

result_type:
BASELINE_CREATED / DIFF_GENERATED

trigger_type:
MANUAL / SCHEDULED
```

关键 FK：

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

target_datasource_id → target_datasource(id) RESTRICT
(baseline_snapshot_run_id,task_id) → delete_snapshot_run(id,task_id) RESTRICT
triggered_by → app_user(id) RESTRICT
```

同 Task 一个活动 Snapshot：

```text
UNIQUE INDEX uk_delete_snapshot_run_active_task
ON delete_snapshot_run(task_id)
WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING')
```

`COMPLETED + result_type`、计数、错误、时间组合以 Status/CHECK Matrix 为准。

### 删除

`delete_snapshot_run` 是永久控制历史，不提供普通 DELETE，也不因为 Doris Key Snapshot 被清理而删除 PostgreSQL Run。

字段：

```text
candidate_cleanup_after
cleaned_at
```

只记录 Doris Snapshot 数据何时允许/已经清理。

## 3. `task_delete_snapshot_state`

```text
task_id PK
current_baseline_snapshot_run_id
last_reconciliation_validation_run_id
revision
updated_at/updated_by
```

这是当前 Baseline 指针控制状态，不是历史明细；P0 不提供普通 DELETE。Task 逻辑删除时继续保留该 State 和其 Run/Validation 引用。

历史 Baseline 轨迹从 `delete_snapshot_run + validation_run` 解释，不建立 State History 表。

## 4. Delete Reconciliation Validation

统一使用：

```text
validation_scope='DELETE_RECONCILIATION'
validation_method='DELETE_KEY_DIFF'
validation_source='FIXED'
execution_id IS NULL
```

同一 Candidate 一条 Delete Reconciliation。结果：

```text
COMPLETED + PASS     = 无删除差异
COMPLETED + MISMATCH = 发现删除键
```

Validation Run 永久保留，不自动删除 ODS。

## 5. `delete_apply_run`

```text
status:
PENDING / RUNNING / SUCCEEDED / PARTIAL_FAILED / FAILED / CANCELLED
```

FK：

```text
(validation_run_id,task_id) → validation_run(id,task_id) RESTRICT
(task_id,institution_id,dataset_id) → sync_task(id,institution_id,dataset_id) RESTRICT
requested_by/confirmed_by → app_user(id) RESTRICT
```

真实 Apply 安全唯一：

```text
UNIQUE INDEX uk_delete_apply_effective
ON delete_apply_run(validation_run_id)
WHERE dry_run=false
  AND status IN ('PENDING','RUNNING','SUCCEEDED')
```

Dry Run 可多次；成功真实 Apply 后禁止再次真实 Apply。

### 删除

`delete_apply_run` 是人工删除应用审计事实，永久保留；不提供 DELETE/retention。

## 6. 基线切换

首次：

```text
COMPLETED + BASELINE_CREATED
→ 建立 current_baseline_snapshot_run_id
```

后续：

```text
Candidate 完整写 Doris
→ Baseline vs Candidate anti join
→ 写 _dfetl_delete_diff
→ Delete Reconciliation COMPLETED + PASS/MISMATCH
→ 锁定 State
→ 原子切换 Baseline
```

发现差异不阻止完整 Candidate 成为下一 Baseline；失败/取消/不完整 Candidate 永不切换。

## 7. Doris Key Snapshot / Delete Diff 清理

```text
_dfetl_key_snapshot
_dfetl_delete_diff
```

属于大规模技术数据，可按既有 Delete Snapshot 生命周期清理。

固定原则：

- 清理 Doris Key/Diff 不删除 PostgreSQL `delete_snapshot_run/validation_run/delete_apply_run`。
- 当前 Baseline 对应 Key 数据在仍被 State 使用时不得清理。
- Failed/Cancelled Candidate、旧 Baseline、已完成 Diff 的具体清理时机沿用专项生命周期和 `candidate_cleanup_after/cleaned_at` 控制。
- 本轮 Delete Behavior Matrix 不新增未经确认的精确保留天数。

## 8. 明确不建立

```text
PostgreSQL task_snapshot_key
逐键 PostgreSQL Delete Diff
automatic ODS delete
Delete Apply approval workflow
PostgreSQL Delete History Purge
```

## 9. 验收

- Delete Snapshot/Validation/Delete Apply PostgreSQL 元数据永久保留。
- Task 逻辑删除不级联 Delete History/State。
- Doris Snapshot/Diff 可以按生命周期清理，但 Run 元数据继续存在。
- 当前 Baseline Key 不会被提前清理。
- 已成功真实 Apply 不能再次发起。
- Delete Diff 只生成，不自动应用。

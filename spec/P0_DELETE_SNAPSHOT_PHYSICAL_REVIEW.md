# P0 删除快照控制对象与校验外键闭环

> 状态：阶段 1 删除识别物理模型已按当前 Task + 单机构 Route 收口  
> 首次 Review：2026-08-15  
> 最近收口：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Execution/Validation 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 最终对象和边界

删除识别使用 PostgreSQL 控制元数据 + Doris 大规模业务键集合：

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

明确不建立：

```text
task_version_id
sync_task_version
collection_route_version_institution
逐键 PostgreSQL Snapshot/Diff 表
自动删除 ODS 状态机
```

运行上下文统一由：

```text
task_id + task_revision
+ institution_id
+ dataset_id + dataset_version_id
+ route_version_id
```

固定，并通过当前单机构 Route Version 四元身份保证不跨机构/数据集/数据集版本。

## 2. `delete_snapshot_run`

职责：一次完整联合业务键快照提取、候选写入、差集计算和结果摘要；不保存逐键明细。

### 2.1 字段

```text
id
run_uuid
task_id
task_revision
institution_id
institution_code
dataset_id
dataset_version_id
route_version_id
target_datasource_id
baseline_snapshot_run_id
status
result_type
dataset_definition_hash
source_structure_hash
field_resolution_hash
key_protocol_version
source_row_count
key_row_count
null_key_count
duplicate_key_count
difference_count
trigger_type
triggered_by
started_at
finished_at
candidate_cleanup_after
cleaned_at
error_code
error_message
created_at
updated_at
```

状态：

```text
PENDING
EXTRACTING
WRITING
COMPARING
COMPLETED
FAILED
CANCELLED
```

结果：

```text
BASELINE_CREATED
DIFF_GENERATED
```

触发：

```text
MANUAL
SCHEDULED
```

### 2.2 约束

```text
UNIQUE(run_uuid)
UNIQUE(id,task_id)

CHECK (task_revision >= 0)
CHECK (status IN ('PENDING','EXTRACTING','WRITING','COMPARING','COMPLETED','FAILED','CANCELLED'))
CHECK (result_type IS NULL OR result_type IN ('BASELINE_CREATED','DIFF_GENERATED'))
CHECK (trigger_type IN ('MANUAL','SCHEDULED'))
CHECK (source_row_count >= 0)
CHECK (key_row_count >= 0)
CHECK (null_key_count >= 0)
CHECK (duplicate_key_count >= 0)
CHECK (difference_count >= 0)
CHECK (baseline_snapshot_run_id IS NULL OR baseline_snapshot_run_id <> id)
```

COMPLETED 必须：

```text
result_type 非空
finished_at 非空
null_key_count = 0
duplicate_key_count = 0
source_row_count = key_row_count
```

### 2.3 当前外键闭环

Task 身份：

```text
FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

Route/Dataset Version 使用统一四元 FK：

```text
FOREIGN KEY (
  route_version_id,
  institution_id,
  dataset_id,
  dataset_version_id
)
REFERENCES collection_route_version(
  id,
  institution_id,
  dataset_id,
  dataset_version_id
)
ON DELETE RESTRICT
```

Target：

```text
FOREIGN KEY (target_datasource_id)
REFERENCES target_datasource(id)
ON DELETE RESTRICT
```

基线必须属于同一 Task：

```text
FOREIGN KEY (baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT
```

**不再使用任何 `collection_route_version_institution` 外键。**

同一 Task 活动 Snapshot 唯一：

```sql
CREATE UNIQUE INDEX uk_delete_snapshot_run_active_task
ON delete_snapshot_run(task_id)
WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING');
```

## 3. `task_delete_snapshot_state`

职责：每个 Task 的当前有效基线指针和最近一次成功删除对账引用。

字段：

```text
task_id
current_baseline_snapshot_run_id
last_reconciliation_validation_run_id
revision
updated_at
updated_by
```

外键：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT

FOREIGN KEY (current_baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (last_reconciliation_validation_run_id,task_id)
REFERENCES validation_run(id,task_id)
ON DELETE RESTRICT
```

因此 `validation_run` 提供：

```text
UNIQUE(id,task_id)
```

基线切换时应用锁定 State 并验证：

- Candidate 属于同一 Task；
- Candidate `COMPLETED`；
- Doris Candidate 数据未清理；
- 首次基线允许最后对账为空；
- 后续切换必须关联同一 Task 的 `DELETE_RECONCILIATION` 完成记录。

不建立基线历史表；历史由 `delete_snapshot_run + validation_run` 保留。

## 4. `validation_run` 删除对账闭环

删除对账使用统一 `validation_run`，字段：

```text
baseline_snapshot_run_id
current_snapshot_run_id
difference_count
difference_ratio numeric(12,8)
difference_summary
```

外键：

```text
FOREIGN KEY (baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (current_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT
```

约束：

```text
CHECK (difference_ratio IS NULL OR (difference_ratio >= 0 AND difference_ratio <= 1))
CHECK (baseline_snapshot_run_id IS NULL OR current_snapshot_run_id IS NULL OR
       baseline_snapshot_run_id <> current_snapshot_run_id)
```

DELETE_RECONCILIATION 固定：

```text
validation_scope = DELETE_RECONCILIATION
validation_method = DELETE_KEY_DIFF
execution_id IS NULL
baseline_snapshot_run_id IS NOT NULL
current_snapshot_run_id IS NOT NULL
difference_count IS NOT NULL
difference_ratio IS NOT NULL
```

其他 Validation Scope 中两个 Snapshot FK 和 `difference_ratio` 为空。

同一 Candidate 只生成一条删除对账：

```sql
CREATE UNIQUE INDEX uk_validation_delete_current_snapshot
ON validation_run(current_snapshot_run_id)
WHERE validation_scope='DELETE_RECONCILIATION';
```

`PASS` 表示无删除差异；`MISMATCH` 表示发现删除键。两者都是技术执行完成，不自动删除 ODS。

## 5. `delete_apply_run`

职责：保存删除差异 Dry Run 和管理员二次确认后的人工应用结果；不保存逐键明细。

字段：

```text
id
run_uuid
validation_run_id
task_id
institution_id
dataset_id
dry_run
status
planned_count
applied_count
failed_count
risk_threshold_snapshot
requested_by
requested_at
confirmed_by
confirmed_at
started_at
finished_at
doris_label_prefix
error_code
error_message
created_at
updated_at
```

明确不保存：

```text
task_version_id
```

外键：

```text
FOREIGN KEY (validation_run_id,task_id)
REFERENCES validation_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

实际应用前必须验证：

```text
validation_scope = DELETE_RECONCILIATION
status = COMPLETED
difference_count > 0
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

唯一性：

```sql
CREATE UNIQUE INDEX uk_delete_apply_active
ON delete_apply_run(validation_run_id)
WHERE dry_run=false AND status IN ('PENDING','RUNNING');

CREATE UNIQUE INDEX uk_delete_apply_success
ON delete_apply_run(validation_run_id)
WHERE dry_run=false AND status='SUCCEEDED';
```

允许多次 Dry Run；实际应用必须二次确认并审计。

## 6. 基线切换事务

首次成功 Snapshot：

```text
delete_snapshot_run = COMPLETED + BASELINE_CREATED
→ 插入/更新 task_delete_snapshot_state.current_baseline_snapshot_run_id
→ 不生成 Delete Diff
```

后续：

```text
Candidate 完整写 Doris
→ 原 Baseline vs Candidate anti join
→ 写 _dfetl_delete_diff
→ 完成 DELETE_RECONCILIATION validation_run
→ 锁定 task_delete_snapshot_state
→ 确认原 Baseline 未变化
→ 切换 current_baseline_snapshot_run_id
→ 更新 last_reconciliation_validation_run_id
→ commit
```

`difference_count > 0` 不阻止 Candidate 成为下一 Baseline；失败/取消/不完整 Candidate 永不切换基线。

## 7. Doris 技术表

```sql
CREATE TABLE _dfetl_key_snapshot (
    snapshot_run_id       BIGINT       NOT NULL,
    task_id               BIGINT       NOT NULL,
    key_hash              CHAR(64)     NOT NULL,
    key_payload           STRING       NOT NULL,
    key_protocol_version  VARCHAR(64)  NOT NULL,
    captured_at           DATETIME(6)  NOT NULL
)
DUPLICATE KEY(snapshot_run_id, task_id, key_hash)
DISTRIBUTED BY HASH(task_id, key_hash) BUCKETS AUTO;
```

```sql
CREATE TABLE _dfetl_delete_diff (
    validation_run_id  BIGINT       NOT NULL,
    task_id            BIGINT       NOT NULL,
    key_hash           CHAR(64)     NOT NULL,
    key_payload        STRING       NOT NULL,
    detected_at        DATETIME(6)  NOT NULL
)
DUPLICATE KEY(validation_run_id, task_id, key_hash)
DISTRIBUTED BY HASH(task_id, key_hash) BUCKETS AUTO;
```

机构、Dataset、Route 上下文由 PostgreSQL Run 记录解释，不在百万级业务键明细重复保存。

## 8. 明确不建立

```text
PostgreSQL task_snapshot_key
逐键 PostgreSQL Delete Diff 表
task_version_id
collection_route_version_institution
Java HashSet 全量差集
自动删除 ODS
失败 Candidate 覆盖 Baseline
Delete Apply 审批流/自动补偿状态机
```

## 9. 验收

- Snapshot/Validation/Delete Apply 都能证明属于同一 Task。
- Task Institution/Dataset 与 Route Version/Dataset Version 通过当前四元 FK 一致。
- 不存在多机构 Route 关系表依赖。
- Validation Baseline/Candidate 不能跨 Task。
- 首次 Snapshot 只建立 Baseline。
- 后续完整 Candidate 对账后原子切换 Baseline。
- Failure/Cancelled Candidate 不切换。
- Delete Diff 只生成，不自动应用。
- Doris 技术表不保存 `task_version_id` 和重复业务上下文。

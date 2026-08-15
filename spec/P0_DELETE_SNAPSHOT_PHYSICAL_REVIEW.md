# P0 删除快照控制对象与校验外键闭环

> 状态：阶段 1 工作包 3 批量复核完成  
> 日期：2026-08-15  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 执行字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`，不得修改实体、Repository 或数据库结构。

## 1. 结论

删除识别继续采用 PostgreSQL 控制元数据与 Doris 大规模键集合分层存储：

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

删除任务版本模型后，所有对象删除：

```text
task_version_id
```

运行上下文由 `task_id + task_revision + 数据集版本 + 链路版本 + 机构身份` 固定。

## 2. `delete_snapshot_run`

职责：保存一次完整联合业务键快照提取、候选写入、差集计算和结果摘要，不保存逐键明细。

目标字段：

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

关键约束：

```text
UNIQUE (run_uuid)
UNIQUE (id, task_id)

CHECK (task_revision >= 0)
CHECK (status IN
       ('PENDING','EXTRACTING','WRITING','COMPARING',
        'COMPLETED','FAILED','CANCELLED'))
CHECK (result_type IS NULL OR
       result_type IN ('BASELINE_CREATED','DIFF_GENERATED'))
CHECK (trigger_type IN ('MANUAL','SCHEDULED'))
CHECK (source_row_count >= 0)
CHECK (key_row_count >= 0)
CHECK (null_key_count >= 0)
CHECK (duplicate_key_count >= 0)
CHECK (difference_count >= 0)
CHECK (baseline_snapshot_run_id IS NULL OR baseline_snapshot_run_id <> id)
```

完成状态必须满足：

```text
status = COMPLETED
→ result_type 非空
→ finished_at 非空
→ null_key_count = 0
→ duplicate_key_count = 0
→ source_row_count = key_row_count
```

外键：

```text
FOREIGN KEY (task_id, institution_id, dataset_id)
REFERENCES sync_task(id, institution_id, dataset_id)
ON DELETE RESTRICT

FOREIGN KEY (route_version_id, institution_id)
REFERENCES collection_route_version_institution(route_version_id, institution_id)
ON DELETE RESTRICT

FOREIGN KEY (route_version_id, dataset_id)
REFERENCES collection_route_version(id, dataset_id)
ON DELETE RESTRICT

FOREIGN KEY (route_version_id, dataset_version_id)
REFERENCES collection_route_version(id, dataset_version_id)
ON DELETE RESTRICT

FOREIGN KEY (target_datasource_id)
REFERENCES target_datasource(id)
ON DELETE RESTRICT

FOREIGN KEY (baseline_snapshot_run_id, task_id)
REFERENCES delete_snapshot_run(id, task_id)
ON DELETE RESTRICT
```

同一任务活动快照唯一：

```sql
CREATE UNIQUE INDEX uk_delete_snapshot_run_active_task
    ON delete_snapshot_run (task_id)
    WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING');
```

## 3. `task_delete_snapshot_state`

职责：保存每个任务当前有效基线的唯一指针和最近一次成功删除对账。

目标字段：

```text
task_id
current_baseline_snapshot_run_id
last_reconciliation_validation_run_id
revision
updated_at
updated_by
```

外键闭环：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT

FOREIGN KEY (current_baseline_snapshot_run_id, task_id)
REFERENCES delete_snapshot_run(id, task_id)
ON DELETE RESTRICT

FOREIGN KEY (last_reconciliation_validation_run_id, task_id)
REFERENCES validation_run(id, task_id)
ON DELETE RESTRICT
```

为最后一条复合外键，`validation_run` 增加：

```text
UNIQUE (id, task_id)
```

应用在切换基线的短事务中验证：

- 候选快照属于同一任务；
- 候选状态为 `COMPLETED`；
- 候选 Doris 数据尚未清理；
- 首次基线允许 `last_reconciliation_validation_run_id=NULL`；
- 后续切换时，最近校验必须是同一任务的 `DELETE_RECONCILIATION` 完成记录。

不建立基线历史表；历史由 `delete_snapshot_run` 和 `validation_run` 长期保留。

## 4. `validation_run` 删除对账闭环

`validation_run` 继续统一保存删除对账整体结果，补齐：

```text
baseline_snapshot_run_id
current_snapshot_run_id
difference_ratio numeric(12,8) NULL
```

外键：

```text
FOREIGN KEY (baseline_snapshot_run_id, task_id)
REFERENCES delete_snapshot_run(id, task_id)
ON DELETE RESTRICT

FOREIGN KEY (current_snapshot_run_id, task_id)
REFERENCES delete_snapshot_run(id, task_id)
ON DELETE RESTRICT
```

约束：

```text
CHECK (difference_ratio IS NULL OR
       (difference_ratio >= 0 AND difference_ratio <= 1))
CHECK (baseline_snapshot_run_id IS NULL OR
       current_snapshot_run_id IS NULL OR
       baseline_snapshot_run_id <> current_snapshot_run_id)
```

删除对账组合：

```text
validation_scope = DELETE_RECONCILIATION
validation_method = DELETE_KEY_DIFF
execution_id IS NULL
baseline_snapshot_run_id IS NOT NULL
current_snapshot_run_id IS NOT NULL
difference_count IS NOT NULL
difference_ratio IS NOT NULL
```

其他校验范围中，两个快照外键和 `difference_ratio` 必须为空。

同一候选快照只生成一条删除对账：

```sql
CREATE UNIQUE INDEX uk_validation_delete_current_snapshot
    ON validation_run (current_snapshot_run_id)
    WHERE validation_scope = 'DELETE_RECONCILIATION';
```

`PASS` 表示没有删除差异；`MISMATCH` 表示发现删除键。两者都属于技术执行完成，不自动删除 ODS。

## 5. `delete_apply_run`

职责：保存删除差异 dry-run 和管理员确认后的人工应用结果，不保存逐键明细。

目标字段：

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

删除：

```text
task_version_id
```

外键闭环：

```text
FOREIGN KEY (validation_run_id, task_id)
REFERENCES validation_run(id, task_id)
ON DELETE RESTRICT

FOREIGN KEY (task_id, institution_id, dataset_id)
REFERENCES sync_task(id, institution_id, dataset_id)
ON DELETE RESTRICT
```

服务启动前验证 `validation_run`：

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

实际应用唯一性：

```sql
CREATE UNIQUE INDEX uk_delete_apply_active
    ON delete_apply_run (validation_run_id)
    WHERE dry_run = false AND status IN ('PENDING','RUNNING');

CREATE UNIQUE INDEX uk_delete_apply_success
    ON delete_apply_run (validation_run_id)
    WHERE dry_run = false AND status = 'SUCCEEDED';
```

允许多次 dry-run；实际应用必须二次确认并记录成功或失败审计。

## 6. 基线切换事务

首次成功快照：

```text
delete_snapshot_run = COMPLETED + BASELINE_CREATED
→ 插入或更新 task_delete_snapshot_state.current_baseline_snapshot_run_id
→ 不创建删除差异
```

后续快照：

```text
候选快照完整写入 Doris
→ 使用原基线和候选做 anti join
→ 写入 _dfetl_delete_diff
→ 完成 DELETE_RECONCILIATION validation_run
→ 锁定 task_delete_snapshot_state
→ 再次确认原基线未变化
→ 切换 current_baseline_snapshot_run_id
→ 更新 last_reconciliation_validation_run_id
→ 提交
```

差异数量大于 0 不阻止新候选成为下一次基线；它只表示源端业务键相对上一基线发生删除。失败、取消或不完整候选永远不能切换基线。

## 7. Doris 技术表

删除任务版本后，技术表只保存差集计算和分页查询真正需要的列。

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

机构、数据集、链路和快照上下文由 PostgreSQL 控制记录解释，不在百万级键明细中重复保存。

## 8. 明确不建立

```text
PostgreSQL task_snapshot_key
逐键 PostgreSQL 删除差异表
任务版本字段
Java HashSet 全量差集
自动删除 ODS
失败候选覆盖基线
删除应用审批流
删除应用自动补偿状态机
```

## 9. 验收

- 所有快照、对账和删除应用均能通过复合外键证明属于同一任务。
- 任务机构和数据集身份由数据库保证一致。
- `validation_run` 的基线与候选不能跨任务引用。
- 第一次快照只建立基线。
- 后续完整候选完成对账后原子切换基线。
- 失败候选不切换基线。
- 删除差异只生成，不自动应用。
- Doris 技术表中不存在 `task_version_id` 和重复业务上下文列。

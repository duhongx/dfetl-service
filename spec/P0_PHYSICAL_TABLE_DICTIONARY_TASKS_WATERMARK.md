# P0 物理表字典：同步任务与正式水位

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Dataset 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
sync_task
task_watermark
```

不建立 Task Version、Watermark History、任务草稿/发布/回退/身份迁移表。

## 2. `sync_task`

Task：

```text
固定身份 = institution_id + dataset_id
当前配置 = dataset_version_id + route_version_id + 执行/调度/校验配置
历史不可变上下文 = sync_execution / validation_run 启动快照
```

核心字段：

```text
id
institution_id/dataset_id/dataset_version_id/route_version_id
name
task_kind/write_mode/doris_key_model/incremental_field_code
fetch_size/upper_bound_delay_minutes/lookback_seconds
schedule_mode/schedule_interval_hours/schedule_cron/schedule_timezone
schedule_source/schedule_source_revision/schedule_enabled
validation_method_override
revision
deleted_at/deleted_by
created_*/updated_*
```

四元强 FK：

```text
(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id)
ON DELETE RESTRICT
```

增量字段：

```text
(dataset_version_id,incremental_field_code)
→ standard_dataset_field(dataset_version_id,field_code)
ON DELETE RESTRICT
```

业务唯一：

```text
UNIQUE INDEX uk_sync_task_active_institution_dataset
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

## 3. Task 状态与调度

Task 不保存生命周期 Status；当前是否自动调度由：

```text
deleted_at
schedule_enabled
schedule_mode
schedule_cron
```

决定。

Quartz Projection 条件：

```text
deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>'MANUAL'
AND schedule_cron 有效
```

Task Pause 只设置 `schedule_enabled=false`；不取消当前 Execution。

## 4. Task 逻辑删除

Task 固定使用：

```text
LOGICAL_DELETE
```

流程：

```text
锁定 sync_task
→ 检查活动 sync_execution
→ 有活动执行则拒绝 TASK_EXECUTION_ACTIVE
→ 设置 deleted_at/deleted_by
→ schedule_enabled=false
→ 删除对应 Quartz Job/Trigger 投影
→ Audit
```

固定规则：

- 不物理删除 `sync_task`。
- 不级联删除 Execution/Batch/Validation/Outbox/Delete History/Audit。
- **不级联删除 `task_watermark`。**
- 逻辑删除释放 `(institution_id,dataset_id) WHERE deleted_at IS NULL`，以后允许新建新的 Task ID。
- 改变 Institution 或 Dataset 固定为“逻辑删除旧 Task + 新建新 Task”，不 UPDATE 身份。

## 5. `task_watermark`

核心字段：

```text
task_id bigint PK
watermark_value timestamptz NOT NULL
source_execution_id bigint NULL
revision bigint NOT NULL DEFAULT 0
updated_at timestamptz NOT NULL
updated_by bigint NULL
```

FK：

```text
task_id → sync_task(id) ON DELETE RESTRICT
(source_execution_id,task_id) → sync_execution(id,task_id) ON DELETE RESTRICT
updated_by → app_user(id) ON DELETE SET NULL
```

`task_watermark` 是**当前状态行，不是历史表**。

## 6. Watermark 推进与显式 Clear

正式推进：

- INITIAL_FULL 成功 + SYNC_GATE PASS 后 `watermark=T0`。
- 下一次正常运行才 INCREMENTAL。
- INCREMENTAL 成功 + Gate PASS 后推进到固定 `window_upper`。
- Failure/Cancel/Backfill/独立 Validation 不推进。

人工操作：

```text
设置/重置 Watermark
→ UPSERT/UPDATE 当前行
→ source_execution_id=NULL
→ Audit
```

显式“清除水位”：

```text
DELETE FROM task_watermark WHERE task_id=?
→ Audit
```

固定语义：

```text
Watermark Row 不存在
= 当前 Task 没有正式水位
```

不增加：

```text
watermark_status=CLEARED
task_watermark_history
```

Task 逻辑删除不执行 Clear；旧 Watermark Row 随旧 Task ID 保留。

## 7. Execution Snapshot

创建 `sync_execution` 时固定：

```text
task_id + task_revision
institution/dataset/dataset_version/route_version
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size/upper_bound_delay/lookback
本次范围
最终 Validation 方法/来源
Message Policy Snapshot
```

Task 后续编辑/逻辑删除不修改已创建 Execution Snapshot。

## 8. 验收

- Task 使用逻辑删除，不物理删除。
- Task 删除先阻止活动 Execution，并关闭 Quartz Projection。
- Task 逻辑删除不级联 Watermark 或运行历史。
- Watermark 只有显式“清除水位”操作允许物理删除当前行。
- 无 Watermark Row 表示没有正式水位。
- 不建立 Watermark History/Task Version。

# P0 物理表字典：Execution、Batch、Precheck、Validation 与 Message Outbox

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> Task：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

运行模型统一：

- `SUCCEEDED` = 同步/批次/消息等业务动作真正成功。
- `COMPLETED + result` = Precheck/Validation 技术完成，结果单独表达。
- 历史运行使用启动快照和 RESTRICT FK。
- **上述六张 PostgreSQL 表全部属于长期运行/质量/消息历史，不提供普通 DELETE API，也不设置自动 PostgreSQL retention。**

## 2. `sync_execution`

保存 Task/Institution/Dataset/Route Version 身份、启动配置、范围、Validation/Message Snapshot、状态、统计和错误摘要。

关键枚举：

```text
operation_type: NORMAL / RECOLLECT / BACKFILL
trigger_type: SCHEDULED / MANUAL / EXTERNAL_API
status: PENDING / RUNNING / LOADING / VALIDATING / SUCCEEDED / FAILED / CANCELLED
execution_scope: FULL / INITIAL_FULL / INCREMENTAL / BACKFILL_TIME / BACKFILL_KEY
validation_source: GLOBAL / DATASET / TASK / CONTRACT
```

关键 FK：

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

(external_client_id,external_request_id)
→ external_api_request(client_id,request_id) RESTRICT
```

活动并发：

```text
UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING')
```

Trigger/Operation/Range/Terminal/Cancel CHECK 以 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` 为最终基线。

### 删除

`sync_execution` 永久保留。Task 后续编辑、暂停、逻辑删除都不得修改或删除历史 Execution。

## 3. `load_batch`

```text
status: PENDING / LOADING / PROBING / SUCCEEDED / FAILED / CANCELLED
doris_state: UNKNOWN / PREPARE / COMMITTED / VISIBLE / ABORTED
```

```text
execution_id → sync_execution(id) RESTRICT
UNIQUE(execution_id,batch_no)
UNIQUE(doris_label)
```

只有 Doris `VISIBLE` + rejected=0 才 SUCCEEDED。

### 删除

Batch 是 Execution 内真实 Load 事实，永久保留；不因为 Execution 终态、Recollect 或 Doris 数据变化而删除。

## 4. `precheck_run`

```text
execution_status: PENDING / EXTRACTING / VALIDATING / COMPLETED / FAILED / CANCELLED
result_status: PASS / ISSUES
raw_cleanup_status: NOT_READY / PENDING / CLEANED / FAILED
```

Precheck 只人工启动，同 Route 一个活动 Run；保存单机构 Route/Dataset Version 快照。

### PostgreSQL 删除

```text
precheck_run
precheck_issue_summary
```

永久保留，不提供 DELETE/retention。

### Doris RAW 清理

Precheck 业务 RAW 不是 PostgreSQL 历史：终态后按既有规则保留 1 天，再清理 Doris RAW，并更新：

```text
raw_cleanup_status
raw_cleaned_at
raw_cleanup_error
```

Doris RAW 清理不删除 PostgreSQL Run/Summary。

## 5. `precheck_issue_summary`

只保存 `STRUCTURE/FIELD/COMPOSITE` 汇总，不保存行级问题/业务键/样例。

```text
run_id → precheck_run(id) RESTRICT
```

属于 Precheck 历史，永久保留。

## 6. `validation_run`

统一承载 SYNC_GATE、Manual Recheck、独立 Validation、Delete Reconciliation。

```text
validation_source:
GLOBAL / DATASET / TASK / CONTRACT / FIXED
```

`FIXED` 只用于：

```text
DELETE_RECONCILIATION + DELETE_KEY_DIFF
```

关联 Execution 时必须通过 `(execution_id,task_id)` 保证同 Task；Delete Reconciliation 的 Baseline/Current Snapshot 也通过复合 FK 保证同 Task。

### 删除

Validation 是质量/删除对账历史，永久保留；不存在 Validation History Purge 或 Policy Retention。

## 7. `message_outbox`

```text
status: PENDING / PUBLISHING / PUBLISHED / DEAD_LETTER
publish_scope: FULL / INCREMENTAL
```

父身份：

```text
(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id) RESTRICT
```

```text
UNIQUE(event_id)
UNIQUE(execution_id)
```

不保存业务 Payload、分页进度、逐次 Attempt。

### 删除

Outbox 是成功 Execution 后的消息发布事实，永久保留；PUBLISHED/DEAD_LETTER 都不自动清理 PostgreSQL Row。人工重发沿用 Event ID，修改当前 Outbox 状态并写 Audit，不删除重建历史行。

## 8. 为什么 P0 不做 Runtime PostgreSQL Retention

高容量明细已经放在适合的位置：

```text
Precheck RAW → Doris，1 天后清理
Delete Key/Diff → Doris，按 Delete Snapshot 生命周期清理
完整应用/SeaTunnel 日志 → 日志系统
Outbox → 不保存业务 Payload
```

因此 P0 不增加：

```text
archive table
retention_status
history_purge_job
partition drop policy
```

后续若实际运行量证明 PostgreSQL 历史需要归档，必须作为独立迁移 Review，不在 V1 预埋复杂状态机。

## 9. 成功收尾事务

```text
全部 Batch SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE COMPLETED + PASS
→ Execution SUCCEEDED
→ Watermark 创建/推进
→ Message Policy 启用则插入 Outbox
→ commit
```

事务内不调用 Doris/RabbitMQ。

## 10. 验收

- Execution/Batch/Precheck/Summary/Validation/Outbox 全部无普通 DELETE API。
- Task 逻辑删除不级联这些历史。
- Precheck 只清 Doris RAW，不删 PostgreSQL Run/Summary。
- Outbox 终态不自动 purge。
- FK/Unique/Status/CHECK 继续以对应冻结 Matrix 为准。

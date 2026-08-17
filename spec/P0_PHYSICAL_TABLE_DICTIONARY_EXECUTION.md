# P0 物理表字典：Execution、Batch、Precheck、Validation 与 Message Outbox

> 状态：阶段 1 FK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Delete Snapshot：`spec/P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`  
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

明确不建立：

```text
sync_task_version
collection_route_version_institution
execution_checkpoint
execution_reconciliation
validation_run_segment
validation_difference_summary
Precheck 行级 Issue 表
message_delivery_attempt
Message 分页进度/逐条消息表
异步导出任务表
```

核心原则：

- Task 保存当前配置；Execution/Validation 创建时固定不可变启动上下文。
- Route 为单机构对象，运行层统一使用 Route Version 四元身份。
- Watermark/Validation 必须由数据库证明 Execution 属于同一 Task。
- External API Execution 必须由数据库证明来源于同一 Client 的真实 `external_api_request`。
- Outbox 只使用父 Execution 强复合身份 FK。
- 历史运行对象全部 `ON DELETE RESTRICT`。

## 2. `sync_execution`

职责：一次真实同步运行的 Task 身份、启动配置快照、固定范围、状态、统计和错误摘要。

### 2.1 字段

```text
id bigint identity PK
execution_uuid uuid NOT NULL

task_id bigint NOT NULL
task_revision bigint NOT NULL
institution_id bigint NOT NULL
institution_code varchar(100) NOT NULL
dataset_id bigint NOT NULL
dataset_version_id bigint NOT NULL
route_version_id bigint NOT NULL

task_kind varchar(32) NOT NULL
write_mode varchar(40) NOT NULL
doris_key_model varchar(24) NOT NULL
incremental_field_code varchar(100) NULL
fetch_size integer NOT NULL
upper_bound_delay_minutes integer NOT NULL DEFAULT 0
lookback_seconds integer NOT NULL DEFAULT 0

operation_type varchar(20) NOT NULL
trigger_type varchar(24) NOT NULL
status varchar(20) NOT NULL DEFAULT 'PENDING'
execution_scope varchar(24) NOT NULL
target_prepare_mode varchar(40) NOT NULL DEFAULT 'NONE'

window_lower timestamptz NULL
window_upper timestamptz NULL
key_lower jsonb NULL
key_upper jsonb NULL
watermark_before timestamptz NULL
watermark_commit_expected boolean NOT NULL DEFAULT false

schedule_fire_time timestamptz NULL
external_client_id bigint NULL
external_request_id varchar(128) NULL
requested_by_user_id bigint NULL

precheck_fact_snapshot jsonb NOT NULL DEFAULT '{}'
validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
message_policy_snapshot jsonb NOT NULL DEFAULT '{}'
checksum_protocol_version varchar(64) NOT NULL

source_row_count bigint NOT NULL DEFAULT 0
loaded_row_count bigint NOT NULL DEFAULT 0
rejected_row_count bigint NOT NULL DEFAULT 0
batch_count integer NOT NULL DEFAULT 0
engine_job_id varchar(128) NULL

cancel_requested_at timestamptz NULL
cancel_requested_by bigint NULL
cancel_reason varchar(1000) NULL
error_code varchar(100) NULL
error_message varchar(2000) NULL
started_at timestamptz NULL
finished_at timestamptz NULL
revision bigint NOT NULL DEFAULT 0
created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
```

不保存：

```text
task_version_id
validation_policy_snapshot
execution_contract_hash
```

### 2.2 受控值

```text
operation_type: NORMAL / RECOLLECT / BACKFILL
trigger_type: SCHEDULED / MANUAL / EXTERNAL_API
status: PENDING / RUNNING / LOADING / VALIDATING / SUCCEEDED / FAILED / CANCELLED
execution_scope: FULL / INITIAL_FULL / INCREMENTAL / BACKFILL_TIME / BACKFILL_KEY
target_prepare_mode: NONE / REPLACE_INSTITUTION_SCOPE
validation_method: ROW_COUNT / ROW_COUNT_CHECKSUM
validation_source: GLOBAL / DATASET / TASK / CONTRACT
```

Task 三种固定组合必须与 `sync_task` 一致。

### 2.3 FK：Task 身份

```text
FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

### 2.4 FK：Route/Dataset Version

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

不再额外建立 Route/Dataset/Institution 的重复单列 FK。

### 2.5 FK：Incremental Field

```text
FOREIGN KEY (dataset_version_id,incremental_field_code)
REFERENCES standard_dataset_field(dataset_version_id,field_code)
ON DELETE RESTRICT
```

可空时不触发。

### 2.6 FK：External API Request

External API Execution 必须关联真实幂等请求：

```text
FOREIGN KEY (external_client_id,external_request_id)
REFERENCES external_api_request(client_id,request_id)
ON DELETE RESTRICT
```

固定 CHECK 语义：

```text
trigger_type='EXTERNAL_API'
→ external_client_id/external_request_id 均非空

trigger_type<>'EXTERNAL_API'
→ external_client_id/external_request_id 均为空
```

因此不会出现 Execution 写了 Client A，却引用 Client B 的 Request ID。

### 2.7 责任用户 FK

```text
requested_by_user_id → app_user(id) ON DELETE RESTRICT
cancel_requested_by  → app_user(id) ON DELETE RESTRICT
```

MANUAL Execution 必须有 `requested_by_user_id`；SCHEDULED/EXTERNAL_API 不要求本地用户。

### 2.8 Parent Unique / Index

```text
UNIQUE(execution_uuid)
UNIQUE(id,task_id)
UNIQUE(id,task_id,dataset_id,institution_id)
```

用途：

- `(id,task_id)`：Watermark/Validation 同 Task Execution FK；
- 四列：Outbox 身份 FK。

并发：

```text
UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING')
```

查询/FK 子索引：

```text
INDEX idx_sync_execution_task_history
ON sync_execution(task_id,created_at DESC,id DESC)

INDEX idx_sync_execution_route_version
ON sync_execution(route_version_id,created_at DESC,id DESC)

INDEX idx_sync_execution_status
ON sync_execution(status,created_at,id)

INDEX idx_sync_execution_dataset_history
ON sync_execution(dataset_id,institution_id,created_at DESC,id DESC)

INDEX idx_sync_execution_engine_job
ON sync_execution(engine_job_id)
WHERE engine_job_id IS NOT NULL

INDEX idx_sync_execution_external_request
ON sync_execution(external_client_id,external_request_id)
WHERE external_client_id IS NOT NULL
```

### 2.9 状态/范围边界

- INCREMENTAL/BACKFILL_TIME 使用时间范围。
- BACKFILL_KEY 使用 Key Range。
- FULL/INITIAL_FULL 不制造伪业务窗口。
- BACKFILL 固定 `watermark_commit_expected=false`。
- 活动态 `finished_at=NULL`；终态必须有 `finished_at`。
- SUCCEEDED 必须 `rejected_row_count=0`；FAILED 必须有 `error_code`。
- Cancel 不新增 CANCELLING；先确认在途 Doris Label，再收敛为 CANCELLED。

## 3. `load_batch`

字段：

```text
id bigint identity PK
execution_id bigint NOT NULL
batch_no integer NOT NULL
status varchar(20) NOT NULL DEFAULT 'PENDING'
cursor_lower/cursor_upper jsonb NULL
source_row_count/loaded_row_count/rejected_row_count bigint NOT NULL DEFAULT 0
payload_checksum varchar(128) NULL
doris_label varchar(128) NOT NULL
doris_txn_id bigint NULL
doris_state varchar(32) NULL
probe_count integer NOT NULL DEFAULT 0
last_probed_at/submitted_at/visible_at timestamptz NULL
error_code/error_message
created_at/updated_at
```

FK：

```text
FOREIGN KEY (execution_id)
REFERENCES sync_execution(id)
ON DELETE RESTRICT
```

`UNIQUE(execution_id,batch_no)` 已覆盖 FK 子列。

状态：

```text
PENDING/LOADING/PROBING/SUCCEEDED/FAILED/CANCELLED
Doris: UNKNOWN/PREPARE/COMMITTED/VISIBLE/ABORTED
```

只有 VISIBLE + rejected=0 才成功。响应不明确只探测原 Label，不自动新 Label 重投。

## 4. `precheck_run`

职责：一次单机构 Route 的人工全量 Precheck。

核心字段：

```text
id/run_uuid
route_id/route_version_id
institution_id/institution_code
dataset_id/dataset_version_id
execution_status/result_status/current_phase
source_structure_hash/field_resolution_hash/dataset_definition_hash
extracted_row_count/checked_row_count/issue_row_count/issue_summary_count
raw_retention_deadline/raw_cleanup_status/raw_cleaned_at/raw_cleanup_error
cancel_requested_at
requested_by
error_code/error_message
started_at/finished_at/created_at/updated_at
```

FK：

```text
FOREIGN KEY (route_id,route_version_id)
REFERENCES collection_route_version(route_id,id)
ON DELETE RESTRICT

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

两条复合 FK 分别证明：Version 属于 Route、运行身份属于该 Version，不属于重复单列 FK。

责任用户：

```text
requested_by → app_user(id) ON DELETE RESTRICT
```

Unique/Index：

```text
UNIQUE(run_uuid)
UNIQUE INDEX uk_precheck_run_active_route
ON precheck_run(route_id)
WHERE execution_status IN ('PENDING','EXTRACTING','VALIDATING')

INDEX idx_precheck_run_route_history
ON precheck_run(route_id,created_at DESC,id DESC)

INDEX idx_precheck_run_route_version
ON precheck_run(route_version_id,created_at DESC,id DESC)
```

Precheck 只人工启动；终态 RAW 保留 1 天；不作为正式同步业务数据源。

## 5. `precheck_issue_summary`

字段：

```text
id bigint identity PK
run_id bigint NOT NULL
rule_scope varchar(20) NOT NULL
standard_field_code varchar(100) NULL
rule_code varchar(100) NOT NULL
rule_definition_version varchar(64) NOT NULL
checked_rows/affected_rows bigint NOT NULL DEFAULT 0
observed_metrics jsonb NOT NULL DEFAULT '{}'
summary varchar(2000) NOT NULL
created_at timestamptz NOT NULL
```

FK：

```text
FOREIGN KEY (run_id)
REFERENCES precheck_run(id)
ON DELETE RESTRICT
```

业务 Unique 以 `run_id` 为首列，覆盖 FK 子列。

只允许 `STRUCTURE/FIELD/COMPOSITE`，不保存行级问题、业务键、样例或修复状态。

## 6. `validation_run`

职责：统一保存 SYNC_GATE、Manual Recheck、独立治理 Validation 和 Delete Reconciliation。

核心字段：

```text
id/run_uuid
task_id/execution_id/task_revision
context_snapshot/range_snapshot
validation_scope/trigger_type/validation_method
validation_source/validation_source_revision/validation_contract_forced
status/result
checksum_protocol_version
source_row_count/target_row_count
source_checksum/target_checksum
difference_count/difference_ratio/difference_summary
baseline_snapshot_run_id/current_snapshot_run_id
requested_by
cancel_requested_at/cancel_requested_by
error_code/error_message
started_at/finished_at/created_at/updated_at
```

### 6.1 FK

父 Task：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT
```

关联 Execution 时必须属于同一 Task：

```text
FOREIGN KEY (execution_id,task_id)
REFERENCES sync_execution(id,task_id)
ON DELETE RESTRICT
```

删除对账：

```text
FOREIGN KEY (baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (current_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT
```

责任用户：

```text
requested_by        → app_user(id) ON DELETE RESTRICT
cancel_requested_by → app_user(id) ON DELETE RESTRICT
```

### 6.2 Parent Unique / Index

```text
UNIQUE(id,task_id)
UNIQUE(run_uuid)
```

并发/业务 Unique：

```text
UNIQUE INDEX uk_validation_sync_gate_execution
ON validation_run(execution_id)
WHERE trigger_type='SYNC_GATE'

UNIQUE INDEX uk_validation_run_active_independent_task
ON validation_run(task_id)
WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
  AND status IN ('PENDING','RUNNING')
```

FK 子索引：

```text
INDEX idx_validation_task_history
ON validation_run(task_id,created_at DESC,id DESC)

INDEX idx_validation_execution
ON validation_run(execution_id,created_at DESC,id)
WHERE execution_id IS NOT NULL

INDEX idx_validation_baseline_snapshot
ON validation_run(baseline_snapshot_run_id,task_id)
WHERE baseline_snapshot_run_id IS NOT NULL
```

`current_snapshot_run_id` 由 Delete Reconciliation Partial Unique 覆盖。

## 7. `message_outbox`

职责：Execution 成功收尾事务中保存一条小型 RabbitMQ 发布指令。

核心字段：

```text
id/event_id
execution_id/task_id/dataset_id/institution_id
status/available_at/attempt_count/max_attempts
policy_revision/publish_scope
source_system/tenant_id/routing_key/topic/key_template
rate_limit_per_second/page_size
range_snapshot
last_attempt_at/published_at
last_error_code/last_error_message
created_at/updated_at
```

### 7.1 只保留父 Execution 强复合 FK

```text
FOREIGN KEY (
  execution_id,
  task_id,
  dataset_id,
  institution_id
)
REFERENCES sync_execution(
  id,
  task_id,
  dataset_id,
  institution_id
)
ON DELETE RESTRICT
```

不再创建：

```text
execution_id → sync_execution(id)
task_id → sync_task(id)
dataset_id → standard_dataset(id)
institution_id → institution(id)
```

这些关系已被父 Execution 复合 FK 完全覆盖。

### 7.2 Unique / Index

```text
UNIQUE(event_id)
UNIQUE(execution_id)

INDEX idx_message_outbox_scan(status,available_at,id)
INDEX idx_message_outbox_publishing_recovery(status,last_attempt_at,id)
  WHERE status='PUBLISHING'
INDEX idx_message_outbox_task_history(task_id,created_at DESC,id DESC)
```

状态：

```text
PENDING → PUBLISHING → PUBLISHED / DEAD_LETTER
```

Outbox 失败不回滚已成功 Execution/Watermark；人工重发重读当前 Doris，不保存业务 Payload/分页进度。

## 8. 成功收尾事务

```text
锁定 sync_execution
→ 全部 load_batch=SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE=COMPLETED + PASS
→ rejected_row_count=0
→ sync_execution=SUCCEEDED
→ 按规则创建/推进 task_watermark
→ Message Policy 启用则插入唯一 message_outbox
→ commit
```

事务内不调用 Doris/RabbitMQ。

## 9. 验收

- Execution 的 Task/Institution/Dataset 由复合 FK 固定。
- Execution 的 Route/Dataset Version 使用四元强 FK。
- External API Execution 必须引用同一 Client 的真实 `external_api_request`。
- `sync_execution(id,task_id)` 可供 Watermark/Validation 使用。
- Validation 不可能引用其他 Task 的 Execution。
- Outbox 不可能保存与父 Execution 不一致的 Task/Dataset/Institution。
- 所有运行历史 FK 使用 RESTRICT。
- 责任用户使用 RESTRICT；普通审计用户由各配置字典按 SET NULL 处理。

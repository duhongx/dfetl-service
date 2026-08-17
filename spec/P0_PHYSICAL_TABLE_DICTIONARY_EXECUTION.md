# P0 物理表字典：执行、批次、预检、校验与消息 Outbox

> 状态：阶段 1 运行模型已按当前 Task + 单机构 Route 模型收口  
> 首次 Review：2026-08-15  
> 最近收口：2026-08-17  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> Outbox Scope：`spec/P0_OUTBOX_SCOPE_MAPPING_REVIEW.md`  
> Delete Snapshot：`spec/P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`  
> Label Probe：`spec/P0_DORIS_LABEL_PROBE_REVIEW.md`  
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

- Task 保存当前配置；Execution/Validation 启动时固定不可变运行上下文。
- Route 为单机构对象，运行模型不再依赖覆盖机构关系表。
- Task/Execution/Delete Snapshot 使用同一 Route Version 四元身份：`route_version_id + institution_id + dataset_id + dataset_version_id`。
- Outbox 的 Task/Dataset/Institution 必须由父 Execution 复合 FK 保证完全一致。

## 2. `sync_execution`

职责：保存一次真实同步运行的 Task 身份、启动配置快照、固定范围、状态、统计和错误摘要。

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

operation_type varchar(20) NOT NULL               # NORMAL/RECOLLECT/BACKFILL
trigger_type varchar(24) NOT NULL                 # SCHEDULED/MANUAL/EXTERNAL_API
status varchar(20) NOT NULL DEFAULT 'PENDING'
execution_scope varchar(24) NOT NULL               # FULL/INITIAL_FULL/INCREMENTAL/BACKFILL_TIME/BACKFILL_KEY
target_prepare_mode varchar(40) NOT NULL DEFAULT 'NONE'

window_lower timestamptz NULL
window_upper timestamptz NULL
key_lower jsonb NULL
key_upper jsonb NULL
watermark_before timestamptz NULL
watermark_commit_expected boolean NOT NULL DEFAULT false

schedule_fire_time timestamptz NULL
external_request_id varchar(128) NULL
requested_by_user_id bigint NULL
external_client_id bigint NULL

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

明确不保存：

```text
task_version_id
validation_policy_snapshot
execution_contract_hash
```

### 2.2 核心 CHECK

```text
CHECK task_revision >= 0
CHECK task_kind IN ('FULL_ONLY','FULL_THEN_INCREMENTAL')
CHECK write_mode IN ('REPLACE_INSTITUTION_SCOPE','UPSERT')
CHECK doris_key_model IN ('DUPLICATE_KEY','UNIQUE_KEY')
CHECK operation_type IN ('NORMAL','RECOLLECT','BACKFILL')
CHECK trigger_type IN ('SCHEDULED','MANUAL','EXTERNAL_API')
CHECK status IN ('PENDING','RUNNING','LOADING','VALIDATING','SUCCEEDED','FAILED','CANCELLED')
CHECK execution_scope IN ('FULL','INITIAL_FULL','INCREMENTAL','BACKFILL_TIME','BACKFILL_KEY')
CHECK target_prepare_mode IN ('NONE','REPLACE_INSTITUTION_SCOPE')
CHECK validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
CHECK validation_source IN ('GLOBAL','DATASET','TASK','CONTRACT')
CHECK validation_source_revision IS NULL OR validation_source_revision >= 0
CHECK source_row_count >= 0 AND loaded_row_count >= 0 AND rejected_row_count >= 0
CHECK batch_count >= 0 AND revision >= 0
CHECK jsonb_typeof(precheck_fact_snapshot)='object'
CHECK jsonb_typeof(message_policy_snapshot)='object'
```

Validation Source：

```text
CONTRACT → validation_contract_forced=true + source_revision=NULL
非 CONTRACT → validation_contract_forced=false
```

Task 三种固定组合与 `sync_task` 完全一致。

### 2.3 范围/终态

- INCREMENTAL/BACKFILL_TIME 使用时间范围。
- BACKFILL_KEY 使用 Key Range。
- FULL/INITIAL_FULL 不使用伪业务窗口。
- BACKFILL 固定 `watermark_commit_expected=false`。
- SCHEDULED 必须有 `schedule_fire_time`；EXTERNAL_API 必须有 Client + Request ID。
- 活动态 `finished_at=NULL`；终态必须有 `finished_at`。
- SUCCEEDED 必须 `rejected_row_count=0`；FAILED 必须有 `error_code`。
- Cancel 不新增 CANCELLING 状态；确认在途 Doris Label 后收敛到 CANCELLED。

### 2.4 FK 与身份闭环

Task：

```text
FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

Route/Dataset Version：

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

Incremental Field：

```text
FOREIGN KEY (dataset_version_id,incremental_field_code)
REFERENCES standard_dataset_field(dataset_version_id,field_code)
ON DELETE RESTRICT
```

### 2.5 Unique / Index

```text
UNIQUE(execution_uuid)
UNIQUE(id,task_id,dataset_id,institution_id)   # Outbox 复合身份父键

UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING')

INDEX idx_sync_execution_task_history
ON sync_execution(task_id,created_at DESC,id DESC)

INDEX idx_sync_execution_status
ON sync_execution(status,created_at,id)

INDEX idx_sync_execution_dataset_history
ON sync_execution(dataset_id,institution_id,created_at DESC,id DESC)

INDEX idx_sync_execution_engine_job
ON sync_execution(engine_job_id) WHERE engine_job_id IS NOT NULL

INDEX idx_sync_execution_external_request
ON sync_execution(external_client_id,external_request_id)
WHERE external_client_id IS NOT NULL AND external_request_id IS NOT NULL
```

### 2.6 状态流转

```text
PENDING → RUNNING → LOADING → VALIDATING → SUCCEEDED
PENDING/RUNNING/LOADING/VALIDATING → FAILED/CANCELLED
```

失败不 Retry、不推进 Watermark；Recollect 创建新 Execution；Schedule 冲突跳过，不创建 SKIPPED Execution。

## 3. `load_batch`

职责：保存 Execution 内实际分页游标、确定性 Doris Label、Label 探测和最终载入结果；不是跨 Execution Checkpoint。

### 3.1 字段

```text
id bigint identity PK
execution_id bigint NOT NULL
batch_no integer NOT NULL
status varchar(20) NOT NULL DEFAULT 'PENDING'
cursor_lower jsonb NULL
cursor_upper jsonb NULL
source_row_count bigint NOT NULL DEFAULT 0
loaded_row_count bigint NOT NULL DEFAULT 0
rejected_row_count bigint NOT NULL DEFAULT 0
payload_checksum varchar(128) NULL
doris_label varchar(128) NOT NULL
doris_txn_id bigint NULL
doris_state varchar(32) NULL
probe_count integer NOT NULL DEFAULT 0
last_probed_at timestamptz NULL
submitted_at timestamptz NULL
visible_at timestamptz NULL
error_code varchar(100) NULL
error_message varchar(2000) NULL
created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
```

不保存：

```text
phase
time_lower/time_upper
probe_result
institution_code
checksum_protocol_version
batch_type/stage
resume_from_batch_id/next_cursor
committed_at
```

### 3.2 约束/索引

```text
CHECK status IN ('PENDING','LOADING','PROBING','SUCCEEDED','FAILED','CANCELLED')
CHECK doris_state IS NULL OR doris_state IN ('UNKNOWN','PREPARE','COMMITTED','VISIBLE','ABORTED')
CHECK source_row_count >= 0 AND loaded_row_count >= 0 AND rejected_row_count >= 0
CHECK probe_count >= 0
CHECK SUCCEEDED → doris_state='VISIBLE' AND visible_at IS NOT NULL AND rejected_row_count=0
CHECK FAILED → error_code IS NOT NULL

UNIQUE(execution_id,batch_no)
UNIQUE(doris_label)
INDEX idx_load_batch_execution_status ON load_batch(execution_id,status,batch_no)
INDEX idx_load_batch_probe ON load_batch(status,updated_at,id)
WHERE status IN ('LOADING','PROBING')
```

Publish Timeout/响应不明确只探测原 Label；VISIBLE 成功、ABORTED 失败、UNKNOWN 超时失败，不盲目重投。

## 4. `precheck_run`

职责：一次**单机构 Route** 的人工全量 Precheck。

### 4.1 字段

```text
id bigint identity PK
run_uuid uuid NOT NULL
route_id bigint NOT NULL
route_version_id bigint NOT NULL
institution_id bigint NOT NULL
institution_code varchar(100) NOT NULL
dataset_id bigint NOT NULL
dataset_version_id bigint NOT NULL
execution_status varchar(20) NOT NULL DEFAULT 'PENDING'
result_status varchar(16) NULL
current_phase varchar(20) NOT NULL DEFAULT 'STRUCTURE'
source_structure_hash char(64) NULL
field_resolution_hash char(64) NOT NULL
dataset_definition_hash char(64) NOT NULL
extracted_row_count bigint NOT NULL DEFAULT 0
checked_row_count bigint NOT NULL DEFAULT 0
issue_row_count bigint NOT NULL DEFAULT 0
issue_summary_count integer NOT NULL DEFAULT 0
raw_retention_deadline timestamptz NULL
raw_cleanup_status varchar(16) NOT NULL DEFAULT 'NOT_READY'
raw_cleaned_at timestamptz NULL
raw_cleanup_error varchar(2000) NULL
cancel_requested_at timestamptz NULL
requested_by bigint NULL
error_code/error_message
started_at/finished_at/created_at/updated_at
```

### 4.2 FK / Unique

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

UNIQUE(run_uuid)
UNIQUE INDEX uk_precheck_run_active_route
ON precheck_run(route_id)
WHERE execution_status IN ('PENDING','EXTRACTING','VALIDATING')
```

Precheck 只人工启动；`COMPLETED+ISSUES` 是技术成功 + 有数据问题；不成为正式同步数据源/硬门禁；RAW 终态保留 1 天。

## 5. `precheck_issue_summary`

职责：字段级/组合规则级汇总，不保存行级问题。

父 Run 已唯一确定 Institution，因此本表不重复 Institution 字段，也不存在 `INSTITUTION` Rule Scope。

```text
id bigint identity PK
run_id bigint NOT NULL
rule_scope varchar(20) NOT NULL                  # STRUCTURE/FIELD/COMPOSITE
standard_field_code varchar(100) NULL
rule_code varchar(100) NOT NULL
rule_definition_version varchar(64) NOT NULL
checked_rows bigint NOT NULL DEFAULT 0
affected_rows bigint NOT NULL DEFAULT 0
observed_metrics jsonb NOT NULL DEFAULT '{}'
summary varchar(2000) NOT NULL
created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
```

```text
CHECK rule_scope IN ('STRUCTURE','FIELD','COMPOSITE')
CHECK checked_rows >= 0 AND affected_rows >= 0
CHECK affected_rows <= checked_rows OR checked_rows=0
UNIQUE(run_id,rule_scope,coalesce(standard_field_code,''),rule_code)
```

不保存 Severity、Issue Status、Row Number、Business Key、Original/Fixed Value 或 Sample Payload。

## 6. `validation_run`

职责：统一保存 SYNC_GATE、Manual Recheck、独立治理 Validation 和 Delete Reconciliation 的整次结果。

### 6.1 字段

```text
id bigint identity PK
run_uuid uuid NOT NULL
task_id bigint NOT NULL
execution_id bigint NULL
task_revision bigint NOT NULL
context_snapshot jsonb NOT NULL DEFAULT '{}'
validation_scope varchar(32) NOT NULL
trigger_type varchar(24) NOT NULL
validation_method varchar(32) NOT NULL
validation_source varchar(16) NOT NULL
validation_source_revision bigint NULL
validation_contract_forced boolean NOT NULL DEFAULT false
status varchar(20) NOT NULL DEFAULT 'PENDING'
result varchar(16) NULL
range_snapshot jsonb NOT NULL DEFAULT '{}'
checksum_protocol_version varchar(64) NULL
source_row_count bigint NULL
target_row_count bigint NULL
source_checksum varchar(256) NULL
target_checksum varchar(256) NULL
difference_count bigint NULL
difference_ratio numeric(12,8) NULL
difference_summary jsonb NOT NULL DEFAULT '{}'
baseline_snapshot_run_id bigint NULL
current_snapshot_run_id bigint NULL
requested_by bigint NULL
cancel_requested_at/cancel_requested_by
error_code/error_message
started_at/finished_at/created_at/updated_at
```

明确不保存：

```text
task_version_id
policy_snapshot
```

### 6.2 核心约束

```text
validation_scope IN ('SYNC_WINDOW','FULL_DATASET','CHANGE_WINDOW','DELETE_RECONCILIATION')
trigger_type IN ('SYNC_GATE','MANUAL','MANUAL_RECHECK','SCHEDULED')
validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM','DELETE_KEY_DIFF')
validation_source IN ('GLOBAL','DATASET','TASK','CONTRACT')
status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')
result IS NULL OR result IN ('PASS','MISMATCH')
difference_count IS NULL OR difference_count >= 0
difference_ratio IS NULL OR difference_ratio BETWEEN 0 AND 1
```

- SYNC_GATE/MANUAL_RECHECK 必须关联父 Execution。
- ROW_COUNT_CHECKSUM 必须有 Protocol Version。
- DELETE_RECONCILIATION 必须 DELETE_KEY_DIFF + Baseline/Current Snapshot + Difference Count/Ratio。
- 非 Delete Reconciliation 的 Difference Ratio/Snapshot FK 为空。
- COMPLETED 必须 PASS/MISMATCH；FAILED 必须有 Error Code。
- 整体 Checksum 不一致而无法推导准确差异行数时 `difference_count` 可 NULL。

### 6.3 FK / Unique / Concurrency

```text
FK task_id → sync_task(id) ON DELETE RESTRICT
FK execution_id → sync_execution(id) ON DELETE RESTRICT
UNIQUE(id,task_id)
UNIQUE(run_uuid)

UNIQUE INDEX uk_validation_sync_gate_execution
ON validation_run(execution_id)
WHERE trigger_type='SYNC_GATE'

UNIQUE INDEX uk_validation_run_active_independent_task
ON validation_run(task_id)
WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
  AND status IN ('PENDING','RUNNING')
```

独立 Validation 与同步启动锁定同一 Task 互斥；独立 Validation 运行后不冻结 Task 编辑。

## 7. `message_outbox`

职责：Execution 成功收尾事务中保存一条小型 RabbitMQ 发布指令；每个 Execution 最多一条。

### 7.1 字段

```text
id bigint identity PK
event_id uuid NOT NULL
execution_id bigint NOT NULL
task_id bigint NOT NULL
dataset_id bigint NOT NULL
institution_id bigint NOT NULL
status varchar(20) NOT NULL DEFAULT 'PENDING'
available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
attempt_count integer NOT NULL DEFAULT 0
max_attempts integer NOT NULL
policy_revision bigint NOT NULL
publish_scope varchar(16) NOT NULL
source_system varchar(50) NOT NULL
tenant_id varchar(50) NOT NULL
routing_key varchar(100) NOT NULL
topic varchar(100) NOT NULL
key_template varchar(500) NOT NULL
rate_limit_per_second integer NOT NULL
page_size integer NOT NULL
range_snapshot jsonb NOT NULL DEFAULT '{}'
last_attempt_at timestamptz NULL
published_at timestamptz NULL
last_error_code/last_error_message
created_at/updated_at
```

明确不保存：

```text
task_version_id
event_type
provider_message_id
next_attempt_at
业务 Payload
分页进度/逐条 Message/逐次 Attempt
RabbitMQ Exchange/连接凭据
```

### 7.2 父 Execution 复合身份 FK

Outbox 中保留 `task_id/dataset_id/institution_id` 是为了高频查询和审计，但这些值必须与原 Execution 完全一致：

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

不再用四条互相独立 FK 表达同一 Execution 身份事实。

```text
UNIQUE(event_id)
UNIQUE(execution_id)
INDEX idx_message_outbox_scan ON message_outbox(status,available_at,id)
INDEX idx_message_outbox_publishing_recovery
ON message_outbox(status,last_attempt_at,id) WHERE status='PUBLISHING'
INDEX idx_message_outbox_task_history
ON message_outbox(task_id,created_at DESC,id DESC)
```

### 7.3 发布/重试

- `PENDING → PUBLISHING → PUBLISHED/DEAD_LETTER`。
- 临时失败回 PENDING 并更新 Available At；耗尽进入 DEAD_LETTER。
- 人工重发沿用 Event ID、重置 Attempt Count 并 Audit。
- 每次真实发送重新生成 27 位 Message ID。
- 不保存分页进度；中途失败后从范围起点重读当前 Doris。
- Outbox 失败不回滚成功 Execution/Watermark。

## 8. 成功收尾原子事务

```text
锁定 sync_execution
→ 全部 load_batch=SUCCEEDED + VISIBLE
→ 唯一 SYNC_GATE=COMPLETED + PASS
→ rejected_row_count=0
→ sync_execution=SUCCEEDED
→ 按范围创建/推进 task_watermark
→ Message Policy 启用则插入唯一 message_outbox
→ commit
```

事务内不调用 Doris/RabbitMQ。

## 9. 运行前技术检查失败边界

创建 Execution 前拒绝：Task 无效、无权限、参数非法、Idempotency Conflict、已有活动 Sync/Independent Validation。

接受请求后：

```text
锁定 sync_task
→ 读取当前 Task/Route/Dataset/Validation/Message
→ 创建 PENDING sync_execution 并固定快照
→ Source/Doris/合同技术前检
→ 通过进入 RUNNING
→ 失败则保留 FAILED Execution
```

不增加 PRECHECKING 状态。

## 10. 日志边界

不建立完整 Log Line 表；Execution/Batch 只保留 ID、状态、Label、错误摘要等结构化事实，完整应用/SeaTunnel 日志由日志系统保存。

## 11. 一致性结论

```text
Task Version
→ 当前 sync_task + Execution/Validation 启动快照

Validation Policy 表
→ System Setting + Dataset Override + Task Override + Execution Validation Snapshot

多机构 Route FK
→ collection_route_version 四元复合身份 FK

Outbox 身份
→ sync_execution 四元复合身份 FK
```

旧词仅允许出现在“明确不建立/明确删除/历史防回归”说明中。

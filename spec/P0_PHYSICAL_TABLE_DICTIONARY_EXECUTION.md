# P0 物理表字典：Execution、Batch、Precheck、Validation 与 Message Outbox

> 状态：阶段 1 FK + Unique + Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> Route 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
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

明确不建立 Task Version、Multi-Institution Route、Execution Checkpoint、Validation Segment、行级 Precheck Issue、Message Attempt/分页进度等旧对象。

运行模型统一原则：

- `SUCCEEDED` 表示同步/批次/发布等业务动作真正成功。
- `COMPLETED + result` 表示 Precheck/Validation 等检查流程技术完成，最终结果单独表达。
- 历史运行使用 RESTRICT FK 和启动快照。

## 2. `sync_execution`

### 2.1 核心字段

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

window_lower/window_upper timestamptz NULL
key_lower/key_upper jsonb NULL
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

source_row_count/loaded_row_count/rejected_row_count bigint NOT NULL DEFAULT 0
batch_count integer NOT NULL DEFAULT 0
engine_job_id varchar(128) NULL

cancel_requested_at timestamptz NULL
cancel_requested_by bigint NULL
cancel_reason varchar(1000) NULL
error_code varchar(100) NULL
error_message varchar(2000) NULL
started_at/finished_at timestamptz NULL
revision bigint NOT NULL DEFAULT 0
created_at/updated_at timestamptz NOT NULL
```

### 2.2 枚举

```text
operation_type: NORMAL / RECOLLECT / BACKFILL
trigger_type: SCHEDULED / MANUAL / EXTERNAL_API
status: PENDING / RUNNING / LOADING / VALIDATING / SUCCEEDED / FAILED / CANCELLED
execution_scope: FULL / INITIAL_FULL / INCREMENTAL / BACKFILL_TIME / BACKFILL_KEY
target_prepare_mode: NONE / REPLACE_INSTITUTION_SCOPE
validation_method: ROW_COUNT / ROW_COUNT_CHECKSUM
validation_source: GLOBAL / DATASET / TASK / CONTRACT
```

`sync_execution.validation_source` 不允许 `FIXED`。

### 2.3 FK

```text
(task_id,institution_id,dataset_id)
→ sync_task(id,institution_id,dataset_id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

(dataset_version_id,incremental_field_code)
→ standard_dataset_field(dataset_version_id,field_code) RESTRICT

(external_client_id,external_request_id)
→ external_api_request(client_id,request_id) RESTRICT

requested_by_user_id/cancel_requested_by
→ app_user(id) RESTRICT
```

### 2.4 Trigger CHECK

```text
SCHEDULED
→ schedule_fire_time IS NOT NULL
→ requested_by_user_id IS NULL
→ external_client_id/external_request_id IS NULL

MANUAL
→ requested_by_user_id IS NOT NULL
→ schedule_fire_time IS NULL
→ external_client_id/external_request_id IS NULL

EXTERNAL_API
→ external_client_id/external_request_id IS NOT NULL
→ requested_by_user_id IS NULL
→ schedule_fire_time IS NULL
```

### 2.5 Operation CHECK

```text
BACKFILL
→ trigger_type='MANUAL'
→ execution_scope IN ('BACKFILL_TIME','BACKFILL_KEY')
→ watermark_commit_expected=false

RECOLLECT
→ trigger_type='MANUAL'
→ execution_scope IN ('FULL','INITIAL_FULL','INCREMENTAL')

NORMAL
→ trigger_type IN ('SCHEDULED','MANUAL','EXTERNAL_API')
→ execution_scope IN ('FULL','INITIAL_FULL','INCREMENTAL')
```

### 2.6 Range CHECK

```text
INCREMENTAL/BACKFILL_TIME
→ window_lower/window_upper 均非空
→ window_lower < window_upper
→ key_lower/key_upper 均为空

BACKFILL_KEY
→ window_lower/window_upper 均为空
→ key_lower/key_upper 均非空

FULL/INITIAL_FULL
→ window_lower/window_upper/key_lower/key_upper 均为空
```

### 2.7 Terminal / Cancel CHECK

```text
PENDING/RUNNING/LOADING/VALIDATING → finished_at IS NULL
SUCCEEDED/FAILED/CANCELLED          → finished_at IS NOT NULL

SUCCEEDED
→ rejected_row_count=0
→ error_code/error_message IS NULL

FAILED
→ error_code IS NOT NULL

cancel_requested_at IS NULL  → cancel_requested_by IS NULL
cancel_requested_at IS NOT NULL → cancel_requested_by IS NOT NULL
CANCELLED → cancel_requested_at IS NOT NULL
```

`CANCELLED` 只表示明确取消，不承担技术失败语义。

### 2.8 Unique / Index

```text
UNIQUE(execution_uuid)
UNIQUE(id,task_id)
UNIQUE(id,task_id,dataset_id,institution_id)

UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING')
```

并保留 Task History、Route Version、Status、Dataset、Engine Job、External Request 查询索引。

## 3. `load_batch`

核心字段：Execution、Batch No、游标、行数、Payload Checksum、Doris Label/Txn/State、Probe、时间和错误摘要。

```text
status:
PENDING / LOADING / PROBING / SUCCEEDED / FAILED / CANCELLED

doris_state:
UNKNOWN / PREPARE / COMMITTED / VISIBLE / ABORTED
```

FK：

```text
execution_id → sync_execution(id) RESTRICT
```

Unique：

```text
UNIQUE(execution_id,batch_no)
UNIQUE(doris_label)
```

CHECK：

```text
SUCCEEDED
→ doris_state='VISIBLE'
→ visible_at IS NOT NULL
→ rejected_row_count=0
→ error_code/error_message IS NULL

FAILED → error_code IS NOT NULL
ABORTED → status='FAILED'
非 SUCCEEDED → visible_at IS NULL
```

`COMMITTED` 不是 DFETL 成功终态；不明确结果只探测原 Label。

## 4. `precheck_run`

```text
execution_status:
PENDING / EXTRACTING / VALIDATING / COMPLETED / FAILED / CANCELLED

result_status:
PASS / ISSUES

current_phase:
STRUCTURE / EXTRACT / VALIDATE / COMPLETE

raw_cleanup_status:
NOT_READY / PENDING / CLEANED / FAILED
```

FK：

```text
(route_id,route_version_id)
→ collection_route_version(route_id,id) RESTRICT

(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT

requested_by → app_user(id) RESTRICT
```

CHECK：

```text
PENDING/EXTRACTING/VALIDATING
→ result_status IS NULL
→ finished_at IS NULL

COMPLETED
→ result_status IN ('PASS','ISSUES')
→ current_phase='COMPLETE'
→ finished_at IS NOT NULL
→ error_code/error_message IS NULL

FAILED
→ result_status IS NULL
→ finished_at IS NOT NULL
→ error_code IS NOT NULL

CANCELLED
→ result_status IS NULL
→ finished_at IS NOT NULL
→ cancel_requested_at IS NOT NULL

CLEANED → raw_cleaned_at IS NOT NULL
FAILED cleanup → raw_cleanup_error IS NOT NULL
```

结构阶段未产生 RAW 时，终态可直接记 `CLEANED + raw_cleaned_at=finished_at`，不增加 `NOT_REQUIRED`。

同 Route 活动 Precheck 继续使用 Partial Unique。

## 5. `precheck_issue_summary`

只保存：

```text
rule_scope: STRUCTURE / FIELD / COMPOSITE
standard_field_code
rule_code/rule_definition_version
checked_rows/affected_rows
observed_metrics
summary
```

不保存行号、业务键、样例、原始值、修复值或 Issue 生命周期。

## 6. `validation_run`

完整枚举和 CHECK 以：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md
```

为权威。

关键差异：

```text
validation_source:
GLOBAL / DATASET / TASK / CONTRACT / FIXED
```

`FIXED` 只用于 `DELETE_RECONCILIATION + DELETE_KEY_DIFF`；普通 Sync Validation 仍不使用 FIXED。

关联 Execution 时使用 `(execution_id,task_id) → sync_execution(id,task_id)`，Delete Reconciliation 两个 Snapshot FK 也固定同 Task。

## 7. `message_outbox`

```text
status:
PENDING / PUBLISHING / PUBLISHED / DEAD_LETTER

publish_scope:
FULL / INCREMENTAL
```

父身份只保留：

```text
(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id) RESTRICT
```

Unique：

```text
UNIQUE(event_id)
UNIQUE(execution_id)
```

CHECK：

```text
max_attempts > 0
0 <= attempt_count <= max_attempts

PENDING → published_at IS NULL
PUBLISHING → last_attempt_at IS NOT NULL + published_at IS NULL
PUBLISHED → published_at IS NOT NULL + last_error_* IS NULL
DEAD_LETTER → published_at IS NULL + last_error_code IS NOT NULL
```

人工重发：

```text
PUBLISHED/DEAD_LETTER
→ PENDING
→ attempt_count=0
→ published_at=NULL
→ clear last_error_*
```

Event ID 继续沿用；不保存业务 Payload、分页进度或逐次 Attempt。

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

- Execution Trigger/Operation/Range/Terminal/Cancel 组合由 CHECK 阻止非法半状态。
- Load Batch 只有 `VISIBLE` 才 SUCCEEDED。
- Precheck 使用 `COMPLETED + PASS/ISSUES`。
- Validation 使用 `COMPLETED + PASS/MISMATCH`，Delete Reconciliation 来源为 FIXED。
- Outbox 的状态、时间、错误和 Attempt 组合无歧义。
- 基础 Count/JSON/时间/Hash CHECK 统一遵守 Status Matrix。

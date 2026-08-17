# P0 Status / Enum / CHECK Matrix Review

> 状态：阶段 1 第 4 项最终一致性 Review 已确认  
> 确认日期：2026-08-17  
> 表清单：39 张 DFETL P0 表；Quartz 11 张官方表单独管理  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 限制：本文是 Flyway V1 的受控值和 CHECK 设计基线，不是 SQL；阶段 1 最终签字前不创建 `V1__baseline.sql`。

## 1. 已确认的统一语义

状态字段分三类：

```text
生命周期 Status
= 一个有执行过程的对象当前走到哪里

完成 Result
= 技术执行完成后，业务检查结果是什么

配置 State
= 配置是否启用、有效或作废
```

固定术语：

```text
SUCCEEDED
= 同步、批次、投递、删除应用等业务动作真正成功

COMPLETED
= Precheck / Validation / Delete Snapshot 等检查或分析流程技术执行完成，
  最终业务结果由 result/result_status/result_type 再表达
```

因此：

```text
sync_execution = SUCCEEDED
load_batch = SUCCEEDED

precheck_run = COMPLETED + PASS/ISSUES
validation_run = COMPLETED + PASS/MISMATCH
delete_snapshot_run = COMPLETED + BASELINE_CREATED/DIFF_GENERATED
```

不把 `COMPLETED` 和 `SUCCEEDED` 机械统一为一个词。

## 2. 全局 CHECK 约定

所有 V1 业务表统一遵守：

- 状态/模式使用 `varchar + CHECK`，不使用 PostgreSQL ENUM。
- 所有 count/attempt/revision/ordinal 等非负字段按语义使用 `>= 0` 或 `> 0` CHECK。
- 比例字段固定 `0 <= value <= 1`。
- 端口固定 `1..65535`。
- SHA-256 固定 64 位小写十六进制。
- `jsonb` 对象/数组使用 `jsonb_typeof(...)` CHECK。
- 同时存在的时间窗口必须 `lower < upper`。
- 终态时间不得早于开始时间。
- `FAILED` 类终态必须有稳定 `error_code`；成功终态清空错误字段。
- 可空状态关联字段必须通过跨列 CHECK 防止“半套字段”。

## 3. Resource Enum / CHECK

### 3.1 Institution / Business Catalog

```text
status:
ENABLED
DISABLED
```

### 3.2 Source Datasource

```text
db_type:
MYSQL
POSTGRESQL
ORACLE
SQLSERVER

connection_mode:
HOST_PORT
JDBC_URL

status:
ENABLED
DISABLED

last_test_status:
UNTESTED
SUCCESS
FAILED
```

连接模式：

```text
HOST_PORT
→ host/port/database_name 非空
→ jdbc_url 为空

JDBC_URL
→ jdbc_url 非空
→ host/port/database_name 为空
```

测试状态：

```text
UNTESTED
→ last_tested_at IS NULL
→ last_test_error IS NULL

SUCCESS
→ last_tested_at IS NOT NULL
→ last_test_error IS NULL

FAILED
→ last_tested_at IS NOT NULL
→ last_test_error IS NOT NULL
```

### 3.3 Target Datasource

```text
status:
ENABLED
DISABLED

last_test_status:
UNTESTED
SUCCESS
PARTIAL
FAILED
```

```text
UNTESTED → last_tested_at/error 均为空
SUCCESS  → last_tested_at 非空，error 为空
PARTIAL  → last_tested_at 非空，error 非空
FAILED   → last_tested_at 非空，error 非空
```

`PARTIAL` 仅表示多 FE 聚合测试部分失败。

### 3.4 Target FE Endpoint

```text
last_test_status:
UNTESTED
SUCCESS
FAILED
```

使用与 Source 相同的时间/错误组合。

资源业务 `status` 与连接测试结果保持独立；`ENABLED` 不要求最近测试必须 `SUCCESS`。

## 4. Dataset / Contract Enum / CHECK

### 4.1 Standard Dataset

```text
status:
ACTIVE
VOID

last_sync_result:
CREATED
UPDATED
UNCHANGED
REACTIVATED
VOIDED
FAILED

validation_method_override:
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

`FAILED` 时 `last_sync_error` 非空；其他结果 `last_sync_error` 为空。

### 4.2 Standard Dataset Field

```text
conversion_status:
RESOLVED
UNSUPPORTED
```

```text
RESOLVED
→ doris_type/doris_nullable 非空

UNSUPPORTED
→ 正式 DDL/同步不得使用该版本
```

### 4.3 Field Conversion Contract

```text
status:
ACTIVE
RETIRED
```

### 4.4 Generic JDBC Mapping

```text
compatibility_level:
PASS
WARN
REJECT
```

### 4.5 Dataset Sync Policy

```text
schedule_mode:
INHERIT
MANUAL
EVERY_N_HOURS
CRON
```

字段组合固定：

```text
INHERIT
→ schedule_interval_hours IS NULL
→ schedule_cron IS NULL
→ schedule_timezone IS NULL

MANUAL
→ schedule_interval_hours IS NULL
→ schedule_cron IS NULL
→ schedule_timezone IS NULL

EVERY_N_HOURS
→ schedule_interval_hours BETWEEN 1 AND 8760
→ schedule_cron IS NULL
→ schedule_timezone IS NOT NULL

CRON
→ schedule_interval_hours IS NULL
→ schedule_cron 非空
→ schedule_timezone IS NOT NULL
```

Dataset Policy 是“创建 Task 的默认输入”，**不保存最终错峰 Quartz Cron**。最终 Cron 只在创建/编辑 Task 时生成并固化到 `sync_task.schedule_cron`。

## 5. Route Enum / CHECK

```text
source_object_type:
TABLE
VIEW
MATERIALIZED_VIEW

status:
DISABLED
ENABLED

structure_status:
NOT_CHECKED
PASSED
FAILED
OUTDATED
```

Structure 组合：

```text
NOT_CHECKED
→ structure_checked_at IS NULL
→ structure_error_summary IS NULL

PASSED
→ structure_checked_at IS NOT NULL
→ structure_error_summary IS NULL

FAILED
→ structure_checked_at IS NOT NULL
→ structure_error_summary IS NOT NULL

OUTDATED
→ structure_checked_at IS NOT NULL
→ structure_error_summary IS NULL
```

进入 OUTDATED 时保留最近结构核对时间，但清掉旧错误摘要。

`collection_route.status` 与 `structure_status` 是两个独立事实，**不建立数据库耦合 CHECK**；允许：

```text
status=ENABLED + structure_status=OUTDATED
```

是否允许创建/运行 Task 由业务 Gate 判断。

## 6. Task Enum / CHECK

```text
task_kind:
FULL_ONLY
FULL_THEN_INCREMENTAL

write_mode:
REPLACE_INSTITUTION_SCOPE
UPSERT

doris_key_model:
DUPLICATE_KEY
UNIQUE_KEY

schedule_mode:
MANUAL
EVERY_N_HOURS
CRON

schedule_source:
GLOBAL
DATASET
TASK
```

三种 Task 组合：

```text
FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY
→ incremental_field_code IS NULL
→ upper_bound_delay_minutes = 0
→ lookback_seconds = 0

FULL_THEN_INCREMENTAL + UPSERT + UNIQUE_KEY
→ incremental_field_code IS NOT NULL

FULL_ONLY + UPSERT + UNIQUE_KEY
→ incremental_field_code IS NULL
→ upper_bound_delay_minutes = 0
→ lookback_seconds = 0
```

Task Schedule：

```text
MANUAL
→ schedule_interval_hours IS NULL
→ schedule_cron IS NULL

EVERY_N_HOURS
→ schedule_interval_hours BETWEEN 1 AND 8760
→ schedule_cron 非空
→ schedule_timezone 非空

CRON
→ schedule_interval_hours IS NULL
→ schedule_cron 非空
→ schedule_timezone 非空
```

EVERY_N_HOURS 的最终错峰 Cron 保存在 Task。

`schedule_mode` 与 `schedule_enabled` 保持正交；不建立 `MANUAL → schedule_enabled=false` CHECK。

Quartz Projection 条件继续为：

```text
deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>'MANUAL'
AND schedule_cron 有效
```

## 7. Sync Execution Enum / CHECK

### 7.1 枚举

```text
operation_type:
NORMAL
RECOLLECT
BACKFILL

trigger_type:
SCHEDULED
MANUAL
EXTERNAL_API

status:
PENDING
RUNNING
LOADING
VALIDATING
SUCCEEDED
FAILED
CANCELLED

execution_scope:
FULL
INITIAL_FULL
INCREMENTAL
BACKFILL_TIME
BACKFILL_KEY

target_prepare_mode:
NONE
REPLACE_INSTITUTION_SCOPE

validation_method:
ROW_COUNT
ROW_COUNT_CHECKSUM

validation_source:
GLOBAL
DATASET
TASK
CONTRACT
```

`sync_execution.validation_source` **不增加 `FIXED`**。

### 7.2 Trigger CHECK

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

### 7.3 Operation CHECK

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

### 7.4 Range CHECK

```text
INCREMENTAL / BACKFILL_TIME
→ window_lower/window_upper 均非空
→ window_lower < window_upper
→ key_lower/key_upper 均为空

BACKFILL_KEY
→ window_lower/window_upper 均为空
→ key_lower/key_upper 均非空

FULL / INITIAL_FULL
→ window_lower/window_upper/key_lower/key_upper 均为空
```

### 7.5 Terminal / Error CHECK

```text
PENDING/RUNNING/LOADING/VALIDATING
→ finished_at IS NULL

SUCCEEDED/FAILED/CANCELLED
→ finished_at IS NOT NULL

SUCCEEDED
→ error_code/error_message IS NULL
→ rejected_row_count = 0

FAILED
→ error_code IS NOT NULL
```

### 7.6 Cancel CHECK

```text
cancel_requested_at IS NULL
→ cancel_requested_by IS NULL

cancel_requested_at IS NOT NULL
→ cancel_requested_by IS NOT NULL

status='CANCELLED'
→ cancel_requested_at IS NOT NULL
```

`CANCELLED` 只表示明确取消，不用于技术失败。

## 8. Load Batch Enum / CHECK

```text
status:
PENDING
LOADING
PROBING
SUCCEEDED
FAILED
CANCELLED

doris_state:
UNKNOWN
PREPARE
COMMITTED
VISIBLE
ABORTED
```

```text
SUCCEEDED
→ doris_state='VISIBLE'
→ visible_at IS NOT NULL
→ rejected_row_count=0
→ error_code/error_message IS NULL

FAILED
→ error_code IS NOT NULL

ABORTED
→ status='FAILED'

非 SUCCEEDED
→ visible_at IS NULL
```

`COMMITTED` 不是成功终态。

## 9. Precheck Enum / CHECK

```text
execution_status:
PENDING
EXTRACTING
VALIDATING
COMPLETED
FAILED
CANCELLED

result_status:
PASS
ISSUES

current_phase:
STRUCTURE
EXTRACT
VALIDATE
COMPLETE

raw_cleanup_status:
NOT_READY
PENDING
CLEANED
FAILED
```

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
```

RAW Cleanup：

```text
CLEANED → raw_cleaned_at IS NOT NULL
FAILED  → raw_cleanup_error IS NOT NULL
```

若结构阶段结束且从未产生 RAW 数据，终态可直接记为：

```text
raw_cleanup_status='CLEANED'
raw_cleaned_at=finished_at
```

不增加 `NOT_REQUIRED`。

## 10. Validation Enum / CHECK

### 10.1 枚举

```text
validation_scope:
SYNC_WINDOW
FULL_DATASET
CHANGE_WINDOW
DELETE_RECONCILIATION

trigger_type:
SYNC_GATE
MANUAL
MANUAL_RECHECK
SCHEDULED

validation_method:
ROW_COUNT
ROW_COUNT_CHECKSUM
DELETE_KEY_DIFF

validation_source:
GLOBAL
DATASET
TASK
CONTRACT
FIXED

status:
PENDING
RUNNING
COMPLETED
FAILED
CANCELLED

result:
PASS
MISMATCH
```

`FIXED` 只用于系统固定方法：

```text
DELETE_RECONCILIATION + DELETE_KEY_DIFF
```

### 10.2 Source CHECK

```text
DELETE_RECONCILIATION
→ validation_method='DELETE_KEY_DIFF'
→ validation_source='FIXED'
→ validation_source_revision IS NULL
→ validation_contract_forced=false

validation_source='FIXED'
→ validation_scope='DELETE_RECONCILIATION'
→ validation_method='DELETE_KEY_DIFF'

validation_source='CONTRACT'
→ validation_contract_forced=true
→ validation_source_revision IS NULL

validation_source IN ('GLOBAL','DATASET','TASK')
→ validation_contract_forced=false
```

### 10.3 Scope / Trigger CHECK

```text
SYNC_GATE
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ validation_source<>'FIXED'

MANUAL_RECHECK
→ execution_id IS NOT NULL
→ validation_scope='SYNC_WINDOW'
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')

MANUAL / SCHEDULED
→ execution_id IS NULL
→ validation_scope IN ('FULL_DATASET','CHANGE_WINDOW','DELETE_RECONCILIATION')
```

Delete Reconciliation：

```text
validation_scope='DELETE_RECONCILIATION'
→ execution_id IS NULL
→ validation_method='DELETE_KEY_DIFF'
→ validation_source='FIXED'
→ baseline_snapshot_run_id/current_snapshot_run_id 非空
→ difference_count/difference_ratio 非空
```

非 Delete Reconciliation：

```text
→ validation_method IN ('ROW_COUNT','ROW_COUNT_CHECKSUM')
→ baseline_snapshot_run_id/current_snapshot_run_id/difference_ratio 均为空
```

### 10.4 Terminal CHECK

```text
PENDING/RUNNING
→ result IS NULL
→ finished_at IS NULL

COMPLETED
→ result IN ('PASS','MISMATCH')
→ finished_at IS NOT NULL
→ error_code/error_message IS NULL

FAILED
→ result IS NULL
→ finished_at IS NOT NULL
→ error_code IS NOT NULL

CANCELLED
→ result IS NULL
→ finished_at IS NOT NULL
```

`ROW_COUNT_CHECKSUM` 必须有 `checksum_protocol_version`。

## 11. Message Outbox Enum / CHECK

```text
status:
PENDING
PUBLISHING
PUBLISHED
DEAD_LETTER

publish_scope:
FULL
INCREMENTAL
```

```text
max_attempts > 0
0 <= attempt_count <= max_attempts

PENDING
→ published_at IS NULL

PUBLISHING
→ last_attempt_at IS NOT NULL
→ published_at IS NULL

PUBLISHED
→ published_at IS NOT NULL
→ last_error_code/last_error_message IS NULL

DEAD_LETTER
→ published_at IS NULL
→ last_error_code IS NOT NULL
```

人工重发时：

```text
PUBLISHED/DEAD_LETTER
→ PENDING
→ attempt_count=0
→ published_at=NULL
→ clear last_error_*
```

`event_id` 继续沿用。

## 12. Delete Snapshot Enum / CHECK

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

```text
PENDING/EXTRACTING/WRITING/COMPARING
→ result_type IS NULL
→ finished_at IS NULL

COMPLETED
→ result_type 非空
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

## 13. Delete Apply Enum / CHECK

```text
status:
PENDING
RUNNING
SUCCEEDED
PARTIAL_FAILED
FAILED
CANCELLED
```

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

终态：

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

## 14. Audit Enum / CHECK

```text
actor_type:
LOCAL_USER
EXTERNAL_CLIENT
SCHEDULER
SYSTEM

source:
WEB
EXTERNAL_API
SCHEDULER
SYSTEM

result:
SUCCESS
FAILED
```

固定一一对应：

```text
LOCAL_USER      ↔ WEB
EXTERNAL_CLIENT ↔ EXTERNAL_API
SCHEDULER       ↔ SCHEDULER
SYSTEM          ↔ SYSTEM
```

Actor FK 组合：

```text
LOCAL_USER      → actor_user_id 非空，actor_client_id 为空
EXTERNAL_CLIENT → actor_client_id 非空，actor_user_id 为空
SCHEDULER/SYSTEM→ 两者均为空
```

```text
SUCCESS → error_code/error_message 均为空
FAILED  → error_code 非空
```

## 15. Alert Enum / CHECK

```text
channel_type:
DINGTALK
WECOM

message_format:
TEXT
MARKDOWN

condition_op:
EQ
NE
GT
GTE
LT
LTE

severity:
INFO
WARNING
CRITICAL

scope_type:
ALL
TASK

alert_delivery.status:
PENDING
SENDING
SUCCEEDED
FAILED
```

Scope：

```text
ALL  → scope_task_id IS NULL
TASK → scope_task_id IS NOT NULL
```

Delivery：

```text
PENDING → delivered_at IS NULL
SENDING → last_attempt_at IS NOT NULL + delivered_at IS NULL
SUCCEEDED → delivered_at IS NOT NULL + last_error_code/last_error IS NULL
FAILED → delivered_at IS NULL + last_error_code IS NOT NULL
```

## 16. External API Enum / CHECK

```text
authorization_mode:
ALL
SELECTED

operation_code:
TASK_ENSURE
TASK_RUN
TASK_DELETE
MESSAGE_RETRY

external_api_request.status:
PROCESSING
SUCCEEDED
FAILED
```

Request：

```text
PROCESSING
→ completed_at IS NULL

SUCCEEDED
→ completed_at IS NOT NULL
→ response_body IS NOT NULL
→ error_code/error_message IS NULL

FAILED
→ completed_at IS NOT NULL
→ error_code IS NOT NULL
```

不增加 `RECOVERING/RETRYING`；超时恢复继续操作原 `PROCESSING` 行。

## 17. 不由 CHECK 表达的规则

以下继续由事务/服务层表达，不新增状态表：

- Route `status` 与 `structure_status` 的业务 Gate。
- Dataset 是否具备真实业务主键决定能否选 `ROW_COUNT_CHECKSUM`。
- Task/Execution 的三种任务组合与具体 Dataset Contract 完整一致性。
- Sync Execution 与 Independent Validation 跨表互斥。
- Dataset/Route Hash 命中历史 Version 时复用旧 Version 的查询流程。
- External Client `ALL/SELECTED` 与授权关联行数量的一致性。

数据库 CHECK 只表达单行内可稳定验证的不变量。

## 18. 验收

- `SUCCEEDED` 与 `COMPLETED + result` 语义不混用。
- Resource 测试状态和时间/错误字段组合一致。
- Route Business Status 与 Structure Status 不被数据库强耦合。
- Dataset Sync Policy 不保存 EVERY_N_HOURS 最终 Cron；Task 保存最终 Cron。
- Execution Trigger/Operation/Range/Terminal/Cancel 组合可由 CHECK 阻止非法半状态。
- `sync_execution.validation_source` 仍只有 `GLOBAL/DATASET/TASK/CONTRACT`。
- `validation_run.validation_source` 增加 `FIXED`，且只用于 Delete Reconciliation。
- Precheck/Validation/Delete Snapshot 使用 `COMPLETED + result`。
- Outbox/Delete Apply/Audit/Alert/External Request 的终态时间和错误字段组合无歧义。
- 所有基础 count/ratio/port/hash/json CHECK 统一进入 V1。

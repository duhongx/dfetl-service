# P0 支撑对象 Review

> 状态：阶段 1 FK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Quartz：`spec/QUARTZ_JOBSTORE_REVIEW.md`

## 1. 范围

P0 支撑对象：

```text
本地管理员账号
Audit
Registered System Setting
Alert
External API
Quartz JDBC JobStore
```

支撑对象不得重新定义 Resource/Route/Task/Validation 主模型；阶段 1 最终签字前不创建 Flyway V1。

## 2. 本地管理员账号

P0 使用少量同权限管理员：

- 允许一个或多个账号，实际通常 1～3 人。
- 不建立 RBAC/角色/权限/机构数据权限。
- 账号不物理删除，只启用/停用。
- 当前登录账号不能停用自己；最后一个启用账号不能停用。
- 停用、重置密码使既有 Refresh Token 失效。
- 初始化 SQL 不写固定管理员密码/Hash。

账号管理：列表、新增、启停、重置密码。

## 3. User FK 统一规则

普通审计字段：

```text
created_by
updated_by
deleted_by
imported_by
retired_by
```

统一：

```text
→ app_user(id) ON DELETE SET NULL
```

业务运行责任字段：

```text
requested_by
requested_by_user_id
confirmed_by
triggered_by
cancel_requested_by
```

统一：

```text
→ app_user(id) ON DELETE RESTRICT
```

虽然 `app_user` 产品上不提供物理删除，但数据库仍显式区分“普通审计引用”和“运行责任引用”。

## 4. `audit_log`

业务写操作同时记录 SUCCESS/FAILED。

至少保存：

```text
actor_type / actor identity snapshot
source = WEB/EXTERNAL_API/SCHEDULER/SYSTEM
operation_code
target type/id/name snapshot
result
request/correlation id
client ip
脱敏 summary
error_code/error_message
occurred_at
```

FK：

```text
actor_user_id   → app_user(id)            ON DELETE SET NULL
actor_client_id → external_api_client(id) ON DELETE SET NULL
```

Audit 已保存 Actor Name Snapshot，因此主体引用允许为空。

不记录 Password/Hash、DB/RabbitMQ Credential、API Secret、HMAC Signature、完整 Authorization Header。Audit 追加写，不提供普通 Update/Delete。

## 5. `system_setting`

`system_setting` 只保存应用已注册 Key。

- 类型、默认值、范围、敏感性由统一 Setting Registry 定义。
- `revision` 乐观锁。
- 规范库 Password 可保存密文并掩码返回。
- RabbitMQ、应用数据库、JWT、Encryption Master Key 等部署 Secret 不进入本表。
- 普通 `created_by/updated_by → app_user ON DELETE SET NULL`。

Validation 全局默认唯一入口：

```text
validation.default_method
```

允许 `ROW_COUNT/ROW_COUNT_CHECKSUM`，注册默认 `ROW_COUNT`。

解析：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
→ Dataset 合同能力
```

不恢复 Validation Enabled/Tolerance/Lookback/Auto Revalidate/Fail Block/Override Mode。

## 6. Alert

P0 表：

```text
alert_channel
alert_rule
alert_rule_channel
alert_event
alert_delivery
```

### 6.1 Rule ↔ Channel

```text
alert_rule 1 ── N alert_rule_channel N ── 1 alert_channel
```

`alert_rule_channel` 使用：

```text
rule_id    → alert_rule(id)    ON DELETE CASCADE
channel_id → alert_channel(id) ON DELETE RESTRICT
created_by → app_user(id)      ON DELETE SET NULL
```

`PRIMARY KEY(rule_id,channel_id)`；反向索引 `(channel_id,rule_id)`。

### 6.2 `alert_rule`

Task Scope：

```text
scope_task_id → sync_task(id) ON DELETE RESTRICT
```

### 6.3 `alert_event`

Rule 配置可删除，但 Event 保存完整 Rule Snapshot：

```text
rule_id → alert_rule(id) ON DELETE SET NULL
```

运行来源属于历史事实，全部 RESTRICT：

```text
task_id                → sync_task(id)
execution_id           → sync_execution(id)
precheck_run_id        → precheck_run(id)
validation_run_id      → validation_run(id)
message_outbox_id      → message_outbox(id)
delete_snapshot_run_id → delete_snapshot_run(id)
```

为上述可空来源 FK 分别建立反向索引。

### 6.4 `alert_delivery`

```text
event_id   → alert_event(id)   ON DELETE CASCADE
channel_id → alert_channel(id) ON DELETE SET NULL
```

Delivery 保存 Channel Name/Type Snapshot，因此历史 Channel 引用允许为空。

Alert 不建设确认、认领、工单、审批、逐次投递明细。

## 7. External API

P0 表：

```text
external_api_client
external_api_client_institution
external_api_request_nonce
external_api_request
```

Client 不物理删除，只启停；Secret 重置后旧值立即失效，不支持双 Secret。

### 7.1 Institution Scope

```text
external_api_client_institution.client_id
→ external_api_client(id) ON DELETE RESTRICT

institution_id
→ institution(id) ON DELETE RESTRICT

created_by
→ app_user(id) ON DELETE SET NULL
```

PK `(client_id,institution_id)`；反向索引 `(institution_id,client_id)`。

### 7.2 Nonce

```text
external_api_request_nonce.client_id
→ external_api_client(id) ON DELETE RESTRICT
```

`UNIQUE(client_id,nonce)` 覆盖 FK 子列；Nonce 保留 1 小时。

### 7.3 Idempotency Request

```text
external_api_request.client_id
→ external_api_client(id) ON DELETE RESTRICT
```

必须提供：

```text
UNIQUE(client_id,request_id)
```

这不仅承担 External API 幂等，也作为：

```text
sync_execution(external_client_id,external_request_id)
→ external_api_request(client_id,request_id)
```

的父键。

外部写操作统一 Request ID；P0 不做应用层 Rate Limit。

## 8. Quartz JDBC JobStore

Quartz 只是当前 Task 调度配置的可重建投影。

业务事实：

```text
sync_task.deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>MANUAL
AND schedule_cron 有效
```

JobDataMap 只保存 `taskId`；Trigger 后重新读当前 Task 并创建 Execution Snapshot。

Quartz 官方 PostgreSQL 表固定 11 张：

```text
qrtz_job_details
qrtz_triggers
qrtz_simple_triggers
qrtz_cron_triggers
qrtz_simprop_triggers
qrtz_blob_triggers
qrtz_calendars
qrtz_paused_trigger_grps
qrtz_fired_triggers
qrtz_scheduler_state
qrtz_locks
```

使用项目锁定 Quartz 版本官方 DDL，不自行重设计其 FK。

## 9. 支撑对象清单

```text
app_user
audit_log
system_setting

alert_channel
alert_rule
alert_rule_channel
alert_event
alert_delivery

external_api_client
external_api_client_institution
external_api_request_nonce
external_api_request
```

共 12 张，计入 DFETL P0 39 张表。

## 10. 明确不建立

```text
RBAC 表
Global/Dataset/Task Validation Policy 表
Scheduler Reconciliation 表
External API Rate Limit/Quota 表
Secret History/Dual Key 表
Alert Workflow/Approval 表
```

## 11. 当前状态

- [x] 支撑对象业务范围确认。
- [x] `alert_rule_channel` 保留。
- [x] 普通审计用户 SET NULL / 运行责任用户 RESTRICT 已确认。
- [x] Alert/External API FK 纳入最终 FK Matrix。
- [x] External Request `(client_id,request_id)` 同时作为 Execution 外部请求来源父键。
- [x] Quartz 官方表固定 11 张并排除自定义 FK Review。
- [ ] Business/Concurrency Unique Matrix。
- [ ] Status/Enum/CHECK Matrix。
- [ ] Delete Behavior Matrix。
- [ ] Phase 1 Final Review。

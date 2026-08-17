# P0 支撑对象 Review

> 状态：阶段 1 FK + Unique + Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Quartz：`spec/QUARTZ_JOBSTORE_REVIEW.md`

## 1. 范围

P0 支撑对象：

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
Quartz 官方 JobStore
```

共 12 张 DFETL 支撑表；Quartz 11 张官方表单独统计。

## 2. `app_user`

少量同权限管理员，通常 1～3 人；不建设 RBAC。

```text
UNIQUE INDEX uk_app_user_username_ci
ON app_user(lower(username))
```

- 账号不物理删除，只启用/停用。
- 当前用户不能停用自己。
- 最后一个启用账号不能停用。
- 停用/重置密码使 Refresh Token 失效。
- 初始化 SQL 不写固定管理员密码或 Hash。

## 3. User FK

普通审计：

```text
created_by/updated_by/deleted_by/imported_by/retired_by
→ app_user(id) ON DELETE SET NULL
```

运行责任：

```text
requested_by/requested_by_user_id/confirmed_by/triggered_by/cancel_requested_by
→ app_user(id) ON DELETE RESTRICT
```

## 4. `audit_log`

枚举：

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

Actor/Source 固定一一对应：

```text
LOCAL_USER      ↔ WEB
EXTERNAL_CLIENT ↔ EXTERNAL_API
SCHEDULER       ↔ SCHEDULER
SYSTEM          ↔ SYSTEM
```

Actor FK 组合：

```text
LOCAL_USER
→ actor_user_id 非空
→ actor_client_id 为空

EXTERNAL_CLIENT
→ actor_client_id 非空
→ actor_user_id 为空

SCHEDULER/SYSTEM
→ actor_user_id/actor_client_id 均为空
```

Result：

```text
SUCCESS → error_code/error_message 均为空
FAILED  → error_code 非空
```

FK：

```text
actor_user_id   → app_user(id) SET NULL
actor_client_id → external_api_client(id) SET NULL
```

Audit 保存 Actor Name Snapshot，追加写，不提供普通 Update/Delete；不设置业务 Unique。

## 5. `system_setting`

`setting_key` 是 PK，只允许应用注册 Key；Value 类型、默认值、范围和敏感性由 Setting Registry 定义。

Validation 全局默认：

```text
validation.default_method
= ROW_COUNT / ROW_COUNT_CHECKSUM
默认 ROW_COUNT
```

不恢复独立 Validation Policy Table、Enable/Tolerance/Lookback/Auto Revalidate 等旧配置。

## 6. Alert

### 6.1 Channel

```text
channel_type:
DINGTALK
WECOM

message_format:
TEXT
MARKDOWN

last_test_status:
UNTESTED
SUCCESS
FAILED
```

Channel Test 使用 Resource 单点测试相同组合：

```text
UNTESTED → last_tested_at/error 均为空
SUCCESS  → last_tested_at 非空，error 为空
FAILED   → last_tested_at 非空，error 非空
```

Business Unique：

```text
UNIQUE INDEX uk_alert_channel_name_ci
ON alert_channel(lower(name))
```

### 6.2 Rule

```text
condition_op:
EQ / NE / GT / GTE / LT / LTE

severity:
INFO / WARNING / CRITICAL

scope_type:
ALL / TASK
```

```text
ALL  → scope_task_id IS NULL
TASK → scope_task_id IS NOT NULL
```

Business Unique：

```text
UNIQUE INDEX uk_alert_rule_name_ci
ON alert_rule(lower(name))
```

### 6.3 Rule ↔ Channel

```text
PRIMARY KEY(rule_id,channel_id)
rule_id → alert_rule(id) CASCADE
channel_id → alert_channel(id) RESTRICT
```

### 6.4 Event

```text
UNIQUE(event_uuid)
severity = INFO/WARNING/CRITICAL
```

`source_type` 与对应业务来源 FK 必须通过 CHECK 一一匹配；`SYSTEM` 允许全部来源 FK 为空。

### 6.5 Delivery

```text
status:
PENDING
SENDING
SUCCEEDED
FAILED
```

```text
PENDING
→ delivered_at IS NULL

SENDING
→ last_attempt_at IS NOT NULL
→ delivered_at IS NULL

SUCCEEDED
→ delivered_at IS NOT NULL
→ last_error_code/last_error IS NULL

FAILED
→ delivered_at IS NULL
→ last_error_code IS NOT NULL
```

Business Unique：

```text
UNIQUE(event_id,channel_id)
```

Alert 不建设确认/认领/工单/审批/逐次投递明细。

## 7. External API

### 7.1 Client

```text
authorization_mode:
ALL
SELECTED
```

唯一程序身份：

```text
UNIQUE(client_id)
```

`client_name` 只是可编辑显示名称，可重复，不建立 Name Unique。

Client 不物理删除，只启停；Secret Reset 后旧 Secret 立即失效，不保存双 Key。

### 7.2 Institution Scope

```text
PRIMARY KEY(client_id,institution_id)
```

`ALL/SELECTED` 与关联行数量由服务事务检查，不引入状态表。

### 7.3 Nonce

```text
UNIQUE(client_id,nonce)
expires_at > created_at
```

Nonce 保留 1 小时。

### 7.4 `external_api_request`

```text
operation_code:
TASK_ENSURE
TASK_RUN
TASK_DELETE
MESSAGE_RETRY

status:
PROCESSING
SUCCEEDED
FAILED
```

Business Unique：

```text
UNIQUE(client_id,request_id)
```

状态 CHECK：

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

不增加 `RECOVERING/RETRYING` 状态；超时恢复继续操作原 `PROCESSING` 行并核对真实副作用。

## 8. Quartz

Quartz 只作为当前 Task 调度配置的可重建投影。

```text
sync_task.deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>'MANUAL'
AND schedule_cron 有效
```

Quartz 11 张官方 PostgreSQL 表按项目锁定版本的官方 DDL 建立，不参与 DFETL 自定义 FK/Unique/Status CHECK Review。

## 9. 明确不建立

```text
RBAC
Global/Dataset/Task Validation Policy
Scheduler Reconciliation
External API Rate Limit/Quota
Secret History/Dual Key
Alert Workflow/Approval
External Client Name Unique
```

## 10. 验收

- Audit Actor/Source/Result 组合无歧义。
- Alert Channel 测试状态与时间/错误字段一致。
- Alert Delivery 终态时间/错误组合一致。
- External Request `PROCESSING/SUCCEEDED/FAILED` 组合严格。
- External Client Name 不唯一，只保证 Client ID。
- Support Object 所有状态/CHECK 与 `P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` 一致。

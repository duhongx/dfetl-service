# P0 支撑对象 Review

> 状态：阶段 1 P0 支撑对象业务范围已确认；已按当前 Validation/Task 模型收口  
> 首次 Review：2026-08-14  
> 最近收口：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标模型：`spec/TARGET_METADATA_MODEL.md`  
> Validation：`spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md`  
> Quartz：`spec/QUARTZ_JOBSTORE_REVIEW.md`

## 1. Review 原则

P0 支撑对象包括：

```text
本地账号
操作审计
system_setting
告警
External API
Quartz JDBC JobStore
```

统一规则：

1. 老系统真实业务能力在新产品没有删除时继续保留，但按当前领域模型重写。
2. 不复制孤立旧表、旧状态机或未来假设。
3. 已被后续专项 Review 废止的 Task Version/Validation Policy 等旧结构不得继续作为本文件当前模型。
4. 支撑对象不应重新定义 Resource/Route/Task/Validation 主模型。
5. 阶段 1 最终签字前不创建 Flyway V1。

## 2. 本地管理员账号

P0 使用简单同权限管理员模型：

- 允许一个或多个管理员账号，实际规模通常 1～3 人。
- 所有本地账号权限相同。
- 不建立 `role/permission/user_role/role_permission` 等 RBAC 表。
- 不建设角色、权限、机构级数据权限或菜单权限配置器。
- 保留登录、Access/Refresh Token、Refresh Token 失效、退出和修改密码。
- 账号不物理删除，只启用/停用。
- 当前登录账号不能停用自己。
- 最后一个启用账号不能被停用。
- 停用或重置密码必须使旧 Refresh Token 失效。
- Flyway/初始化 SQL 不写固定管理员密码或固定生产 Password Hash。

账号管理业务能力已确认：

```text
列表
新增
启用/停用
重置密码
```

具体导航入口位置仍以 `PENDING_DECISIONS.md` 的前端信息架构确认结果为准；功能本身不建设 RBAC。

## 3. `audit_log`

业务写操作同时记录成功和失败。

至少保存：

```text
actor type / actor identity snapshot
source = WEB/EXTERNAL_API/SCHEDULER/SYSTEM
operation_code
target type/id/name snapshot
result = SUCCESS/FAILED
request/correlation id
client ip
脱敏 summary
error_code/error_message
occurred_at
```

固定边界：

- 本地账号、External Client、Scheduler、System Task 均可成为 Actor。
- 目标后续改名/逻辑删除不破坏历史审计理解。
- 普通只读查询默认不写业务 Audit。
- 登录失败、无效 Token、HMAC 失败进入 Security Log。
- Password/Hash、DB Password、RabbitMQ Credential、API Secret、签名原文和完整 Auth Header 不得写 Audit。
- `audit_log` 追加写，不提供普通 Update/Delete。
- 不建设 Audit 审批/处理状态/明细子表。

## 4. `system_setting`

### 4.1 定位

`system_setting` 是**已注册系统设置**的持久化，不是用户自由键值中心。

固定规则：

1. `setting_key` 稳定且大小写敏感；接口只允许代码注册的 Known Key。
2. Value 可使用文本物理存储，但类型、默认值、范围、是否敏感、是否可空由统一 Setting Registry 定义。
3. Bool/Integer/Duration/Enum/URL/Port/数量等统一验证。
4. 使用 `revision` 乐观锁，防止多页面静默覆盖。
5. 医共体名称/编码继续放在 Setting；一个部署只服务一个医共体。
6. 规范库连接可页面维护；Password 保存密文并只返回掩码。
7. RabbitMQ、应用数据库、JWT、Encryption Master Key 等部署级 Secret 不进入 `system_setting`。
8. Field Contract、Datasource、Doris、External Client 等已有领域对象不重复塞进 Setting。
9. 所有 Setting 写操作成功/失败均 Audit；敏感值只记录“已变更”，不记录值。
10. 老库任意 Key 不整体迁移，新库只迁移最终注册项。

### 4.2 Validation 全局默认的唯一入口

旧描述：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
```

**全部废止。**

当前全局默认直接注册为：

```text
setting_key = validation.default_method
```

允许：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认：

```text
ROW_COUNT
```

数据库没有该 Setting 行时，直接使用注册默认值；Flyway V1 不要求插入单例 Policy Row。

完整解析：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

运行中 Execution 已保存启动 Validation Snapshot，因此修改全局默认只影响**后续新 Execution**，不热更新正在运行或历史 Execution；这不是 Task Version/“下一版本生效”机制。

### 4.3 不进入 `system_setting` 的 Validation 旧配置

```text
validation enabled/disabled
row tolerance
validation lookback
auto revalidate
fail_block
override_mode
```

这些能力已经从目标产品删除，不作为任意 Setting Key 恢复。

## 5. Alert

P0 保留：

```text
alert_channel
alert_rule
alert_rule_channel
alert_event
alert_delivery
```

其中 `alert_rule_channel` 是 Alert Rule 与 Channel 的结构化多对多关系：

```text
alert_rule 1 ── N alert_rule_channel N ── 1 alert_channel
```

固定规则：

- 一个 Rule 可选择一个或多个 Channel，不把 Channel ID 数组塞入 JSON。
- `alert_rule_channel` 至少保存 `rule_id + channel_id + created_at/created_by`，以 `(rule_id,channel_id)` 为主键/唯一关系。
- Rule 删除时关联行可 `CASCADE`；Channel 仍被规则引用时不得物理删除，具体删除行为在最终 Delete Matrix 冻结。
- Rule 命中产生一条 `alert_event`。
- 每个目标 Channel 产生一条 `alert_delivery`。
- Event 保存 Rule/Severity/Source/业务关联对象/Title/Summary/Time Snapshot。
- Delivery 保存 Channel Snapshot、状态、汇总 Attempt Count、最后 Attempt、成功时间、脱敏 Error。
- 一个 Channel 失败不影响其他 Channel。
- 不建设确认、认领、处理人、工单、审批流程。
- 不建设逐次投递明细表。
- Webhook URL/Secret 和敏感 Response 不进入 Event/Audit。
- Alert 实现优先级最低，但仍属于最终交付范围。

`alert_rule_channel` 是最终 P0 PostgreSQL 表清单的一部分，不属于可省略的实现细节。

## 6. External API

详细合同以 `EXTERNAL_API_REVIEW.md` 为准。

当前支撑结论：

- 请求可批量，但内部固定拆成 Institution + Dataset 原子目标。
- Task 已存在返回 `EXISTS`，不隐式修改当前 Task 配置。
- 新 Task 直接插入 `sync_task`，不创建 Task Version。
- `runAfterCreate` 只运行本次新建 Task；Execution 启动时固定快照。
- Client Institution Scope 支持 `ALL/SELECTED`。
- Client 不物理删除，只启停和 Reset Secret。
- Secret 明文创建/重置时只展示一次；Reset 后旧值立即失效，不支持双 Key。
- 外部写操作统一要求 `requestId`，按 `(client_id,request_id)` 幂等。
- Timestamp Window ±5 分钟；Nonce 保留 1 小时。
- `/api/v1/**` 使用独立 HMAC Security Chain。
- P0 不做应用层 Rate Limit；通用 Traffic Protection 由 Nginx/Ingress/Gateway 提供。

最小对象：

```text
external_api_client
external_api_client_institution
external_api_request_nonce
external_api_request
```

## 7. Quartz JDBC JobStore

Quartz 只作为当前 Task 调度配置的**可重建运行投影**。

唯一业务事实：

```text
sync_task.deleted_at IS NULL
AND sync_task.schedule_enabled = true
AND sync_task.schedule_mode != MANUAL
AND sync_task.schedule_cron 有效
```

明确不存在：

```text
sync_task_version
sync_task.current_version_id
“当前 Task Version Cron”
```

固定行为：

1. `schedule_enabled=false`、MANUAL、无有效 Cron 或 Task 逻辑删除时删除 Job/Trigger。
2. 当前 `sync_task.schedule_cron/timezone` 变化时 reschedule。
3. 启动/周期对账读取当前 `sync_task`，补建/更新/删除 Quartz Projection。
4. Quartz 状态不得反向修改 Task。
5. Misfire 固定 `DO_NOTHING`；错过不补跑。
6. Task 已有活动 Execution 时跳过本次 Trigger，不排队、不追赶。
7. JobDataMap 只保存 `taskId`。
8. Trigger 后重新读取当前 Task，创建 `sync_execution`，并在创建时固定 Task/Route/Dataset/Validation/Message Snapshot。
9. 真实执行并发由 `sync_execution` 数据库唯一约束保证。
10. Quartz 官方表位于新独立 PostgreSQL `df_etl` Schema，显式 Table Prefix、独立 Pool、Clustered Mode。
11. 老系统 Quartz Runtime 不迁移。

Quartz 官方 PostgreSQL JobStore 表固定单独统计为 11 张：

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

## 8. 当前支撑对象目标表

DFETL 支撑对象：

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

Quartz 官方 PostgreSQL `qrtz_*` 11 张表单独统计，不计入 DFETL 39 张领域/控制表。

不建立：

```text
RBAC 表
global_validation_policy
dataset_validation_policy
task_validation_policy
Scheduler Reconciliation 表
External API Rate Limit 表
Secret History/Dual Key 表
Alert Workflow/Approval 表
```

## 9. 当前状态

- [x] 简单同权限管理员业务范围确认。
- [x] Audit 成功/失败双向记录确认。
- [x] `system_setting` 收敛为 Registered Setting。
- [x] Validation 全局默认改为 `validation.default_method`，不使用 Policy Table。
- [x] Alert 最小 Event/Delivery History 确认。
- [x] `alert_rule_channel` 作为 Rule↔Channel 多对多关系保留并计入最终 P0 表清单。
- [x] External API 业务边界、Auth、Idempotency、Client Lifecycle 确认。
- [x] External API 已去除 Task Version 当前语义。
- [x] Quartz 已改为直接读取当前 `sync_task`，不读取 Task Version。
- [x] Quartz 官方 PostgreSQL JobStore 表清单固定为 11 张。
- [ ] 最终全量 FK/Unique/Enum/Delete Matrix 统一核对。
- [ ] 阶段 1 Final Review。

本文件只记录阶段 1 Review 结论，不创建 Flyway、不修改当前数据库。

# P0 支撑对象 Review

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> Quartz：`spec/QUARTZ_JOBSTORE_REVIEW.md`

## 1. 范围

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

少量同权限管理员，不建设 RBAC。

```text
UNIQUE INDEX uk_app_user_username_ci ON app_user(lower(username))
```

删除行为：

```text
STATE_ONLY
→ enabled=false
```

不提供物理 DELETE，不增加 `deleted_at`；当前用户不能停用自己，最后一个启用账号不能停用。停用/重置密码使 Refresh Token 失效。

普通审计 User FK 可 `SET NULL`，运行责任 User FK 使用 `RESTRICT`，但这不改变产品上账号不可删除的规则。

## 3. `audit_log`

追加写业务审计：

```text
actor_type: LOCAL_USER / EXTERNAL_CLIENT / SCHEDULER / SYSTEM
source: WEB / EXTERNAL_API / SCHEDULER / SYSTEM
result: SUCCESS / FAILED
```

保存 Actor Name Snapshot；Actor FK 可 SET NULL。

删除：

```text
PERMANENT_HISTORY
```

不提供 Update/Delete API，不做自动 PostgreSQL retention。

## 4. `system_setting`

只保存应用注册 Key；`setting_key` 是 PK。

删除行为：

- 不提供通用 `DELETE /settings/{key}`。
- 页面只提供读取、修改、恢复默认。
- “恢复默认”由 Setting Registry 计算并通过受控 UPDATE/UPSERT 表达，不用删除数据库行作为产品操作。
- 数据库没有可选 Setting Row 时仍可使用注册默认，但正常维护不暴露自由 Row Delete。

Validation 全局默认仍为：

```text
validation.default_method = ROW_COUNT / ROW_COUNT_CHECKSUM
```

## 5. Alert

### 5.1 `alert_rule`

允许物理删除，不增加 `deleted_at`。

```text
alert_rule_channel.rule_id → CASCADE
alert_event.rule_id → SET NULL
```

Event 已保存 Rule Name/Metric/Rule Snapshot/Severity，因此 Rule 删除不破坏历史。

### 5.2 `alert_channel`

允许物理删除，但当前仍被 Rule 使用时：

```text
alert_rule_channel.channel_id → RESTRICT
```

流程：

```text
先从所有当前 Rule 移除 Channel
→ 再删除 Channel
```

历史 Delivery：

```text
alert_delivery.channel_id → SET NULL
```

并保留 Channel Name/Type Snapshot。

### 5.3 `alert_rule_channel`

纯当前关系配置：新增/删除关系行就是 Rule Channel 编辑。

```text
PRIMARY KEY(rule_id,channel_id)
```

### 5.4 `alert_event` / `alert_delivery`

```text
PERMANENT_HISTORY
```

不提供删除/自动 retention。虽然 `alert_delivery.event_id → alert_event ON DELETE CASCADE` 仍保留为 FK 结构行为，但 P0 正常业务不物理删除 Event，因此不会触发历史级联清理。

## 6. External API

### 6.1 `external_api_client`

稳定程序身份：

```text
UNIQUE(client_id)
client_name 可重复
```

删除行为：

```text
STATE_ONLY
→ enabled=false
```

不提供物理 DELETE，不增加 `deleted_at`。Secret Reset 后旧 Secret 立即失效。

### 6.2 `external_api_client_institution`

纯当前授权关系，可物理增删。

```text
SELECTED → ALL
```

时清空 Institution 关系；授权变更历史由 Audit 解释。

### 6.3 `external_api_request_nonce`

```text
TTL_CLEANUP
```

固定：

```text
UNIQUE(client_id,nonce)
expires_at = created_at + 1 hour
expires_at < now() → 小批量 DELETE
```

Nonce 清理失败只告警。

### 6.4 `external_api_request`

承担长期幂等：

```text
UNIQUE(client_id,request_id)
```

```text
PERMANENT_HISTORY
```

不自动删除或复用历史 Request ID；长期追溯与 Audit 共同承担。

## 7. Quartz

Quartz 是当前 Task 调度配置的：

```text
REBUILDABLE_PROJECTION
```

业务事实：

```text
sync_task.deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>'MANUAL'
AND schedule_cron 有效
```

因此：

```text
Task pause / MANUAL / logical delete
→ 删除对应 Quartz Job/Trigger

Task resume / 调度重新有效
→ 从当前 sync_task 重建
```

Quartz Runtime 不作为业务历史，也不迁移老系统 Quartz Runtime。

## 8. User FK 统一规则

普通审计：

```text
created_by/updated_by/deleted_by/imported_by/retired_by
→ app_user SET NULL
```

运行责任：

```text
requested_by/requested_by_user_id/confirmed_by/triggered_by/cancel_requested_by
→ app_user RESTRICT
```

这只是数据库删除完整性规则，不代表 `app_user` 提供物理删除入口。

## 9. 支撑对象删除模式总览

| 表 | 删除模式 |
| --- | --- |
| `app_user` | 只停用 |
| `audit_log` | 永久历史 |
| `system_setting` | 无通用 DELETE |
| `alert_channel` | 可物理删除；当前 Rule 引用时 RESTRICT |
| `alert_rule` | 可物理删除；Event Snapshot 保历史 |
| `alert_rule_channel` | 当前关系可物理删除 |
| `alert_event` | 永久历史 |
| `alert_delivery` | 永久历史 |
| `external_api_client` | 只停用 |
| `external_api_client_institution` | 当前关系可物理删除 |
| `external_api_request_nonce` | 1 小时 TTL |
| `external_api_request` | 永久历史 |

## 10. 验收

- User/External Client 无删除入口，只启停。
- System Setting 无自由 DELETE。
- Alert Rule/Channel 可物理删且 Snapshot 保持历史可解释。
- Event/Delivery 永久保留。
- External Client Institution 是当前授权关系，可增删。
- Nonce 1 小时 TTL 自动清理。
- External Request 长期保留幂等。
- Quartz Job/Trigger 随 Task 当前调度状态删除/重建。

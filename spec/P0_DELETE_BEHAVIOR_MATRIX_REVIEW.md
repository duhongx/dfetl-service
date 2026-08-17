# P0 Delete Behavior Matrix Review

> 状态：阶段 1 第 5 项最终一致性 Review 已确认  
> 确认日期：2026-08-17  
> 表清单：39 张 DFETL P0 表；Quartz 11 张官方表单独管理  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 限制：本文是 Flyway V1、Service 删除行为和前端危险操作的统一基线，不是 SQL；阶段 1 最终签字前不创建 `V1__baseline.sql`。

## 1. 删除语义与 FK `ON DELETE` 必须分开

业务上的删除能力：

```text
用户/系统是否允许删除某个对象
```

数据库 FK 的 `ON DELETE`：

```text
父行真的发生物理 DELETE 时，数据库如何保护/处理子引用
```

两者不是同一个概念。

例如：

```text
app_user
```

业务上不提供物理 DELETE，只允许停用；但普通审计字段仍统一声明：

```text
created_by/updated_by/... → app_user(id) ON DELETE SET NULL
```

这是数据库完整性边界，不代表产品提供删除账号入口。

## 2. P0 删除行为分类

P0 只使用以下删除/失效模式：

```text
PHYSICAL_IF_UNREFERENCED
= 未引用时允许物理删除；有引用时数据库 RESTRICT，产品引导停用

LOGICAL_DELETE
= 保留主对象和全部历史，设置 deleted_at/deleted_by

STATE_ONLY
= 不删除，通过 ENABLED/DISABLED、ACTIVE/VOID、ACTIVE/RETIRED 等状态表达失效

PERMANENT_HISTORY
= 不提供业务 DELETE，也不自动按保留期清理 PostgreSQL 行

CURRENT_CONFIG_DELETE
= 当前配置/关系可直接物理删除

TTL_CLEANUP
= 明确定义的短期技术状态按 TTL 物理清理

REBUILDABLE_PROJECTION
= 不是业务事实，可按业务源状态删除并重建
```

不因为“未来可能需要归档”预先增加 `archived/deleted_at/retention_status` 等字段。

## 3. Resource：无引用可物理删除，有引用只能停用

| 表 | 业务删除模式 | 有引用时 | 失效替代 | 备注 |
| --- | --- | --- | --- | --- |
| `institution` | `PHYSICAL_IF_UNREFERENCED` | RESTRICT | `status=DISABLED` | 不增加 `deleted_at` |
| `business_catalog` | `PHYSICAL_IF_UNREFERENCED` | RESTRICT | `status=DISABLED` | 不增加 `deleted_at` |
| `source_datasource` | `PHYSICAL_IF_UNREFERENCED` | RESTRICT | `status=DISABLED` | 一旦进入 Route/Route Version/历史链后只能停用 |
| `target_datasource` | `PHYSICAL_IF_UNREFERENCED` | RESTRICT | `status=DISABLED` | 一旦被 Route/Version/Delete Snapshot 等引用后只能停用 |
| `target_datasource_fe_endpoint` | `CURRENT_CONFIG_DELETE` | 不承担独立历史 | 删除当前 FE | Parent Target 真正物理删除时允许 CASCADE |

固定规则：

1. Resource 不增加逻辑删除字段。
2. 删除前由 Service 做引用统计并返回可读阻塞原因；数据库 FK 作为最终防线。
3. 已有历史引用时不允许为了“删干净”级联清历史。
4. 停用不影响历史解释，但新建 Route/Task 和执行启动按当前业务 Gate 检查资源可用性。

## 4. Dataset / Version / Field / Conversion Contract：定义历史永久保留

| 表 | 删除模式 | 失效/替代 |
| --- | --- | --- |
| `standard_dataset` | `STATE_ONLY` | `ACTIVE → VOID` |
| `standard_dataset_version` | `PERMANENT_HISTORY` | 无 |
| `standard_dataset_field` | `PERMANENT_HISTORY` | 无 |
| `field_conversion_contract` | `STATE_ONLY` | `ACTIVE → RETIRED` |
| `field_conversion_rule` | `PERMANENT_HISTORY` | 随不可变 Contract 长期保留 |
| `dataset_sync_policy` | 不提供单独 DELETE | 跟随 Dataset 当前配置维护 |
| `dataset_message_policy` | 不提供单独 DELETE | `enabled=false` 表示关闭消息 |

原因：

```text
Dataset Version / Field / Contract
```

承担 Route Version、Task 当前配置和运行启动快照的历史解释，同时 Unique Matrix 已确认：

```text
相同 definition_hash / contract_hash
→ 复用历史不可变 Version
```

因此这些历史定义不能被 retention 清理。

FK 中仍可保留：

```text
field_conversion_rule → field_conversion_contract ON DELETE CASCADE
dataset_sync_policy → standard_dataset ON DELETE CASCADE
dataset_message_policy → standard_dataset ON DELETE CASCADE
```

它们只表达“如果父行真的被数据库物理删除”的结构行为；P0 正常业务流程不物理删除 Dataset/Contract，因此这些 CASCADE 不构成业务删除入口。

## 5. Route / Task：逻辑删除

### 5.1 `collection_route`

```text
删除模式 = LOGICAL_DELETE
```

流程：

```text
检查活动 Precheck / Task 等业务阻塞
→ 设置 deleted_at/deleted_by
→ Route 不再作为当前可选对象
→ 保留 collection_route_version / field resolution / 历史运行解释
```

逻辑删除释放：

```text
(institution_id,dataset_id) WHERE deleted_at IS NULL
```

业务唯一，使未来可创建新的 Route ID，而旧 Route 历史仍保持原身份。

不物理删除 `collection_route_version` 或 `route_field_resolution`。

### 5.2 `sync_task`

```text
删除模式 = LOGICAL_DELETE
```

流程：

```text
锁定 Task
→ 有活动 sync_execution 时拒绝
→ 设置 deleted_at/deleted_by
→ schedule_enabled=false
→ 删除 Quartz Job/Trigger 投影
→ 保留 Watermark 和全部运行历史
```

逻辑删除释放：

```text
(institution_id,dataset_id) WHERE deleted_at IS NULL
```

以后可创建新 Task ID；旧 Execution/Validation/Outbox/Delete History 继续引用旧 Task。

改变 Institution/Dataset 不做 UPDATE，固定为：

```text
逻辑删除旧 Task
→ 新建新身份 Task
```

## 6. `task_watermark`：当前状态行可显式清除，但 Task 删除不级联

`task_watermark` 不是历史表，只表达当前正式水位。

允许的物理删除只有明确业务操作：

```text
“清除水位”
→ DELETE task_watermark WHERE task_id=?
→ 写 audit_log
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

Task 逻辑删除时：

```text
不自动删除 task_watermark
```

FK 继续：

```text
task_watermark.task_id → sync_task(id) ON DELETE RESTRICT
```

从而任何数据库层误物理删除 Task 都不会顺带清掉 Watermark。

## 7. Runtime / Audit / Idempotency / Alert History：永久保留 PostgreSQL 元数据

以下对象 P0 不提供普通 DELETE API，也不设置自动 PostgreSQL retention：

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox

delete_snapshot_run
task_delete_snapshot_state
delete_apply_run

audit_log
external_api_request

alert_event
alert_delivery
```

统一模式：

```text
PERMANENT_HISTORY
```

固定原因：

- Execution/Batch 是真实同步事实。
- Precheck/Validation 是质量检查事实。
- Outbox 是成功 Execution 后的消息发布事实。
- Delete Snapshot/Apply 是删除识别和人工应用审计事实。
- Audit 是业务写操作事实。
- External Request 承担长期 `(client_id,request_id)` 幂等和追溯。
- Alert Event/Delivery 是通知历史。

P0 不建设：

```text
history archive table
retention status
cold storage state machine
scheduled PostgreSQL history purge
```

需要控制的大数据量不放 PostgreSQL 明细：Precheck RAW、Delete Snapshot Key/Diff、完整应用日志分别由 Doris/日志系统承担。

## 8. 当前配置 / 关系对象：允许物理删除

### 8.1 `target_datasource_fe_endpoint`

从当前 Target 移除 FE 即物理删除该 Endpoint。

### 8.2 `alert_rule_channel`

修改 Rule 通知渠道时物理删除关系行：

```text
(rule_id,channel_id)
```

Rule 真的物理删除时关系行 `ON DELETE CASCADE`。

### 8.3 `external_api_client_institution`

`SELECTED` 授权范围变化时物理增删关系行。

切换：

```text
SELECTED → ALL
```

时必须清空现有 Institution Scope 关系；该关系不是历史，授权变更历史由 `audit_log` 解释。

### 8.4 `generic_jdbc_type_mapping`

属于非标准/诊断当前配置，可物理删除不再需要的 Mapping；它不参与医疗标准 Dataset Version 的历史合同解释。

## 9. Alert Rule / Channel：允许物理删除，历史使用 Snapshot

### 9.1 `alert_rule`

允许物理删除。

删除时：

```text
alert_rule_channel → CASCADE
alert_event.rule_id → SET NULL
```

`alert_event` 已保存：

```text
rule_name_snapshot
metric_code_snapshot
rule_snapshot
severity
```

所以历史事件不依赖当前 Rule 行继续存在。

### 9.2 `alert_channel`

允许物理删除，但当前仍被 `alert_rule_channel` 引用时：

```text
RESTRICT
```

管理员需要：

```text
先从所有当前 Rule 移除 Channel
→ 再删除 Channel
```

历史 Delivery：

```text
alert_delivery.channel_id → SET NULL
```

并保存：

```text
channel_name_snapshot
channel_type_snapshot
```

因此删除 Channel 不破坏历史通知解释。

Alert Rule/Channel 不增加 `deleted_at`。

## 10. User / External Client：只停用，不删除

### `app_user`

```text
删除模式 = STATE_ONLY
enabled=false
```

不提供物理 DELETE，不增加 `deleted_at`。

### `external_api_client`

```text
删除模式 = STATE_ONLY
enabled=false
```

不提供物理 DELETE，不增加 `deleted_at`。

原因：两者都是稳定长期身份；External Client 还被 `external_api_request` / Execution 来源引用。

停用/重置 Secret/密码等动作继续写 Audit。

## 11. `system_setting`：不提供通用 DELETE

`system_setting` 页面只提供：

```text
读取
修改
恢复默认
```

“恢复默认”不通过删除数据库行表达，统一由 Setting Registry 决定默认值并通过受控 UPDATE/UPSERT 恢复。

不得提供：

```text
DELETE /settings/{key}
自由删除注册 Key
```

数据库没有某个可选 Setting Row 时应用仍可使用注册默认；但产品正常维护不把“删除 Row”作为恢复默认操作。

## 12. External API Nonce：TTL 自动物理清理

```text
external_api_request_nonce
```

模式：

```text
TTL_CLEANUP
```

固定：

```text
expires_at = created_at + 1 hour
expires_at < CURRENT_TIMESTAMP
→ 小批量 DELETE
```

Nonce 不是长期审计；长期追溯由：

```text
external_api_request
audit_log
```

承担。

Nonce 清理失败只告警，不改变已认证请求事实。

## 13. Doris RAW / Snapshot / Diff：清理大数据，不删 PostgreSQL Run

### Precheck RAW

终态 RAW 按既有规则保留 1 天，之后清理 Doris 数据并更新：

```text
precheck_run.raw_cleanup_status
precheck_run.raw_cleaned_at
```

`precheck_run/precheck_issue_summary` PostgreSQL 元数据继续保留。

### Delete Snapshot Key / Diff

```text
_dfetl_key_snapshot
_dfetl_delete_diff
```

按 Delete Snapshot 生命周期、Baseline/Candidate 状态和既有 cleanup 标记进行 Doris 数据清理；不因为 Doris 明细清理删除：

```text
delete_snapshot_run
validation_run
delete_apply_run
```

PostgreSQL Run 中继续保存 `cleaned_at/candidate_cleanup_after` 等控制事实。

本 Matrix 不新增尚未确认的 Delete Snapshot 精确保留时长；沿用既有专项 Review 生命周期。

## 14. Quartz：可重建运行投影

Quartz 11 张官方 JobStore 表属于：

```text
REBUILDABLE_PROJECTION
```

唯一业务事实仍是当前 `sync_task`：

```text
sync_task.deleted_at IS NULL
AND schedule_enabled=true
AND schedule_mode<>'MANUAL'
AND schedule_cron 有效
```

因此：

```text
Task pause / schedule_enabled=false
Task 改 MANUAL
Task logical delete
→ 删除对应 Quartz Job/Trigger

Task resume / 当前调度重新有效
→ 从 sync_task 当前配置重建 Job/Trigger
```

Quartz Runtime 不作为业务历史，不迁移老系统 Quartz Runtime。

## 15. 39 张 DFETL 表删除模式总表

| 表 | 模式 |
| --- | --- |
| `institution` | PHYSICAL_IF_UNREFERENCED |
| `business_catalog` | PHYSICAL_IF_UNREFERENCED |
| `source_datasource` | PHYSICAL_IF_UNREFERENCED |
| `target_datasource` | PHYSICAL_IF_UNREFERENCED |
| `target_datasource_fe_endpoint` | CURRENT_CONFIG_DELETE |
| `standard_dataset` | STATE_ONLY (`VOID`) |
| `standard_dataset_version` | PERMANENT_HISTORY |
| `standard_dataset_field` | PERMANENT_HISTORY |
| `field_conversion_contract` | STATE_ONLY (`RETIRED`) |
| `field_conversion_rule` | PERMANENT_HISTORY |
| `generic_jdbc_type_mapping` | CURRENT_CONFIG_DELETE |
| `dataset_sync_policy` | 当前 Dataset 配置；不提供独立 DELETE |
| `dataset_message_policy` | 当前 Dataset 配置；不提供独立 DELETE |
| `collection_route` | LOGICAL_DELETE |
| `collection_route_version` | PERMANENT_HISTORY |
| `route_field_resolution` | PERMANENT_HISTORY |
| `sync_task` | LOGICAL_DELETE |
| `task_watermark` | 仅显式 Clear 可 CURRENT_CONFIG_DELETE |
| `sync_execution` | PERMANENT_HISTORY |
| `load_batch` | PERMANENT_HISTORY |
| `precheck_run` | PERMANENT_HISTORY |
| `precheck_issue_summary` | PERMANENT_HISTORY |
| `validation_run` | PERMANENT_HISTORY |
| `message_outbox` | PERMANENT_HISTORY |
| `delete_snapshot_run` | PERMANENT_HISTORY |
| `task_delete_snapshot_state` | 当前控制状态；不提供普通 DELETE |
| `delete_apply_run` | PERMANENT_HISTORY |
| `app_user` | STATE_ONLY (`enabled=false`) |
| `audit_log` | PERMANENT_HISTORY |
| `system_setting` | 当前注册设置；不提供通用 DELETE |
| `alert_channel` | PHYSICAL_DELETE_WITH_SNAPSHOT_HISTORY |
| `alert_rule` | PHYSICAL_DELETE_WITH_SNAPSHOT_HISTORY |
| `alert_rule_channel` | CURRENT_CONFIG_DELETE |
| `alert_event` | PERMANENT_HISTORY |
| `alert_delivery` | PERMANENT_HISTORY |
| `external_api_client` | STATE_ONLY (`enabled=false`) |
| `external_api_client_institution` | CURRENT_CONFIG_DELETE |
| `external_api_request_nonce` | TTL_CLEANUP (1 hour) |
| `external_api_request` | PERMANENT_HISTORY |

## 16. 前端/API 删除动作约束

前端和 API 不得根据是否存在数据库 `CASCADE` 来决定是否展示删除按钮。

固定交互：

- Resource：删除前获取引用/阻塞摘要；有引用时提示“无法删除，可停用”。
- Route/Task：页面使用“删除”文案，但 API 实际执行逻辑删除，并记录 Audit。
- Watermark：单独危险操作“清除水位”，不能挂在 Task 删除逻辑里。
- Alert Channel：被 Rule 使用时显示引用并要求先移除关系。
- User/External Client：只提供启停，不提供删除。
- System Setting：只提供恢复默认，不提供 Delete。
- Runtime History：无删除入口。
- External Nonce：无人工删除入口，由系统 TTL 清理。

## 17. 与 FK Matrix 的关系

本 Matrix 不修改第 2 项已确认的 FK 原则：

```text
历史对象 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
运行责任用户 RESTRICT
```

典型看似“业务不删除但 FK 有 CASCADE”的关系是合法的：

```text
field_conversion_rule → contract CASCADE
dataset_*_policy → dataset CASCADE
alert_delivery → event CASCADE
```

其中父对象在正常 P0 业务中可能根本不提供物理删除；`ON DELETE` 只规定数据库发生父行物理 DELETE 时的完整性行为。

## 18. 验收标准

- Resource 不增加 `deleted_at`，无引用可物理删除、有引用只能停用。
- Dataset/Version/Field/Contract 历史不会被 retention 清理。
- Route/Task 使用逻辑删除并释放未删除 Business Unique。
- Task 逻辑删除不级联清 Watermark；Watermark 只有显式 Clear 才删除当前行。
- Runtime/Audit/External Request/Alert History 不提供 DELETE/自动 PostgreSQL retention。
- 当前配置关系允许直接物理增删。
- Alert Rule/Channel 允许物理删除但不破坏 Event/Delivery 历史。
- App User/External Client 只停用。
- System Setting 无通用 DELETE。
- Nonce 1 小时 TTL 清理。
- Doris 大数据按生命周期清理而 PostgreSQL Run 保留。
- Quartz JobStore 只是可重建投影，随 Task 当前调度配置删除/重建。

# P0 Business / Concurrency Unique Matrix Review

> 状态：阶段 1 第 3 项最终一致性 Review 已确认  
> 确认日期：2026-08-17  
> 表清单基线：39 张 DFETL P0 表；Quartz 11 张官方表单独管理  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文是 Flyway V1 的 Unique / Concurrency Constraint 基线，不是 SQL；阶段 1 最终签字前不创建 `V1__baseline.sql`。

## 1. 已确认原则

P0 唯一性约束固定分成三类，禁止混用：

1. **Business Unique**：稳定 Code/ID、父对象内 Version No、不可变内容 Hash、业务关系 Pair 等真实业务身份必须唯一。
2. **Concurrency / Safety Partial Unique**：活动 Execution、Precheck、Independent Validation、Delete Snapshot 等通过 PostgreSQL Partial Unique Index 做并发兜底。
3. **FK Support Unique**：仅为复合 FK 提供 Parent Unique 的约束，不算业务唯一，也不在本文件重复解释业务含义；完整清单以 `P0_FOREIGN_KEY_MATRIX_REVIEW.md` 为准。
4. **Name 不是默认业务身份**：已有稳定 Code/ID 的对象不因展示名称相同而拒绝创建；名称唯一只保留在没有独立稳定 Code、且同名会直接造成管理歧义的对象上。
5. **Version 表表示不可变内容版本，不表示每次保存操作**：Dataset/Route 相同内容 Hash 命中历史版本时复用已有不可变 Version，不创建内容完全相同的新 Version；保存操作历史由 `audit_log` 解释。
6. **跨表互斥不新增锁表**：`sync_execution` 与独立 `validation_run` 的跨表互斥继续通过锁定同一 `sync_task` + 两张表各自 Partial Unique 保证，不建立 `task_operation_lock/slot`。
7. Quartz 11 张表采用项目锁定 Quartz 版本官方 PostgreSQL DDL，不纳入 DFETL 自定义 Unique 重设计。

## 2. FK Support Unique 不计入 Business Unique

以下示例只是 FK 支撑键：

```text
source_datasource(id,institution_id)
collection_route(id,institution_id,dataset_id)
collection_route_version(route_id,id)
collection_route_version(id,dataset_version_id)
collection_route_version(id,institution_id,dataset_id,dataset_version_id)
standard_dataset_version(dataset_id,id)
standard_dataset_field(dataset_version_id,id)
sync_task(id,institution_id,dataset_id)
sync_execution(id,task_id)
sync_execution(id,task_id,dataset_id,institution_id)
validation_run(id,task_id)
delete_snapshot_run(id,task_id)
```

它们即使物理上使用 `UNIQUE`，也不能在产品文档中解释成新的业务身份规则。

## 3. Resource Business Unique

| 表 | Business Unique | 语义 |
| --- | --- | --- |
| `institution` | `UNIQUE INDEX ... ON lower(code)` | Institution Code 稳定且大小写不敏感唯一；Name 不唯一。 |
| `business_catalog` | `UNIQUE INDEX ... ON lower(code)` | HIS/LIS/PACS 等 Catalog Code 唯一；Name 不作为身份。 |
| `source_datasource` | `UNIQUE INDEX ... ON lower(code)` | Source Code 唯一；Name 可重复。 |
| `target_datasource` | `UNIQUE INDEX ... ON lower(code)` | Target Doris Code 唯一；Name 可重复。 |
| `target_datasource_fe_endpoint` | `UNIQUE(target_datasource_id,host,query_port)` | 同一 Target 不重复定义同一 Query Endpoint。 |
| `target_datasource_fe_endpoint` | `UNIQUE(target_datasource_id,ordinal_no)` | FE 展示/优先顺序在父 Target 内唯一。 |

不增加 Institution/Source/Target Name Unique。

## 4. Dataset / Contract Business Unique

### 4.1 Dataset Identity

```text
UNIQUE(standard_dataset.external_dataset_id)
UNIQUE INDEX uk_standard_dataset_code_ci
  ON standard_dataset(lower(dataset_code))
```

External ID 与 Dataset Code 都是稳定身份；Dataset Name 不唯一。

### 4.2 Dataset Version

```text
UNIQUE(dataset_id,version_no)
UNIQUE(dataset_id,definition_hash)
```

含义不同：

- `version_no`：父 Dataset 内可读的递增版本号；
- `definition_hash`：不可变内容身份。

**相同 Hash 复用历史 Version：**

```text
当前 V1 = Hash A
当前后续切到 V2 = Hash B
规范定义再次恢复到 Hash A
→ 查到历史 V1
→ standard_dataset.current_version_id 重新指向 V1
→ 不创建 V3 = Hash A
→ 操作写 audit_log
```

因此 `UNIQUE(dataset_id,definition_hash)` 不是单纯防重索引，而是版本内容复用规则。

### 4.3 Dataset Field

```text
UNIQUE(dataset_version_id,external_field_id)
UNIQUE(dataset_version_id,field_code)
UNIQUE(dataset_version_id,ordinal_no)

UNIQUE INDEX uk_dataset_field_business_key_order
ON standard_dataset_field(dataset_version_id,business_key_ordinal)
WHERE business_key_ordinal IS NOT NULL
```

保证同一 Dataset Version 内外部 Field ID、标准 Field Code、字段顺序和业务主键顺序没有重复。

### 4.4 Field Conversion Contract

```text
UNIQUE(field_conversion_contract.contract_hash)
UNIQUE(contract_version,rule_code)
```

合同 Hash 表示不可变合同内容；Rule Code 只要求在一个合同版本内唯一。

### 4.5 Generic JDBC Mapping

```text
UNIQUE(profile_name,profile_version,rule_code)
```

### 4.6 Dataset 附属配置

```text
dataset_sync_policy.dataset_id PRIMARY KEY
dataset_message_policy.dataset_id PRIMARY KEY
```

每个 Dataset 最多一行同步默认和一行消息策略。

## 5. Route / Task / Watermark Business Unique

### 5.1 当前 Route

```sql
CREATE UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL;
```

一家 Institution + 一个 Dataset 同时只能有一条未删除 Route。

### 5.2 Route Version

```text
UNIQUE(route_id,version_no)
UNIQUE(route_id,contract_hash)
```

`contract_hash` 是不可变 Route 配置内容身份。

**相同 Hash 复用历史 Route Version：**

```text
Route V1 = Config Hash A
Route V2 = Config Hash B
当前配置再次编辑回 Hash A
→ 查到历史 V1
→ 更新 collection_route 当前投影与 current_version_id 指向 V1
→ 不创建 V3 = Hash A
→ 操作写 audit_log
```

因此 Route Version No 只在真正出现新的不可变内容时递增。

### 5.3 Route Field Resolution

当前字段：

```text
route_version_id
dataset_version_id
standard_field_id
source_column_name
source_ordinal
source_jdbc_type
source_type_name
resolved_at
```

Business Unique：

```text
PRIMARY KEY(route_version_id,standard_field_id)

UNIQUE INDEX uk_route_resolution_source_column_ci
ON route_field_resolution(route_version_id,lower(source_column_name))
```

`dataset_version_id` 用于 FK 身份闭环，不重复保存 `field_code`。

### 5.4 当前 Task

```sql
CREATE UNIQUE INDEX uk_sync_task_active_institution_dataset
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL;
```

Task Name 不唯一；固定业务身份是 Institution + Dataset。

### 5.5 Watermark

```text
task_watermark.task_id PRIMARY KEY
```

一个 Task 最多一条当前正式 Watermark；不建设 Watermark History 表。

## 6. Runtime Business / Cardinality Unique

### 6.1 Execution

```text
UNIQUE(sync_execution.execution_uuid)
```

### 6.2 Load Batch

```text
UNIQUE(execution_id,batch_no)
UNIQUE(doris_label)
```

同一 Execution 内 Batch No 唯一；Doris Label 全局确定且不可重复。

### 6.3 Precheck

```text
UNIQUE(precheck_run.run_uuid)
```

Issue Summary：

```sql
CREATE UNIQUE INDEX uk_precheck_issue_summary_scope
ON precheck_issue_summary(
  run_id,
  rule_scope,
  coalesce(standard_field_code,''),
  rule_code
);
```

同一次 Precheck 的同一 Scope/Field/Rule 只保留一条汇总。

### 6.4 Validation

```text
UNIQUE(validation_run.run_uuid)
```

每个同步 Execution 最多一个 SYNC_GATE：

```sql
CREATE UNIQUE INDEX uk_validation_sync_gate_execution
ON validation_run(execution_id)
WHERE trigger_type='SYNC_GATE';
```

每个 Delete Snapshot Candidate 最多一个 Delete Reconciliation：

```sql
CREATE UNIQUE INDEX uk_validation_delete_current_snapshot
ON validation_run(current_snapshot_run_id)
WHERE validation_scope='DELETE_RECONCILIATION';
```

### 6.5 Message Outbox

```text
UNIQUE(message_outbox.event_id)
UNIQUE(message_outbox.execution_id)
```

一个成功 Execution 最多创建一条 Outbox；人工重发沿用 Event ID，不创建第二条业务事件。

## 7. Delete Snapshot / Apply Business Unique

### 7.1 Snapshot

```text
UNIQUE(delete_snapshot_run.run_uuid)
task_delete_snapshot_state.task_id PRIMARY KEY
```

### 7.2 Delete Apply

```text
UNIQUE(delete_apply_run.run_uuid)
```

Dry Run 允许多次。

真实 Apply 使用**一条** Safety Partial Unique：

```sql
CREATE UNIQUE INDEX uk_delete_apply_effective
ON delete_apply_run(validation_run_id)
WHERE dry_run = false
  AND status IN ('PENDING','RUNNING','SUCCEEDED');
```

语义：

```text
无真实 Apply → 可发起 PENDING
已有 PENDING/RUNNING → 禁止第二次真实 Apply
已有 SUCCEEDED → 永久禁止再次真实 Apply
FAILED/PARTIAL_FAILED/CANCELLED → 允许用户重新发起新的真实 Apply
Dry Run → 不受该唯一索引限制
```

删除旧的两条重叠索引：

```text
uk_delete_apply_active
uk_delete_apply_success
```

避免“已有 SUCCEEDED 后仍可先插入新的 PENDING，直到第二次成功才冲突”的延迟失败漏洞。

## 8. Support Object Business Unique

### 8.1 Local User

```text
UNIQUE INDEX uk_app_user_username_ci
ON app_user(lower(username))
```

### 8.2 System Setting

```text
system_setting.setting_key PRIMARY KEY
```

### 8.3 Alert

```text
UNIQUE INDEX uk_alert_channel_name_ci
ON alert_channel(lower(name))

UNIQUE INDEX uk_alert_rule_name_ci
ON alert_rule(lower(name))

PRIMARY KEY(alert_rule_channel.rule_id,alert_rule_channel.channel_id)

UNIQUE(alert_event.event_uuid)
UNIQUE(alert_delivery.event_id,alert_delivery.channel_id)
```

Alert Channel / Rule 没有独立稳定 Code；同名会直接造成后台管理歧义，因此保留 Name Unique。

### 8.4 External API

```text
UNIQUE(external_api_client.client_id)
PRIMARY KEY(external_api_client_institution.client_id,institution_id)
UNIQUE(external_api_request_nonce.client_id,nonce)
UNIQUE(external_api_request.client_id,request_id)
```

**`external_api_client.client_name` 不唯一。**

固定语义：

```text
client_id   = 稳定、大小写敏感、不可复用的程序身份
client_name = 可编辑展示名称，可重复
```

例如生产 Client 和灾备 Client 可以都展示为“ HIS 接口”，但必须有不同 `client_id`。

## 9. Concurrency / Safety Partial Unique

### 9.1 Active Field Conversion Contract

```sql
CREATE UNIQUE INDEX uk_field_conversion_contract_active
ON field_conversion_contract((1))
WHERE status='ACTIVE';
```

同一时间只能一个合同用于后续 Dataset Sync。

### 9.2 Active Sync Execution

```sql
CREATE UNIQUE INDEX uk_sync_execution_active_task
ON sync_execution(task_id)
WHERE status IN ('PENDING','RUNNING','LOADING','VALIDATING');
```

同 Task 同时最多一个活动同步 Execution。

### 9.3 Active Precheck

```sql
CREATE UNIQUE INDEX uk_precheck_run_active_route
ON precheck_run(route_id)
WHERE execution_status IN ('PENDING','EXTRACTING','VALIDATING');
```

同 Route 同时最多一个活动 Precheck。

### 9.4 Active Independent Validation

```sql
CREATE UNIQUE INDEX uk_validation_run_active_independent_task
ON validation_run(task_id)
WHERE trigger_type IN ('MANUAL','MANUAL_RECHECK','SCHEDULED')
  AND status IN ('PENDING','RUNNING');
```

`SYNC_GATE` 不参与这条独立 Validation 并发约束。

### 9.5 Active Delete Snapshot

```sql
CREATE UNIQUE INDEX uk_delete_snapshot_run_active_task
ON delete_snapshot_run(task_id)
WHERE status IN ('PENDING','EXTRACTING','WRITING','COMPARING');
```

### 9.6 Effective Delete Apply

使用第 7.2 节 `uk_delete_apply_effective`，同时承担并发和“成功后不可再次真实应用”的安全门禁。

## 10. 明确不建立跨表 Unique Lock 模型

同步与独立 Validation 分别位于：

```text
sync_execution
validation_run
```

PostgreSQL Unique Index 不能跨表表达互斥，因此继续：

```text
锁定同一 sync_task
→ 检查另一个运行表
→ 插入当前运行对象
→ 当前表 Partial Unique 做极端竞争兜底
```

不新增：

```text
task_operation_lock
task_operation_slot
operation_queue
waiting_operation
```

39 张 DFETL P0 表数量不因此增加。

## 11. V1 验收规则

Flyway V1 生成前必须确认：

- 每一条 Business Unique 都能对应明确业务身份/父内身份/不可变内容身份/关系 Pair；
- FK Support Unique 仅作为 FK 基础设施，不在产品层解释成额外唯一规则；
- 所有运行并发 Partial Unique 的谓词与最终 Status Matrix 完全一致；
- Dataset/Route Hash 命中历史内容时使用历史 Version，不尝试插入重复 Hash；
- External Client Name 没有 Unique Constraint；
- Delete Apply 只存在一条 `uk_delete_apply_effective`；
- 跨表 Sync/Independent Validation 互斥不新增持久化锁表；
- Quartz Unique/PK 使用官方 PostgreSQL Schema。

## 12. 结论

第 3 项 Business / Concurrency Unique Matrix 已冻结。下一项按既定顺序只讨论：

```text
Status / Enum / CHECK Matrix
```

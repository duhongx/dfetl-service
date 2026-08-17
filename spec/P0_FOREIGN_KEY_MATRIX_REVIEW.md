# P0 PostgreSQL 外键矩阵 Review

> 状态：阶段 1 第 2 项最终一致性 Review 已确认  
> 确认日期：2026-08-17  
> 表清单基线：39 张 DFETL P0 表；Quartz 11 张官方表单独管理  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 总物理字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 限制：本文是 Flyway V1 的 FK 设计基线，不是 SQL；阶段 1 最终签字前不创建 `V1__baseline.sql`。

## 1. 已确认原则

P0 FK 固定采用以下规则：

1. **最强复合 FK 优先**：能用一条复合 FK 直接证明“属于同一业务对象”的，不再同时保留被它完全覆盖的重复单列 FK。
2. **历史对象 `ON DELETE RESTRICT`**：Version、Task、Execution、Batch、Precheck、Validation、Outbox、Delete Snapshot/Delete Apply 等历史链不得因删除父配置被级联破坏。
3. **纯配置子对象允许 `CASCADE`**：父对象可物理删除且子对象不承担独立历史时，使用 `ON DELETE CASCADE`。
4. **普通审计用户 `SET NULL`**：`created_by/updated_by/deleted_by/imported_by/retired_by` 等普通审计 FK 统一 `ON DELETE SET NULL`；同时保存业务对象自身历史，不依赖用户行解释。
5. **业务运行责任用户 `RESTRICT`**：`requested_by/confirmed_by/triggered_by/cancel_requested_by` 等直接表示一次运行/人工动作责任人的 FK 使用 `ON DELETE RESTRICT`。
6. **FK 子列必须具备可用索引**：PostgreSQL 不自动为 FK 子列创建索引；V1 必须保证父删除检查和常用 Join 不因 FK 形成大表全扫。
7. Quartz 11 张表使用项目锁定 Quartz 版本的官方 PostgreSQL JDBC JobStore DDL，不纳入 DFETL 自定义 FK 重设计。

## 2. 普通审计 FK 通用规则

除下文特别说明外，业务配置表中的：

```text
created_by
updated_by
deleted_by
imported_by
retired_by
```

统一：

```text
FOREIGN KEY (...) REFERENCES app_user(id) ON DELETE SET NULL
```

P0 不因为这些低频反向关系给每个审计列单独创建索引；`app_user` 产品上本身不提供物理删除。若某页面未来新增“按创建人/修改人”高频查询，再通过后续迁移增加查询索引。

业务运行责任字段统一：

```text
requested_by
requested_by_user_id
confirmed_by
triggered_by
cancel_requested_by
```

使用：

```text
FOREIGN KEY (...) REFERENCES app_user(id) ON DELETE RESTRICT
```

它们不属于“普通审计列”。

## 3. 接入资源 FK

| Child | Child Columns | Parent | Parent Key | ON DELETE | Child Index |
| --- | --- | --- | --- | --- | --- |
| `source_datasource` | `institution_id` | `institution` | PK `(id)` | RESTRICT | `idx_source_datasource_institution(institution_id,status,id)` |
| `source_datasource` | `business_catalog_id` | `business_catalog` | PK `(id)` | RESTRICT | `idx_source_datasource_business(business_catalog_id,status,id)` |
| `target_datasource_fe_endpoint` | `target_datasource_id` | `target_datasource` | PK `(id)` | CASCADE | `UNIQUE(target_datasource_id,host,query_port)` / `(target_datasource_id,ordinal_no)` 前缀覆盖 |

资源层固定父键：

```text
source_datasource(id,institution_id) UNIQUE
```

该键不新增业务唯一语义，只用于 Route/Route Version 的机构一致性 FK。

## 4. Dataset / Field Contract FK

| Child | Child Columns | Parent | Parent Key | ON DELETE | Child Index |
| --- | --- | --- | --- | --- | --- |
| `standard_dataset` | `(id,current_version_id)` | `standard_dataset_version` | `UNIQUE(dataset_id,id)` | RESTRICT | `standard_dataset.id` PK；FK `DEFERRABLE INITIALLY DEFERRED` |
| `standard_dataset_version` | `dataset_id` | `standard_dataset` | PK `(id)` | RESTRICT | `UNIQUE(dataset_id,version_no)` 前缀覆盖 |
| `standard_dataset_version` | `conversion_contract_version` | `field_conversion_contract` | PK `(contract_version)` | RESTRICT | `idx_dataset_version_conversion_contract(conversion_contract_version,id)` |
| `standard_dataset_version` | `(id,institution_code_field_code)` | `standard_dataset_field` | `UNIQUE(dataset_version_id,field_code)` | RESTRICT | `standard_dataset_version.id` PK；`DEFERRABLE INITIALLY DEFERRED` |
| `standard_dataset_version` | `(id,incremental_field_code)` | `standard_dataset_field` | `UNIQUE(dataset_version_id,field_code)` | RESTRICT | `standard_dataset_version.id` PK；可空，`DEFERRABLE INITIALLY DEFERRED` |
| `standard_dataset_field` | `dataset_version_id` | `standard_dataset_version` | PK `(id)` | RESTRICT | 字段表全部主要 Unique/Index 均以 `dataset_version_id` 开头 |
| `field_conversion_rule` | `contract_version` | `field_conversion_contract` | PK `(contract_version)` | CASCADE | `UNIQUE(contract_version,rule_code)` 前缀覆盖 |
| `dataset_sync_policy` | `dataset_id` | `standard_dataset` | PK `(id)` | CASCADE | PK `(dataset_id)` |
| `dataset_message_policy` | `dataset_id` | `standard_dataset` | PK `(id)` | CASCADE | PK `(dataset_id)` |

### 4.1 `conversion_rule_code` 有意不增加重复 FK 列

`standard_dataset_field.conversion_rule_code` 与父 `standard_dataset_version.conversion_contract_version` 共同定位：

```text
field_conversion_rule(contract_version,rule_code)
```

不为每个 Field 再复制 `conversion_contract_version`。Dataset 导入事务在创建 Version + Fields 时一次性验证 Rule 必须属于该 Version 的 Contract。该约束属于不可变 Dataset 导入事务合同，不通过重复列制造第二份事实。

## 5. Route / Route Version / Field Resolution FK

### 5.1 `collection_route` 父身份键

新增支撑唯一键：

```text
collection_route(id,institution_id,dataset_id) UNIQUE
```

它用于保证 Route Version 的 Institution/Dataset 快照确实属于同一个父 Route。

### 5.2 Route 当前指针

```text
FOREIGN KEY (id,current_version_id)
REFERENCES collection_route_version(route_id,id)
ON DELETE RESTRICT
DEFERRABLE INITIALLY DEFERRED
```

因此 `collection_route.current_version_id` 不可能指向其他 Route 的 Version。

### 5.3 Route 当前配置

| Child | Child Columns | Parent | Parent Key | ON DELETE | Child Index |
| --- | --- | --- | --- | --- | --- |
| `collection_route` | `dataset_id` | `standard_dataset` | PK `(id)` | RESTRICT | `idx_collection_route_dataset(dataset_id,deleted_at,id)` |
| `collection_route` | `(source_datasource_id,institution_id)` | `source_datasource` | `UNIQUE(id,institution_id)` | RESTRICT | `idx_collection_route_source_institution(source_datasource_id,institution_id,deleted_at,id)` |
| `collection_route` | `target_datasource_id` | `target_datasource` | PK `(id)` | RESTRICT | `idx_collection_route_target(target_datasource_id,deleted_at,id)` |
| `collection_route` | `(id,current_version_id)` | `collection_route_version` | `UNIQUE(route_id,id)` | RESTRICT | PK `id`；Deferred Same-parent Pointer |

`institution_id → institution(id)` 不再单独建立：它已被 `(source_datasource_id,institution_id)` 强复合 FK 完全覆盖；Source 自身保证 Institution 存在。

### 5.4 `collection_route_version`

| Child Columns | Parent | Parent Key | ON DELETE | Child Index |
| --- | --- | --- | --- | --- |
| `(route_id,institution_id,dataset_id)` | `collection_route` | `UNIQUE(id,institution_id,dataset_id)` | RESTRICT | `idx_route_version_parent(route_id,institution_id,dataset_id,version_no)` |
| `(dataset_id,dataset_version_id)` | `standard_dataset_version` | `UNIQUE(dataset_id,id)` | RESTRICT | `idx_route_version_dataset_version(dataset_id,dataset_version_id,id)` |
| `(source_datasource_id,institution_id)` | `source_datasource` | `UNIQUE(id,institution_id)` | RESTRICT | `idx_route_version_source_institution(source_datasource_id,institution_id,id)` |
| `target_datasource_id` | `target_datasource` | PK `(id)` | RESTRICT | `idx_route_version_target(target_datasource_id,id)` |

删除被上述复合 FK 完全覆盖的单列：

```text
route_id → collection_route(id)
institution_id → institution(id)
dataset_id → standard_dataset(id)
dataset_version_id → standard_dataset_version(id)
source_datasource_id → source_datasource(id)
```

Target 没有更强业务复合关系，因此保留单列 FK。

Route Version 支撑唯一键最终保留：

```text
UNIQUE(route_id,id)
UNIQUE(id,dataset_version_id)
UNIQUE(id,institution_id,dataset_id,dataset_version_id)
```

以及 Version 本身需要的业务唯一键，后续 Business Unique Matrix 单独 Review。

### 5.5 `route_field_resolution`

字段收敛为：

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

删除重复事实：

```text
field_code
```

FK：

```text
FOREIGN KEY (route_version_id,dataset_version_id)
REFERENCES collection_route_version(id,dataset_version_id)
ON DELETE RESTRICT

FOREIGN KEY (dataset_version_id,standard_field_id)
REFERENCES standard_dataset_field(dataset_version_id,id)
ON DELETE RESTRICT
```

因此数据库直接保证：

```text
Route Version 使用的 Dataset Version
=
Field Resolution 中 Standard Field 所属 Dataset Version
```

主键/唯一：

```text
PRIMARY KEY(route_version_id,standard_field_id)
UNIQUE(route_version_id,lower(source_column_name))
```

不再保存/索引 `field_code`；展示时通过 `standard_field_id` 关联 `standard_dataset_field.field_code`。

## 6. Task / Watermark FK

### 6.1 `sync_task`

最终只保留强关系：

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

删除被该 FK 完全覆盖的：

```text
institution_id → institution(id)
dataset_id → standard_dataset(id)
dataset_version_id → standard_dataset_version(id)
route_version_id → collection_route_version(id)
(route_version_id,institution_id,dataset_id) → collection_route_version(...)
```

增量字段继续独立保证属于当前 Dataset Version：

```text
FOREIGN KEY (dataset_version_id,incremental_field_code)
REFERENCES standard_dataset_field(dataset_version_id,field_code)
ON DELETE RESTRICT
```

Child Index：

```text
idx_sync_task_route_version(route_version_id,deleted_at,id)
idx_sync_task_dataset_version(dataset_version_id,deleted_at,id)
```

### 6.2 `task_watermark`

父 Task：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT
```

Source Execution 必须属于同一 Task：

```text
sync_execution(id,task_id) UNIQUE

FOREIGN KEY (source_execution_id,task_id)
REFERENCES sync_execution(id,task_id)
ON DELETE RESTRICT
```

`source_execution_id` 可空（人工设置 Watermark 时为空）。

Child Index：

```text
idx_task_watermark_source_execution(source_execution_id)
WHERE source_execution_id IS NOT NULL
```

## 7. Execution / Batch / Precheck / Validation / Outbox FK

### 7.1 `sync_execution`

Task 身份：

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

External API Execution 必须关联真实幂等请求：

```text
FOREIGN KEY (external_client_id,external_request_id)
REFERENCES external_api_request(client_id,request_id)
ON DELETE RESTRICT
```

两列对非 External Execution 均为空；`trigger_type='EXTERNAL_API'` 时均非空。

责任用户：

```text
requested_by_user_id → app_user(id) ON DELETE RESTRICT
cancel_requested_by  → app_user(id) ON DELETE RESTRICT
```

支撑父键：

```text
sync_execution(id,task_id) UNIQUE
sync_execution(id,task_id,dataset_id,institution_id) UNIQUE
```

Child Index 至少：

```text
idx_sync_execution_task_history(task_id,created_at DESC,id DESC)
idx_sync_execution_route_version(route_version_id,created_at DESC,id DESC)
idx_sync_execution_external_request(external_client_id,external_request_id)
  WHERE external_client_id IS NOT NULL
```

### 7.2 `load_batch`

```text
FOREIGN KEY (execution_id)
REFERENCES sync_execution(id)
ON DELETE RESTRICT
```

`UNIQUE(execution_id,batch_no)` 已以 FK 子列为前缀，无需额外单列索引。

### 7.3 `precheck_run`

保留两条互补复合 FK：

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

第一条证明 Version 属于指定 Route，第二条证明运行使用的业务身份属于同一 Version；两者表达不同事实，不属于重复单列 FK。

责任用户：

```text
requested_by → app_user(id) ON DELETE RESTRICT
```

Child Index：

```text
idx_precheck_run_route_history(route_id,created_at DESC,id DESC)
idx_precheck_run_route_version(route_version_id,created_at DESC,id DESC)
```

### 7.4 `precheck_issue_summary`

```text
FOREIGN KEY (run_id)
REFERENCES precheck_run(id)
ON DELETE RESTRICT
```

其业务 Unique 以 `run_id` 为首列，足够覆盖 FK 子列。

### 7.5 `validation_run`

父 Task：

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT
```

如果关联 Execution，则必须属于同一个 Task：

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
requested_by         → app_user(id) ON DELETE RESTRICT
cancel_requested_by  → app_user(id) ON DELETE RESTRICT
```

Child Index：

```text
idx_validation_task_history(task_id,created_at DESC,id DESC)
idx_validation_execution(execution_id,created_at DESC,id)
  WHERE execution_id IS NOT NULL
idx_validation_baseline_snapshot(baseline_snapshot_run_id,task_id)
  WHERE baseline_snapshot_run_id IS NOT NULL
```

`current_snapshot_run_id` 由 Delete Reconciliation 的 Partial Unique 覆盖。

### 7.6 `message_outbox`

只保留父 Execution 强复合身份 FK：

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

不再增加：

```text
execution_id → sync_execution(id)
task_id → sync_task(id)
dataset_id → standard_dataset(id)
institution_id → institution(id)
```

`UNIQUE(execution_id)` 已覆盖父关系删除检查；`idx_message_outbox_task_history` 服务 Task 历史查询。

## 8. Delete Snapshot / Apply FK

### 8.1 `delete_snapshot_run`

```text
FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
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

FOREIGN KEY (target_datasource_id)
REFERENCES target_datasource(id)
ON DELETE RESTRICT

FOREIGN KEY (baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT
```

责任用户：

```text
triggered_by → app_user(id) ON DELETE RESTRICT
```

Child Index 至少：

```text
idx_delete_snapshot_task_time(task_id,created_at DESC)
idx_delete_snapshot_route_version(route_version_id,created_at DESC,id)
idx_delete_snapshot_target(target_datasource_id,created_at DESC,id)
idx_delete_snapshot_baseline(baseline_snapshot_run_id,task_id)
  WHERE baseline_snapshot_run_id IS NOT NULL
```

### 8.2 `task_delete_snapshot_state`

```text
FOREIGN KEY (task_id)
REFERENCES sync_task(id)
ON DELETE RESTRICT

FOREIGN KEY (current_baseline_snapshot_run_id,task_id)
REFERENCES delete_snapshot_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (last_reconciliation_validation_run_id,task_id)
REFERENCES validation_run(id,task_id)
ON DELETE RESTRICT
```

普通 `updated_by`：`ON DELETE SET NULL`。

建议索引：

```text
idx_task_delete_state_baseline(current_baseline_snapshot_run_id,task_id)
idx_task_delete_state_validation(last_reconciliation_validation_run_id,task_id)
  WHERE last_reconciliation_validation_run_id IS NOT NULL
```

### 8.3 `delete_apply_run`

```text
FOREIGN KEY (validation_run_id,task_id)
REFERENCES validation_run(id,task_id)
ON DELETE RESTRICT

FOREIGN KEY (task_id,institution_id,dataset_id)
REFERENCES sync_task(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

责任用户：

```text
requested_by → app_user(id) ON DELETE RESTRICT
confirmed_by → app_user(id) ON DELETE RESTRICT
```

`idx_delete_apply_validation`、`idx_delete_apply_task` 覆盖主要父关系检查和历史查询。

## 9. Alert FK

### 9.1 `alert_rule`

```text
scope_task_id → sync_task(id) ON DELETE RESTRICT
```

`idx_alert_rule_task(scope_task_id)` 在非空时覆盖。

### 9.2 `alert_rule_channel`

```text
rule_id    → alert_rule(id)    ON DELETE CASCADE
channel_id → alert_channel(id) ON DELETE RESTRICT
created_by → app_user(id)      ON DELETE SET NULL
```

`PRIMARY KEY(rule_id,channel_id)` 覆盖 Rule FK；反向索引：

```text
idx_alert_rule_channel_channel(channel_id,rule_id)
```

### 9.3 `alert_event`

```text
rule_id                 → alert_rule(id)          ON DELETE SET NULL
task_id                 → sync_task(id)           ON DELETE RESTRICT
execution_id            → sync_execution(id)      ON DELETE RESTRICT
precheck_run_id         → precheck_run(id)        ON DELETE RESTRICT
validation_run_id       → validation_run(id)      ON DELETE RESTRICT
message_outbox_id       → message_outbox(id)      ON DELETE RESTRICT
delete_snapshot_run_id  → delete_snapshot_run(id) ON DELETE RESTRICT
```

历史事件保存 Rule Snapshot，因此 Rule 可被删除后 `rule_id=NULL`；运行来源对象属于历史事实，不允许被删除。

索引：

```text
idx_alert_event_rule(rule_id,triggered_at DESC) WHERE rule_id IS NOT NULL
idx_alert_event_task(task_id,triggered_at DESC) WHERE task_id IS NOT NULL
idx_alert_event_execution(execution_id) WHERE execution_id IS NOT NULL
idx_alert_event_precheck(precheck_run_id) WHERE precheck_run_id IS NOT NULL
idx_alert_event_validation(validation_run_id) WHERE validation_run_id IS NOT NULL
idx_alert_event_outbox(message_outbox_id) WHERE message_outbox_id IS NOT NULL
idx_alert_event_delete_snapshot(delete_snapshot_run_id) WHERE delete_snapshot_run_id IS NOT NULL
```

### 9.4 `alert_delivery`

```text
event_id   → alert_event(id)   ON DELETE CASCADE
channel_id → alert_channel(id) ON DELETE SET NULL
```

Delivery 保存 Channel Name/Type Snapshot，因此历史 Channel 可为空。`UNIQUE(event_id,channel_id)` / Event Index 覆盖 Event FK；Channel History Index 覆盖 Channel FK。

## 10. External API FK

### 10.1 `external_api_client_institution`

```text
client_id      → external_api_client(id) ON DELETE RESTRICT
institution_id → institution(id)         ON DELETE RESTRICT
created_by     → app_user(id)             ON DELETE SET NULL
```

PK `(client_id,institution_id)` 覆盖 Client FK；反向索引覆盖 Institution FK。

### 10.2 `external_api_request_nonce`

```text
client_id → external_api_client(id) ON DELETE RESTRICT
```

`UNIQUE(client_id,nonce)` 覆盖 FK 子列。

### 10.3 `external_api_request`

```text
client_id → external_api_client(id) ON DELETE RESTRICT
```

并提供：

```text
UNIQUE(client_id,request_id)
```

供 `sync_execution(external_client_id,external_request_id)` 复合 FK 使用。

## 11. Audit / System Setting / User FK

### `audit_log`

```text
actor_user_id   → app_user(id)            ON DELETE SET NULL
actor_client_id → external_api_client(id) ON DELETE SET NULL
```

Audit 已保存 Actor Name Snapshot，因此不因主体停用/未来维护删除破坏历史。

### `system_setting`

```text
created_by/updated_by → app_user(id) ON DELETE SET NULL
```

无其他领域 FK。

### `app_user`

无业务父 FK；账号不提供物理删除入口。

## 12. 无业务 FK 的 P0 表

以下表除普通审计用户外没有领域父 FK：

```text
institution
business_catalog
target_datasource
field_conversion_contract
generic_jdbc_type_mapping
app_user
alert_channel
external_api_client
```

其中子对象关系已在对应 Child 表中定义。

## 13. 必须同步到物理字典的结构修正

本 Review 确认后，以下变化属于机械物理模型更新，不再重新讨论：

1. `collection_route` 增加 `UNIQUE(id,institution_id,dataset_id)`。
2. `collection_route(id,current_version_id)` → `collection_route_version(route_id,id)` Deferred Same-parent FK。
3. `collection_route_version` 使用 `(route_id,institution_id,dataset_id)` 强复合 FK 指向父 Route，并删除被覆盖的单列 FK。
4. `collection_route_version` 增加 `UNIQUE(id,dataset_version_id)` 支撑 Field Resolution。
5. `route_field_resolution` 增加 `dataset_version_id`，删除 `field_code`；增加 Route Version/Dataset Version 与 Standard Field 两条复合 FK。
6. `sync_task` Route/Dataset/Institution 关系只保留四元复合 FK；删除被覆盖的重复单列 FK。
7. `sync_execution` 增加 `UNIQUE(id,task_id)`；Watermark/Validation 使用同 Task Execution 复合 FK。
8. `sync_execution(external_client_id,external_request_id)` 复合引用 `external_api_request(client_id,request_id)`。
9. `message_outbox` 只保留父 Execution 四元复合身份 FK。
10. 普通审计用户统一 SET NULL；运行责任用户统一 RESTRICT。
11. 为矩阵中标记缺失的 FK 子列补齐索引。

## 14. 验收标准

- 39 张 DFETL P0 表中的每条领域 FK 都能在本文件找到 Child Columns、Parent Key、ON DELETE 和 Child Index。
- 所有复合 FK 的 Parent Columns 必须具有 PK/UNIQUE。
- PostgreSQL V1 不创建被强复合 FK 完全覆盖的重复单列 FK。
- Route/Route Version/Task/Execution/Precheck/Delete Snapshot 的 Institution/Dataset/Dataset Version 不可能通过数据库约束产生交叉引用。
- Watermark 和 Validation 不可能引用其他 Task 的 Execution。
- External API 触发的 Execution 不可能引用不存在或属于其他 Client 的 Request ID。
- Route Field Resolution 的 Standard Field 必须属于 Route Version 使用的 Dataset Version。
- 历史链不使用 CASCADE；纯配置子对象 CASCADE 仅出现在明确允许的关系。
- Quartz 11 张官方表不在本 Review 中自行修改 FK。

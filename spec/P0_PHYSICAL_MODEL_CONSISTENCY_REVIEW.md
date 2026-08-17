# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL 表清单 + FK Matrix 已确认；进入 Unique Matrix Review  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`

## 1. 已完成的主模型收口

```text
Resource:
Business System Instance 多对多旧模型
→ Institution + Business Catalog + Source

Route:
Multi-Institution Shared Route
→ Single-Institution Route + Immutable Route Version

Task:
sync_task_version
→ sync_task Current Config + Execution/Validation Startup Snapshot

Validation:
Global/Dataset/Task Policy Tables
→ System Setting + Dataset Override + Task Override + Execution Snapshot
```

## 2. P0 PostgreSQL 表清单：已冻结

```text
DFETL 领域/控制表 39
Quartz 官方表       11
----------------------
V1 创建             50
```

`flyway_schema_history` 不计入 50。

## 3. 第 2 项：FK Matrix 已确认

用户确认原则：

```text
最强复合 FK
+ 历史 RESTRICT
+ 纯配置子对象 CASCADE
+ 普通审计用户 SET NULL
+ 业务运行责任用户 RESTRICT
```

并确认：

- Route → Route Version 同父指针。
- Route Version → Route Identity 强复合 FK。
- Watermark / Validation → 同 Task Execution。
- External Execution → External API Request。
- `route_field_resolution` 增加 `dataset_version_id`、删除重复 `field_code`。
- 被强复合 FK 完全覆盖的重复单列 FK 不进入 V1。
- FK 子列必须具有可用索引。

完整矩阵见 `P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

## 4. 当前关键复合身份

### Source → Route

```text
source_datasource(id,institution_id) UNIQUE

collection_route(source_datasource_id,institution_id)
→ source_datasource(id,institution_id)
```

### Route → Route Version

```text
collection_route(id,institution_id,dataset_id) UNIQUE

collection_route_version(route_id,institution_id,dataset_id)
→ collection_route(id,institution_id,dataset_id)
```

当前指针：

```text
collection_route(id,current_version_id)
→ collection_route_version(route_id,id)
DEFERRABLE INITIALLY DEFERRED
```

### Route Version

```text
UNIQUE(id,dataset_version_id)
UNIQUE(id,institution_id,dataset_id,dataset_version_id)
```

### Field Resolution

```text
(route_version_id,dataset_version_id)
→ collection_route_version(id,dataset_version_id)

(dataset_version_id,standard_field_id)
→ standard_dataset_field(dataset_version_id,id)
```

`field_code` 不再持久化。

### Task → Execution

```text
sync_task(id,institution_id,dataset_id) UNIQUE
sync_execution(id,task_id) UNIQUE
```

Watermark/Validation 通过 `(execution_id,task_id)` 固定同 Task。

### External API → Execution

```text
external_api_request(client_id,request_id) UNIQUE

sync_execution(external_client_id,external_request_id)
→ external_api_request(client_id,request_id)
```

### Execution → Outbox

```text
sync_execution(id,task_id,dataset_id,institution_id) UNIQUE

message_outbox(execution_id,task_id,dataset_id,institution_id)
→ sync_execution(id,task_id,dataset_id,institution_id)
```

## 5. User FK 规则

普通审计：

```text
created_by/updated_by/deleted_by/imported_by/retired_by
→ app_user ON DELETE SET NULL
```

运行责任：

```text
requested_by/requested_by_user_id/confirmed_by/triggered_by/cancel_requested_by
→ app_user ON DELETE RESTRICT
```

## 6. 当前明确废止对象

不得作为 Active Model 进入 V1/API/Entity/Frontend：

```text
business_system_instance*
collection_route_institution
collection_route_version_institution
sync_task_version
sync_task.current_version_id
task_version_id
global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode
```

同样不恢复 Validation Disable/Tolerance/Lookback/Auto Revalidate、Task Message Policy、RBAC、Scheduler Reconciliation、External API Rate Limit/Quota。

## 7. Active Spec 清理状态

已同步：

- `TARGET_METADATA_MODEL.md`
- `P0_PHYSICAL_TABLE_DICTIONARY.md`
- `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`
- `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`
- `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`
- `P0_SUPPORT_OBJECT_REVIEW.md`
- `P0_FOREIGN_KEY_MATRIX_REVIEW.md`

Delete Snapshot 当前强 FK 已与 Matrix 一致；Dataset/Resource 字典中未冲突的既有 FK 继续有效，缺失子索引以 FK Matrix 为最终 V1 基线。

## 8. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [ ] Business / Concurrency Unique Matrix。
4. [ ] Status / Enum / CHECK Matrix。
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

**下一项只讨论第 3 项：Business / Concurrency Unique Matrix。**

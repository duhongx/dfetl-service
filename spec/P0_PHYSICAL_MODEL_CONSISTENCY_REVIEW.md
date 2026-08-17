# P0 物理模型一致性 Review

> 状态：P0 PostgreSQL 表清单 + FK Matrix + Unique Matrix 已确认；进入 Status/Enum/CHECK Matrix Review  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Unique 基线：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`

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

原则：

```text
最强复合 FK
历史 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列具备索引
```

已闭环：

- Route → Route Version 同父指针。
- Route Version → Route Identity。
- Watermark / Validation → 同 Task Execution。
- External Execution → External API Request。
- `route_field_resolution` 使用 `dataset_version_id + standard_field_id`，不保存重复 `field_code`。
- Execution → Outbox 复合身份。

## 4. 第 3 项：Business / Concurrency Unique Matrix 已确认

唯一性分三类：

```text
Business Unique
Concurrency / Safety Partial Unique
FK Support Unique
```

FK Support Unique 只为复合 FK 服务，不解释为产品业务身份。

### 4.1 Business Unique 原则

稳定身份使用：

```text
Code / External ID / Client ID / Username / Setting Key
父对象内 Version No
不可变内容 Hash
业务关系 Pair
Run UUID / Event ID
父内自然序号
```

已有稳定 Code/ID 的对象，展示 Name 默认不唯一。

例外：Alert Channel / Alert Rule 没有独立稳定 Code，因此 `lower(name)` 保持唯一。

External Client：

```text
UNIQUE(client_id)
client_name 可重复
```

### 4.2 Dataset / Route Hash 复用

固定规则：

```text
新 Hash = 当前 Hash
→ 不创建 Version

新 Hash != 当前 Hash，但历史已存在
→ 复用历史不可变 Version
→ 切换 current_version_id
→ 不创建重复内容 Version
→ 写 audit_log

新 Hash 历史从未出现
→ 创建新 Version No
```

因此早期文档中“Hash 变化就插入 Version”的表述只适用于**历史中从未出现过的新 Hash**。本节及 `P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md` 优先。

Version 表表达内容版本；重复保存动作由 `audit_log` 表达。

### 4.3 Runtime Concurrency Partial Unique

```text
一个 ACTIVE Field Conversion Contract
一个 Task 一个活动 sync_execution
一个 Route 一个活动 precheck_run
一个 Task 一个活动 Independent validation_run
一个 Task 一个活动 delete_snapshot_run
```

跨表 Sync vs Independent Validation 继续锁定同一 `sync_task` 后检查，不新增 Lock/Slot 表。

### 4.4 Delete Apply Safety

只保留：

```sql
CREATE UNIQUE INDEX uk_delete_apply_effective
ON delete_apply_run(validation_run_id)
WHERE dry_run=false
  AND status IN ('PENDING','RUNNING','SUCCEEDED');
```

删除旧：

```text
uk_delete_apply_active
uk_delete_apply_success
```

语义：PENDING/RUNNING 防并发；SUCCEEDED 后永久阻止再次真实 Apply；FAILED/PARTIAL_FAILED/CANCELLED 后允许重新发起；Dry Run 可多次。

## 5. 当前关键复合身份

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

### Task → Execution

```text
sync_task(id,institution_id,dataset_id) UNIQUE
sync_execution(id,task_id) UNIQUE
```

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

## 6. User FK 规则

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

## 7. 当前明确废止对象

不得进入 V1/API/Entity/Frontend：

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
External Client Name Unique
Delete Apply 双 Partial Unique
```

同样不恢复 Validation Disable/Tolerance/Lookback/Auto Revalidate、Task Message Policy、RBAC、Scheduler Reconciliation、External API Rate Limit/Quota。

## 8. Active Spec 收口说明

已新增权威矩阵：

```text
P0_FOREIGN_KEY_MATRIX_REVIEW.md
P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md
```

涉及 Unique 的早期字典若存在更宽泛的旧描述，以 Unique Matrix 为准。尤其：

```text
Dataset/Route “Hash 变化 → 新 Version”
```

必须解释为：只有历史中从未出现过的新 Hash 才创建新 Version；历史相同 Hash 直接复用旧 Version。

## 9. Review 顺序

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [ ] **Status / Enum / CHECK Matrix。**
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 4 项。

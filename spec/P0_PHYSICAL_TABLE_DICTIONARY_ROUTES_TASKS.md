# P0 物理表字典：机构采集路由与 Task 关系

> 状态：阶段 1 FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> FK：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> Task 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
collection_route
collection_route_version
route_field_resolution
sync_task
task_watermark
```

不建立 Multi-Institution Route、Task Version、Task Validation Policy。

## 2. `collection_route`

一家机构一个标准 Dataset 的当前 Route 投影。

核心字段：

```text
id
institution_id/dataset_id
source_datasource_id/source_schema/source_object/source_object_type
target_datasource_id
status/structure_status/structure_checked_at/structure_error_summary
current_version_id
revision
deleted_at/deleted_by
created_*/updated_*
```

当前关系继续使用已冻结复合 FK：

```text
dataset_id → standard_dataset(id) RESTRICT
(source_datasource_id,institution_id) → source_datasource(id,institution_id) RESTRICT
target_datasource_id → target_datasource(id) RESTRICT
(id,current_version_id) → collection_route_version(route_id,id) RESTRICT Deferred
```

Business Unique：

```text
UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

### 删除

Route 固定使用逻辑删除：

```text
LOGICAL_DELETE
→ deleted_at/deleted_by
```

删除前检查活动 Precheck、未删除 Task 等业务阻塞；逻辑删除后 Route 不再作为当前可选对象，并释放未删除 `(institution_id,dataset_id)` 业务唯一，使未来可创建新的 Route ID。

**不物理删除**：

```text
collection_route_version
route_field_resolution
```

旧 Route 的 Version/Field Resolution 必须长期解释历史运行。

## 3. `collection_route_version`

一次规范化 Route 配置的不可变内容版本。

强复合 FK：

```text
(route_id,institution_id,dataset_id)
→ collection_route(id,institution_id,dataset_id) RESTRICT

(dataset_id,dataset_version_id)
→ standard_dataset_version(dataset_id,id) RESTRICT

(source_datasource_id,institution_id)
→ source_datasource(id,institution_id) RESTRICT

target_datasource_id → target_datasource(id) RESTRICT
```

Business Unique：

```text
UNIQUE(route_id,version_no)
UNIQUE(route_id,contract_hash)
```

相同历史 `contract_hash` 复用旧 Version；Version 永久只读，不提供 DELETE/retention。

## 4. `route_field_resolution`

最终字段：

```text
route_version_id
dataset_version_id
standard_field_id
source_column_name/source_ordinal/source_jdbc_type/source_type_name
resolved_at
```

不保存重复 `field_code`。

```text
(route_version_id,dataset_version_id)
→ collection_route_version(id,dataset_version_id) RESTRICT

(dataset_version_id,standard_field_id)
→ standard_dataset_field(dataset_version_id,id) RESTRICT
```

Field Resolution 随 Route Version 永久保留，不提供独立 DELETE。

## 5. Route Structure / Business Status

```text
status: DISABLED / ENABLED
structure_status: NOT_CHECKED / PASSED / FAILED / OUTDATED
```

两者保持独立；逻辑删除是第三个独立事实。允许历史上出现：

```text
status=ENABLED
structure_status=OUTDATED
deleted_at IS NULL
```

Task 创建/执行 Gate 决定是否允许使用，不通过 CHECK 自动改状态。

## 6. `sync_task` 关系

Task 固定 Institution + Dataset 身份，当前 Route/Dataset/Institution 只保留四元强 FK：

```text
(route_version_id,institution_id,dataset_id,dataset_version_id)
→ collection_route_version(id,institution_id,dataset_id,dataset_version_id) RESTRICT
```

Task 完整生命周期与删除行为以 `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` 为准。

## 7. `task_watermark`

Watermark 只保存当前正式水位；Task 逻辑删除不级联删除 Watermark。显式“清除水位”才允许删除当前 Watermark Row，详细规则见 Task/Watermark 字典和 Delete Behavior Matrix。

## 8. 验收

- Route 单机构。
- Route 使用逻辑删除，不物理删除历史 Version/Field Resolution。
- Route 逻辑删除释放未删除业务唯一并保留旧 ID 历史。
- Route Version/Field Resolution 永久保留。
- Task 继续逻辑删除；Watermark 不随 Task 删除级联。
- FK/Unique/Status 仍以对应已冻结 Matrix 为准。

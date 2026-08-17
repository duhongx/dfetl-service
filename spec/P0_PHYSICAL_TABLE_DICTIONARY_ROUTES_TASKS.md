# P0 物理表字典：机构采集路由、任务关联与水位

> 状态：阶段 1 Route/Task 关系模型收口完成  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> Task 专项：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
> Task/Watermark 字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
collection_route
collection_route_version
route_field_resolution
sync_task
task_watermark
```

明确不建立：

```text
collection_route_institution
collection_route_version_institution
sync_task_version
task_validation_policy
```

Route 和 Task 都固定是一家机构上下文。

## 2. 关系总览

```text
institution
  └── source_datasource

institution + standard_dataset
  └── collection_route
       └── collection_route_version
            └── route_field_resolution

institution + standard_dataset
  └── sync_task
       ├── route_version_id
       └── task_watermark
```

## 3. `collection_route`

职责：一家机构一个标准 Dataset 的当前采集映射、业务状态、结构核对状态和当前不可变 Route Version 指针。

### 3.1 字段

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | Route ID |
| `institution_id` | `bigint` | NOT NULL | 当前机构 |
| `dataset_id` | `bigint` | NOT NULL | 当前标准 Dataset |
| `source_datasource_id` | `bigint` | NOT NULL | 当前 Source |
| `source_schema` | `varchar(128)` | NULL | 当前 Schema |
| `source_object` | `varchar(256)` | NOT NULL | Table/View/Materialized View 名称 |
| `source_object_type` | `varchar(32)` | NOT NULL | `TABLE/VIEW/MATERIALIZED_VIEW` |
| `target_datasource_id` | `bigint` | NOT NULL | 当前 Target Doris |
| `status` | `varchar(16)` | NOT NULL DEFAULT `'DISABLED'` | `DISABLED/ENABLED` |
| `structure_status` | `varchar(24)` | NOT NULL DEFAULT `'NOT_CHECKED'` | `NOT_CHECKED/PASSED/FAILED/OUTDATED` |
| `structure_checked_at` | `timestamptz` | NULL | 最近结构核对时间 |
| `structure_error_summary` | `jsonb` | NULL | 小型结构问题摘要 |
| `current_version_id` | `bigint` | NULL | 当前不可变 Route Version |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 当前投影乐观锁 |
| `deleted_at/deleted_by` |  | NULL | 逻辑删除 |
| `created_*/updated_*` |  |  | 审计字段 |

### 3.2 关键约束

```text
FK institution_id → institution(id) ON DELETE RESTRICT
FK dataset_id → standard_dataset(id) ON DELETE RESTRICT
FK source_datasource_id → source_datasource(id) ON DELETE RESTRICT
FK target_datasource_id → target_datasource(id) ON DELETE RESTRICT

CHECK source_object_type IN ('TABLE','VIEW','MATERIALIZED_VIEW')
CHECK status IN ('DISABLED','ENABLED')
CHECK structure_status IN ('NOT_CHECKED','PASSED','FAILED','OUTDATED')
CHECK revision >= 0
```

Source 必须直接属于同一机构：

```text
FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
ON DELETE RESTRICT
```

父表 `source_datasource` 提供：

```text
UNIQUE(id,institution_id)
```

### 3.3 未删除业务唯一性

```text
UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

同一机构 + Dataset 不创建并行 Route；切 Source/Schema/Object/Target 时修改当前 Route 并创建新 Route Version。

### 3.4 生命周期

- 新建默认 `DISABLED + NOT_CHECKED`。
- 结构核对通过只更新 `structure_status=PASSED`，不自动启用。
- 用户显式启用/停用 Route。
- Dataset 或 Source 结构变化可标记 `OUTDATED`。
- 已被未删除 Task 使用时不得物理删除。
- Route 不保存 Execution 状态、最近同步和 Precheck 问题明细。

## 4. `collection_route_version`

职责：一次规范化 Route 配置的不可变快照，是 Task、Execution、Precheck 和删除 Snapshot 的稳定配置引用。

### 4.1 字段

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | Route Version ID |
| `route_id` | `bigint` | NOT NULL | 父 Route |
| `version_no` | `integer` | NOT NULL | Route 内递增 |
| `institution_id` | `bigint` | NOT NULL | 单机构身份快照 |
| `dataset_id` | `bigint` | NOT NULL | Dataset 身份快照 |
| `dataset_version_id` | `bigint` | NOT NULL | Dataset 定义版本快照 |
| `source_datasource_id` | `bigint` | NOT NULL | Source 快照 |
| `source_schema` | `varchar(128)` | NULL | Schema 快照 |
| `source_object` | `varchar(256)` | NOT NULL | Source Object 快照 |
| `source_object_type` | `varchar(32)` | NOT NULL | Object Type |
| `target_datasource_id` | `bigint` | NOT NULL | Target 快照 |
| `structure_hash` | `char(64)` | NOT NULL | Source 结构 Hash |
| `contract_hash` | `char(64)` | NOT NULL | Route + 字段解析规范化 Hash |
| `created_at/created_by` |  |  | 创建审计 |

### 4.2 唯一与复合 FK 父键

```text
UNIQUE(route_id,version_no)
UNIQUE(route_id,contract_hash)
UNIQUE(route_id,id)

UNIQUE(id,institution_id,dataset_id)
UNIQUE(id,institution_id,dataset_id,dataset_version_id)
```

其中四元唯一键是当前 Task/Execution/Delete Snapshot 物理模型的标准父键：

```text
route_version_id
+ institution_id
+ dataset_id
+ dataset_version_id
```

这样能够由数据库直接保证一次运行引用的 Route Version、机构、Dataset 和 Dataset Version 属于同一不可变 Route Snapshot。

### 4.3 基础关系

```text
FK route_id → collection_route(id) ON DELETE RESTRICT
FK institution_id → institution(id) ON DELETE RESTRICT
FK dataset_id → standard_dataset(id) ON DELETE RESTRICT
FK dataset_version_id → standard_dataset_version(id) ON DELETE RESTRICT
FK source_datasource_id → source_datasource(id) ON DELETE RESTRICT
FK target_datasource_id → target_datasource(id) ON DELETE RESTRICT

FOREIGN KEY (dataset_id,dataset_version_id)
REFERENCES standard_dataset_version(dataset_id,id)
ON DELETE RESTRICT

FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
ON DELETE RESTRICT
```

Version 创建后只读，不因当前 Route 后续编辑而修改。

## 5. `route_field_resolution`

职责：Route Version 下标准字段到 JDBC 实际字段的只读解析快照。

核心字段：

```text
route_version_id
standard_field_id
field_code
source_column_name
source_ordinal
source_jdbc_type
source_type_name
resolved_at
```

约束：

```text
PRIMARY KEY(route_version_id,standard_field_id)
UNIQUE(route_version_id,field_code)
UNIQUE(route_version_id,lower(source_column_name))
```

只允许字段大小写差异，不保存重命名、表达式、默认值或人工转换规则。

## 6. Route 结构核对

至少检查：

- Source 可连接；
- Schema/Object 存在且类型允许；
- 标准字段与 Source 字段集合严格一致；
- 大小写不敏感后唯一匹配；
- Source 类型可由当前字段合同处理；
- 机构代码、业务主键、增量字段可正确解析；
- Target Doris 实际结构与预期合同可比较。

结果更新：

```text
structure_status
structure_checked_at
structure_error_summary
```

结构通过不自动启用 Route。

## 7. `sync_task` 关系摘要

Task 完整字段和行为以 `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` 为权威来源。

固定身份：

```text
institution_id
dataset_id
```

当前配置至少包括：

```text
dataset_version_id
route_version_id
task_kind/write_mode/doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
lookback_seconds
schedule_*
validation_method_override
revision
```

未删除唯一：

```text
UNIQUE INDEX uk_sync_task_active_identity
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

### 7.1 Route/Dataset Version 四元一致性

推荐只保留一条标准复合 FK：

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

不再引用任何 `collection_route_version_institution`。

### 7.2 编辑并发

活动同步状态：

```text
PENDING/RUNNING/LOADING/VALIDATING
```

存在时禁止编辑 Task，返回 `TASK_EXECUTION_ACTIVE`。

活动独立 Validation 不阻止普通 Task 编辑；本次 Validation 使用启动快照。

## 8. 三种标准 Task 组合

```text
无真实业务主键
→ FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY

有业务主键 + 增量字段
→ FULL_THEN_INCREMENTAL + UPSERT + UNIQUE_KEY

有业务主键、无增量字段
→ FULL_ONLY + UPSERT + UNIQUE_KEY
```

无主键任务只清理当前机构范围，不清空其他机构数据，不生成假主键。

## 9. `task_watermark` 摘要

```text
task_id PK
watermark_value
source_execution_id
revision
updated_at/updated_by
```

规则：

- 只属于 Task，不属于 Task Version。
- 成功同步 + SYNC_GATE PASS 后才推进。
- INITIAL_FULL 成功后使用启动 `T0` 建立初始 Watermark。
- 不在同一 INITIAL_FULL 内立即追加增量。
- 下一次正常运行创建独立 INCREMENTAL。
- Backfill 不修改正式 Watermark。
- Route 切换不自动重置。
- 不建立 Watermark History。

## 10. 验收

- Route 只有单个 `institution_id`。
- 不存在 `collection_route_institution/collection_route_version_institution`。
- Source 与 Route 机构一致性由复合 FK 保证。
- 一机构 + Dataset 一条未删除 Route。
- Route Version 提供四元身份父唯一键。
- Task 一机构 + Dataset 一个未删除 Task。
- Task 不存在版本表。
- Task/Execution/Delete Snapshot 的 Route Version 引用不能跨机构、跨 Dataset 或跨 Dataset Version。
- 历史运行上下文由 Execution/Validation 启动快照解释。

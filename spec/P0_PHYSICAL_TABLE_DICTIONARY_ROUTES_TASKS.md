# P0 物理表字典：机构采集路由、任务关联与水位

> 状态：阶段 1 Route/Task 关系模型收口完成  
> 更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 任务模型专项：`spec/P0_MUTABLE_TASK_MODEL_REVIEW.md`  
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
Route 覆盖机构当前关系表
Route 版本覆盖机构关系表
sync_task_version
task_validation_policy
```

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

Route 和 Task 都固定是一家机构上下文。

## 3. `collection_route`

职责：一家机构一个标准 Dataset 的当前采集映射及当前版本指针。

### 3.1 字段

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | Route ID |
| `institution_id` | `bigint` | NOT NULL | 当前机构 |
| `dataset_id` | `bigint` | NOT NULL | 标准 Dataset |
| `source_datasource_id` | `bigint` | NOT NULL | 当前源端数据源 |
| `source_schema` | `varchar(128)` | NULL | 当前 Schema |
| `source_object` | `varchar(256)` | NOT NULL | 当前真实对象 |
| `source_object_type` | `varchar(32)` | NOT NULL | `TABLE/VIEW/MATERIALIZED_VIEW` |
| `target_datasource_id` | `bigint` | NOT NULL | 当前目标 Doris |
| `status` | `varchar(16)` | NOT NULL DEFAULT `'DISABLED'` | `DISABLED/ENABLED` |
| `structure_status` | `varchar(24)` | NOT NULL DEFAULT `'NOT_CHECKED'` | `NOT_CHECKED/PASSED/FAILED/OUTDATED` |
| `structure_checked_at` | `timestamptz` | NULL | 最近结构核对完成时间 |
| `structure_error_summary` | `jsonb` | NULL | 小型结构问题摘要 |
| `current_version_id` | `bigint` | NULL | 当前不可变 Route version |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁 |
| `deleted_at` | `timestamptz` | NULL | 逻辑删除 |
| `deleted_by` | `bigint` | NULL | 删除人 |
| `created_at/created_by` |  |  | 创建审计 |
| `updated_at/updated_by` |  |  | 更新审计 |

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

源数据源机构一致性使用复合外键：

```text
FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
ON DELETE RESTRICT
```

因此 `source_datasource` 必须提供 `UNIQUE(id,institution_id)`。

### 3.3 唯一性

当前产品一所机构一个 Dataset 只维护一条未删除 Route：

```text
UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

切换 Source/Schema/Object/Target 时不创建第二条并行 Route，而是更新当前投影并生成新 `collection_route_version`。

### 3.4 生命周期

- 新建 Route 默认 `DISABLED + NOT_CHECKED`；
- 结构核对通过只把 `structure_status` 置为 `PASSED`，不自动启用；
- 用户显式启用后才变为 `ENABLED`；
- Dataset 或源结构变化可标记 `OUTDATED`；
- 已被未删除 Task 使用时不能物理删除，只允许逻辑删除条件满足后保留历史引用；
- Route 不保存任务运行状态、最近执行或预检问题明细。

## 4. `collection_route_version`

职责：一次规范化 Route 配置的不可变快照。

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | 版本 ID |
| `route_id` | `bigint` | NOT NULL | FK `collection_route(id)` |
| `version_no` | `integer` | NOT NULL | Route 内递增 |
| `institution_id` | `bigint` | NOT NULL | 机构快照 |
| `dataset_id` | `bigint` | NOT NULL | Dataset 身份快照 |
| `dataset_version_id` | `bigint` | NOT NULL | Dataset 定义版本 |
| `source_datasource_id` | `bigint` | NOT NULL | Source 快照 |
| `source_schema` | `varchar(128)` | NULL | Schema |
| `source_object` | `varchar(256)` | NOT NULL | 源对象 |
| `source_object_type` | `varchar(32)` | NOT NULL | 对象类型 |
| `target_datasource_id` | `bigint` | NOT NULL | Target 快照 |
| `structure_hash` | `char(64)` | NOT NULL | 实际源结构 Hash |
| `contract_hash` | `char(64)` | NOT NULL | 规范化 Route/字段解析合同 Hash |
| `created_at` | `timestamptz` | NOT NULL | 创建时间 |
| `created_by` | `bigint` | NULL | 创建人 |

唯一/支撑约束：

```text
UNIQUE(route_id,version_no)
UNIQUE(route_id,contract_hash)
UNIQUE(route_id,id)
UNIQUE(id,institution_id,dataset_id)
```

版本创建后只读，不因 Route 后续编辑而修改。

## 5. `route_field_resolution`

职责：Route version 下标准字段到 JDBC 实际字段的解析快照。

建议字段：

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

不保存目标字段改名、表达式、默认值或人工转换规则。

## 6. Route 结构核对

结构核对属于 Route 配置事实，至少验证：

- Source 连接可用；
- Schema/Object 存在且类型允许；
- 标准字段与源字段集合严格一致；
- 大小写不敏感唯一匹配；
- 源类型可由当前字段合同处理；
- 机构代码字段、业务主键和增量字段能正确解析；
- 目标 Doris 当前结构可与预期合同对比。

输出更新 `structure_status/structure_checked_at/structure_error_summary`；不自动启用 Route。

## 7. `sync_task`

任务采用“固定身份 + 当前配置覆盖”，不建立任务版本表。

### 7.1 固定身份

```text
institution_id
dataset_id
```

创建后不可修改。

### 7.2 当前配置

至少保存：

```text
id
institution_id
dataset_id
dataset_version_id
route_version_id
name
task_kind
write_mode
doris_key_model
incremental_field_code
fetch_size
upper_bound_delay_minutes
schedule_mode
schedule_interval_hours
schedule_cron
schedule_timezone
schedule_source
schedule_source_revision
schedule_enabled
validation_method_override
revision
deleted_at/deleted_by
created_*/updated_*
```

未删除唯一：

```text
UNIQUE INDEX uk_sync_task_active_identity
ON sync_task(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

### 7.3 Route version 一致性

`collection_route_version` 提供：

```text
UNIQUE(id,institution_id,dataset_id)
```

Task 使用：

```text
FOREIGN KEY (route_version_id,institution_id,dataset_id)
REFERENCES collection_route_version(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

这样数据库直接保证任务不能引用其他机构或其他 Dataset 的 Route version。

`dataset_version_id` 同样必须属于 `dataset_id`。

### 7.4 编辑并发

存在活动同步执行：

```text
PENDING/RUNNING/LOADING/VALIDATING
```

时禁止编辑任务，返回 `TASK_EXECUTION_ACTIVE`。

活动独立校验不阻止普通任务配置编辑；独立校验使用启动快照。

## 8. 三种标准任务组合

```text
无真实业务主键:
FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY

有真实业务主键 + 增量字段:
FULL_THEN_INCREMENTAL + UPSERT + UNIQUE_KEY

有真实业务主键、无增量字段:
FULL_ONLY + UPSERT + UNIQUE_KEY
```

无主键任务只清理当前机构范围，不清空其他机构数据，不生成假主键。

## 9. `task_watermark`

职责：任务当前正式增量水位。

建议字段：

```text
task_id PK
watermark_value
source_execution_id
updated_at
```

规则：

- 只属于 Task，不属于 Task version；
- 成功同步且阻断校验通过后才推进；
- 空增量窗口成功也可推进；
- 补采不修改；
- 任务切换 Route 不自动重置；
- 不建立水位历史表，历史范围从 Execution 查询。

## 10. Task 创建与 Route 状态

管理端新建 Task 时：

1. 选择机构和 Dataset；
2. 只列出同一机构、同一 Dataset 的 Route；
3. Route 必须处于当前允许创建任务的业务状态；
4. 保存 Task 当前 `route_version_id/dataset_version_id`；
5. 形成固定 `institution_id + dataset_id` 身份。

数据预检问题作为风险信息展示，不把预检中间结果写入 Task。

## 11. 验收

- Route 只有单个 `institution_id`；
- 不存在任何 Route 多机构覆盖表；
- Source 与 Route 机构一致性由复合外键保证；
- 一机构一 Dataset 只有一条未删除 Route；
- Task 一机构一 Dataset 只有一个未删除任务；
- Task 不存在版本表；
- Task 的 Route version 不能跨机构、跨 Dataset；
- 历史运行上下文由 Execution/Validation 快照解释。

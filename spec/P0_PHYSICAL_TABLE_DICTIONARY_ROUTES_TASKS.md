# P0 物理表字典：机构采集路由与 Task 关系

> 状态：阶段 1 FK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> FK 基线：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`  
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

明确不建立：

```text
collection_route_institution
collection_route_version_institution
sync_task_version
task_validation_policy
```

Route 和 Task 均固定是一家机构上下文。

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

FK 采用已确认原则：最强复合 FK 优先；历史 `RESTRICT`；纯配置子对象才允许 `CASCADE`；被强复合 FK 完全覆盖的重复单列 FK 不进入 V1。

## 3. `collection_route`

职责：一家机构一个标准 Dataset 的当前采集映射、业务状态、结构核对状态和当前 Route Version 指针。

### 3.1 字段

```text
id bigint identity PK
institution_id bigint NOT NULL
dataset_id bigint NOT NULL
source_datasource_id bigint NOT NULL
source_schema varchar(128) NULL
source_object varchar(256) NOT NULL
source_object_type varchar(32) NOT NULL
target_datasource_id bigint NOT NULL
status varchar(16) NOT NULL DEFAULT 'DISABLED'
structure_status varchar(24) NOT NULL DEFAULT 'NOT_CHECKED'
structure_checked_at timestamptz NULL
structure_error_summary jsonb NULL
current_version_id bigint NULL
revision bigint NOT NULL DEFAULT 0
deleted_at timestamptz NULL
deleted_by bigint NULL
created_at/created_by
updated_at/updated_by
```

### 3.2 基础 CHECK

```text
CHECK source_object_type IN ('TABLE','VIEW','MATERIALIZED_VIEW')
CHECK status IN ('DISABLED','ENABLED')
CHECK structure_status IN ('NOT_CHECKED','PASSED','FAILED','OUTDATED')
CHECK revision >= 0
```

### 3.3 FK

Dataset 当前身份：

```text
FOREIGN KEY (dataset_id)
REFERENCES standard_dataset(id)
ON DELETE RESTRICT
```

Source 必须属于同一机构：

```text
FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
ON DELETE RESTRICT
```

因此不再额外建立 `institution_id → institution(id)`；机构存在性已由 Source 强复合关系覆盖。

Target：

```text
FOREIGN KEY (target_datasource_id)
REFERENCES target_datasource(id)
ON DELETE RESTRICT
```

当前 Version 必须属于自身 Route：

```text
FOREIGN KEY (id,current_version_id)
REFERENCES collection_route_version(route_id,id)
ON DELETE RESTRICT
DEFERRABLE INITIALLY DEFERRED
```

初次创建 Route 时可以先插入当前行、再创建 Version、最后在同一事务切换 `current_version_id`。

### 3.4 Parent Unique / Business Unique

为 Route Version 提供同父身份键：

```text
UNIQUE(id,institution_id,dataset_id)
```

当前业务唯一：

```text
UNIQUE INDEX uk_collection_route_active_identity
ON collection_route(institution_id,dataset_id)
WHERE deleted_at IS NULL
```

### 3.5 FK 子索引

```text
INDEX idx_collection_route_dataset
ON collection_route(dataset_id,deleted_at,id)

INDEX idx_collection_route_source_institution
ON collection_route(source_datasource_id,institution_id,deleted_at,id)

INDEX idx_collection_route_target
ON collection_route(target_datasource_id,deleted_at,id)
```

同一机构 + Dataset 不创建并行 Route；切换 Source/Schema/Object/Target 时更新当前投影并生成新 Version。

## 4. `collection_route_version`

职责：一次规范化 Route 配置的不可变快照，是 Task、Execution、Precheck、Delete Snapshot 的稳定配置引用。

### 4.1 字段

```text
id bigint identity PK
route_id bigint NOT NULL
version_no integer NOT NULL
institution_id bigint NOT NULL
dataset_id bigint NOT NULL
dataset_version_id bigint NOT NULL
source_datasource_id bigint NOT NULL
source_schema varchar(128) NULL
source_object varchar(256) NOT NULL
source_object_type varchar(32) NOT NULL
target_datasource_id bigint NOT NULL
structure_hash char(64) NOT NULL
contract_hash char(64) NOT NULL
created_at timestamptz NOT NULL
created_by bigint NULL
```

### 4.2 强复合 FK

Version 的父 Route 身份必须完全一致：

```text
FOREIGN KEY (route_id,institution_id,dataset_id)
REFERENCES collection_route(id,institution_id,dataset_id)
ON DELETE RESTRICT
```

Dataset Version 必须属于同一个 Dataset：

```text
FOREIGN KEY (dataset_id,dataset_version_id)
REFERENCES standard_dataset_version(dataset_id,id)
ON DELETE RESTRICT
```

Source 必须属于同一个 Institution：

```text
FOREIGN KEY (source_datasource_id,institution_id)
REFERENCES source_datasource(id,institution_id)
ON DELETE RESTRICT
```

Target：

```text
FOREIGN KEY (target_datasource_id)
REFERENCES target_datasource(id)
ON DELETE RESTRICT
```

被这些复合 FK 完全覆盖的单列 FK 不进入 V1：

```text
route_id → collection_route(id)
institution_id → institution(id)
dataset_id → standard_dataset(id)
dataset_version_id → standard_dataset_version(id)
source_datasource_id → source_datasource(id)
```

### 4.3 支撑 Unique

```text
UNIQUE(route_id,version_no)
UNIQUE(route_id,contract_hash)
UNIQUE(route_id,id)

UNIQUE(id,dataset_version_id)
UNIQUE(id,institution_id,dataset_id,dataset_version_id)
```

其中：

- `(route_id,id)` 支撑 Route `current_version_id` 和 Precheck Route/Version 同父关系；
- `(id,dataset_version_id)` 支撑 Field Resolution；
- 四元键是 Task/Execution/Delete Snapshot 的统一业务身份父键。

### 4.4 FK 子索引

```text
INDEX idx_route_version_parent
ON collection_route_version(route_id,institution_id,dataset_id,version_no)

INDEX idx_route_version_dataset_version
ON collection_route_version(dataset_id,dataset_version_id,id)

INDEX idx_route_version_source_institution
ON collection_route_version(source_datasource_id,institution_id,id)

INDEX idx_route_version_target
ON collection_route_version(target_datasource_id,id)
```

Version 创建完成后只读，不因当前 Route 后续编辑而修改。

## 5. `route_field_resolution`

职责：Route Version 下标准字段到 JDBC 实际字段的只读解析快照。

### 5.1 最终字段

```text
route_version_id bigint NOT NULL
dataset_version_id bigint NOT NULL
standard_field_id bigint NOT NULL
source_column_name varchar(256) NOT NULL
source_ordinal integer NOT NULL
source_jdbc_type integer NOT NULL
source_type_name varchar(128) NULL
resolved_at timestamptz NOT NULL
```

删除重复字段：

```text
field_code
```

标准 Field Code 通过：

```text
standard_field_id → standard_dataset_field.field_code
```

读取，不再持久化第二份字段身份。

### 5.2 FK

Resolution 的 Dataset Version 必须等于 Route Version 使用的 Dataset Version：

```text
FOREIGN KEY (route_version_id,dataset_version_id)
REFERENCES collection_route_version(id,dataset_version_id)
ON DELETE RESTRICT
```

Standard Field 必须属于同一个 Dataset Version：

```text
FOREIGN KEY (dataset_version_id,standard_field_id)
REFERENCES standard_dataset_field(dataset_version_id,id)
ON DELETE RESTRICT
```

因此数据库直接保证 Standard Field 不会跨 Dataset Version 绑定到 Route。

### 5.3 主键/唯一

```text
PRIMARY KEY(route_version_id,standard_field_id)
UNIQUE(route_version_id,lower(source_column_name))
```

只允许大小写差异匹配；不保存重命名、表达式、默认值或人工转换规则。

## 6. Route 结构核对

至少检查：

- Source 可连接；
- Schema/Object 存在且类型允许；
- 标准字段与 Source 字段集合严格一致；
- 大小写不敏感后唯一匹配；
- Source 类型可由当前字段合同处理；
- 机构代码、业务主键、增量字段可解析；
- Target Doris 实际结构可与预期合同比较。

结构结果只更新：

```text
structure_status
structure_checked_at
structure_error_summary
```

结构通过不自动启用 Route。

## 7. `sync_task` 关系摘要

完整字段和生命周期以 `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` 为准。

最终 Route/Dataset/Institution 只保留一条四元强 FK：

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

不再保留被它覆盖的单列/三列 FK。

## 8. 三种标准 Task 组合

```text
无真实业务主键
→ FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY

有业务主键 + 增量字段
→ FULL_THEN_INCREMENTAL + UPSERT + UNIQUE_KEY

有业务主键、无增量字段
→ FULL_ONLY + UPSERT + UNIQUE_KEY
```

无主键任务只清理当前机构范围，不生成假主键。

## 9. `task_watermark` 摘要

```text
task_id PK
watermark_value
source_execution_id
revision
updated_at/updated_by
```

Source Execution 使用“同 Task”复合 FK，详细见 Task/Watermark 字典和 FK Matrix。

## 10. 验收

- Route 只有单个 `institution_id`。
- 不存在 Route/Route Version 多机构关系表。
- Route Version 通过复合 FK 证明 Institution/Dataset 快照属于父 Route。
- `current_version_id` 只能指向自身 Route Version。
- Source 与 Route/Route Version 的机构一致性由复合 FK 保证。
- Field Resolution 的 Standard Field 必须属于 Route Version 对应 Dataset Version。
- `route_field_resolution` 不再重复保存 `field_code`。
- Task 只使用四元 Route Version FK。
- 历史运行上下文由 Execution/Validation 启动快照解释。

# P0 物理表字典：机构、业务目录与数据源

> 状态：阶段 1 资源模型收口完成  
> 更新：2026-08-17  
> 适用数据库：新系统独立 PostgreSQL 元数据库  
> 总体字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 本批次对象

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

当前资源模型没有独立系统实例对象和任何实例—机构、实例—数据源关联表。

## 2. 共同规则

- 业务编码保存 `btrim` 后原值，唯一性按 `lower(code)`；
- `code` 创建后普通编辑不可修改；
- 可变资源使用 `revision` 乐观锁；
- 用户启停状态与连接测试状态分开；
- 已被 Route/Task/历史引用的资源不得物理删除，只能停用；
- 密码使用加密密文，接口只返回掩码；
- 删除、启停、凭据修改和连接测试记录成功/失败审计。

## 3. `institution`

职责：医共体内医疗机构主数据，扁平集合。

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | 内部主键 |
| `code` | `varchar(64)` | NOT NULL | 医疗机构编码 |
| `name` | `varchar(200)` | NOT NULL | 名称 |
| `short_name` | `varchar(100)` | NULL | 简称 |
| `institution_type` | `varchar(32)` | NULL | 医疗机构类型 |
| `institution_level` | `varchar(16)` | NULL | 等级 |
| `region_code` | `varchar(20)` | NULL | 行政区划 |
| `status` | `varchar(16)` | NOT NULL DEFAULT `'ENABLED'` | `ENABLED/DISABLED` |
| `description` | `text` | NULL | 说明 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁 |
| `created_at/created_by` |  |  | 创建审计 |
| `updated_at/updated_by` |  |  | 更新审计 |

约束/索引：

```text
CHECK btrim(code) <> ''
CHECK btrim(name) <> ''
CHECK status IN ('ENABLED','DISABLED')
CHECK revision >= 0
UNIQUE INDEX uk_institution_code_ci ON institution(lower(code))
INDEX idx_institution_status ON institution(status,id)
INDEX idx_institution_name_ci ON institution(lower(name),id)
```

明确不建立 `parent_id/path/ancestor_ids` 等机构树字段。

## 4. `business_catalog`

职责：HIS/LIS/PACS/EMR 等全局轻量业务分类。

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | 主键 |
| `code` | `varchar(64)` | NOT NULL | 稳定业务编码 |
| `name` | `varchar(100)` | NOT NULL | 展示名称 |
| `description` | `varchar(500)` | NULL | 说明 |
| `status` | `varchar(16)` | NOT NULL DEFAULT `'ENABLED'` | 启停 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁 |
| `created_at/created_by` |  |  | 创建审计 |
| `updated_at/updated_by` |  |  | 更新审计 |

约束/索引：

```text
CHECK btrim(code) <> ''
CHECK btrim(name) <> ''
CHECK status IN ('ENABLED','DISABLED')
UNIQUE INDEX uk_business_catalog_code_ci ON business_catalog(lower(code))
INDEX idx_business_catalog_status ON business_catalog(status,id)
```

业务目录被源数据源引用后不能物理删除；可停用。停用目录不能用于新建数据源，已有历史继续解释。

## 5. `source_datasource`

职责：DFETL 连接某一家机构某一业务数据库的资源。

### 5.1 字段

| 列 | 类型 | 空值/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | `bigint identity` | PK | 主键 |
| `code` | `varchar(100)` | NOT NULL | 稳定数据源编码 |
| `name` | `varchar(200)` | NOT NULL | 名称 |
| `institution_id` | `bigint` | NOT NULL | FK `institution(id)` |
| `business_catalog_id` | `bigint` | NOT NULL | FK `business_catalog(id)` |
| `db_type` | `varchar(24)` | NOT NULL | `MYSQL/POSTGRESQL/ORACLE/SQLSERVER` |
| `connection_mode` | `varchar(16)` | NOT NULL | `HOST_PORT/JDBC_URL` |
| `host` | `varchar(255)` | NULL | HOST_PORT 必填 |
| `port` | `integer` | NULL | HOST_PORT 必填 |
| `database_name` | `varchar(128)` | NULL | HOST_PORT 必填 |
| `default_schema` | `varchar(128)` | NULL | Route 默认选择值 |
| `jdbc_url` | `text` | NULL | JDBC_URL 必填，不含账号密码 |
| `username` | `varchar(128)` | NOT NULL | 数据库账号 |
| `password_enc` | `text` | NOT NULL | 加密密码 |
| `ssl_enabled` | `boolean` | NOT NULL DEFAULT `false` | HOST_PORT URL 构造参数 |
| `read_only` | `boolean` | NOT NULL DEFAULT `true` | 请求只读连接 |
| `query_timeout_seconds` | `integer` | NOT NULL DEFAULT `60` | 查询超时 |
| `connect_timeout_seconds` | `integer` | NOT NULL DEFAULT `30` | 建连超时 |
| `socket_timeout_seconds` | `integer` | NOT NULL DEFAULT `60` | Socket 超时 |
| `pool_max_size` | `integer` | NOT NULL DEFAULT `10` | 池上限 |
| `status` | `varchar(16)` | NOT NULL DEFAULT `'ENABLED'` | 用户启停 |
| `last_test_status` | `varchar(16)` | NOT NULL DEFAULT `'UNTESTED'` | `UNTESTED/SUCCESS/FAILED` |
| `last_tested_at` | `timestamptz` | NULL | 最近测试 |
| `last_test_error` | `varchar(1000)` | NULL | 脱敏错误 |
| `description` | `text` | NULL | 说明 |
| `revision` | `bigint` | NOT NULL DEFAULT `0` | 乐观锁 |
| `created_at/created_by` |  |  | 创建审计 |
| `updated_at/updated_by` |  |  | 更新审计 |

### 5.2 关键约束

```text
FK institution_id → institution(id) ON DELETE RESTRICT
FK business_catalog_id → business_catalog(id) ON DELETE RESTRICT
CHECK db_type IN ('MYSQL','POSTGRESQL','ORACLE','SQLSERVER')
CHECK connection_mode IN ('HOST_PORT','JDBC_URL')
CHECK status IN ('ENABLED','DISABLED')
CHECK last_test_status IN ('UNTESTED','SUCCESS','FAILED')
CHECK port IS NULL OR port BETWEEN 1 AND 65535
CHECK query/connect/socket timeout > 0
CHECK pool_max_size > 0
```

连接模式互斥：

```text
HOST_PORT:
  host/port/database_name 非空，jdbc_url 为空
JDBC_URL:
  jdbc_url 非空，host/port/database_name 为空
```

索引：

```text
UNIQUE INDEX uk_source_datasource_code_ci ON source_datasource(lower(code))
UNIQUE (id,institution_id)
INDEX idx_source_datasource_institution ON source_datasource(institution_id,status,id)
INDEX idx_source_datasource_business ON source_datasource(business_catalog_id,status,id)
INDEX idx_source_datasource_name_ci ON source_datasource(lower(name),id)
```

### 5.3 归属边界

- `institution_id`、`business_catalog_id` 表示资源归属，不由 Route 覆盖；
- 未被引用时可受控修改；一旦被 Route 或历史引用，归属变更应拒绝，用户新建正确数据源；
- 不保存具体 Dataset、Schema、Table/View 或目标表；
- Route 只能选择当前机构所属且 `ENABLED` 的数据源。

## 6. `target_datasource`

职责：逻辑 Doris 部署。

建议字段：

```text
id
code
name
database_name
username
password_enc
http_port
status
last_test_status
last_tested_at
last_test_error
revision
created_*/updated_*
```

固定规则：

- `status` 为 `ENABLED/DISABLED`；
- 测试聚合允许 `UNTESTED/SUCCESS/PARTIAL/FAILED`；
- 不保存 institution/business/dataset/target_table 关系；
- 数据库连接密码加密保存。

## 7. `target_datasource_fe_endpoint`

职责：一个逻辑 Doris 下的 FE 端点。

建议字段：

```text
id
target_datasource_id
host
query_port
http_port
enabled
ordinal_no
last_test_status
last_tested_at
last_test_error
created_at/created_by
updated_at/updated_by
```

关键约束：

```text
FK target_datasource_id → target_datasource(id) ON DELETE CASCADE
UNIQUE(target_datasource_id,host,query_port)
UNIQUE(target_datasource_id,ordinal_no)
```

不管理 BE。

## 8. 删除和停用

- 已有 Route/Task/Execution/Validation/审计历史引用的机构、源数据源和目标数据源不得物理删除；
- 业务目录被数据源引用后不得物理删除；
- 停用资源不破坏历史，但新建 Route/Task 时不再可选；
- 执行启动前重新校验机构、源数据源、目标数据源和 Route 当前业务状态。

## 9. 验收

- 资源表只有 `institution/business_catalog/source_datasource/target_datasource/target_datasource_fe_endpoint`；
- 源数据源直接且唯一归属一家机构和一个业务目录；
- 不存在任何系统实例多对多中间表；
- Route 无需选择额外实例对象即可从当前机构筛选源数据源；
- 页面能够通过“机构 → 源数据源 → Route”解释完整来源链路。

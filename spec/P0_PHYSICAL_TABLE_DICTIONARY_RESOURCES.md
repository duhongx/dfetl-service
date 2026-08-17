# P0 物理表字典：机构、业务目录与数据源

> 状态：阶段 1 Resource FK + Unique + Status/CHECK + Delete Behavior Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 总体字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Status/CHECK：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
> Delete Behavior：`spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md`  
> 限制：本文不是 Flyway SQL；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 当前对象

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint
```

没有 Business System Instance 或实例—机构/数据源关联表。

## 2. Resource 通用规则

- 稳定 `code` 创建后普通编辑不可修改，按 `lower(code)` 唯一。
- 可变资源使用 `revision` 乐观锁。
- 用户启停状态与连接测试结果独立。
- Password 使用加密密文，接口只返回掩码。
- 普通 `created_by/updated_by → app_user(id) ON DELETE SET NULL`。
- Institution/Business Catalog/Source/Target **不增加 `deleted_at`**。
- 这四类 Resource：无任何当前/历史引用时允许物理删除；存在引用时数据库 `RESTRICT`，产品引导用户停用。
- 删除前 Service 必须返回引用/阻塞摘要，不能通过级联删除历史来“删干净”。

## 3. `institution`

核心字段：

```text
id
code
name/short_name
institution_type/institution_level/region_code
status
description
revision
created_*/updated_*
```

```text
status: ENABLED / DISABLED
```

CHECK：

```text
btrim(code) <> ''
btrim(name) <> ''
revision >= 0
```

Business Unique：

```text
UNIQUE INDEX uk_institution_code_ci ON institution(lower(code))
```

删除：未被 Source、External Client Institution 等对象引用时可物理删除；有引用时只能 `DISABLED`。不建立机构树字段。

## 4. `business_catalog`

核心字段：`id/code/name/description/status/revision/created_*/updated_*`。

```text
status: ENABLED / DISABLED
UNIQUE INDEX uk_business_catalog_code_ci ON business_catalog(lower(code))
```

未被 Source 引用时可物理删除；有引用时只能停用。

## 5. `source_datasource`

核心字段：

```text
id/code/name
institution_id/business_catalog_id
db_type/connection_mode
host/port/database_name/default_schema/jdbc_url
username/password_enc
ssl_enabled/read_only
query_timeout_seconds/connect_timeout_seconds/socket_timeout_seconds
pool_max_size
status
last_test_status/last_tested_at/last_test_error
description/revision/created_*/updated_*
```

枚举：

```text
db_type: MYSQL / POSTGRESQL / ORACLE / SQLSERVER
connection_mode: HOST_PORT / JDBC_URL
status: ENABLED / DISABLED
last_test_status: UNTESTED / SUCCESS / FAILED
```

FK：

```text
institution_id → institution(id) RESTRICT
business_catalog_id → business_catalog(id) RESTRICT
```

连接模式：

```text
HOST_PORT → host/port/database_name 非空，jdbc_url 为空
JDBC_URL  → jdbc_url 非空，host/port/database_name 为空
```

基础 CHECK：

```text
port IS NULL OR port BETWEEN 1 AND 65535
query/connect/socket timeout > 0
pool_max_size > 0
revision >= 0
```

测试状态：

```text
UNTESTED → last_tested_at/error 均为空
SUCCESS  → last_tested_at 非空，error 为空
FAILED   → last_tested_at 非空，error 非空
```

Business Unique / FK Support：

```text
UNIQUE INDEX uk_source_datasource_code_ci ON source_datasource(lower(code))
UNIQUE(id,institution_id)
```

删除：从未进入 Route/Route Version/历史链且没有其他引用时可物理删除；一旦被引用只能 `DISABLED`。

## 6. `target_datasource`

核心字段：

```text
id/code/name/database_name/username/password_enc/http_port
status
last_test_status/last_tested_at/last_test_error
revision
created_*/updated_*
```

```text
status: ENABLED / DISABLED
last_test_status: UNTESTED / SUCCESS / PARTIAL / FAILED
```

测试组合：

```text
UNTESTED → last_tested_at/error 均为空
SUCCESS  → last_tested_at 非空，error 为空
PARTIAL  → last_tested_at 非空，error 非空
FAILED   → last_tested_at 非空，error 非空
```

稳定 `code` 大小写不敏感唯一。删除：未被 Route/Route Version/Delete Snapshot 等引用时可物理删除；有引用只能 `DISABLED`。

## 7. `target_datasource_fe_endpoint`

核心字段：

```text
id
target_datasource_id
host/query_port/http_port
enabled
ordinal_no
last_test_status/last_tested_at/last_test_error
created_*/updated_*
```

FK：

```text
target_datasource_id → target_datasource(id) ON DELETE CASCADE
```

Business Unique：

```text
UNIQUE(target_datasource_id,host,query_port)
UNIQUE(target_datasource_id,ordinal_no)
```

这是纯当前配置：管理员从 Target 移除 FE 时直接物理删除 Endpoint；它不承担独立历史。

## 8. 状态与删除独立

资源启停、Test Status、Delete Eligibility 是三个不同事实：

- `ENABLED` 不要求最近 Test 必须 SUCCESS。
- `DISABLED` 不等于已删除。
- “可删除”只由当前/历史引用情况决定。

创建 Route、执行启动等入口按当前业务 Gate 检查资源状态。

## 9. 验收

- Resource 表固定 5 张。
- Source 直接属于一个 Institution + Business Catalog。
- Resource 不增加逻辑删除字段。
- Institution/Business Catalog/Source/Target 无引用可物理删，有引用只能停用。
- FE Endpoint 是纯当前配置，可直接物理删除。
- Test Status 与 Business Status 保持独立。
- 不恢复 Business System Instance。

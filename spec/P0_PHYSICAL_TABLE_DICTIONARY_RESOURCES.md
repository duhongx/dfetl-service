# P0 物理表字典：机构、业务目录与数据源

> 状态：阶段 1 Resource FK + Unique + Status/Enum/CHECK Matrix 已确认并收口  
> 最近更新：2026-08-17  
> 总体字典：`spec/P0_PHYSICAL_TABLE_DICTIONARY.md`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Status/CHECK 基线：`spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md`  
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

## 2. 通用规则

- 稳定 `code` 创建后普通编辑不可修改。
- Code 业务唯一按 `lower(code)`。
- 可变资源使用 `revision` 乐观锁。
- 用户启停状态与连接测试结果保持独立。
- 已被 Route/Task/历史引用的资源不得物理删除。
- Password 使用加密密文，接口只返回掩码。
- 普通 `created_by/updated_by → app_user(id) ON DELETE SET NULL`。

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
status:
ENABLED
DISABLED
```

CHECK：

```text
btrim(code) <> ''
btrim(name) <> ''
revision >= 0
```

Business Unique：

```text
UNIQUE INDEX uk_institution_code_ci
ON institution(lower(code))
```

不建立机构树字段。

## 4. `business_catalog`

核心字段：`id/code/name/description/status/revision/created_*/updated_*`。

```text
status:
ENABLED
DISABLED
```

Business Unique：

```text
UNIQUE INDEX uk_business_catalog_code_ci
ON business_catalog(lower(code))
```

被 Source 引用后不得物理删除；可停用。

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
db_type:
MYSQL / POSTGRESQL / ORACLE / SQLSERVER

connection_mode:
HOST_PORT / JDBC_URL

status:
ENABLED / DISABLED

last_test_status:
UNTESTED / SUCCESS / FAILED
```

FK：

```text
institution_id → institution(id) RESTRICT
business_catalog_id → business_catalog(id) RESTRICT
```

连接模式 CHECK：

```text
HOST_PORT
→ host/port/database_name 非空
→ jdbc_url 为空

JDBC_URL
→ jdbc_url 非空
→ host/port/database_name 为空
```

基础 CHECK：

```text
port IS NULL OR port BETWEEN 1 AND 65535
query/connect/socket timeout > 0
pool_max_size > 0
revision >= 0
```

连接测试 CHECK：

```text
UNTESTED
→ last_tested_at IS NULL
→ last_test_error IS NULL

SUCCESS
→ last_tested_at IS NOT NULL
→ last_test_error IS NULL

FAILED
→ last_tested_at IS NOT NULL
→ last_test_error IS NOT NULL
```

Business Unique / FK Support：

```text
UNIQUE INDEX uk_source_datasource_code_ci
ON source_datasource(lower(code))

UNIQUE(id,institution_id)
```

Route 只能选择当前 Institution 所属且 `ENABLED` 的 Source；`ENABLED` 不要求最近 Test 必须 SUCCESS。

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
status:
ENABLED / DISABLED

last_test_status:
UNTESTED / SUCCESS / PARTIAL / FAILED
```

测试组合：

```text
UNTESTED → last_tested_at/error 均为空
SUCCESS  → last_tested_at 非空，error 为空
PARTIAL  → last_tested_at 非空，error 非空
FAILED   → last_tested_at 非空，error 非空
```

`PARTIAL` 只表示多个 FE 中部分成功；Error 保存失败 FE 的脱敏摘要。

稳定 `code` 按大小写不敏感唯一；不绑定 Institution/Business Catalog/Dataset。

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

测试状态：

```text
UNTESTED / SUCCESS / FAILED
```

使用与 Source 相同的 `last_tested_at/last_test_error` 组合 CHECK。

## 8. 状态独立性

资源启停和 Test Status 不做数据库耦合：

```text
status=ENABLED + last_test_status=UNTESTED/FAILED
```

是允许的配置事实。创建 Route、执行启动等入口再按业务规则检查当前资源可用性。

## 9. 验收

- 资源表固定为 5 张。
- Source 直接且唯一归属一个 Institution + Business Catalog。
- Source/FE 单点测试使用 `UNTESTED/SUCCESS/FAILED`。
- Target 聚合测试额外允许 `PARTIAL`。
- Test Status 与时间/错误字段组合由 CHECK 保证。
- Resource Business Status 与 Test Status 保持独立。
- 不恢复 Business System Instance。

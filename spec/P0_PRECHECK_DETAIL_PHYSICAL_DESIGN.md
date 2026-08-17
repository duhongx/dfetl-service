# P0 数据预检问题明细物理设计

> 状态：`CONFIRMED_FOR_SIGNOFF`  
> 日期：2026-08-17  
> 工作包：`C1`  
> 产品依据：`CURRENT_CONFIRMED_PROCESS_RULES.md`、`FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`  
> 实施边界：本文冻结物理职责、表职责、生命周期、查询和导出合同；当前不创建 Flyway、不修改 Java、不创建 Doris 表。

## 1. 最终结论

P0 采用三层职责分离：

```text
PostgreSQL 控制面
  ├─ precheck_run
  ├─ precheck_issue_summary
  ├─ precheck_detail_manifest
  └─ export_job

Doris 明细面
  ├─ raw_precheck_<dataset_hash>_v<dataset_version>
  ├─ dfetl_precheck_issue_record
  └─ dfetl_precheck_issue_item

S3 兼容对象存储导出面
  └─ MinIO / S3 Export Object
```

关键原则：

1. PostgreSQL 长期保存运行事实、汇总和明细存储清单，不保存海量问题行、完整原始行或敏感原值。
2. Doris 保存限期原始预检记录、问题记录和问题项，承担分页、筛选、下钻及导出查询。
3. 导出文件写入 S3 兼容对象存储；生产环境不以单机本地目录作为共享下载介质。
4. 问题明细以“问题源记录”为页面顶层，以“字段/组合规则问题项”为规范化明细；一条问题记录可以有多个问题项。
5. 敏感原值不在问题项表重复保存。授权查看或敏感导出时，按运行、记录定位和字段从限期 RAW 表回读。
6. 正式同步不读取、复制或提升任何预检 RAW/明细数据，始终重新读取真实源对象。

## 2. 为什么不采用单一 PostgreSQL 明细表

旧代码中的 `DfetlPrecheckIssue` 同时保存 `raw_row_json`、`raw_value` 和字段问题。该方式不作为新系统物理基线，原因包括：

- 一条源记录多个问题会重复保存完整原始行；
- 医疗敏感数据进入元数据库后会扩大安全边界；
- 大批量预检会造成 PostgreSQL 表、索引、WAL、VACUUM 和备份体积快速增长；
- 元数据库的主要职责是配置、状态、版本和审计，不应成为大规模明细仓库。

旧实体只能作为迁移审计材料；新模型不得直接复用其表结构。

## 3. PostgreSQL 控制面

### 3.1 `precheck_run`

一行表示一次不可变预检运行事实。

| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | bigint identity | 主键 |
| `route_id` | bigint | FK，稳定采集链路身份 |
| `route_version_id` | bigint | FK，本次固定链路版本 |
| `dataset_version_id` | bigint | FK，本次固定数据集版本 |
| `status` | varchar(24) | `PENDING/EXTRACTING/VALIDATING/COMPLETED/FAILED/CANCELLED` |
| `result` | varchar(16) | `PASS/ISSUES`；未完成时为 NULL |
| `source_structure_hash` | varchar(128) | 本次源结构事实 |
| `extracted_rows` | bigint | 非负 |
| `checked_rows` | bigint | 非负 |
| `problem_record_count` | bigint | 存在至少一个问题的源记录数 |
| `problem_item_count` | bigint | 字段和组合规则问题项总数 |
| `affected_institution_count` | integer | 非负 |
| `started_by` | bigint | FK `user_account`；系统触发时允许 NULL 并保存 actor snapshot |
| `started_at/finished_at` | timestamptz | 运行时间 |
| `failure_code/failure_message` | varchar/text | 失败事实，敏感值必须脱敏 |
| `retention_policy_snapshot` | jsonb | 本次 RAW、明细和导出保留策略快照 |
| `created_at` | timestamptz | 不可修改 |

约束和索引：

- `CHECK` 保证计数非负；
- `COMPLETED` 时 `result` 必须非空，其他状态不得伪造 PASS/ISSUES；
- 部分唯一索引保证同一 `route_id` 只有一个活动 Run；
- 索引：`(route_id, started_at desc)`、`(status, started_at)`、`(result, finished_at desc)`。

### 3.2 `precheck_issue_summary`

长期保存字段级、组合规则级和机构级汇总。

核心字段：

```text
id
precheck_run_id
institution_id / institution_code_snapshot
scope = FIELD | COMPOSITE | STRUCTURE
primary_field_code
field_codes_json
rule_code
rule_version
checked_count
affected_record_count
problem_item_count
deviation_summary_json
created_at
```

唯一约束：

```text
(precheck_run_id, institution_code_snapshot, scope,
 primary_field_code, rule_code, rule_version)
```

组合规则使用 `field_codes_json` 保存有序字段集合，但高频筛选的 `primary_field_code`、`rule_code` 和 `scope` 必须结构化。

索引：

- `(precheck_run_id, affected_record_count desc)`；
- `(precheck_run_id, institution_code_snapshot)`；
- `(rule_code, created_at desc)`。

### 3.3 `precheck_detail_manifest`

一行对应一个 Run 的 Doris 明细载体和清理状态，是页面判断“明细可用/已过期”的唯一控制面事实。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint identity | 主键 |
| `precheck_run_id` | bigint | FK + UNIQUE |
| `storage_version` | smallint | 当前为 1 |
| `raw_table_name` | varchar(128) | 数据集版本 RAW 表 |
| `record_table_name` | varchar(128) | 固定为问题记录表 |
| `item_table_name` | varchar(128) | 固定为问题项表 |
| `run_partition_date` | date | Doris 日分区键 |
| `status` | varchar(24) | `AVAILABLE/EXPIRING/CLEANING/EXPIRED/CLEAN_FAILED` |
| `raw_expires_at` | timestamptz | RAW 到期时间 |
| `detail_expires_at` | timestamptz | 问题记录/问题项到期时间 |
| `raw_row_count` | bigint | 实际 RAW 行数 |
| `problem_record_count` | bigint | 与 Run 对账 |
| `problem_item_count` | bigint | 与 Run 对账 |
| `cleanup_attempt_count` | integer | 清理尝试次数 |
| `cleanup_started_at/finished_at` | timestamptz | 清理事实 |
| `cleanup_error` | text | 脱敏错误信息 |
| `revision` | bigint | 乐观锁 |
| `created_at/updated_at` | timestamptz | 审计时间 |

不允许通过“查不到 Doris 行”推断本次没有问题；页面必须读取本表状态。`EXPIRED` 表示历史汇总仍存在，但明细已经按策略清理。

### 3.4 `export_job`

使用跨领域通用导出任务，不再创建仅服务预检的孤立导出模型。

核心字段：

```text
id uuid
export_type
subject_type / subject_id
filter_snapshot jsonb
format = CSV | XLSX
contains_sensitive boolean
status = PENDING | RUNNING | SUCCEEDED | FAILED | EXPIRED
object_manifest jsonb
row_count / byte_count
requested_by / requested_at
started_at / finished_at / expires_at
failure_code / failure_message
idempotency_key / request_hash
```

约束：

- 筛选快照、请求人、敏感标志和格式创建后不可修改；
- 对象清单只保存 Bucket、Object Key、大小和校验值，不保存访问密钥或永久下载 URL；
- 相同主体、用户、接口及 `Idempotency-Key` 复用时返回同一导出任务；请求摘要不同则返回冲突。

## 4. Doris 明细面

### 4.1 数据集版本 RAW 表

每个不可变标准数据集版本创建一张内部 RAW 表：

```text
raw_precheck_<dataset_code_hash>_v<dataset_version>
```

不是每个 Run 创建一张表，从而避免频繁建表/删表造成元数据抖动。数据集版本变化时创建新表，旧版本表在所有运行明细过期后删除。

固定技术列：

```text
run_date DATE NOT NULL
precheck_run_id BIGINT NOT NULL
route_version_id BIGINT NOT NULL
dataset_version_id BIGINT NOT NULL
institution_code VARCHAR(64) NOT NULL
source_row_no BIGINT NOT NULL
locator_type VARCHAR(20) NOT NULL
record_locator_hash CHAR(64) NOT NULL
row_fingerprint CHAR(64) NOT NULL
captured_at DATETIME(6) NOT NULL
```

随后为该数据集版本的每个标准字段创建一个 `STRING NULL` 列，保持源始文本语义，区分 NULL、空字符串和原始格式。

表设计：

```text
DUPLICATE KEY(run_date, precheck_run_id, institution_code)
PARTITION BY RANGE(run_date) -- 日分区
DISTRIBUTED BY RANDOM BUCKETS <按数据量和 BE 数计算>
```

要求：

- 不固定 `replication_num=1`；继承目标 Doris 生产副本策略；
- 不固定所有表 16 Buckets；根据预计单日数据量、BE 数和压缩后每 Bucket 体积计算；
- 动态分区可作为日分区滚动清理的安全网，但 PostgreSQL Manifest 清理仍是业务状态的唯一来源；
- `source_row_no` 只在本 Run 内有效；`record_locator_hash` 和 `row_fingerprint` 都不得成为正式同步业务键。

### 4.2 `dfetl_precheck_issue_record`

一行表示一条问题源记录。

```text
run_date
precheck_run_id
route_version_id
dataset_version_id
institution_code
record_locator_hash
locator_type
locator_display_masked
source_row_no
row_fingerprint
problem_field_count
problem_item_count
contains_sensitive
checked_at
```

表设计：

```text
DUPLICATE KEY(run_date, precheck_run_id, institution_code)
PARTITION BY RANGE(run_date)
DISTRIBUTED BY RANDOM
```

记录定位显示值必须默认脱敏。真实联合业务主键仍只用于本次问题定位，不进入其他任务合同。

### 4.3 `dfetl_precheck_issue_item`

一行表示一条字段或组合规则问题项。

```text
run_date
precheck_run_id
institution_code
record_locator_hash
issue_no
scope = FIELD | COMPOSITE
primary_field_code
field_codes_json
rule_code
rule_version
masked_value
expected_rule
problem_reason
deviation
sensitive
checked_at
```

不保存 `raw_value`、完整 `raw_row_json` 或可直接识别患者的未脱敏业务主键。授权查看原值时，通过：

```text
precheck_run_id
+ institution_code
+ record_locator_hash/source_row_no
+ field code
```

回读同一数据集版本 RAW 表。

基础查询索引：

- 排序前缀以 `run_date, precheck_run_id, institution_code` 为主；
- `record_locator_hash`、`primary_field_code`、`rule_code` 可按现场 Doris 版本增加 Bloom Filter；
- 倒排索引是可选优化，不作为 P0 正确性依赖。

## 5. 查询合同

### 5.1 汇总

汇总页面只查询 PostgreSQL `precheck_issue_summary`，不扫描 Doris 明细。

### 5.2 问题记录分页

服务端先在 `dfetl_precheck_issue_item` 按以下条件筛选问题记录定位 Hash：

```text
precheck_run_id
institution_code
scope
primary_field_code
rule_code
contains_sensitive
```

再与 `dfetl_precheck_issue_record` 合并，按：

```text
institution_code asc,
record_locator_hash asc
```

稳定分页。API 返回准确 `total`，不能在前端对已截断数据自行计算总数。

### 5.3 单条记录详情

读取 `issue_record` 和全部 `issue_item`。默认只返回 `masked_value`。

### 5.4 查看原值

必须满足：

1. `precheck.detail.reveal` 权限；
2. Manifest 为 `AVAILABLE/EXPIRING`；
3. S1 确认；
4. 服务端写 `PRECHECK_DETAIL_VALUE_REVEAL` 审计；
5. 从 RAW 表只读取当前请求字段；
6. 响应使用 `Cache-Control: no-store`；
7. 审计、应用日志和错误信息不保存原值。

页面刷新或会话结束后必须恢复脱敏显示。

## 6. 保留策略

### 6.1 P0 默认值

| 数据 | 默认保留 | 允许范围 | 说明 |
| --- | ---: | ---: | --- |
| Run 和 Summary | 长期 | 不在 P0 自动清理 | 支持审计和趋势 |
| `ISSUES` Run 的 RAW 与问题明细 | 7 天 | 1–30 天 | 供整改、复核和导出 |
| `PASS` Run 的 RAW | 1 天 | 0–7 天 | 仅供运行恢复和诊断；无问题明细 |
| `FAILED/CANCELLED` Run 的已写 RAW | 1 天 | 0–7 天 | 供故障诊断 |
| 导出对象 | 24 小时 | 1–168 小时 | 到期删除对象并将 Job 标为 EXPIRED |

策略变更只影响新 Run；P0 不提供单 Run 延期、永久保留或法律保全功能，避免同一日分区中出现不一致生命周期。

### 6.2 清理流程

清理任务：

1. 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 领取到期 Manifest；
2. `AVAILABLE/EXPIRING -> CLEANING`；
3. 删除当前 Run 的 RAW、问题记录和问题项；
4. 对账 Doris 中该 Run 行数为 0；
5. 更新 `EXPIRED` 和 `cleanup_finished_at`；
6. 失败时进入 `CLEAN_FAILED`，指数退避重试并产生告警；
7. 日分区内所有 Run 均过期后才允许整分区删除。

清理必须幂等。重复执行不得误删其他 Run、其他数据集版本或正式 ODS 数据。

## 7. 导出方案

### 7.1 存储介质

生产环境使用 S3 兼容对象存储，首选已有 MinIO：

```text
bucket: dfetl-export
object key:
precheck/<runId>/<exportJobId>/part-00001.csv
```

本地目录只允许开发和单节点测试，不作为生产默认。

### 7.2 导出行为

- 汇总和明细均创建 `export_job`；
- 明细导出始终异步，避免 HTTP 请求长时间占用；
- 默认导出脱敏值；原值导出需要 `precheck.detail.export_sensitive`；
- 敏感导出对象必须启用服务端加密，下载链接短时有效；
- CSV 必须处理公式注入：以 `= + - @` 开头的文本按安全文本输出；
- 多文件导出按固定行数切片，并记录每个对象 SHA-256；
- 对象存储生命周期负责物理删除，数据库清理任务负责核对并将 Job 标记为 `EXPIRED`。

## 8. 安全边界

1. Doris 预检内部表使用独立只读/写入账号，普通业务账号无权访问。
2. RAW 和问题明细不得通过通用 SQL 查询接口直接暴露。
3. PostgreSQL 不保存敏感原值、完整原始行、数据库凭据或对象存储 Secret。
4. 对象存储凭据通过环境 Secret/KMS 注入，数据库只保存密文配置或 Secret 引用。
5. 所有查看原值、原值导出和下载均写审计，审计仅保存对象、字段、范围和结果。
6. 正式同步、Checksum、删除对账和消息发布不得引用 Run Scoped Locator。

## 9. 与当前代码的处置关系

后端实施时：

- 旧 `DfetlPrecheckIssue` 不迁移为新 V1 表；其 `raw_row_json/raw_value/remediation_status/severity` 均不进入目标模型；
- 旧 `DfetlPrecheckRun` 中任务窗口、单机构和旧状态字段按新 Route/Version/Result 模型重建；
- 旧 `DfetlPrecheckExport` 的“不可变筛选快照、请求人和异步状态”语义可迁移到通用 `export_job`，但不复用旧表；
- `application.yml` 中当前 `raw=30/issue=90/summary=90` 及本地导出目录不是新基线，后续按本文统一。

## 10. 验收场景

1. 一条记录有三个非法字段，列表只显示一条问题记录，展开显示三个问题项。
2. 有业务主键时按机构代码和联合业务主键定位；无业务主键时只使用 Run Scoped Locator。
3. 普通用户看到脱敏值，无权限用户看不到查看原值和敏感导出入口。
4. 原值查看后刷新页面恢复脱敏，审计中没有原值。
5. 明细到期后，Run 和 Summary 仍可查看，页面明确显示 `EXPIRED`，不能显示为零问题。
6. 清理重试不会删除其他 Run 数据。
7. 导出任务支持分页筛选快照、分片、失败恢复和到期清理。
8. 多实例同时执行清理时，同一 Manifest 只被一个实例领取。
9. PostgreSQL 中不存在完整问题行和敏感原值的重复副本。
10. 正式同步执行路径不读取任何预检表。

## 11. 官方技术依据

- Apache Doris Duplicate Key Model：用于保留原始明细且不去重。
- Apache Doris Dynamic/Range Partition：用于短周期明细的日分区和滚动清理。
- Apache Doris Random Bucketing：适用于 Duplicate Key 且无稳定高基数业务键的明细表。

具体实现时必须以客户现场 Doris 版本的官方文档和能力探针为准。
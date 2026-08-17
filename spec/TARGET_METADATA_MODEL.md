# DFETL 目标元数据模型

> 状态：阶段 1 逻辑模型收口版  
> 更新：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 限制：本文定义目标逻辑关系，不是 Flyway SQL；最终签字前不得据此直接修改生产数据库。

## 1. 设计目标

目标元数据模型只表达当前产品需要的稳定事实：

```text
接入资源
→ 机构采集路由
→ 同步任务
→ 执行/批次
→ 校验/水位/消息
```

原则：

- 一个部署只服务一个医共体，不增加 tenant_id；
- 机构为扁平集合；
- HIS/LIS/PACS 只作为轻量业务目录；
- 源端数据源直接属于一家机构和一个业务目录；
- Route 固定属于一家机构；
- 标准 Dataset、目标 Doris 为全局资源；
- Dataset version 和 Route version 保留不可变版本；
- Task 不建立版本表，保存当前配置；
- Execution/Validation 保存启动快照解释历史。

## 2. 顶层关系

```text
institution 1 ── N source_datasource N ── 1 business_catalog
       │
       └── 1 ── N collection_route N ── 1 standard_dataset
                         │
                         ├── current_version_id → collection_route_version
                         │                         └── route_field_resolution
                         │
                         └── 1 ── 0..1 sync_task（按 institution + dataset 唯一）
                                         │
                                         ├── task_watermark
                                         ├── sync_execution
                                         │      └── load_batch
                                         ├── validation_run
                                         └── message_outbox（通过 execution）

target_datasource
  └── target_datasource_fe_endpoint

standard_dataset
  └── standard_dataset_version
       └── standard_dataset_field
```

## 3. 接入资源模型

### 3.1 `institution`

职责：医共体内医疗机构身份。

核心字段：

```text
id
code
name
short_name
institution_type
institution_level
region_code
status
revision
created_*/updated_*
```

不保存：父机构、数据源配置、Route、Dataset、调度或运行状态。

### 3.2 `business_catalog`

职责：HIS/LIS/PACS/EMR 等轻量业务分类。

核心字段：

```text
id
code
name
description
status
revision
created_*/updated_*
```

该表不是部署实例表，不与机构建立多对多覆盖关系。

### 3.3 `source_datasource`

职责：某一家机构某类业务数据库连接。

核心关系：

```text
institution_id      → institution.id
business_catalog_id → business_catalog.id
```

核心连接字段：

```text
code/name
db_type
connection_mode
host/port/database_name/default_schema
jdbc_url
username/password_enc
ssl_enabled/read_only
connect/query/socket timeout
pool_max_size
status
last_test_status/last_tested_at/last_test_error
revision
```

不保存 Dataset、实际 Schema/Object 映射或目标表映射。

### 3.4 `target_datasource`

职责：逻辑 Doris 部署。

一对多子表：

```text
target_datasource
  └── target_datasource_fe_endpoint
```

目标资源不绑定机构、业务目录或 Dataset。

## 4. 标准数据集模型

继续保留：

```text
standard_dataset
standard_dataset_version
standard_dataset_field
field_conversion_contract
field_conversion_rule
generic_jdbc_type_mapping
```

`standard_dataset.current_version_id` 指向当前不可变定义版本。

当前策略存储遵循后续专项 Review：

- 数据集同步默认参数保存在数据集侧既定对象/字段；
- `standard_dataset.validation_method_override` 为可空覆盖；
- 消息策略只存在数据集级；
- 不建立任务级消息策略。

## 5. 机构采集路由模型

### 5.1 `collection_route`

职责：一家机构对一个标准 Dataset 的当前采集映射。

建议当前字段：

```text
id
institution_id
dataset_id
source_datasource_id
source_schema
source_object
source_object_type
target_datasource_id
status
structure_status
structure_checked_at
structure_error_summary
current_version_id
revision
deleted_at/deleted_by
created_*/updated_*
```

关键不变量：

1. Route 只有一个 `institution_id`，不存在覆盖机构集合。
2. `source_datasource.institution_id` 必须等于 Route 的 `institution_id`。
3. 业务目录从 Source 推导，Route 不重复保存业务分类。
4. 同一机构 + Dataset 只维护一条未删除当前 Route；切换源端时更新当前配置并生成新 Route version。
5. ODS/RAW 表名按 Dataset 和统一命名规则推导，不在 Route 自由填写。
6. Route 状态和结构核对状态是两个独立事实。

### 5.2 `collection_route_version`

职责：一次规范化 Route 配置快照，只插入不更新。

建议字段：

```text
id
route_id
version_no
institution_id
dataset_id
dataset_version_id
source_datasource_id
source_schema
source_object
source_object_type
target_datasource_id
structure_hash
contract_hash
created_at/created_by
```

保留 `institution_id/dataset_id` 快照是为了让 Task/Execution 能通过复合关系验证 Route version 的业务归属。

### 5.3 `route_field_resolution`

职责：某个 Route version 下“标准字段 → JDBC 真实字段名”的只读解析结果。

建议字段：

```text
route_version_id
standard_field_id
field_code
source_column_name
source_ordinal
source_jdbc_type
resolved_at
```

固定规则：

- 一标准字段一条解析；
- 源字段按大小写折叠后必须唯一；
- 不提供人工重命名、别名或转换表达式；
- 所有源 SQL 共用这份解析结果。

## 6. Task 模型

### 6.1 `sync_task`

任务采用已确认的“固定身份 + 当前配置覆盖”。

固定业务身份：

```text
institution_id
dataset_id
```

创建后不可修改；同一机构 + Dataset 只能存在一个未删除任务。

当前配置至少包括：

```text
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
```

删除旧任务版本模型：

```text
不建立 sync_task_version
不保存 sync_task.current_version_id
Execution/Validation/Outbox/Watermark 不保存 task_version_id
```

### 6.2 Route 与 Task 的一致性

Task 选择的 `route_version_id` 必须满足：

```text
route_version.institution_id = task.institution_id
route_version.dataset_id     = task.dataset_id
```

Task 可显式切换到同一机构、同一 Dataset 的新 Route version；不会自动切换，也不会自动重置水位。

## 7. `task_watermark`

一任务最多一条当前正式水位：

```text
task_id
watermark_value
source_execution_id
updated_at
```

不建立 task version 维度和水位历史表。历史窗口从 `sync_execution` 查询。

## 8. 执行和批次

### 8.1 `sync_execution`

一次接受的真实同步运行。

除运行状态、范围、统计外，必须保存本次启动快照：

```text
task_id
task_revision
institution_id/institution_code
dataset_id/dataset_version_id
route_version_id
source/target 快照
field contract/ref snapshot
task_kind/write_mode/doris_key_model
incremental field
fetch_size / upper bound delay
range snapshot
validation method/source/revision
message policy snapshot
```

任务后续修改不影响历史执行。

### 8.2 `load_batch`

批次只保存本次执行内部的 Doris Load 事实：

```text
execution_id
batch_no
cursor_start/cursor_end
source_row_count
loaded_row_count/rejected_row_count
payload_digest
doris_label
doris_txn_id
status
doris_status
probe_count
visible_at
error_code/error_message
```

不作为跨执行恢复检查点。

## 9. 预检模型

当前目标对象：

```text
precheck_run
precheck_issue_summary
```

`precheck_run` 直接关联 `route_id/route_version_id`；一条 Route 同时最多一个活动预检。

预检保存整次运行和字段/组合规则汇总，正式同步不读取预检中间结果作为业务数据源。

## 10. 校验模型

当前只保留统一 `validation_run`，不建立独立“任务版本校验”对象。

同步门禁校验通过 `execution_id` 使用原执行快照；人工/定期独立校验通过 `task_id + context_snapshot + range_snapshot` 固定本次上下文。

校验方法来源：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
```

## 11. 消息模型

消息仅使用 RabbitMQ，数据集级配置。

```text
sync_execution
  └── 0..1 message_outbox
```

Outbox 保存执行、机构、数据集、范围和消息策略小型快照，不保存逐条业务 payload 或任务版本引用。

## 12. 支撑对象

继续按专项 Review 保留：

- `app_user`、`audit_log`、`system_setting`；
- 告警 channel/rule/event/delivery；
- 外部 API client、授权、nonce/request；
- Quartz 官方 JDBC JobStore 表；
- 删除识别/人工应用对象；
- Doris `_dfetl_key_snapshot`、`_dfetl_delete_diff` 等技术表。

## 13. 明确废止的旧关系

阶段 1 最终模型不得重新引入：

- 真实部署系统实例及其机构/数据源多对多中间层；
- Route 多机构覆盖集合与 `collection_route_version_institution`；
- `sync_task_version` 和任务版本发布/切换状态机；
- 任务级消息配置；
- 标准任务 `CUSTOM_SQL`；
- 数据源组和任务组；
- 机构树。

后续数据库、Java DTO/Entity、API 和前端类型必须以本模型为准。

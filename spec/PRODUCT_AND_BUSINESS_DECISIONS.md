# DFETL 产品与业务决策汇总

> 项目：`duhongx/dfetl-service`  
> 目标分支：`main`  
> 首次整理：2026-08-13  
> 最近收口：2026-08-17  
> 文档性质：当前产品、业务与前端信息架构的统一基线。  
> 冲突处理：后续用户明确确认 > 本文件 > 已确认专项 Review > 物理字典 > `TASKS.md` > 历史代码和归档文档。

## 1. 项目目标与边界

DFETL 是面向单个医共体部署的数据采集与传输平台，负责把医疗机构 PostgreSQL、MySQL、Oracle、SQL Server 等源数据库中的标准化 Table/View/Materialized View 数据，按照医共体标准数据集合同进行结构核对、数据预检、正式同步、同步后校验并写入 Doris。

一个 DFETL 部署及其独立 PostgreSQL 元数据库固定服务一个医共体：

- 不建立租户表；
- 医共体名称、编码等少量部署级信息保存为系统设置；
- 医疗机构按扁平集合管理，不建立父子层级；
- 新系统 PostgreSQL、Quartz、运行记录、水位和消息状态与老系统完全隔离。

平台原则：配置透明、运行可追溯、失败显式，不通过自动猜测、静默修复或隐藏状态替用户作业务决策。

## 2. 当前四层产品模型

```text
资源层：接入资源
  ├─ 医疗机构
  ├─ 业务目录（HIS/LIS/PACS 等轻量分类）
  ├─ 源端数据源
  ├─ 目标端数据源
  └─ 医共体标准数据集
        ↓
关系层：机构采集路由
  ├─ 当前机构
  ├─ 标准 Dataset
  ├─ 源端数据源
  ├─ Schema + Table/View/Materialized View
  ├─ 目标 Doris
  └─ 结构核对 + Route 启停
        ↓
运行层：同步任务
  ├─ 固定身份：机构 + Dataset
  ├─ 当前 Route/合同配置
  ├─ 全量/增量/UPSERT
  ├─ 调度
  └─ 执行快照
        ↓
质量层
  ├─ 数据预检
  └─ 同步后校验
```

### 2.1 明确删除的旧资源中间层

当前产品模型不再设置独立的“真实部署系统实例”领域对象，也不维护该对象与机构、数据源之间的多对多关系。

HIS/LIS/PACS 只作为**业务目录/业务分类**使用：

- 业务目录是全局轻量字典；
- 不保存厂商实例覆盖机构；
- 不保存实例与数据源多对多；
- Route 不需要先选择一个系统实例；
- 前端不增加独立“业务系统实例”页面。

## 3. 接入资源

### 3.1 医疗机构

医疗机构是数据采集业务归属主体。

固定规则：

- `institution.code` 全局唯一，创建后不得通过普通编辑修改；
- 源数据中的医疗机构代码必须与机构编码完全一致；
- 不建立厂商内部机构编码映射；
- 机构按扁平集合管理；
- 停用机构后不能创建新的源数据源、Route 或任务，也不能启动新的同步执行；历史记录继续保留。

### 3.2 业务目录

业务目录用于表达 HIS、LIS、PACS、EMR 等业务分类，只回答“这个数据库属于哪类业务”。

固定规则：

- 全局维护 `code/name/description/status`；
- 不与具体机构建立独立多对多关系；
- 一个源端数据源必须选择一个业务目录；
- Route 选择源端数据源后，业务目录只读带出，不在 Route 再次配置。

### 3.3 源端数据源

一条源端数据源表示 DFETL 如何连接**某一家机构的某一类业务数据库**。

固定归属：

```text
一个医疗机构
+
一个业务目录
+
一个数据库连接
```

固定规则：

- 必须直接绑定 `institution_id` 和 `business_catalog_id`；
- 支持 PostgreSQL/MySQL/Oracle/SQL Server；
- 连接支持 `HOST_PORT` 和完整 `JDBC_URL` 两种互斥方式；
- 用户名和密码单独保存，密码加密，账号密码不得嵌入 JDBC URL；
- 数据源保存 Database 和可选 Default Schema，但不保存 Dataset ↔ View 映射；
- 不保存具体采集 Table/View；实际 Schema 和源对象属于 Route；
- 数据源启停状态与最近连接测试结果分开保存；连接测试失败不自动停用数据源。

### 3.4 目标端数据源

目标端数据源表示一个逻辑 Doris 部署。

固定规则：

- 全局资源，不绑定机构或业务目录；
- 支持一个或多个 FE 端点，不管理 BE；
- 保存 Doris database、凭据及必要写入参数；
- 不在目标数据源维护 Dataset → Table 映射；
- ODS/RAW 表名由标准 Dataset 编码和统一命名规则推导；
- 普通同步不得自动创建、补列或修改 Doris 表，建表/重建必须由用户显式操作。

### 3.5 医共体标准数据集

- 只能由管理员从医共体规范库人工同步；
- 不允许手工新增，不自动同步；
- 规范化定义 Hash 变化时生成新的不可变 `standard_dataset_version`；
- 标准字段编码、类型、长度、精度、可空、业务主键、增量字段和值域等形成字段合同；
- 标准字段与源字段只允许大小写差异，不提供字段重命名、别名、默认值或转换表达式改变字段身份；
- 医共体标准任务不提供 `CUSTOM_SQL`。

## 4. 机构采集路由

### 4.1 Route 的产品定义

Route 回答：

> 某一家机构的某个标准 Dataset，从该机构哪个源端数据源的哪个 Schema/Table/View 读取，并写向哪个 Doris。

一条 Route 至少由以下内容组成：

```text
机构
+
标准 Dataset
+
源端数据源
+
Schema
+
Table / View / Materialized View
+
目标 Doris
+
字段解析合同
```

### 4.2 单机构 Route

Route 固定属于一家机构：

- 页面先确定当前机构；
- 只展示当前机构的 Route；
- 只允许选择属于当前机构且已启用的源端数据源；
- 不再存在“一个 Route 覆盖多家机构”的产品模型；
- 不建立 Route 覆盖机构集合，也不需要覆盖机构差集、覆盖移除保护等旧规则；
- 共享源 View 中如包含多家机构数据，每家机构分别建立自己的 Route，并在读取时按自身机构代码过滤。

### 4.3 Route 配置和版本

`collection_route` 保存当前可编辑配置和当前版本指针；规范化配置变化时生成新的不可变 `collection_route_version`。

版本内容至少固定：

- Dataset version；
- Source datasource；
- Schema；
- Source object/type；
- Target datasource；
- 标准字段 → JDBC 真实字段名解析快照；
- 结构 Hash/合同 Hash。

Route 的启停状态与结构核对状态独立：

```text
route status: DISABLED / ENABLED
structure status: NOT_CHECKED / PASSED / FAILED / OUTDATED
```

标准模型或当前源结构变化后可把 Route 标记为 `OUTDATED`；结构重新核对通过后仍由用户显式启用 Route，不自动启用。

### 4.4 字段解析

- 标准字段与源字段按大小写不敏感唯一命中；
- 字段集合必须完全一致，缺失、额外或大小写折叠后重复均为结构错误；
- 源字段物理顺序不要求与标准顺序一致；Reader 按标准合同顺序显式投影；
- 机构过滤、增量窗口、正式 Reader、数据预检和 Checksum 共用同一解析结果；
- Doris 业务字段固定使用标准字段小写名称。

## 5. 数据预检

数据预检与正式同步严格分离。

固定流程：

```text
Route
→ 人工启动预检
→ 从真实源对象读取
→ 发现字段/组合规则问题
→ 医院修复源 View/Table
→ 再次预检
→ 正式同步重新从源对象读取
```

固定规则：

- 只允许人工启动，不设置自动调度；
- 同一 Route 同时最多一个活动预检；不同 Route 的并行由全局参数控制；
- 重新预检创建新运行并从头执行；
- 预检用于发现数据质量问题，不把预检中间数据直接作为正式同步来源；
- 预检结果不是正式同步数据；
- 预检问题至少覆盖非空、长度、类型、日期、精度、值域、业务主键和重复等；
- Route 的结构核对与数据预检是不同事实；预检有问题不自动修改 Route 或任务。

## 6. 同步任务

### 6.1 固定身份 + 当前配置覆盖

一个任务长期身份固定为：

```text
institution_id + dataset_id
```

固定规则：

- 同一机构、同一 Dataset 只能存在一个未删除任务；
- 机构和 Dataset 创建后不可修改；需要变更时逻辑删除旧任务并新建；
- `sync_task` 直接保存当前有效配置；
- 日常编辑直接更新当前任务，不建立任务版本表、发布/切换/回退状态机；
- 当前可修改 Route version、Dataset version、读取参数、调度和任务级校验方式覆盖；
- 存在活动同步执行时禁止编辑任务；活动独立校验不阻止普通配置编辑；
- 任务修改只影响后续执行，历史执行和历史校验读取启动快照。

### 6.2 三种标准任务组合

| 数据集合同 | 任务类型 | 写入方式 | Doris Key |
| --- | --- | --- | --- |
| 无真实业务主键 | `FULL_ONLY` | `REPLACE_INSTITUTION_SCOPE` | `DUPLICATE_KEY` |
| 有业务主键且有增量字段 | `FULL_THEN_INCREMENTAL` | `UPSERT` | `UNIQUE_KEY` |
| 有业务主键但无增量字段 | `FULL_ONLY` | `UPSERT` | `UNIQUE_KEY` |

不生成 Hash、自增 ID、`row_number()` 等假主键，不使用 APPEND 作为标准默认写入语义。

### 6.3 调度和水位

- 默认同步周期可由全局设置提供，当前业务默认按 4 小时理解；
- 调度周期不等于固定数据窗口；
- 同一任务禁止并发执行；活动执行期间到达的新计划触发直接跳过，不排队追赶；
- 失败不自动重试、不自动暂停任务、不推进水位；
- 增量范围使用固定上界，成功且阻断校验通过后才推进正式水位；
- 空窗口成功也允许推进水位；
- 人工补采创建独立执行，不修改正式水位；
- 重新采集创建新执行，从范围起点和第 1 批重新读取；
- 取消只取消当前执行，不改变任务调度开关。

## 7. 执行、批次和运行快照

`sync_execution` 是一次真实运行。启动时固定本次实际使用的：

- 任务 revision；
- 机构；
- Dataset/Dataset version；
- Route/Route version；
- 源端和目标端；
- 字段合同；
- 本次全量/增量范围；
- 读取、写入、校验和消息策略。

后续任务或 Route 编辑不能改写已接受执行。

`load_batch` 只表示一次执行内的 Doris Stream Load 单元，保存实际游标范围、行数、Label 和 Doris 可见状态；超时或响应不明确时优先探测原 Label，不自动重投未知批次。

## 8. 正式同步后校验

每次正式同步必须至少执行严格 `ROW_COUNT`，不能关闭。

解析顺序：

```text
sync_task.validation_method_override
→ standard_dataset.validation_method_override
→ system_setting[validation.default_method]
→ ROW_COUNT
→ Dataset 合同能力强制
```

固定规则：

- 数据集/任务覆盖的 `NULL` 表示继承；
- 有真实业务主键时可选择 `ROW_COUNT_CHECKSUM`；
- 无真实业务主键只能使用 `ROW_COUNT`；
- 不允许在执行中静默降级；
- 阻断校验失败时执行失败、水位不推进、消息不发布；
- 人工重新校验生成独立 `validation_run`，不覆盖历史执行结论。

## 9. RabbitMQ 消息

- P0 只使用 RabbitMQ；
- 消息配置只存在于数据集级，任务不允许覆盖；
- 正式执行只有在同步完成且阻断校验通过后才生成消息 Outbox；
- 每个成功执行最多一条小型 `message_outbox` 指令；
- Outbox 不保存完整业务 payload、逐条消息或分页进度；
- 重发时按既定旧协议读取并发送，消息 Key、空值和消息 ID 合同不得自行简化。

## 10. 前端信息架构约束

当前前端首先完成页面和交互，不以旧 Java Controller 或旧表结构反推产品导航。

“接入资源”必须能够维护：

```text
医疗机构
业务目录
源端数据源
目标端数据源
医共体标准
```

“机构采集路由”是独立关系页面，以当前机构为上下文配置 Dataset → Source → Schema/Object → Target。

明确禁止因为旧文档、旧表或旧代码重新加入“业务系统实例”页面。

后续开发顺序：

```text
产品/Spec 收口
→ 前端导航、页面、交互和文案 100%
→ API 合同
→ 后端模型/数据库/服务整改
→ 端到端联调
```

## 11. 当前权威专项 Review

以下后续 Review 在各自专题内继续有效；与本文件发生冲突时，以更晚确认结果为准：

- `P0_MUTABLE_TASK_MODEL_REVIEW.md`：固定任务身份 + 当前配置覆盖；
- `P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md`：首次全量/后续增量执行边界；
- `P0_LOAD_BATCH_MODEL_REVIEW.md`：批次和 Doris Label；
- `P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md`、`P0_GLOBAL_VALIDATION_SETTING_REVIEW.md`：校验覆盖；
- `P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md`：独立校验并发；
- `P0_OUTBOX_SCOPE_MAPPING_REVIEW.md`：消息 Outbox；
- `DELETE_SNAPSHOT_MODEL_REVIEW.md`、`P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`：删除识别；
- `EXTERNAL_API_REVIEW.md`：外部任务 API；
- `QUARTZ_JOBSTORE_REVIEW.md`：Quartz 投影。

本文件不再继承早期“系统实例多对多 + 多机构共享 Route”的旧资源模型。

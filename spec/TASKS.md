# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：P0 PostgreSQL 表清单已冻结 + 前端优先  
> 最近更新：2026-08-17  
> 产品基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 0. 当前执行原则

冲突优先级：

1. 用户最新明确确认；
2. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
3. 日期更晚且已确认的专项 Review；
4. 当前 P0 物理字典；
5. 本任务清单；
6. 老代码、老 SQL、历史/归档文档。

当前顺序：

```text
Active Spec 语义收口
→ 前端页面/导航/交互/文案 100%
→ API Contract
→ 最终 P0 表/FK/枚举矩阵与 Flyway V1
→ Java 后端整改
→ 联调/端到端验收
```

当前不主动推进数据库/Java 实施。

---

## 1. 当前核心业务基线

### 1.1 接入资源

- [x] 一个部署服务一个医共体，不建立租户表。
- [x] Institution 为扁平集合。
- [x] 保留轻量 Business Catalog（HIS/LIS/PACS）。
- [x] Source Datasource 直接绑定一家 Institution + 一个 Business Catalog。
- [x] Source 支持 `HOST_PORT/JDBC_URL`，凭据与 URL 分离。
- [x] Target Doris 为全局逻辑资源，可多个 FE，不管理 BE。
- [x] 删除旧“业务系统实例 + 机构/数据源多对多”中间层。
- [x] 前端不增加业务系统实例管理页。

### 1.2 标准 Dataset

- [x] 只允许管理员从医共体规范库人工同步。
- [x] 不手工新增，不自动同步。
- [x] 规范化定义变化才生成不可变 `standard_dataset_version`。
- [x] 标准字段和 Source 字段只允许大小写差异，不支持人工重命名。
- [x] 标准 Task 不支持 `CUSTOM_SQL`。
- [x] 医疗字段合同统一服务 Doris DDL、Reader、Precheck、Checksum。
- [x] `standard_dataset.validation_method_override` 直接保存 Dataset Validation Override。

### 1.3 机构采集 Route

- [x] Route 固定属于一家 Institution。
- [x] Route 选择当前 Institution 所属且启用的 Source。
- [x] Route 保存 Dataset、Source、Schema/Object、Target 和字段解析合同。
- [x] 不存在共享 Route 覆盖多机构模型。
- [x] 不建立 Route/Route Version 覆盖机构关系表。
- [x] Route Status 与 Structure Status 分离。
- [x] 配置变化生成不可变 `collection_route_version`。
- [x] Route Version 提供 `id + institution + dataset + dataset_version` 四元父唯一键。

### 1.4 Task

- [x] 一个 Task 固定属于一个 Institution + Dataset。
- [x] 同一 Institution + Dataset 最多一个未删除 Task。
- [x] `institution_id/dataset_id` 创建后不可修改。
- [x] Task 保存当前配置，不建立 `sync_task_version`。
- [x] Task 可显式切换同 Institution/同 Dataset 的 Route Version，不自动重置 Watermark。
- [x] 活动同步 Execution 期间禁止编辑；活动独立 Validation 期间允许普通编辑。
- [x] Execution/Validation 使用启动快照解释历史。
- [x] Task Validation Override 使用 `sync_task.validation_method_override`，NULL=继承。

### 1.5 三种标准 Task

| Dataset 合同 | Task Kind | Write Mode | Doris Key |
| --- | --- | --- | --- |
| 无真实业务主键 | `FULL_ONLY` | `REPLACE_INSTITUTION_SCOPE` | `DUPLICATE_KEY` |
| 有业务主键 + 增量字段 | `FULL_THEN_INCREMENTAL` | `UPSERT` | `UNIQUE_KEY` |
| 有业务主键、无增量字段 | `FULL_ONLY` | `UPSERT` | `UNIQUE_KEY` |

- [x] 不生成假主键。
- [x] 无主键全量只清理当前 Institution Scope。
- [x] Reader 第一阶段固定单并发，Fetch Size 可配置。

### 1.6 Execution / Watermark

- [x] 同一 Task 禁止并发同步 Execution。
- [x] 调度重叠触发跳过，不排队追赶。
- [x] 失败不自动重试、不自动暂停、不推进 Watermark。
- [x] Backfill 不改正式 Watermark。
- [x] Recollect 创建新 Execution，从范围起点和 Batch 1 开始。
- [x] Cancel 只影响当前 Execution。
- [x] `task_watermark` 只保存当前值，不保存 Task Version/History。
- [x] INITIAL_FULL 成功后 Watermark=T0；不在同次运行立即追加增量。

### 1.7 Precheck / Validation / Message

- [x] Precheck 只人工启动，同 Route 最多一个活动 Run。
- [x] Precheck 与正式同步严格分离。
- [x] 单机构 Route 的 Precheck Run 固定 Institution/Dataset/Route Version 快照。
- [x] Precheck 只保存 STRUCTURE/FIELD/COMPOSITE 汇总，不保存行级问题。
- [x] 正式同步最低严格 `ROW_COUNT`，不能关闭。
- [x] 有真实业务主键可选 `ROW_COUNT_CHECKSUM`；无主键只能 ROW_COUNT。
- [x] 全局/Dataset/Task Validation 不建立独立 Policy 表。
- [x] Validation 存储：System Setting + Dataset Override + Task Override。
- [x] RabbitMQ Only；Message Policy 只存在 Dataset 级。
- [x] SYNC_GATE PASS 后才成功、推进 Watermark 和创建 Outbox。

---

## 2. 2026-08-17 Active Spec 收口

### 2.1 业务系统实例 / 多机构 Route 旧模型

- [x] `PRODUCT_AND_BUSINESS_DECISIONS.md` 收口。
- [x] `TARGET_METADATA_MODEL.md` 收口。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` 收口。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` 收口。
- [x] `DATABASE_MIGRATION_BASELINE.md` 收口。
- [x] `LEGACY_FUNCTION_ALIGNMENT.md` 收口。
- [x] `JAVA_PRODUCTION_MIGRATION_REVIEW.md` 收口。
- [x] 阶段状态/计划/Pending Decision 收口。

### 2.2 Task Version / Validation Policy 机械迁移

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`：删除 Global/Dataset Validation Policy 表，合并 Dataset Override。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md`：删除 Task Version 语义和旧 Route Institution FK。
- [x] `EXTERNAL_API_REVIEW.md`：新 Task 直接创建 `sync_task`，不创建第一版 Task Version。
- [x] `QUARTZ_JOBSTORE_REVIEW.md`：Quartz 直接读取当前 `sync_task.schedule_*`，不读取 Current Task Version。

### 2.3 全 `spec/` 扫描发现并修正的结构残留

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`：补齐 Route Version 四元父唯一键。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md`：Execution 改四元 Route FK；Precheck 清理多机构维度。
- [x] `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md`：Delete Snapshot 改四元 Route FK。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY.md`：更新总索引、Task Version 替代关系和 Validation 最终存储。
- [x] `P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`：记录扫描规则和完成情况。
- [x] `P0_SUPPORT_OBJECT_REVIEW.md`：补回 `alert_rule_channel`，并固定 Quartz 11 张官方表。

### 2.4 扫描验收原则

旧词允许继续出现在：

```text
历史审计
明确已废止
明确不建立
不得进入 V1
机械清理映射
```

不要求 `sync_task_version/global_validation_policy/...` 字符串数量为 0；要求**没有 Active Spec 再把它们当当前目标对象或当前运行流程**。

---

## 3. P0 PostgreSQL 最终表清单：已确认

用户已确认：

```text
DFETL P0 领域/控制表       39
Quartz JDBC JobStore       11
--------------------------------
Flyway V1 负责创建         50
```

其中：

- [x] `alert_rule_channel` 保留为 Alert Rule ↔ Channel 多对多关系表。
- [x] Quartz 11 张官方 PostgreSQL JobStore 表单独统计。
- [x] `flyway_schema_history` 由 Flyway 自身维护，不计入 P0 39、Quartz 11 或 V1 自己定义的 50 张表。
- [x] 最终表清单详见 `P0_PHYSICAL_TABLE_DICTIONARY.md` 和 `P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md`。

该数量已经冻结；后续不得因实现方便随意增加 P0 持久化表。

---

## 4. 当前最高优先级：前端页面 100%

目标：先把页面、URL、交互、字段和文案完全确定，再进入后端实现。

### 4.1 导航 / 信息架构

- [ ] 首页/工作台与最终导航一致。
- [ ] 接入资源覆盖 Institution、Business Catalog、Source、Target、医共体标准。
- [ ] 独立“机构采集路由”页面，以 Institution 为上下文。
- [ ] 不出现业务系统实例入口。
- [ ] Task Center、Data Quality、Operations、System Settings 与最终原型一致。
- [ ] Help/Docs 和 Account Management 入口按 `PENDING_DECISIONS.md` 逐项确认。

### 4.2 接入资源页面

- [ ] Institution CRUD/启停/引用统计。
- [ ] Business Catalog CRUD/启停/引用保护。
- [ ] Source CRUD/Test/启停/删除引用保护，显示所属 Institution + Business Catalog。
- [ ] Target Doris CRUD/FE Endpoint/Test/启停。
- [ ] 医共体标准同步、列表、详情、字段合同、同步默认、Validation Override、Message Policy。

### 4.3 Institution Route

- [ ] 页面先选择当前 Institution。
- [ ] 新增 Route：Dataset → Source → Schema → Object → Target。
- [ ] Source 只显示当前 Institution 启用数据源，Business Catalog 自动带出。
- [ ] 实时读取 Schema/Object 元数据。
- [ ] Structure Check 和结果完整。
- [ ] Route 人工启用/停用。
- [ ] Dataset 标准变化后 OUTDATED 展示。
- [ ] Route 详情跳转 Precheck/Task，不混入运行详情。

### 4.4 Task / Precheck / Validation / Operations

- [ ] Task 创建/编辑使用当前配置模型，不出现 Task Version UI。
- [ ] Precheck 人工启动、汇总、再次 Precheck 交互完整。
- [ ] 正式同步/Recollect/Backfill/Cancel/Watermark 文案正确。
- [ ] Validation 总览/详情/重新校验交互正确。
- [ ] Message Policy 只在 Dataset 页编辑，Task 只读展示生效值。
- [ ] Alert/Log/Audit 页面完整。

### 4.5 前端验收

- [ ] `npm run lint` 通过。
- [ ] `npm run build` 通过。
- [ ] 每个菜单有真实 URL，刷新不丢页。
- [ ] 逐页核对字段、操作、状态、空态、错误态、处理中和危险确认。
- [ ] 前端模型确定后再冻结最终 API Contract。

---

## 5. 阶段 1 技术一致性 Review 顺序

按用户确认顺序，一次只讨论一个：

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [ ] 全量 FK Matrix。
3. [ ] Business/Concurrency Unique Matrix。
4. [ ] Status / Enum / CHECK Matrix。
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论：**全量 FK Matrix**。

---

## 6. 前端之后的后端阶段

1. 最终 P0 PostgreSQL/Flyway V1；
2. Resource/Route/Task Java Entity/Repository；
3. API DTO/Controller 与前端合同；
4. PostgreSQL/外部通道 Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ 执行链路；
6. Resource → Route → Precheck → Task → Execution → Validation → Watermark → Message E2E；
7. 多实例调度、通道可靠性、大批次流式处理；
8. 权限、Audit、Alert 和生产验收。

---

## 7. 阶段 1 最终签字门槛

- [x] Active Spec 不再把旧业务系统实例模型当有效设计。
- [x] Active Spec 不再把 Task Version/独立 Validation Policy 当有效目标模型。
- [x] P0 PostgreSQL/Quartz 最终表清单和数量已冻结。
- [ ] FK、Unique、Status、Delete Behavior 最终矩阵一致。
- [ ] 前端产品模型与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md` 完成。
- [ ] 用户明确确认：`目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。`

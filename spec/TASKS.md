# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：Spec 收口 + 前端优先  
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

当前开发顺序已经调整为：

```text
Spec 收口
→ 前端页面/导航/交互/文案 100%
→ API 合同
→ 后端数据库和 Java 模型整改
→ 联调和端到端验收
```

在前端产品模型未稳定前，不主动推进新的后端实现。

---

## 1. 当前核心业务基线

### 1.1 接入资源

- [x] 一个部署只服务一个医共体，不建立租户表。
- [x] 医疗机构为扁平集合。
- [x] 保留全局轻量业务目录（HIS/LIS/PACS 等分类）。
- [x] 源数据源直接绑定一家机构 + 一个业务目录。
- [x] 源数据源支持 `HOST_PORT/JDBC_URL`，凭据与 URL 分离。
- [x] 目标 Doris 为全局逻辑资源，可包含多个 FE，不管理 BE。
- [x] 删除旧“系统实例 + 机构/数据源多对多”资源中间层。
- [x] 前端不增加独立系统实例管理页。

### 1.2 标准 Dataset

- [x] 标准 Dataset 只允许管理员从医共体规范库人工同步。
- [x] 不允许手工新增，不自动同步。
- [x] 只有规范化定义变化才生成新的不可变 Dataset version。
- [x] 标准字段只允许大小写差异匹配，不支持字段重命名或标准任务 `CUSTOM_SQL`。
- [x] 医疗字段合同统一服务 Doris DDL、Reader、预检和 Checksum。

### 1.3 机构采集 Route

- [x] Route 固定属于一家机构。
- [x] Route 选择当前机构所属且启用的 Source datasource。
- [x] Route 保存 Dataset、Source、Schema/Object、Target 和字段解析合同。
- [x] 不再存在共享 Route 覆盖多机构模型。
- [x] 不建立 Route 覆盖机构关系表/版本覆盖机构表。
- [x] Route 状态与结构核对状态分离。
- [x] 配置变化生成不可变 `collection_route_version`。

### 1.4 Task

- [x] 一个 Task 固定属于一个机构 + 一个 Dataset。
- [x] 同一机构 + Dataset 只能存在一个未删除 Task。
- [x] `institution_id/dataset_id` 创建后不可修改。
- [x] Task 保存当前配置，不建立 `sync_task_version`。
- [x] Task 可显式切换同一机构/同一 Dataset 的 Route version，不自动重置水位。
- [x] 活动同步执行期间禁止编辑；活动独立校验期间允许普通编辑。
- [x] Execution/Validation 使用启动快照解释历史。

### 1.5 三种标准任务

| Dataset 合同 | Task kind | 写入 | Doris Key |
| --- | --- | --- | --- |
| 无真实业务主键 | `FULL_ONLY` | `REPLACE_INSTITUTION_SCOPE` | `DUPLICATE_KEY` |
| 有业务主键 + 增量字段 | `FULL_THEN_INCREMENTAL` | `UPSERT` | `UNIQUE_KEY` |
| 有业务主键、无增量字段 | `FULL_ONLY` | `UPSERT` | `UNIQUE_KEY` |

- [x] 不生成假主键。
- [x] 无主键全量只清理当前机构范围。
- [x] Reader 第一阶段固定单并发，Fetch Size 可配置。

### 1.6 Execution/Watermark

- [x] 同一 Task 禁止并发执行。
- [x] 活动执行期间到达的新计划触发跳过，不排队追赶。
- [x] 失败不自动重试、不自动暂停 Task、不推进水位。
- [x] 补采为独立执行，不修改正式水位。
- [x] 重新采集从范围起点和第 1 批重新读取。
- [x] 取消只取消当前执行，不改变调度开关。
- [x] `task_watermark` 只保存当前正式水位，不保存 Task version。

### 1.7 Precheck/Validation/Message

- [x] Precheck 只能人工启动，同 Route 同时最多一个活动运行。
- [x] Precheck 与正式同步严格分离，正式同步重新读取真实 Source。
- [x] 正式同步最低严格 `ROW_COUNT`，不能关闭。
- [x] 有真实业务主键时可选择 `ROW_COUNT_CHECKSUM`，无主键只能 ROW_COUNT。
- [x] 数据集/Task 校验覆盖使用可空字段继承，不建立独立任务校验策略表。
- [x] 消息只用 RabbitMQ，只存在数据集级，Task 不能覆盖。
- [x] 阻断校验通过后才推进水位并创建消息 Outbox。

---

## 2. 本次 Spec 收口工作

### 2.1 旧资源模型清理

- [x] 重写 `PRODUCT_AND_BUSINESS_DECISIONS.md`。
- [x] 重写 `TARGET_METADATA_MODEL.md`。
- [x] 重写 `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md`。
- [x] 重写 `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md`。
- [x] 更新 `P0_PHYSICAL_TABLE_DICTIONARY.md` 总索引。
- [x] 更新阶段状态与实施计划。
- [x] `PENDING_DECISIONS.md` 不再把旧系统实例作为待确认项。

### 2.2 仍需继续的文档一致性清理

以下属于已经明确的机械工作，不需要重新询问业务：

- [ ] 清理 `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 中已经被后续 Review 废止的独立 validation policy / task version 描述。
- [ ] 清理其他早期专项 Review 中“多机构 Route”“Task version”等已经被后续 Review 修正的正文，或明确移入 `reference/legacy`。
- [ ] 形成最终 P0 PostgreSQL 表清单和总数。
- [ ] 核对全部复合 FK 的父唯一键与子索引。
- [ ] 统一内部枚举名称。

---

## 3. 当前最高优先级：前端页面 100%

目标：先把页面、路由、交互和文案完全确定，不推进新的后端改造。

### 3.1 导航/信息架构

- [ ] 首页/工作台与最终导航一致。
- [ ] 接入资源覆盖：机构、业务目录、源端数据源、目标端数据源、医共体标准。
- [ ] 提供独立“机构采集路由”页面；以机构为上下文。
- [ ] 不出现旧系统实例入口。
- [ ] 任务中心、数据质量、运维管理、系统设置入口与原型一致。
- [ ] 帮助/文档和账号管理入口待 `PENDING_DECISIONS.md` 对应事项确认。

### 3.2 接入资源页面

- [ ] 机构 CRUD/启停/引用统计交互完整。
- [ ] 业务目录 CRUD/启停/引用保护完整。
- [ ] Source datasource 新增/编辑/测试/启停/删除引用保护完整。
- [ ] Source datasource 明确显示所属机构 + 业务目录。
- [ ] Target Doris 新增/编辑/FE 端点/测试/启停完整。
- [ ] 医共体标准同步、列表、详情、字段合同、策略完整。

### 3.3 机构采集路由

- [ ] 页面先选择当前机构。
- [ ] 新增 Route：Dataset → Source → Schema → Object → Target。
- [ ] Source 只显示当前机构启用数据源，业务目录自动带出。
- [ ] 实时读取 Schema/Object 元数据。
- [ ] 字段/结构核对及结果展示完整。
- [ ] Route 人工启用/停用完整。
- [ ] Dataset 标准变化后的 OUTDATED 展示完整。
- [ ] Route 详情可跳转 Precheck/Task，但不混入运行详情。

### 3.4 Task/Precheck/Validation/Operations

- [ ] Task 创建/编辑与 Route 当前模型一致。
- [ ] Precheck 人工启动、结果汇总、再次预检交互完整。
- [ ] 正式同步、重新采集、补采、取消、水位操作文案正确。
- [ ] 校验总览/详情/重新校验交互正确。
- [ ] 消息策略只在 Dataset 页可编辑，Task 只读展示生效值。
- [ ] 告警、日志、操作审计页面完整。

### 3.5 前端验收

- [ ] `npm run lint` 通过。
- [ ] `npm run build` 通过。
- [ ] 每个菜单都能直接通过 URL 访问并刷新不丢页面。
- [ ] 逐页与最终原型核对字段、操作、状态、空态、危险确认和文案。
- [ ] 前端模型确定后再定义最终 API contract。

---

## 4. 前端之后的后端阶段

只有前端产品模型确定后再开始：

1. 最终 P0 PostgreSQL/Flyway V1；
2. Resource/Route/Task Java 实体和 Repository；
3. API DTO/Controller 与前端合同；
4. PostgreSQL 和外部通道集成测试；
5. SeaTunnel/Doris/Quartz/RabbitMQ 执行链路；
6. 配置 → Route → Precheck → Task → Execution → Validation → Watermark → Message 端到端联调；
7. 多实例调度、通道可靠性和大批次流式处理；
8. 权限、审计、告警和生产验收。

---

## 5. 阶段 1 最终签字门槛

在数据库实施前必须满足：

- [ ] 所有当前 spec 不再把已废止资源模型当成有效设计；
- [ ] P0 表清单、FK、唯一性、状态、删除行为一致；
- [ ] 前端产品模型与 spec 一致；
- [ ] `PHASE1_FINAL_REVIEW.md` 完成；
- [ ] 用户明确确认：`目标元数据模型 Review 通过，允许进入数据库/后端实施阶段。`

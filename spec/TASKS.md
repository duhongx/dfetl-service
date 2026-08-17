# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：P0 PostgreSQL 表清单 + FK Matrix + Unique Matrix 已冻结；前端优先  
> 最近更新：2026-08-17  
> 产品基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 0. 执行原则

冲突优先级：

1. 用户最新明确确认；
2. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
3. 日期更晚且已确认专项 Review；
4. 当前 P0 物理字典；
5. 本任务清单；
6. 老代码/SQL/历史文档。

当前开发主顺序仍是：

```text
Spec 技术一致性逐项收口
+
前端页面/导航/交互/文案优先完成
→ API Contract
→ Flyway V1 / Java 后端
→ E2E
```

当前不主动推进数据库/Java 实施。

## 1. 当前核心模型

### Resource

- [x] 一个部署服务一个医共体。
- [x] Institution 扁平。
- [x] Business Catalog 是 HIS/LIS/PACS 轻量分类。
- [x] Source 直接绑定 Institution + Business Catalog。
- [x] Target Doris 全局共享，可多个 FE。
- [x] 不建设 Business System Instance。

### Dataset

- [x] 只从规范库人工同步。
- [x] 定义内容使用不可变 Dataset Version。
- [x] 相同 `definition_hash` 命中历史 Version 时复用旧 Version，不创建重复内容 Version。
- [x] Field Contract 服务 DDL/Reader/Precheck/Checksum。
- [x] Dataset Validation Override 直接保存在 `standard_dataset`。
- [x] Message Policy 只在 Dataset 级。

### Route

- [x] 单机构 Route。
- [x] 一 Institution + Dataset 一条未删除 Route。
- [x] Route Version 不可变。
- [x] 相同 `contract_hash` 命中历史 Route Version 时复用旧 Version。
- [x] Route/Route Version/Source 机构一致性使用复合 FK。
- [x] Route Current Version 使用同父 Deferred FK。
- [x] `route_field_resolution` 使用 `dataset_version_id + standard_field_id`，不重复保存 `field_code`。

### Task

- [x] 固定身份 = Institution + Dataset。
- [x] Task 当前配置可编辑，不建立 Task Version。
- [x] Route/Dataset/Institution 只保留四元强 FK。
- [x] 活动同步期间禁止编辑；活动独立 Validation 期间允许编辑。
- [x] Execution/Validation 启动快照解释历史。

### Runtime

- [x] 同 Task 禁止并发同步。
- [x] INITIAL_FULL 成功后 Watermark=T0，下一次正常运行才 Incremental。
- [x] Watermark Source Execution 必须属于同 Task。
- [x] Validation 关联 Execution 时必须属于同 Task。
- [x] External API Execution 必须引用同 Client 的真实 External Request。
- [x] Outbox 身份通过父 Execution 复合 FK 固定。
- [x] RabbitMQ Only。

## 2. P0 PostgreSQL 表清单：完成

```text
DFETL P0 领域/控制表 39
Quartz 官方表          11
-------------------------
V1 创建                50
```

- [x] `alert_rule_channel` 计入 39 张。
- [x] Quartz 11 张单独统计并使用官方 Schema。
- [x] `flyway_schema_history` 不计入 50。

## 3. P0 FK Matrix：完成

权威文档：`spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md`。

```text
最强复合 FK
历史 RESTRICT
纯配置子对象 CASCADE
普通审计用户 SET NULL
业务运行责任用户 RESTRICT
FK 子列具备索引
```

- [x] Route → Current Route Version 同父 Deferred FK。
- [x] Route Version → Route Identity 复合 FK。
- [x] Field Resolution Dataset Version/Standard Field 闭环。
- [x] Task 四元 Route Version FK。
- [x] Watermark/Validation → 同 Task Execution。
- [x] External Request → Execution。
- [x] Execution → Outbox 四元身份。
- [x] Alert/External API 支撑 FK。

## 4. P0 Business / Concurrency Unique Matrix：完成

权威文档：`spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md`。

唯一性分类：

```text
Business Unique
Concurrency / Safety Partial Unique
FK Support Unique
```

确认结论：

- [x] 稳定 Code/ID、父内 Version No、不可变内容 Hash、业务关系 Pair 使用 Business Unique。
- [x] FK Support Unique 不重复算业务唯一。
- [x] Dataset/Route 相同 Hash 复用历史不可变 Version；Audit 记录再次保存动作。
- [x] Current Route：Institution + Dataset 未删除唯一。
- [x] Current Task：Institution + Dataset 未删除唯一。
- [x] Active Execution per Task Partial Unique。
- [x] Active Precheck per Route Partial Unique。
- [x] Active Independent Validation per Task Partial Unique。
- [x] Active Delete Snapshot per Task Partial Unique。
- [x] 一个 Execution 最多一个 SYNC_GATE 和一个 Message Outbox。
- [x] 一个 Delete Candidate 最多一个 Delete Reconciliation。
- [x] External Client 只保证 `client_id` 唯一；`client_name` 可重复。
- [x] Alert Channel/Rule 因无独立 Code，Name 继续大小写不敏感唯一。
- [x] Delete Apply 使用单一 `uk_delete_apply_effective`，覆盖 PENDING/RUNNING/SUCCEEDED。
- [x] Sync Execution 与 Independent Validation 跨表互斥不新增 Lock/Slot 表。

## 5. 阶段 1 技术一致性 Review 顺序

严格一次讨论一个：

1. [x] P0 PostgreSQL 最终表清单 + 数量。
2. [x] 全量 FK Matrix。
3. [x] Business / Concurrency Unique Matrix。
4. [ ] **Status / Enum / CHECK Matrix。**
5. [ ] Delete Behavior Matrix。
6. [ ] Execution / Validation / Outbox Snapshot 最小充分性 Review。
7. [ ] `PHASE1_FINAL_REVIEW.md`。

下一项只讨论第 4 项。

## 6. 当前最高开发优先级：前端 100%

### Navigation

- [ ] 首页/工作台与最终导航一致。
- [ ] 接入资源：Institution、Business Catalog、Source、Target、医共体标准。
- [ ] 独立 Institution Route 页面。
- [ ] 不出现 Business System Instance。
- [ ] Task/Data Quality/Operations/System Settings 与最终原型一致。
- [ ] Help/Docs、Account Management 入口按 Pending Decisions 确认。

### Resource

- [ ] Institution CRUD/启停/引用统计。
- [ ] Business Catalog CRUD/启停/引用保护。
- [ ] Source CRUD/Test/启停/引用保护。
- [ ] Target Doris/FE/Test/启停。
- [ ] Dataset Sync/Detail/Field Contract/Defaults/Validation/Message。

### Route

- [ ] Institution Context。
- [ ] Dataset → Source → Schema → Object → Target。
- [ ] Source 只列当前 Institution；Business Catalog 自动带出。
- [ ] Structure Check。
- [ ] Route Enable/Disable、OUTDATED。
- [ ] Route Detail 跳转 Precheck/Task。

### Task / Quality / Operations

- [ ] Task UI 不出现 Task Version。
- [ ] Precheck 人工启动/汇总/再次预检。
- [ ] Sync/Recollect/Backfill/Cancel/Watermark 文案正确。
- [ ] Validation 总览/详情/重新校验。
- [ ] Message Policy 只在 Dataset 编辑。
- [ ] Alert/Log/Audit 页面完整。

### Frontend Acceptance

- [ ] lint。
- [ ] build。
- [ ] 所有菜单真实 URL。
- [ ] 逐页原型、状态、空态、错误态、危险确认核对。

## 7. 前端之后

1. API Contract；
2. Flyway V1；
3. Resource/Route/Task Java Model；
4. PostgreSQL/外部通道 Integration Test；
5. SeaTunnel/Doris/Quartz/RabbitMQ；
6. Resource → Route → Precheck → Task → Execution → Validation → Watermark → Message E2E；
7. 多实例与大批次可靠性；
8. Audit/Alert/生产验收。

## 8. 阶段 1 最终签字门槛

- [x] Active Spec 业务模型无 Business System Instance/Multi-Institution Route/Task Version/Validation Policy 残留。
- [x] PostgreSQL/Quartz 表清单和数量冻结。
- [x] FK Matrix 完成。
- [x] Unique Matrix 完成。
- [ ] Status/CHECK Matrix 完成。
- [ ] Delete Behavior Matrix 完成。
- [ ] Snapshot 最小充分性完成。
- [ ] 前端产品模型与 Spec 一致。
- [ ] `PHASE1_FINAL_REVIEW.md` 完成。
- [ ] 用户最终签字。

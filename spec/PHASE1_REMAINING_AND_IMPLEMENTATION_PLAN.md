# 阶段 1 剩余 Review 与后续实施规划

> 状态：Active Spec 语义收口完成；前端优先 + 最终物理矩阵待完成  
> 最近更新：2026-08-17  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 执行原则

1. 已确认业务规则不重复讨论；旧文档残留直接机械清理。
2. 当前产品模型以“接入资源 + 单机构 Route + 当前配置 Task + 运行启动快照”为主线。
3. 当前优先完成前端页面/交互，再冻结 API Contract 和数据库实现。
4. 阶段 1 最终签字前不创建 Flyway `V1__baseline.sql`，不修改正式数据库结构。
5. 新系统只使用独立 PostgreSQL，不连接或认领老 `df_ygt/df_etl`。

## 2. 已完成

- [x] 历史 Java/SQL 审计。
- [x] Dataset/医疗字段合同/Doris ODS/RAW Review。
- [x] 当前配置 Task Review。
- [x] INITIAL_FULL/INCREMENTAL、Load Batch、Doris Label Review。
- [x] Validation/独立 Validation 并发 Review。
- [x] RabbitMQ Outbox Review。
- [x] External API、Quartz、Delete Snapshot Review。
- [x] 2026-08-17 接入资源和单机构 Route 模型收口。
- [x] 2026-08-17 Task Version / Validation Policy Active Spec 语义迁移。

## 3. 工作包 A：Active Spec 一致性收口

### A1. 接入资源 / Route 主线

状态：**完成**。

- [x] Source 直接绑定 Institution + Business Catalog。
- [x] 删除旧 Business System Instance 多对多中间层。
- [x] Route 改为单 Institution。
- [x] 删除 Route/Route Version 覆盖机构关系表。
- [x] Task 保持固定身份 + 当前配置覆盖。
- [x] 产品基线、逻辑模型、资源/Route 字典、状态和任务清单同步。

### A2. Task Version / Validation Policy / 运行 FK 清理

状态：**完成**。

第一批：

- [x] `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` 删除独立 Global/Dataset Validation Policy，改为 Dataset Override。
- [x] `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` 删除 Task Version 当前语义，改为当前 Task + Snapshot。

第二批：

- [x] `EXTERNAL_API_REVIEW.md` 不再创建第一版 Task Version。
- [x] `QUARTZ_JOBSTORE_REVIEW.md` 不再读取 Current Task Version，直接读取 `sync_task.schedule_*`。

全 Spec 扫描发现并修正：

- [x] Route Version 四元父唯一键。
- [x] Execution 四元 Route FK。
- [x] Delete Snapshot 四元 Route FK。
- [x] Precheck 多机构旧维度。
- [x] `P0_SUPPORT_OBJECT_REVIEW.md` 中旧 `global_validation_policy` 和 Quartz Task Version 文案。
- [x] 总物理索引、Consistency Review、TASKS、Phase Status 同步。

扫描验收规则：旧词可以出现在“历史/已废止/明确不建立/不得进入 V1”中，但不能再作为 Active Model。

## 4. 工作包 B：最终物理一致性矩阵

目标：形成可直接生成 V1 的唯一 P0 模型。

- [ ] 最终 PostgreSQL 表清单和总数。
- [ ] Quartz 官方表清单。
- [ ] Doris ODS/RAW 和共享技术表清单。
- [ ] 全量 FK 矩阵。
- [ ] 全量 Unique/Concurrency Constraint 矩阵。
- [ ] Status/Enum/CHECK 矩阵。
- [ ] Delete Behavior 矩阵。
- [ ] Sensitive Field / Secret 边界。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性最终 Review。
- [ ] `PHASE1_FINAL_REVIEW.md`。

当前重点 FK：

```text
source_datasource(id,institution_id)
→ collection_route(source_datasource_id,institution_id)

standard_dataset_version(dataset_id,id)
→ collection_route_version(dataset_id,dataset_version_id)

collection_route_version(
  id,institution_id,dataset_id,dataset_version_id
)
→ sync_task / sync_execution / precheck_run / delete_snapshot_run
  对应 Route Version 身份列

sync_task(id,institution_id,dataset_id)
→ sync_execution / delete_snapshot_run

sync_execution(id,task_id,dataset_id,institution_id)
→ message_outbox 对应身份列
```

最终矩阵阶段需要逐项确认父 Unique、子 Index 和 `ON DELETE`，但不需要重新讨论业务模型。

## 5. 工作包 C：前端产品完成 100%

当前最高优先级。

### C1. 信息架构

- [ ] 左侧导航与最新模型一致。
- [ ] 接入资源包含 Institution、Business Catalog、Source、Target、医共体标准。
- [ ] 独立“机构采集路由”入口。
- [ ] 不出现 Business System Instance 页面。
- [ ] Task/Precheck/Validation/Operations 页面职责不混叠。
- [ ] Account Management / Help 入口按 `PENDING_DECISIONS.md` 继续逐项确认。

### C2. 页面和交互

- [ ] 逐页启动前端核对原型。
- [ ] CRUD、启停、连接测试、结构核对、危险删除和引用保护完整。
- [ ] Route 以 Institution 上下文筛选 Source。
- [ ] Route 新增：Dataset → Source → Schema → Object → Target。
- [ ] Task 创建/编辑使用当前配置，不出现 Task Version UI。
- [ ] Dataset Message Policy 只在 Dataset 页面编辑。
- [ ] Validation UI 不出现 Policy Table/Override Mode/关闭校验/容差等旧概念。
- [ ] Precheck 与正式同步分离。
- [ ] Watermark/Backfill/Recollect/Cancel 文案与真实语义一致。
- [ ] 所有页面 URL 可直接访问、刷新和回退。
- [ ] lint/build 通过。

前端验收后再冻结 API Contract。

## 6. 工作包 D：API Contract

前端模型确认后：

1. Resource DTO/API；
2. Route CRUD/Structure Check/Metadata API；
3. Task CRUD/Run API，只有当前 Task 配置；
4. Precheck/Validation/Operations API；
5. Error Code、Pagination、Revision、危险操作确认参数；
6. 删除所有旧 Business System Instance、Task Version、Validation Policy Table API Contract。

## 7. 工作包 E：数据库和 Java 实施

只在前端/API 收口后：

1. 引入 Flyway PostgreSQL；
2. 依据 Final P0 Dictionary 生成干净 V1；
3. Resource；
4. Dataset Identity/Version/Field Contract；
5. Route Identity/Version/Field Resolution；
6. Task Current Config + Watermark；
7. Execution/Batch/Precheck/Validation/Outbox；
8. Quartz/External API/Delete Snapshot；
9. 删除旧 Entity/Repository/Service/Controller；
10. PostgreSQL/External Channel Integration Test；
11. Spring Boot Migrate/Startup/Quartz/Restart Recovery Test。

## 8. 工作包 F：端到端闭环

```text
接入资源
→ Institution Route
→ Structure Check
→ Precheck
→ Task
→ Execution
→ Load Batch
→ Validation
→ Watermark
→ RabbitMQ Outbox
→ Recollect/Backfill/Manual Validation
```

验收包括：

- Multi-instance Scheduler Concurrency；
- Doris Label 不确定状态；
- 大批次流式处理；
- Alert/Log/Audit；
- Account/Security；
- Production Cutover 和旧系统停调度。

## 9. 阶段门槛

阶段 1 完成需要：

```text
Active Spec 无当前模型冲突
+
前端产品模型确认
+
Final Physical Matrix Review
+
PHASE1_FINAL_REVIEW
+
用户明确签字
```

在此之前不进入数据库/Java 后端实施。

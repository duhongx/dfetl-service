# 阶段 1 剩余 Review 与后续实施规划

> 状态：执行中  
> 更新：2026-08-17  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标逻辑模型：`spec/TARGET_METADATA_MODEL.md`

## 1. 执行原则

1. 已确认业务规则不重复讨论；旧文档残留直接机械清理。
2. 当前产品模型以“接入资源 + 单机构采集 Route + 可变 Task 当前配置”为主线。
3. 先完成 spec 和前端页面/交互，再确定 API 和后端实现。
4. 阶段 1 最终签字前不创建 Flyway `V1__baseline.sql`，不修改新旧正式数据库结构。
5. 新系统只使用独立 PostgreSQL，不连接或认领老 `df_ygt/df_etl`。

## 2. 已完成

- 历史 Java/SQL 审计；
- 标准 Dataset 和医疗字段合同 Review；
- Doris ODS/RAW 合同 Review；
- Task 可变当前配置 Review；
- 首次全量/增量、Load Batch/Doris Label Review；
- Validation、独立校验并发 Review；
- RabbitMQ Outbox Review；
- 外部 API、Quartz、删除识别 Review；
- 2026-08-17 接入资源和机构采集 Route 业务模型收口。

## 3. 工作包 A：Spec 一致性收口

### A1. 接入资源/Route 主线

状态：**完成**。

- [x] Source datasource 直接绑定机构 + 业务目录。
- [x] 删除旧系统实例多对多资源中间层。
- [x] Route 改成单机构上下文。
- [x] 删除 Route 覆盖机构集合和版本覆盖机构集合。
- [x] Task 继续采用固定身份 + 当前配置覆盖。
- [x] 更新产品基线、逻辑模型、资源字典、Route 字典、任务清单和阶段状态。

### A2. 其他早期文档机械清理

状态：**待继续**。

- [ ] 清理 Dataset 字典中后续已废止的独立 validation policy / task version 文字。
- [ ] 清理早期 Review 中已经被更晚专项 Review 取代的 Task version、多机构 Route 等描述，或移到 `reference/legacy`。
- [ ] 核对所有 active spec 的交叉链接和优先级说明。

## 4. 工作包 B：最终物理一致性 Review

目标：形成可直接生成 V1 的唯一 P0 模型。

- [ ] 最终 PostgreSQL 表清单和总数；
- [ ] Quartz 官方表清单；
- [ ] Doris ODS/RAW 和共享技术表清单；
- [ ] 全量 FK 矩阵；
- [ ] 全量唯一性/并发约束矩阵；
- [ ] 状态和枚举矩阵；
- [ ] 删除行为矩阵；
- [ ] 敏感字段和 Secret 边界；
- [ ] 完成 `PHASE1_FINAL_REVIEW.md`。

重点复核：

```text
source_datasource(id,institution_id)
→ collection_route(source_datasource_id,institution_id)

collection_route_version(id,institution_id,dataset_id)
→ sync_task(route_version_id,institution_id,dataset_id)

standard_dataset_version(dataset_id,id)
→ collection_route_version(dataset_id,dataset_version_id)
→ sync_task(dataset_id,dataset_version_id)
```

## 5. 工作包 C：前端产品完成 100%

当前最高优先级。

### C1. 信息架构

- [ ] 左侧导航与最新产品模型一致；
- [ ] 接入资源包含机构、业务目录、Source、Target、医共体标准；
- [ ] 独立“机构采集路由”入口；
- [ ] 不出现旧系统实例管理入口；
- [ ] Task/Precheck/Validation/Operations 页面职责不混叠。

### C2. 页面和交互

- [ ] 逐页启动前端核对原型；
- [ ] CRUD、启停、连接测试、结构核对、危险删除和引用保护完整；
- [ ] Route 以机构上下文筛选 Source；
- [ ] Route 新增逻辑：Dataset → Source → Schema → Object → Target；
- [ ] Task 创建/编辑使用 Route 当前模型；
- [ ] Dataset 消息策略只在 Dataset 页面编辑；
- [ ] Precheck 与正式同步分离；
- [ ] 水位、补采、重新采集、取消等操作文案与真实语义一致；
- [ ] 所有页面 URL 可直接访问、刷新和回退；
- [ ] lint/build 通过。

前端验收完成后再冻结 API contract。

## 6. 工作包 D：API Contract

前端模型确认后：

1. 定义 Resource DTO/API；
2. 定义 Route CRUD/结构核对/元数据枚举 API；
3. 定义 Task CRUD/运行操作 API；
4. 定义 Precheck/Validation/Operations API；
5. 明确错误码、分页、乐观锁和危险操作确认参数；
6. 删除所有仅为旧数据模型服务的 API contract。

## 7. 工作包 E：数据库和 Java 后端实施

只在前端/API 收口后执行：

1. 引入 Flyway PostgreSQL；
2. 根据最终 P0 字典生成干净 V1；
3. Resource：Institution/BusinessCatalog/Source/Target；
4. Dataset identity/version/field contract；
5. Route identity/version/field resolution；
6. Task 当前配置 + Watermark；
7. Execution/Batch/Precheck/Validation/Outbox；
8. Quartz/External API/Delete Snapshot 支撑；
9. 删除旧 Entity/Repository/Service/Controller；
10. PostgreSQL 和外部通道集成测试；
11. Spring Boot 启动、迁移、Quartz 和重启恢复验证。

## 8. 工作包 F：端到端闭环

```text
接入资源
→ 机构采集 Route
→ 结构核对
→ Precheck
→ Task
→ Execution
→ Load Batch
→ Validation
→ Watermark
→ RabbitMQ Outbox
→ 重采/补采/人工复检
```

验收包括：

- 多实例调度互斥；
- Doris Label 不确定状态处理；
- 大批次流式处理；
- 告警、日志、审计；
- 权限/账号；
- 生产切换和老系统停调度策略。

## 9. 阶段门槛

阶段 1 完成需要：

```text
Spec 无当前模型冲突
+
前端产品模型确认
+
最终物理模型 Review 完成
+
PHASE1_FINAL_REVIEW 完成
+
用户明确签字
```

在此之前不进入数据库/后端实施。

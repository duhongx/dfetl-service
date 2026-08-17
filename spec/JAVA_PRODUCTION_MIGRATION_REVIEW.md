# Java 生产代码迁移与业务 Review

> 首次 Review：2026-08-13  
> 最近收口：2026-08-17  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 来源：`duhongx/datax-lite-jdk21@master`（tree `175a15ff6d7f1f3b258a0422420ea672610933a4`）

## 1. 结论

Java 生产代码迁移完整性已经核对：老仓库生产 Java 文件已迁入新仓库，迁移完整不等于业务模型已经符合新产品。

2026-08-17 对 Resource/Route 模型重新收口后，本文件早期关于“增加真实部署系统实例、多机构共享 Route、Task version”的目标结论全部废止。当前后续整改必须遵循：

```text
机构
→ Source datasource（直接属于机构 + 业务目录）
→ 单机构 collection_route
→ 固定机构+Dataset身份、当前配置可编辑的 sync_task
→ sync_execution / validation_run 启动快照
```

## 2. P0 发现及当前目标

| ID | 领域 | 当前代码/历史问题 | 当前目标 |
| --- | --- | --- | --- |
| REV-P0-001 | Task identity | Dataset/Route 等仍有 JSON 或弱关系路径，并发唯一性不足。 | `sync_task.institution_id + dataset_id` 固定身份；未删除唯一；Route version、Dataset version 使用真实 FK。 |
| REV-P0-002 | Task lifecycle | 旧代码存在物理删除、配置原地混杂、历史依赖当前值的问题。 | Task 逻辑删除；当前配置保存在 `sync_task`；不建立 Task version；Execution/Validation 保存启动快照。 |
| REV-P0-003 | Resource/Route | 旧 `InstitutionDatasetRoute` 是单机构，Source 直接归属机构；早期 Review 曾错误要求共享实例和多机构 Route。 | **单机构 Source/Route 本身不再是缺陷。** 需要改造成 Source=机构+业务目录，Route=机构+Dataset+Source+Schema/Object+Target+字段解析 version。 |
| REV-P0-004 | Route/结构核对 | 旧 route 校验、enable 和运行前断言职责混杂。 | Route 保存独立 `structure_status` 与业务启停；核对结果可追溯，不自动启用；Task/Execution 按当前产品门槛显式校验。 |
| REV-P0-005 | Precheck | 旧实现运行状态、问题等级、行级明细和处置模型过重。 | Precheck 人工运行、与正式同步分离；按当前最终页面只保存/展示需要的质量事实。 |
| REV-P0-006 | RAW | 旧 RAW 隔离字段和清理策略需要按最终 Route/Run 关系复核。 | RAW 只服务 Precheck，不作为正式同步来源；按 run/route 精确隔离和清理。 |
| REV-P0-007 | ODS/RAW | 旧代码仍存在严格/宽松模式、静默过滤或非法值转 NULL 等路径。 | ODS 固定医疗合同；RAW 固定预检原始值；正式同步遇到合同错误明确失败。 |
| REV-P0-008 | Batch/Label | 旧 `TaskChunk` 与真实生产写入路径不闭环，Doris 不确定响应处理不足。 | `load_batch` 记录实际游标、Label、Doris 状态；不确定时探测原 Label，不自动重投未知批次。 |
| REV-P0-009 | Checksum | 旧校验可能同步后重新查询 Source，而不是固定本次真实同步上下文。 | Validation 使用 Execution 启动快照和本次精确范围；有业务主键才允许内容 Checksum。 |
| REV-P0-010 | Success finalization | 水位、Validation、Message 的成功边界不够一致。 | 阻断 Validation 通过后才确认 Execution 成功、推进 Watermark、创建 RabbitMQ Outbox。 |
| REV-P0-011 | Engineering/DB | 新系统数据库/Flyway 仍未按最终模型实施。 | 前端产品模型先完成；之后从独立空 PostgreSQL 生成干净 V1，不重放老 SQL。 |
| REV-P0-012 | No-PK full | 旧执行可能整表 DROP/TRUNCATE，破坏共享 ODS 中其他机构数据。 | `FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY`，只清理当前机构范围。 |
| REV-P0-013 | Validation defaults | 旧默认允许关闭、容差、异步非阻断等。 | 正式同步最低严格 ROW_COUNT，不可关闭；最终生效 Validation 必须阻断成功收尾。 |
| REV-P0-014 | No-PK checksum | 旧代码可能把技术字段当比对键。 | 只有合同真实业务主键可使用 `ROW_COUNT_CHECKSUM`；无主键固定 ROW_COUNT。 |
| REV-P0-015 | CUSTOM_SQL | 标准 Task 仍保留通用 CUSTOM_SQL 路径。 | 标准业务移除；复杂逻辑由 Source Table/View/Materialized View 固化。 |
| REV-P0-016 | Field resolution | Reader/Precheck/Validation 各自解析字段，额外字段可能放行。 | 单一 `route_field_resolution`：只允许大小写差异，字段集合严格相等，所有 Source SQL 共用。 |

## 3. Resource/Route 重新评估

### 3.1 Source datasource

旧代码中 Source 直接带机构归属，这一点现在与目标模型方向一致，不再删除。

需要调整为：

```text
source_datasource
  ├─ institution_id
  └─ business_catalog_id
```

并保留连接测试、启停、HOST_PORT/JDBC_URL、凭据加密等能力。

### 3.2 Route

老 `InstitutionDatasetRoute` 的“单机构”概念继续保留，但物理模型需要收敛成：

```text
collection_route
  ├─ institution_id
  ├─ dataset_id
  ├─ source_datasource_id
  ├─ source_schema
  ├─ source_object/type
  ├─ target_datasource_id
  ├─ status
  ├─ structure_status
  └─ current_version_id

collection_route_version
route_field_resolution
```

不再建设覆盖机构集合。

### 3.3 Task

任务身份：

```text
institution_id + dataset_id
```

任务直接保存当前 `dataset_version_id/route_version_id` 和读取、调度、校验覆盖等配置。

不建立：

```text
sync_task_version
sync_task.current_version_id
task_version_id
```

## 4. 仍可直接复用/重构复用的代码能力

以下老能力仍具有实现价值，但必须按新模型改调用关系：

- PostgreSQL/MySQL/Oracle/SQL Server JDBC 元数据读取；
- Source connection test；
- Schema/Table/View/Column 枚举；
- Doris DDL/Stream Load/Label 探测基础能力；
- SeaTunnel 配置生成与运行状态探测；
- Quartz JDBC JobStore；
- RabbitMQ 发布；
- JWT/账号/审计/日志；
- Validation 行数/Checksum 基础组件；
- Precheck 字段规则解析组件。

不能因为代码已经存在就保留旧产品入口或旧表结构。

## 5. 当前前端优先约束

当前不立即按这些发现修改后端。

执行顺序：

```text
Spec 收口
→ 前端页面、导航、交互和文案确认
→ API contract
→ PostgreSQL/Flyway/Java 模型整改
→ 端到端联调
```

前端不得从旧 Controller/Entity 推导出已经废止的系统实例、多机构 Route、Task version、Redis Stream 或 Task 级消息页面。

## 6. 后端实施阶段的验收重点

进入后端阶段后逐项验证：

1. Source 直接机构+业务目录归属；
2. Route 单机构且 Source 机构一致；
3. Task 固定机构+Dataset身份；
4. Task 不存在 version 表；
5. Execution/Validation 启动快照完整；
6. Precheck 与正式同步严格分离；
7. ODS/RAW 合同固定；
8. 无主键只清理当前机构；
9. Doris Label 不确定状态不重复写；
10. Validation 成功后才推进 Watermark 和 Message Outbox；
11. RabbitMQ 为唯一 P0 消息通道；
12. 标准 Task 无 CUSTOM_SQL。

本文件后续只用于记录“旧 Java 能力如何迁移”，不再作为目标 Resource/Route 模型的独立来源。

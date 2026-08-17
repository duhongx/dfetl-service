# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：Spec 一致性收口 + 前端产品模型优先  
> 新系统分支：`duhongx/dfetl-service/main`  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 阶段约束

阶段 1 尚未最终签字：

- 不创建或固化 Flyway `V1__baseline.sql`；
- 不修改正式 PostgreSQL 表结构；
- 不使用新系统 Flyway 认领老 `df_ygt/df_etl`；
- 老系统和新系统元数据库、Quartz、执行、水位、校验、消息完全隔离；
- 当前先完成 spec 和前端产品模型，再进入数据库/Java 实施。

## 2. 已完成基础审计

- 老 Java 生产代码迁移完整性已核对；
- 历史 SQL 已完成审计和分类；
- 标准 Dataset、医疗字段合同、Doris ODS/RAW 规则已完成多轮 Review；
- 可变 Task 模型、Execution、Load Batch、Validation、Outbox、删除识别、外部 API、Quartz 等已有专项 Review；
- 2026-08-17 完成接入资源/机构采集 Route 模型收口。

## 3. 当前已确认的接入资源模型

### 3.1 医疗机构

- 一个部署服务一个医共体；
- 机构为扁平集合；
- 机构编码是源端机构范围的稳定业务编码；
- 不建设机构树和厂商机构编码映射。

### 3.2 业务目录

- HIS/LIS/PACS 等作为全局轻量业务分类；
- 不表示真实部署实例；
- 不维护业务目录与机构的多对多覆盖关系。

### 3.3 Source datasource

- 每个 Source 直接属于一家机构 + 一个业务目录；
- 支持 `HOST_PORT/JDBC_URL`；
- 凭据与 URL 分离；
- Source 页面只维护数据库连接，不维护 Dataset ↔ View。

### 3.4 Target Doris

- 全局逻辑资源；
- 可配置多个 FE；
- 不管理 BE；
- 不直接维护 Dataset → Table 映射。

## 4. 当前已确认的机构采集 Route

- Route 固定属于一家机构；
- 当前机构决定可选择的 Source；
- Route 保存 Dataset、Source、Schema/Object、Target 和字段解析；
- 不存在多机构共享 Route 覆盖集合；
- Route 配置变化生成不可变 Route version；
- Route 状态与结构核对状态独立；
- Route 不保存同步运行状态、最近执行或任务状态。

## 5. 标准 Dataset 与 Doris

- Dataset 只能从规范库人工同步；
- 定义变化生成不可变 Dataset version；
- 标准字段与 Source 字段只允许大小写差异；
- Source 字段集合必须和标准集合严格一致；
- 不提供字段重命名和标准任务 `CUSTOM_SQL`；
- 医疗字段合同统一服务 DDL、Reader、Precheck、Checksum；
- 每个 Dataset 在一个 Doris 逻辑部署中共享 ODS/RAW，按机构代码隔离；
- 普通同步不自动修改 Doris 表。

## 6. Task 当前模型

采用 2026-08-15 已确认的“固定身份 + 当前配置覆盖”：

```text
任务身份 = institution_id + dataset_id
```

- 同一机构 + Dataset 一个未删除 Task；
- 身份创建后不可修改；
- Task 直接保存当前 Route version、Dataset version、读取、调度和校验覆盖；
- 不建立 `sync_task_version`；
- 活动同步执行期间禁止编辑；
- 活动独立校验期间允许普通编辑；
- Execution/Validation 保存启动快照解释历史。

## 7. Execution/Watermark/Validation/Message

- 同 Task 禁止并发同步执行；
- 计划冲突直接跳过，不追赶；
- 失败不自动重试、不自动暂停、不推进水位；
- 补采不改正式水位；
- 重新采集创建新 Execution；
- 正式同步最低严格 ROW_COUNT，不可关闭；
- 有真实业务主键可选择 ROW_COUNT_CHECKSUM；
- 阻断校验通过后才执行成功、推进水位并创建消息 Outbox；
- 消息只用 RabbitMQ，配置只存在 Dataset 级。

## 8. Precheck

- 人工启动；
- 同 Route 同时最多一个活动 Precheck；
- Precheck 与正式同步严格分离；
- 正式同步重新读取真实 Source；
- Precheck 用于发现源端数据质量问题，不用其中间结果替代正式数据。

## 9. 2026-08-17 Spec 收口结果

本次已统一：

```text
PRODUCT_AND_BUSINESS_DECISIONS.md
TARGET_METADATA_MODEL.md
P0_PHYSICAL_TABLE_DICTIONARY.md
P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md
P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
TASKS.md
PHASE1_REVIEW_STATUS.md
PHASE1_REMAINING_AND_IMPLEMENTATION_PLAN.md
P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
PENDING_DECISIONS.md
```

收口结果：

- 删除旧系统实例资源中间层；
- Source 改为直接机构 + 业务目录归属；
- Route 改为单机构模型；
- 删除 Route 覆盖机构集合；
- 保持已确认的可变 Task 模型，不恢复 Task version；
- 后续前端不得出现旧系统实例入口。

## 10. 仍需完成

- [ ] 继续机械清理数据集/校验等早期字典中已经被 8 月 15 日专项 Review 废止的对象描述；
- [ ] 核对最终 P0 表清单和总数；
- [ ] 核对全部复合 FK、唯一键和索引；
- [ ] 统一状态/枚举；
- [ ] 按最新产品模型完成前端页面、导航、交互和文案；
- [ ] 完成 `PHASE1_FINAL_REVIEW.md`；
- [ ] 用户最终签字后才进入数据库/后端实施。

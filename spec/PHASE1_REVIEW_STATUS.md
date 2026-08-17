# 阶段 1 目标模型 Review 状态

> 状态日期：2026-08-17  
> 当前阶段：Active Spec 语义收口完成 + 前端产品模型优先  
> 新系统分支：`duhongx/dfetl-service/main`  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 阶段约束

阶段 1 尚未最终签字：

- 不创建/固化 Flyway `V1__baseline.sql`；
- 不修改正式 PostgreSQL 结构；
- 不使用新系统 Flyway 认领老 `df_ygt/df_etl`；
- 老系统和新系统 PostgreSQL、Quartz、Execution、Watermark、Validation、Message 完全隔离；
- 当前先完成前端产品模型，再进入数据库/Java 实施。

## 2. 已完成基础审计与模型收口

- [x] 老 Java 生产代码迁移完整性核对。
- [x] 历史 SQL 审计和分类。
- [x] 标准 Dataset、医疗字段合同、Doris ODS/RAW Review。
- [x] 可变 Task、Execution、Load Batch、Validation、Outbox、删除识别、External API、Quartz 专项 Review。
- [x] 2026-08-17 接入资源/单机构 Route 模型收口。
- [x] 2026-08-17 Task Version / Validation Policy Active Spec 语义迁移。
- [x] 运行层旧多机构 Route FK 扫描和修正。

## 3. 当前接入资源模型

```text
institution
business_catalog
source_datasource
  → institution + business_catalog

target_datasource
  └── target_datasource_fe_endpoint
```

固定规则：

- 一个部署服务一个医共体。
- Institution 为扁平集合。
- Business Catalog 只是 HIS/LIS/PACS 等轻量分类，不表示真实部署实例。
- Source 直接属于一家 Institution + 一个 Business Catalog。
- Source 支持 `HOST_PORT/JDBC_URL`，凭据与 URL 分离。
- Target Doris 为全局逻辑资源，可多个 FE，不管理 BE。
- 不存在独立 Business System Instance 及其多对多关联表。

## 4. 当前 Dataset / Route 模型

Dataset：

- 只能由管理员人工同步规范库；
- 定义变化生成不可变 `standard_dataset_version`；
- Field/Conversion Contract 保持不可变版本；
- Dataset 当前 Validation Override 直接保存在 `standard_dataset.validation_method_override`。

Route：

```text
Institution + Dataset
→ collection_route
→ collection_route_version
→ route_field_resolution
```

- Route 固定单机构。
- 一 Institution + Dataset 一条未删除 Route。
- Source 必须属于同一 Institution。
- Route Version 保存 Institution/Dataset/Dataset Version/Source/Target/Field Resolution 不可变上下文。
- Route Version 提供四元父唯一键：

```text
(id,institution_id,dataset_id,dataset_version_id)
```

- 不存在 Route/Route Version 覆盖机构关系表。

## 5. 当前 Task 模型

```text
Task identity = institution_id + dataset_id
```

- 同一 Institution + Dataset 一个未删除 Task。
- 身份创建后不可修改。
- `sync_task` 直接保存当前 `dataset_version_id/route_version_id`、读取、写入、调度、Validation Override。
- 普通编辑覆盖当前值 + `revision`，不创建 Task Version。
- 活动同步 Execution 期间禁止编辑。
- 活动独立 Validation 期间允许普通编辑。
- 历史由 Execution/Validation 启动快照解释。

明确不建立：

```text
sync_task_version
sync_task.current_version_id
task_version_id
Task Version 发布/切换/回退流程
```

## 6. 当前 Validation 模型

唯一配置存储：

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

解析顺序：

```text
Task
→ Dataset
→ Global System Setting
→ 注册默认 ROW_COUNT
→ Dataset 合同能力强制
```

运行时固定到：

```text
sync_execution.validation_method
sync_execution.validation_source
sync_execution.validation_source_revision
sync_execution.validation_contract_forced
```

明确不建立：

```text
global_validation_policy
dataset_validation_policy
task_validation_policy
override_mode
Validation enable/disable
Row tolerance
Validation lookback
Auto revalidate/fail_block
```

## 7. 当前运行模型

### Sync

- 同 Task 禁止并发 Execution。
- 重叠 Schedule Trigger 跳过、不追赶。
- Failed 不自动 Retry、不自动暂停、不推进 Watermark。
- Recollect 新建 Execution；Backfill 不改正式 Watermark。
- INITIAL_FULL 成功后 Watermark=T0；下一次正常运行才 INCREMENTAL。

### Precheck

- 只人工启动。
- 同 Route 一个活动 Precheck。
- Route 为单机构，因此 `precheck_run` 保存单 Institution 上下文。
- Issue Summary 只保存 STRUCTURE/FIELD/COMPOSITE 汇总；不重复 Institution 维度，不保存行级问题。
- Precheck 与正式同步严格分离。

### Validation / Message

- 正式同步最低严格 ROW_COUNT，不可关闭。
- 有真实业务主键可选 ROW_COUNT_CHECKSUM；无主键固定 ROW_COUNT。
- SYNC_GATE PASS 后才能 Execution SUCCEEDED、推进 Watermark、创建 Outbox。
- RabbitMQ Only，Message Policy 只存在 Dataset 级。

### Delete Snapshot

- 使用 Task + Route Version 四元身份固定上下文。
- 大规模 Key/Diff 存 Doris 技术表。
- Delete Diff 不自动应用；实际应用必须 Dry Run + 二次确认 + Audit。

## 8. 2026-08-17 Active Spec 清理完成范围

第一轮资源/Route：

```text
PRODUCT_AND_BUSINESS_DECISIONS.md
TARGET_METADATA_MODEL.md
P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md
P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
DATABASE_MIGRATION_BASELINE.md
LEGACY_FUNCTION_ALIGNMENT.md
JAVA_PRODUCTION_MIGRATION_REVIEW.md
```

第二轮 Task Version / Validation Policy：

```text
P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md
P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
EXTERNAL_API_REVIEW.md
QUARTZ_JOBSTORE_REVIEW.md
```

全 Spec 扫描发现并修正：

```text
P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md
P0_PHYSICAL_TABLE_DICTIONARY.md
P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
TASKS.md
```

扫描标准不是旧字符串为 0，而是：**任何旧名只允许以“历史/已废止/明确不建立/不得进入 V1”出现，不得作为 Active Model。**

## 9. 当前尚未完成

这些属于技术一致性核对，不是待业务重新确认：

- [ ] 最终 P0 PostgreSQL 表清单和总数。
- [ ] 全量 FK：子列 / 父 Unique / ON DELETE / 子索引矩阵。
- [ ] 全量 Business Unique / Concurrency Unique 矩阵。
- [ ] 全量状态/CHECK 枚举统一。
- [ ] 删除行为矩阵。
- [ ] Execution/Validation/Outbox Snapshot 最小充分性最终 Review。
- [ ] 前端页面、导航、交互、文案按当前模型完成 100%。
- [ ] `PHASE1_FINAL_REVIEW.md`。
- [ ] 用户最终签字后才进入数据库/后端实施。

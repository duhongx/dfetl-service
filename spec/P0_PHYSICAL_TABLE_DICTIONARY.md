# P0 物理表字典总索引

> 状态：阶段 1 物理模型一致性收口中  
> 更新：2026-08-17  
> 适用数据库：新系统独立 PostgreSQL 元数据库 + Doris 技术表  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 限制：本文是总索引和全局约定，不替代各拆分字典；阶段 1 最终签字前不得创建 `V1__baseline.sql`。

## 1. 全局约定

- PostgreSQL 业务表、Quartz 表和 Flyway history 使用新数据库 `df_etl` Schema；
- 表/列/索引统一小写 `snake_case`；
- 主键默认 `bigint identity`，跨系统运行 ID 可用 UUID；
- 时间统一 `timestamptz`；
- 状态使用受控 `varchar + CHECK`，不使用 PostgreSQL ENUM；
- 可变配置使用 `revision` 乐观锁；
- 历史运行、版本、校验、Outbox、审计不得被配置删除级联破坏；
- 敏感 Secret 不写审计摘要，数据库密码等字段使用加密密文；
- 阶段 1 只做文档模型，不修改旧 `df_ygt/df_etl` 数据库。

## 2. 当前拆分字典

| 领域 | 权威物理字典 |
| --- | --- |
| 接入资源 | `P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` |
| 标准 Dataset/字段合同 | `P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` |
| Route/Task/Watermark | `P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` + `P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` |
| 校验覆盖 | `P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` + 后续可变任务 Review 修正 |
| Execution/Batch/Precheck/Validation/Outbox | `P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` |
| 删除识别 | `P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md` |
| 外部 API | `EXTERNAL_API_REVIEW.md` |
| Quartz | `QUARTZ_JOBSTORE_REVIEW.md` |

发生冲突时按日期更晚且已确认的专项 Review 修正早期字典。

## 3. 当前资源与 Route 主链

```text
institution
business_catalog
source_datasource
target_datasource
target_datasource_fe_endpoint

standard_dataset
standard_dataset_version
standard_dataset_field

collection_route
collection_route_version
route_field_resolution

sync_task
task_watermark

sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

关键关系：

```text
source_datasource → institution + business_catalog
collection_route  → institution + standard_dataset + source_datasource + target_datasource
collection_route_version → collection_route + standard_dataset_version
sync_task → institution + standard_dataset + collection_route_version
sync_execution → sync_task + 启动快照
```

## 4. 当前明确不存在的资源/Route对象

阶段 1 最终 P0 表清单不得出现：

- 独立系统部署实例表及其机构/数据源多对多关联表；
- Route 覆盖机构关系表；
- Route version 覆盖机构关系表；
- `sync_task_version`；
- 任务级消息配置；
- 数据源组和任务组；
- 机构层级表。

## 5. 支撑对象

继续保留当前专项 Review 已确认的：

- `app_user`；
- `audit_log`；
- `system_setting`；
- 告警 channel/rule/event/delivery；
- 外部 API client/授权/nonce/request；
- Quartz 官方 JDBC JobStore 表；
- 删除识别/应用表；
- Doris 删除快照技术表。

本文不重复抄写这些字段，避免总字典和专项字典双写后再次漂移。

## 6. 外键原则

- 配置资源被 Route、Task 或历史运行引用后默认 `RESTRICT`；
- 纯配置子表可在父配置允许物理删除时 `CASCADE`；
- 历史记录需要保留可解释性时使用 `RESTRICT`、必要快照或受控 `SET NULL`；
- `source_datasource(id,institution_id)` 提供唯一支撑键，Route 使用复合 FK 保证机构一致；
- `collection_route_version(id,institution_id,dataset_id)` 提供唯一支撑键，Task 使用复合 FK 保证 Route version 不跨机构/数据集；
- 当前版本指针必须通过同父唯一约束或延迟外键保证指向自身版本。

## 7. 阶段 1 最终验收重点

- 当前表清单与 `TARGET_METADATA_MODEL.md` 一致；
- 不再存在旧系统实例中间层；
- Source 直接属于机构 + 业务目录；
- Route 为单机构模型；
- Task 为固定身份 + 当前配置模型；
- Execution/Validation 历史全部由启动快照解释；
- 所有外键父列都有唯一约束，子列有必要索引；
- 枚举、删除行为和并发唯一约束在各专项字典中无冲突；
- 用户签字后才能进入 Flyway V1。

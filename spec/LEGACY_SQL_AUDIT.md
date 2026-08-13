# 历史 SQL 处置审计

> 状态：阶段 1 审计完成，待目标模型 Review  
> 日期：2026-08-13  
> 审计范围：`server/src/main/resources/db/init.sql`、54 个 `migration_*.sql`、7 个 `rollback_*.sql`  
> 决策基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 结论与使用规则

本次逐项审计共覆盖 62 个 SQL 文件，没有遗漏。处置分类只说明其中的业务语义是否进入新系统目标模型，不代表这些文件可以被新系统执行。

| 分类 | 文件数 | 含义 |
| --- | ---: | --- |
| 保留 | 6 | 业务决策或对象仍有效，在新 `V1` 中按最终模型重新定义；不重放原脚本。 |
| 废止 | 12 | 与最终业务基线冲突，或其功能已明确移除；新系统不得继续建模。 |
| 由新模型替代 | 31 | 业务能力仍需要，但旧字段、关系、状态或约束不能沿用；以目标模型重新实现。 |
| 仅作历史参考 | 13 | 只记录老库演进、数据搬迁、临时检查或人工回滚过程；不进入新库迁移链。 |
| **合计** | **62** | `1 + 54 + 7`。 |

统一规则：

1. 62 个文件均不得改名为 Flyway 版本文件，也不得按文件名排序后重放。
2. “保留”只保留最终语义，不保留脚本内容、历史数据搬迁语句或旧默认值。
3. “由新模型替代”的脚本只能作为字段来源和反例；表名也不构成兼容承诺。
4. “废止”的表、字段和状态不得出现在新 `V1`，除非后续业务基线明确变更。
5. 7 个 `rollback_*.sql` 不是 Flyway down migration；新系统采用应用回退、兼容扩展或更高版本前向修复。
6. 老库结构快照继续只读保存于 `spec/reference/legacy`，新系统不连接或认领老库。

## 2. `init.sql` 审计

| 文件 | 处置 | 审计结论 |
| --- | --- | --- |
| `init.sql` | 仅作历史参考 | 手工单体初始化脚本包含已删除的 `validation_task`、旧任务/链路模型、Quartz 和固定初始化数据，且不能证明与 54 个增量脚本的执行顺序；不得作为新 `V1` 起点。 |

## 3. 54 个升级脚本审计

### 3.1 2026-07-31 至 2026-08-11 脚本

| # | 文件 | 处置 | 审计结论与目标去向 |
| ---: | --- | --- | --- |
| 1 | `migration_20260731_dfetl_dataset_config.sql` | 由新模型替代 | 数据集、字段、路由和任务配置混在一次迁移中；改为数据集身份/版本、链路身份/版本和任务身份/版本。 |
| 2 | `migration_20260731_medical_dirty_row.sql` | 废止 | 行级脏数据和处理状态违反“预检只汇总问题、不保存问题行、不做分流修复”。 |
| 3 | `migration_20260804_dfetl_field_standard_metadata.sql` | 由新模型替代 | 标准元数据仍需要，但必须固化在不可变的数据集版本和字段版本快照中。 |
| 4 | `migration_20260804_dfetl_rebuild_run.sql` | 废止 | 自动重建流程与“结构重建只允许人工维护窗口执行”冲突。 |
| 5 | `migration_20260804_medical_dirty_field_value_domain_audit.sql` | 废止 | 行级字段问题、严重级别和处置流程均不进入新预检模型。 |
| 6 | `migration_20260804_task_execution_medical_diversion_summary.sql` | 废止 | `excluded_rows`、`warning_rows` 等问题分流语义与 ODS 不跳过、不剔除源记录冲突。 |
| 7 | `migration_20260804_task_execution_medical_valid_source_query.sql` | 废止 | “只读取合规行”的查询快照会形成静默过滤，不允许进入执行合同。 |
| 8 | `migration_20260805_medical_standard_route.sql` | 由新模型替代 | 链路能力保留，但旧模型一条链路只属于一个机构；改为系统实例/数据源/数据集链路及覆盖机构集合。 |
| 9 | `migration_20260805_medical_standard_route_precheck.sql` | 仅作历史参考 | 这是老链路切换前的阻断检查，不是新系统迁移对象；其中单机构假设也已失效。 |
| 10 | `migration_20260805_message_publish_recovery.sql` | 由新模型替代 | 失败恢复能力保留，改用与执行成功、水位推进同事务写入的 outbox，不再从日志反推待发消息。 |
| 11 | `migration_20260805_remove_dfetl_task.sql` | 仅作历史参考 | 只记录老表清理过程；新 `V1` 从最终任务模型直接建表。 |
| 12 | `migration_20260806_data_precheck.sql` | 由新模型替代 | 预检运行能力保留；删除问题行、严重级别、修复和脏数据分流字段，采用运行状态与业务结果分离的汇总模型。 |
| 13 | `migration_20260806_data_precheck_staging.sql` | 由新模型替代 | 分阶段状态、导出和 RAW 清理元数据有参考价值，但状态、保留期和问题存储按最终合同重建。 |
| 14 | `migration_20260806_dataset_task_policy.sql` | 由新模型替代 | 数据集默认策略与任务覆盖仍需要；改为有修订号的治理策略和任务版本执行参数，Reader 并发固定为 1。 |
| 15 | `migration_20260806_remove_redundant_dataset_metadata.sql` | 废止 | 删除定义版本等字段与标准元数据可追溯要求冲突；新模型必须保留版本和合同哈希。 |
| 16 | `migration_20260806_split_dataset_policy.sql` | 由新模型替代 | 同步、校验、消息三类策略边界保留，重新定义层级覆盖、修订号和执行时有效快照。 |
| 17 | `migration_20260810_remove_source_datasource_group.sql` | 保留 | 最终模型继续不使用数据源组；新 `V1` 直接不创建 `group_id` 和 `task_group`。 |
| 18 | `migration_20260811_remove_external_api_business_scope.sql` | 保留 | 外部授权不再以旧 `business_scope` 字段表达；保留机构/API 范围的最小授权语义并按新关系解析。 |

### 3.2 无日期前缀脚本

| # | 文件 | 处置 | 审计结论与目标去向 |
| ---: | --- | --- | --- |
| 19 | `migration_add_columns_to_validation_run.sql` | 由新模型替代 | 校验计数和结果仍需要，但旧运行表同时承载 SQL、窗口和结果；改为策略、运行、分片及结果汇总。 |
| 20 | `migration_alert.sql` | 由新模型替代 | 告警能力保留；渠道、规则、事件、投递记录需统一，删除重复 webhook/channel 表达。 |
| 21 | `migration_alert_rule_channel_fields.sql` | 由新模型替代 | 规则到渠道的关系保留，采用显式外键/关联表和可校验配置，不继续叠加兼容字段。 |
| 22 | `migration_batch_task_template.sql` | 废止 | 批量任务模板入口及旧分组导航已从最终产品范围移除。 |
| 23 | `migration_copy_data_from_validation_task.sql` | 仅作历史参考 | 只用于从已废止表搬迁老数据，不能在独立新库执行。 |
| 24 | `migration_datasource_group_id.sql` | 废止 | 数据源组与任务组整体废止，不进入新模型。 |
| 25 | `migration_doris_auto_create_table_policy.sql` | 由新模型替代 | 自动建表配置改为固定 ODS/RAW 用途和只读版本合同；结构重建不自动执行。 |
| 26 | `migration_doris_type_mapping_rules.sql` | 由新模型替代 | 类型映射能力进入带版本的医疗数据转换合同，不保留可任意修改后影响历史执行的全局规则。 |
| 27 | `migration_drop_validation_task.sql` | 保留 | 废止 `validation_task` 的决定有效；新 `V1` 直接不创建该表。 |
| 28 | `migration_etl_verify_chunk_run_unique.sql` | 由新模型替代 | 校验分片唯一性保留，改为 `validation_run_segment(run_id, segment_no)` 的明确约束。 |
| 29 | `migration_etl_verify_diff_repair_source.sql` | 由新模型替代 | 差异复核和修复来源可审计能力保留；与新的校验运行及人工复检关联，不复用旧行级状态机。 |
| 30 | `migration_etl_verify_diff_run_id_index.sql` | 由新模型替代 | 按运行查询差异的索引诉求保留，索引落在新的校验结果/导出模型。 |
| 31 | `migration_external_api_production.sql` | 保留 | API 客户端、请求日志、幂等记录和限流状态仍属 P0 支撑对象；敏感值不得作为 SQL 基础数据写入。 |
| 32 | `migration_external_sync_task_api.sql` | 由新模型替代 | 外部创建任务能力需解析新任务身份、链路和任务版本，不能再写扁平 `sync_task`。 |
| 33 | `migration_external_sync_task_batch_api.sql` | 由新模型替代 | 批量请求幂等语义保留，但每项结果必须引用新任务身份/版本。 |
| 34 | `migration_id_range_validation_window.sql` | 废止 | 标准增量合同使用 `XIUGAISJ` 时间窗口；不再提供 `ID_RANGE` 作为标准增量模式。 |
| 35 | `migration_institution.sql` | 保留 | 机构主数据保留；历史回填和从任务反推机构的数据迁移不进入空库 `V1`。 |
| 36 | `migration_institution_indexes.sql` | 由新模型替代 | 按机构查询仍需要，但数据源和链路关系已变为多对多，索引必须按新外键和覆盖关系重建。 |
| 37 | `migration_message_publish.sql` | 由新模型替代 | 发布策略、事件唯一性和审计保留；消息发送改为事务 outbox，不以执行后补写日志为可靠性边界。 |
| 38 | `migration_message_publish_log_sample.sql` | 废止 | 示例运行数据不属于基础数据，且新库不得导入老执行或消息历史。 |
| 39 | `migration_message_send_record.sql` | 由新模型替代 | 投递尝试记录保留为 outbox 子记录；不允许负批次号等兼容性占位。 |
| 40 | `migration_source_datasource_realign.sql` | 仅作历史参考 | 这是对老库数据源归属的停机校正，新系统以实例/数据源/机构多对多关系直接初始化。 |
| 41 | `migration_spec048_window_checksum.sql` | 由新模型替代 | 固定校验窗口和 checksum 协议版本进入校验运行快照；不再依赖旧 `validation_task`。 |
| 42 | `migration_spec053_cron_builder.sql` | 由新模型替代 | 可视化调度配置、Cron 和时区进入不可变任务版本；调度运行态与版本定义分离。 |
| 43 | `migration_spec075_message_publish_mode.sql` | 由新模型替代 | 数据集默认、任务覆盖和禁用三态保留，按治理层级计算有效策略。 |
| 44 | `migration_sync_task_batch_size_global_fetch.sql` | 由新模型替代 | JDBC fetch size 进入任务版本；Reader 并发固定为 1，不再与可调并发混合。 |
| 45 | `migration_sync_task_phase9_16.sql` | 由新模型替代 | 执行参数中仍有效的时间窗口、Doris 模型等进入任务版本；`CUSTOM_SQL`、`ID_RANGE`、任意过滤和分流字段废止。 |
| 46 | `migration_sync_task_retry_fields.sql` | 废止 | 自动重试、退避和重试上限与“系统不自动重试”冲突；只保留人工重试/重采/补采关系。 |
| 47 | `migration_task_execution_engine_metrics.sql` | 保留 | 引擎作业 ID、读写计数、字节数和速率等观测字段进入新的执行/批次模型。 |
| 48 | `migration_task_execution_reconcile_handling.sql` | 由新模型替代 | Doris 结果不明确和人工核对仍需要；采用执行状态、探测记录与人工处置审计分表。 |
| 49 | `migration_task_execution_triggered_by_length.sql` | 仅作历史参考 | 仅修正老字段长度；新模型直接定义受控触发类型。 |
| 50 | `migration_task_group_realign.sql` | 废止 | 任务分组及历史重排逻辑已移出最终产品范围。 |
| 51 | `migration_task_validation_config_lookback.sql` | 由新模型替代 | 时间窗口回看参数保留，默认值必须是 `0` 且执行时写入有效策略快照。 |
| 52 | `migration_validation_run_sql_fields.sql` | 由新模型替代 | 执行诊断快照需要可追溯，但不把可执行 SQL 当作长期可编辑配置；敏感参数需脱敏。 |
| 53 | `migration_validation_run_trigger_type.sql` | 由新模型替代 | 同步门禁、人工校验、治理复核等触发类型进入新的校验运行状态机。 |
| 54 | `migration_validation_run_unique.sql` | 由新模型替代 | 一次执行对应的门禁校验必须数据库唯一，改用新执行 ID 与校验类型的唯一约束。 |

## 4. 7 个回滚脚本审计

| # | 文件 | 处置 | 审计结论 |
| ---: | --- | --- | --- |
| 1 | `rollback_20260806_data_precheck.sql` | 仅作历史参考 | 删除老预检对象的人工降级脚本，不是新系统可执行迁移。 |
| 2 | `rollback_20260806_data_precheck_staging.sql` | 仅作历史参考 | 恢复旧状态/字段会重新引入过时模型。 |
| 3 | `rollback_20260806_dataset_task_policy.sql` | 仅作历史参考 | 回退旧策略字段会破坏新模型分层和版本边界。 |
| 4 | `rollback_20260806_remove_redundant_dataset_metadata.sql` | 仅作历史参考 | 只对应老库字段删除过程；新数据集版本独立设计。 |
| 5 | `rollback_20260806_split_dataset_policy.sql` | 仅作历史参考 | 合并策略表与最终三类治理策略边界不一致。 |
| 6 | `rollback_20260810_remove_source_datasource_group.sql` | 仅作历史参考 | 恢复数据源组与最终产品范围冲突。 |
| 7 | `rollback_20260811_remove_external_api_business_scope.sql` | 仅作历史参考 | 恢复无约束文本范围不是新授权模型的回退方式。 |

## 5. 与老库结构快照的交叉核对

老库 `df_etl` schema-only 快照证明上述脚本不是线性、可重放的迁移链：

- `validation_task` 已不存在，但多个脚本仍以它为源或目标。
- `task_group` 和 `source_datasource.group_id` 经历创建、搬迁、再删除。
- `institution_dataset_route` 最终仍以单个 `institution_id` 建模，无法表达共享业务系统实例和多机构覆盖。
- `sync_task`、`task_execution` 聚集了定义、运行态、水位、执行快照和已废止的分流字段。
- 预检相关表仍保存行级问题、严重级别和修复状态，与最终预检合同冲突。
- 消息发布由配置、日志和发送记录拼接，缺少与执行完成事务一致的 outbox 边界。

因此，快照只用于验证“最终实际结构是什么”，不用于决定新模型应该是什么。新系统目标结构见 `spec/TARGET_METADATA_MODEL.md`。

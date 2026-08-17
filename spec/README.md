# `spec/` 索引、可信来源与清理记录

> 状态：当前有效  
> 清理日期：2026-08-17  
> 一致性对齐：2026-08-17  
> A1–A3 产品合同：2026-08-17  
> 清理前 `main`：`5268dc54aaf3abef0785d3cd336ce87271404964`  
> 可信交接提交：`341e3b5d070e5b2a242457c74d325ca7639c43d4`  
> 范围：维护可信 Spec、生成 OpenAPI 和实施状态；本次仍不修改 Java、Flyway 或生产数据库结构。

## 1. 实际读取与差异结论

本次已实际读取：

- 清理前 `main` 最新提交及 Git tree；
- `spec/` 根目录 40 个文件；
- `spec/reference/legacy/` 2 个文件；
- 可信交接提交及其 `spec/` 内容；
- 可信交接提交到清理前 `main` 的提交和文件差异。

比较结果：

```text
base = 341e3b5d070e5b2a242457c74d325ca7639c43d4
head = 5268dc54aaf3abef0785d3cd336ce87271404964
head ahead by 268 commits
head behind by 0 commits
```

`spec/` 文件层面的变化为：

- 5 份可信核心文档被改写；
- 35 份文件在交接后新增，其中根目录 34 份、历史目录 1 份；
- `LEGACY_SQL_AUDIT.md` 与老库 Schema SQL 快照保持不变；
- 没有可信交接文件被删除。

## 2. 文档可信优先级

发生冲突时按以下顺序处理：

1. 用户最新明确确认；
2. `CURRENT_CONFIRMED_PROCESS_RULES.md`；
3. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
4. `FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`；
5. `FRONTEND_API_CONTRACT_V1.md`；
6. `TARGET_METADATA_MODEL.md`；
7. `P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md`、`P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md`、`P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md`；
8. `PHASE1_TARGET_MODEL_SIGNOFF.md`；
9. `TASKS.md`；
10. 数据库治理文档；
11. Java/SQL 历史审计和 `reference/legacy` 归档材料。

`FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 冻结页面行为，`FRONTEND_API_CONTRACT_V1.md` 冻结 REST 合同，C1–C3 文档冻结对应物理方案。

`TARGET_METADATA_MODEL.md` 已在 `938566a6659fbf445e00f472ba932fe446d1d886` 完成用户签字并进入 `FROZEN`；实施授权为 `YES`，D1 OpenAPI 已生成，D2 Flyway V1 尚未创建。

## 2.1 当前阶段与实施授权

| 项目 | 当前状态 | 说明 |
| --- | --- | --- |
| 可信文档清理 | `VERIFIED` | 错误主模型和不可信 Review 文件已清理，可信优先级已恢复。 |
| 最新流程业务规则 | `CONFIRMED` | 预检问题明细、正式同步、无主键范围替换、校验和 RabbitMQ 规则已确认。 |
| A1–A3 前端产品合同 | `IMPLEMENTED` | A3 Mock 产品行为已完成并通过 ESLint/Next.js 生产构建；真实 API 和服务端尚未实施。 |
| 核心文档一致性 | `ALIGNED` | 已同步修订核心文档冲突，并建立 A1–A3 产品合同。 |
| P0 目标元数据模型 | `FROZEN` | 阶段 1 已签字；C1–C3 成为 DDL 和后端实施基线。 |
| Flyway V1 | `AUTHORIZED_NOT_CREATED` | D2 将生成物理字典和 V1，D3 在独立空库验证。 |
| Java 生产代码迁移 | `IMPLEMENTED` | 迁移和 JDK 21 编译完成；真实 PostgreSQL 启动与健康检查尚未验证。 |
| 前端产品整改 | `IMPLEMENTED` | 页面、操作、权限、确认、审计、分页和主要失败/空状态已完成；真实 API 接入进入下一阶段。 |
| 前后端业务闭环 | `IN_PROGRESS` | Web 仍有 Mock，真实 API、数据库和端到端联调尚未完成。 |

阶段 1 已完成并签字；阶段 2“OpenAPI、物理表字典、Flyway V1 与后端接口实施”为 `IN_PROGRESS`。当前实施授权为 `YES`；OpenAPI 为 `GENERATED_AND_VALIDATED`，后端和端到端仍未完成。

## 3. 当前有效：保留、回退改写或新增

| 文件 | 分类 | 本次处置 | 说明 |
| --- | --- | --- | --- |
| `spec/README.md` | 当前有效 | 新增并持续维护 | `spec/` 唯一索引、可信顺序、阶段状态和逐文件处置记录。 |
| `spec/CURRENT_CONFIRMED_PROCESS_RULES.md` | 当前有效 | 新增 | 只保留后来由用户明确确认的预检、正式同步、校验和 RabbitMQ 数据集级消息规则。 |
| `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` | 当前有效、产品合同 | 新增并持续维护 | 冻结 A1–A3 产品合同；前端 Mock 行为已实现，服务端仍待实施。 |
| `spec/FRONTEND_API_CONTRACT_V1.md` | 当前有效、API 合同 | 新增 | 冻结页面到 REST API、分页、Revision、幂等、权限、审计、错误码、导出任务和长任务状态合同。 |
| `spec/openapi/dfetl-api-v1.json` | 当前有效、生成合同 | 新增 | OpenAPI 3.1；由 Markdown 合同确定性生成，覆盖 167 个 operations。 |
| `scripts/generate_openapi_v1.py` | 生成与校验工具 | 新增 | 无第三方依赖；生成 OpenAPI并校验 Method/Path、幂等、Revision、权限扩展和合同 Hash。 |
| `.github/workflows/openapi-check.yml` | CI 合同门禁 | 新增 | 合同或生成器变化时验证 OpenAPI 未漂移。 |
| `spec/P0_PRECHECK_DETAIL_PHYSICAL_DESIGN.md` | 当前有效、C1 物理设计 | 新增 | 冻结 PostgreSQL 控制面、Doris 明细面、MinIO/S3 导出面及生命周期。 |
| `spec/P0_DORIS_INSTITUTION_SCOPE_REPLACE_DESIGN.md` | 当前有效、C2 物理设计 | 新增 | 冻结 LIST 分区、临时分区替换、备份回滚、Label 和能力探针。 |
| `spec/P0_SUPPORT_OBJECT_PHYSICAL_MODEL.md` | 当前有效、C3 物理设计 | 新增 | 冻结 RBAC、审计、设置、导出、幂等、告警、External Client 和 Quartz。 |
| `spec/PHASE1_TARGET_MODEL_SIGNOFF.md` | 当前有效、已签字 | 新增 | 记录批准语句、签字基线、授权结果、冻结边界和 D1–D10 实施顺序。 |
| `spec/PRODUCT_AND_BUSINESS_DECISIONS.md` | 当前有效 | 回退并对齐 | 恢复可信交接内容，并于 2026-08-17 对齐问题记录级预检和 `REPLACE_INSTITUTION_SCOPE`。 |
| `spec/TARGET_METADATA_MODEL.md` | 当前有效、已冻结 | 回退并对齐 | 阶段 1 签字后的 P0 PostgreSQL/Doris/后端实施基线。 |
| `spec/TASKS.md` | 当前有效 | 回退并对齐 | 阶段 1 已完成；阶段 2 D1 完成、D2–D10 待实施。 |
| `spec/DATABASE_MIGRATION_BASELINE.md` | 当前有效 | 回退改写 | 恢复可信数据库隔离、Flyway 治理和迁移规则。 |

## 4. 历史审计：保留或归档保留

| 文件 | 分类 | 本次处置 | 说明 |
| --- | --- | --- | --- |
| `spec/JAVA_PRODUCTION_MIGRATION_REVIEW.md` | 历史审计 | 回退改写 | 恢复可信交接版本，作为 2026-08-13 Java 迁移与业务缺口审计，不作为最终模型。 |
| `spec/LEGACY_SQL_AUDIT.md` | 历史审计 | 保留不变 | 可信交接后内容未变化，继续作为 62 个历史 SQL 的处置审计。 |
| `spec/reference/legacy/TASKS_before_phase1_consistency_20260814.md` | 历史审计 | 归档保留不变 | 只用于追溯 2026-08-14 阶段任务状态，不参与当前决策优先级。 |
| `spec/reference/legacy/df_ygt_df_etl_schema_20260813.sql` | 历史审计 | 归档保留不变 | 老库 Schema-only 快照；不得执行为新系统 Flyway，也未在本次提交中修改。 |

本次不把来源不可信的文件复制到 `reference/legacy`。删除记录已经由 Git 历史完整保留，继续复制只会扩大错误信息的检索噪声。

## 5. 已废止：删除

| 文件 | 分类 | 本次处置 | 原因 |
| --- | --- | --- | --- |
| `spec/FRONTEND_PHASE1_ACCEPTANCE_REVIEW.md` | 已废止 | 删除 | 以前端对齐错误主模型为验收基准，结论失效。 |
| `spec/FRONTEND_REMEDIATION_PROGRESS_20260817.md` | 已废止 | 删除 | 基于错误导航和资源模型的临时进度报告。 |
| `spec/PENDING_DECISIONS.md` | 已废止 | 删除 | 将错误技术模型标记为已冻结，并据此缩减待确认事项。 |
| `spec/PHASE1_FINAL_REVIEW.md` | 已废止 | 删除 | 未经可信签字即宣称技术模型 Review 通过。 |
| `spec/PHASE1_REMAINING_AND_IMPLEMENTATION_PLAN.md` | 已废止 | 删除 | 后续顺序和剩余事项建立在错误冻结结论上。 |
| `spec/PHASE1_REVIEW_STATUS.md` | 已废止 | 删除 | “39+11、唯一主模型、只剩前端”的阶段状态不再成立。 |

## 6. 来源不可信：删除

| 文件 | 分类 | 本次处置 | 原因 |
| --- | --- | --- | --- |
| `spec/DELETE_SNAPSHOT_MODEL_REVIEW.md` | 来源不可信 | 删除 | 依赖单机构 Route 和无任务版本当前配置模型。 |
| `spec/EXTERNAL_API_REVIEW.md` | 来源不可信 | 删除 | 外部任务 API 的身份解析建立在错误资源和任务模型上。 |
| `spec/LEGACY_FUNCTION_ALIGNMENT.md` | 来源不可信 | 删除 | 明确把业务目录、Source 直属机构和单机构 Route 写成当前主线。 |
| `spec/QUARTZ_JOBSTORE_REVIEW.md` | 来源不可信 | 删除 | 将无任务版本当前配置写成唯一调度事实。 |
| `spec/P0_DATASET_VALIDATION_OVERRIDE_REVIEW.md` | 来源不可信 | 删除 | 依赖错误的 Dataset/Task 当前配置物理模型。 |
| `spec/P0_DELETE_BEHAVIOR_MATRIX_REVIEW.md` | 来源不可信 | 删除 | 删除矩阵覆盖错误 Resource、Route 和 Task 表集合。 |
| `spec/P0_DELETE_SNAPSHOT_PHYSICAL_REVIEW.md` | 来源不可信 | 删除 | 删除快照外键和身份均依赖错误主模型。 |
| `spec/P0_DORIS_LABEL_PROBE_REVIEW.md` | 来源不可信 | 删除 | 虽含可参考技术原则，但整体被错误物理字典声明为已确认 V1 基线。 |
| `spec/P0_FOREIGN_KEY_MATRIX_REVIEW.md` | 来源不可信 | 删除 | 复合外键矩阵直接固化业务目录、单机构 Route 和无任务版本 Task。 |
| `spec/P0_GLOBAL_VALIDATION_SETTING_REVIEW.md` | 来源不可信 | 删除 | 全局校验配置被嵌入未经确认的最终物理模型。 |
| `spec/P0_INDEPENDENT_VALIDATION_CONCURRENCY_REVIEW.md` | 来源不可信 | 删除 | 并发规则绑定错误 Task 身份和 Snapshot 结构。 |
| `spec/P0_INITIAL_FULL_INCREMENTAL_EXECUTION_REVIEW.md` | 来源不可信 | 删除 | 被作为错误 Execution/Watermark 物理字典的确认依据。 |
| `spec/P0_LOAD_BATCH_MODEL_REVIEW.md` | 来源不可信 | 删除 | 批次字段和约束被未经授权地固化为 V1 目标。 |
| `spec/P0_MUTABLE_TASK_MODEL_REVIEW.md` | 来源不可信 | 删除 | 核心错误来源：取消 Task Version，改为固定身份加当前配置。 |
| `spec/P0_OUTBOX_SCOPE_MAPPING_REVIEW.md` | 来源不可信 | 删除 | Outbox 范围引用错误 Route/Target/Task 身份结构。 |
| `spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md` | 来源不可信 | 删除 | 直接把错误模型宣称为“当前唯一主模型”和技术 PASS。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY.md` | 来源不可信 | 删除 | 错误 39 张领域表和 50 张 V1 总量的总索引。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md` | 来源不可信 | 删除 | Dataset 配置字典依赖未经确认的 Validation/Task Override 模型。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md` | 来源不可信 | 删除 | Execution、Precheck、Validation、Outbox 字段被过早固化。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md` | 来源不可信 | 删除 | 直接定义业务目录、Source 直属机构和 Target 资源层。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md` | 来源不可信 | 删除 | 直接定义机构采集路由和无任务版本 Task。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md` | 来源不可信 | 删除 | Task/Watermark 字典建立在可变当前 Task 模型上。 |
| `spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md` | 来源不可信 | 删除 | Validation 层级和字段绑定错误 Task/Dataset 当前配置模型。 |
| `spec/P0_SNAPSHOT_MINIMUM_SUFFICIENCY_REVIEW.md` | 来源不可信 | 删除 | Snapshot 最小集合引用错误的可变 Source/Target 和 Task 模型。 |
| `spec/P0_STATUS_ENUM_CHECK_MATRIX_REVIEW.md` | 来源不可信 | 删除 | 状态/CHECK 矩阵覆盖错误的最终表集合和字段组合。 |
| `spec/P0_SUPPORT_OBJECT_REVIEW.md` | 来源不可信 | 删除 | 支撑对象被错误阶段结论包装为已冻结模型的一部分。 |
| `spec/P0_TASK_OPERATION_EXCLUSION_REVIEW.md` | 来源不可信 | 删除 | 互斥规则被绑定到错误的当前 Task 物理实现。 |
| `spec/P0_UNIQUE_CONSTRAINT_MATRIX_REVIEW.md` | 来源不可信 | 删除 | 唯一约束直接固化单机构 Route 和无版本 Task 身份。 |

## 7. 错误当前模型的清理结果

以下后续规划稿引入的表达不再是当前产品模型或物理基线：

```text
Institution + Business Catalog
→ Source / Target
→ Single-Institution Route
→ Sync Task Fixed Identity + Current Config
```

对应清理包括：

- 删除把业务目录作为核心资源层的现行定义；
- 删除把源端连接直接固定归属一家机构和一个业务目录的现行定义；
- 删除把目标端连接作为该四层产品模型组成部分的现行定义；
- 删除一家机构一个数据集一条采集 Route 的现行定义；
- 删除取消 Task Version、只保留当前配置的现行定义；
- 删除所有据此生成的表字典、FK、Unique、Status、Delete、Snapshot 和“技术 Review 已通过”证明。

清理后回到可信交接提交继续 Review。可信交接中的业务系统实例、多机构覆盖采集链路、任务版本等仍是待复核基线，不在本次文档清理中擅自宣布最终通过。

## 8. 保留的明确业务结论

后来由用户明确确认、且不依赖错误资源模型的结论已集中写入：

```text
spec/CURRENT_CONFIRMED_PROCESS_RULES.md
```

主要包括：

- 预检仅人工启动、同链路互斥、重新预检从头执行；
- 预检必须能定位问题记录及具体非法字段；汇总长期保留，问题明细和原始数据限期保留；
- 正式同步重新读取真实源对象，不使用预检暂存结果；无主键任务统一使用 `REPLACE_INSTITUTION_SCOPE` 业务语义，禁止整表 `TRUNCATE`/`DROP_DATA`；
- 正式同步与同步后校验、水位提交的成功边界；
- RabbitMQ 是 P0 唯一消息通道；
- 消息只在数据集级配置，不允许任务级覆盖；
- 三个允许数据集及全量/增量固定 `ALL`；
- 空值继续按既有消费合同处理；
- Outbox 投递失败不回滚同步成功。

## 9. 本次修改边界

本次清理和文档维护：

- 未修改任何 `.java`；
- 未修改任何 `.ts` 或 `.tsx`；
- 未修改任何 `.sql`；
- 未修改 Flyway；
- 未执行历史批量清理脚本；
- 未把文档确认解释为数据库、后端或前端代码已经实施。

## 10. 2026-08-17 核心文档一致性对齐

本次只修改 `spec/` 文档，消除三处冲突：

1. **预检问题明细**：从“只保存汇总”统一为“长期汇总 + 限期问题明细”，要求定位具体记录、非法字段和原因；物理载体、保留期及大数据量导出继续 Review。
2. **无主键写入语义**：统一为 `FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY`；旧 `TRUNCATE` 仅为历史术语，禁止解释为清空共享 ODS 全表。
3. **阶段状态**：阶段 1 为 `IN_PROGRESS`，目标模型未冻结，Flyway V1 和数据库/后端批量实施仍未授权。

本次未修改 Java、TypeScript、SQL、Flyway、Doris 表或 PostgreSQL 结构，也没有替预检明细选择具体物理存储方案。

## 11. 2026-08-17 A1–A3 前端产品合同

已新增：

```text
spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md
```

该文档确认：

1. **A1 数据预检问题明细页面合同**：以采集链路为列表顶层，以预检运行保存历史事实；提供运行概览、问题汇总、问题记录明细、脱敏、按权限查看原值、汇总/明细导出和明细到期状态。
2. **A2 无主键机构范围替换前端语义**：普通用户统一看到“每次全量 · 替换当前机构范围”，界面不得出现 TRUNCATE、DROP_DATA 或清空共享整表的表达。
3. **A3 页面—操作—权限—审计矩阵**：确认目标信息架构、`domain.action` 权限命名、确认等级、审计通则，以及全部主要页面的操作、权限和审计事件。

当前状态：产品合同和前端 Mock 行为 `IMPLEMENTED`，API 与物理模型 `FROZEN_FOR_IMPLEMENTATION`，OpenAPI `GENERATED_AND_VALIDATED`。

B1–B5 已全部完成；真实接口切换进入阶段 2 的 D9。

## 12. 2026-08-17 A3 交互与 API 合同完成

本次完成 A3 剩余逐页交互，并新增 `FRONTEND_API_CONTRACT_V1.md`。前端产品行为已经稳定，可以进入 Mock Service → 真实 API 的逐领域替换阶段。

状态边界：

```text
前端产品交互：IMPLEMENTED
REST API 合同：FROZEN_FOR_IMPLEMENTATION
Java 后端接口：NOT_IMPLEMENTED
服务端鉴权与审计：NOT_IMPLEMENTED
PostgreSQL / Doris / RabbitMQ：NOT_IMPLEMENTED
Flyway V1：AUTHORIZED_NOT_CREATED
```


## 13. 2026-08-17 C1–C3 物理设计与阶段 1 签字

```text
C1 Precheck Detail Physical Design: FROZEN_FOR_IMPLEMENTATION
C2 Doris Institution Scope Replace Design: FROZEN_FOR_IMPLEMENTATION
C3 P0 Support Object Physical Model: FROZEN_FOR_IMPLEMENTATION
Target Model: FROZEN
Implementation Authorization: YES
Signoff Baseline: 938566a6659fbf445e00f472ba932fe446d1d886
```

签字范围和变更治理见 `PHASE1_TARGET_MODEL_SIGNOFF.md`。

## 14. 2026-08-17 D1 OpenAPI 3.1 完成

已生成：

```text
spec/openapi/dfetl-api-v1.json
scripts/generate_openapi_v1.py
.github/workflows/openapi-check.yml
```

验证结果：

```text
OpenAPI Version: 3.1.0
Operation Count: 167
Markdown Method/Path Coverage: EXACT MATCH
Idempotency-Key on Commands: PASS
If-Match on PATCH/PUT: PASS
Contract SHA-256 Binding: PASS
```

下一工作包为 D2：物理表字典和 Flyway V1。

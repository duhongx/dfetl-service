# Java 生产代码迁移与业务 Review

> Review 日期：2026-08-13
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`
> 来源：`duhongx/datax-lite-jdk21@master`（tree `175a15ff6d7f1f3b258a0422420ea672610933a4`）
> 目标：`duhongx/dfetl-service@main`（tree `d36588f677a19797bd42609942437e846f9884ce`）

## 1. 结论

Java 生产代码迁移已经完整完成：逐一比对两个分支的 `server/src/main/java/**/*.java`，共 485 个文件，缺失 0、额外 0、内容差异 0。

迁移完整不等于业务闭环完成。按最终业务基线 Review 后，当前服务端仍有任务身份和唯一性、共享采集链路模型、预检数据模型、ODS/RAW 固定存储合同、载入批次检查点及 Checksum 计算方式等 P0 缺口，暂不具备生产验收条件。

本轮已直接修正不需要新增业务判断的确定性问题：

1. 可空数值/日期字段只要实际值非空，也执行格式和目标容量检查。
2. 标准 DDL 生成器按字段定义保留非主键 `NOT NULL`。
3. 所有预检终态的 `raw_` 原始数据默认保留 1 天，清理覆盖 `PASSED`、`HAS_ERRORS`、`FAILED`、`CANCELLED`。
4. `TASKS.md` 中已废止的强制预检门禁、行级明细和问题等级描述已按最终业务基线校正。

## 2. P0 发现

| ID | 领域 | 发现 | 主要证据 | 需要达到的结果 |
|---|---|---|---|---|
| REV-P0-001 | 任务创建 | 数据集和链路身份仍在 `dataCharacteristics` JSON 中；唯一性靠事务内先查询后创建，数据库没有“医共体 + 机构 + 数据集 + 未删除”唯一约束，并发创建存在竞态。 | `SyncTask`、`SyncTaskApplicationService` | 将医共体、机构、数据集、链路、业务系统实例提升为关系字段；增加逻辑删除字段和数据库唯一约束；捕获唯一约束冲突并返回已有任务。 |
| REV-P0-002 | 任务生命周期 | 删除任务会物理删除任务、执行、分片、校验和快照记录，与“只允许逻辑删除并保留全部历史”冲突。执行类配置也可原地更新，没有任务版本模型。 | `SyncTaskService.delete` | 运行中禁止删除；其他状态只逻辑删除；执行、校验、快照、审计永久关联原任务版本；读取/写入/水位/映射变化生成新版本。 |
| REV-P0-003 | 采集链路 | 当前 `InstitutionDatasetRoute` 一条记录只属于一个机构，源数据源也要求属于同一机构；缺少业务系统实例、覆盖机构集合和统一的只读标准字段解析快照。共享源对象会被迫按机构复制链路。 | `InstitutionDatasetRoute`、`InstitutionDatasetRouteService`、`InstitutionDatasetRouteResolver` | 建立业务系统实例—机构、业务系统实例—源数据源多对多；一条链路保存覆盖机构集合和字段解析合同版本；任务仍只归属一个机构并按标准机构代码过滤。 |
| REV-P0-004 | 创建/运行门禁 | 路由必须 `validationStatus=PASSED` 才能启用或解析，预检启动前又先执行结构断言；结构问题无法作为预检第一阶段的结果被记录，并形成了事实上的硬门禁。 | `InstitutionDatasetRouteService`、`InstitutionDatasetRouteResolver`、`DfetlDataPrecheckService.assertActualStructure` | 结构检查进入预检运行第一阶段；结构或预检问题只展示风险，不阻断创建和运行；真正执行遇到连接、读取、转换或写入错误时明确失败。 |
| REV-P0-005 | 数据预检 | 运行状态把执行结果混在同一字段，仍保存 WARNING/BLOCKER、业务主键、原始值、规范化值和行级问题，并提供行级查询/导出接口。 | `DfetlPrecheckRun`、`DfetlPrecheckIssue`、`DorisPrecheckTableSpec`、`DorisPrecheckRuleCompiler`、`DorisPrecheckQueryService`、`DfetlDataPrecheckController` | 执行状态与结果状态分离；移除问题等级和行级问题表/API；直接生成字段级、组合规则级汇总，并保存影响行数和实际偏差。 |
| REV-P0-006 | `raw_` 隔离 | `raw_` 表只有 `run_id`，没有业务基线明确要求的 `route_id`；现实现依赖运行 ID 间接关联路由。 | `DorisPrecheckTableSpec` | `raw_` 行显式保存 `run_id + route_id`；全量扫描使用联合业务主键游标；按运行批次精确清理。 |
| REV-P0-007 | ODS/RAW 固定合同 | 代码仍把严格/宽松作为正式目标表建模分支，未形成固定的表用途及合同版本。正式源查询还会用 `IS NOT NULL` 和长度谓词过滤 ODS 不合规记录，转换也会把非法日期/数值变为 `NULL`，可能静默丢行或改值。 | `MedicalSourceSelectBuilder.buildWriteSafeSelect`、`MedicalDirtyExecutionService`、`MedicalDorisDdlBuilder`、`DorisPrecheckTableSpec` | `ods_` 固定使用医共体字段合同且异常批次失败；`raw_` 固定使用全部业务字段可空字符串并保留原始值；删除所有层级的模式配置和切换；只读记录表用途及合同版本，禁止两类表相互转换。 |
| REV-P0-008 | 批次恢复 | `TaskChunk` 定义了检查点和 `dorisLabel`，但生产执行路径没有创建或更新 `TaskChunk`；也未形成“响应不明确先查 Label/事务状态”的恢复闭环。 | `TaskChunk`、`TaskChunkRepository` 的生产引用仅用于查询、告警和删除 | 第一阶段单 Reader、单在途批次；每个载入批次持久化固定上界、最后主键、确定性 Label、事务状态和提交时间；恢复前查询 Doris 最终状态。 |
| REV-P0-009 | Checksum | 当前 Checksum 在同步结束后重新查询源端和目标端，不是对读取/转换过程中实际载荷计算；没有按载入批次保存协议版本和源端 Checksum。 | `ChecksumService.doVerify` | Reader/转换阶段对实际业务载荷计算 Checksum；排除 `_etl_*`；按执行/批次保存协议版本；目标端按同一批次标识核对，全部批次通过后才推进水位。 |
| REV-P0-010 | 成功收尾 | 水位提交后异步触发校验，并立即准备/发布业务消息；异步校验异常被吞掉。消息可能在整次校验成功前发布，且水位、校验和消息事件没有可恢复的一致性边界。 | `ExecutionSuccessFinalizationService`、`AutoValidationTrigger` | 阻断校验同步完成且成功后推进水位；业务消息只在整次同步和校验成功、水位推进完成后生成唯一事件；通过事务/outbox 保证崩溃可恢复，发送失败不回滚同步成功。 |
| REV-P0-011 | 工程与数据库 | Maven Wrapper 和 JDK 21 编译基线已补齐；带新系统独立 PostgreSQL 元数据库的启动/健康检查尚未验证。旧库结构证明历史脚本已被选择性执行，`init.sql` 和散落的 migration/rollback SQL 不是可重放的线性历史。 | `pom.xml`、`.mvn/wrapper`、`server/pom.xml`、`server/src/main/resources/db`、老库 schema-only 快照 | 老项目和原数据库保持不变；新项目独立建库并使用 Flyway，从按最终模型生成的干净 V1 初始化；后续验证空库安装及新系统相邻版本升级。 |
| REV-P0-012 | 无主键全量任务 | 标准策略已正确将无主键数据集生成为 `FULL_ONLY + TRUNCATE + DUPLICATE_KEY`，但执行配置把 `TRUNCATE` 转为作用于整张物理表的 `data_save_mode=DROP_DATA`；共享目标表守卫只能拒绝运行，无法实现多个机构共用 `ods_` 时的当前机构范围清理。任务配置预览还无条件返回 `UNIQUE_KEY + UPSERT + XIUGAISJ`。 | `DfetlPolicyService.initializeMissing`、`DatasetTaskSnapshotAssembler`、`SeaTunnelConfBuilder`、`SharedTargetTableGuard`、`MedicalRegistryController.previewConfig` | 保留原有三种标准任务组合；无主键任务按标准机构代码只清理并重载当前机构范围，禁止整表 `DROP_DATA`，不生成假主键；预览、DDL、任务快照和执行配置必须展示并使用同一组合。 |
| REV-P0-013 | 默认校验与成功收尾 | 当前全局和数据集默认均为自动校验关闭、`ROW_COUNT`、不阻断、回看 2 小时、30 秒自动复检；任务实体默认值又不完全一致。水位 Gate 固定只执行 `ROW_COUNT`，仅在需要提交水位的窗口执行；随后异步校验异常会被吞掉，因此 `FULL_ONLY` 任务及手工配置为 `ROW_COUNT_CHECKSUM` 的核心数据不能按最终生效策略阻断成功收尾。 | `ValidationPolicy.defaults`、`DfetlValidationPolicy`、`TaskValidationConfig`、`ValidationGateService`、`AutoValidationTrigger`、`ExecutionSuccessFinalizationService`、`DfetlExecutorService` | 正式同步默认自动执行零容差 `ROW_COUNT`，核心数据允许手工覆盖为 `ROW_COUNT_CHECKSUM`；固定 `lookbackHours=0`，取消默认自动复检；所有任务类型都必须在最终生效校验通过后才能确认成功和推进水位，消息随后发布；不得静默降级校验方法。 |
| REV-P0-014 | 无主键内容校验 | 任务级 `ValidationRiskPolicy` 已能拒绝缺少比对键的内容 Checksum，但数据集策略保存不检查数据集主键；多个解析与触发组件把技术分片字段 `splitPk` 也视为 Checksum 键。无主键数据集继承上级 `ROW_COUNT_CHECKSUM` 时可能直到触发阶段才失败，而异步触发异常还会被吞掉。 | `DfetlPolicyService.updateValidationPolicy`、`DatasetTaskSnapshotAssembler`、`ValidationRiskPolicy`、`EffectiveValidationMethodResolver`、`ValidationDispatchService`、`AutoValidationTrigger`、`ChecksumService.resolvePkCols` | 只有合同真实业务主键才能开放 `CHECKSUM`/`ROW_COUNT_CHECKSUM`；无主键固定 `ROW_COUNT`。前端禁用并说明原因，数据集和任务策略保存时一致拒绝；`splitPk`、机构代码和生成字段不得作为业务比对键；未来整体内容 Checksum 必须采用独立版本化协议。 |
| REV-P0-015 | CUSTOM_SQL | 通用任务路径允许创建和执行 `CUSTOM_SQL`，仅通过关键字扫描判断只读；元数据探测连接的 `setReadOnly(true)` 不等于 SeaTunnel 正式执行连接的数据库强制只读。外层机构/窗口过滤依赖 SQL 输出字段，且当前明确不支持 Checksum、`splitPk` 和 ID 范围增量，无法满足标准任务合同。 | `CustomSqlValidator`、`CustomSqlQueryBuilder`、`SourceDataSourceController`、`SourceDataSourceService`、`SyncTaskService.normalizeSourceMode`、`SeaTunnelConfBuilder`、`ChecksumService` | 当前没有历史 `CUSTOM_SQL` 任务，标准业务路径直接移除该能力；任务只绑定表、视图或物化视图，复杂逻辑由源系统固化为视图。未来自由查询同步必须作为独立能力重新设计，不保留半支持入口。 |
| REV-P0-016 | 字段解析 | 当前路由校验按大小写不敏感方式匹配字段，但把额外字段作为警告放行；Reader、预检和 Checksum 各自维护解析逻辑，部分实现用 `putIfAbsent` 保留首列，原始过滤 SQL也未统一解析为 JDBC 真实字段名。遗留 `field_mappings` 又暗示支持实际上被核心校验链路拒绝的字段重命名。 | `InstitutionDatasetRouteValidationService`、`DatasetTaskSnapshotAssembler`、`MedicalSourceSelectBuilder`、`MedicalPrecheckService`、`WhereClauseBuilder`、`ValidationSourceFilterBuilder`、`TargetFieldResolver` | 建立唯一的标准字段解析组件：只允许大小写差异，字段集合完全相等且每个字段唯一命中；Reader、机构过滤、增量窗口、预检和 Checksum 共用只读解析快照，源端使用 JDBC 真实名称，Doris 固定小写；删除字段重命名配置入口。 |

## 3. 分领域 Review

### 3.1 数据集管理

已符合：数据集由医共体注册表同步，没有手工新增入口；当前快照保存合同 Hash、首次入库时间和最近同步时间；字段标准版本可以随字段保存。

尚未符合：

- `DfetlDataset` 没有明确的数据集定义版本字段和版本历史；注册表变化会原地覆盖当前字段。
- 缺少“变化差异—人工维护—调整源视图—重新解析字段—重新预检—新任务版本”的持久化工作流。
- `DfetlDatasetConfigService.list` 一次读取全部数据集、字段、策略和链路后在内存聚合，没有服务端分页；列表还聚合链路校验状态，不符合列表只聚焦数据集本身的要求。

### 3.2 同步任务创建与执行

已符合：标准路由创建路径会固化合同 Hash 和路由修订；标准任务 Reader 并发被设为 1；水位窗口采用半开区间的实现基础已经存在；阻断校验异常按 fail-closed 处理，不推进水位。

尚未符合：除 P0 表中的身份、唯一性、逻辑删除、版本、共享链路、批次检查点外，代码仍保留任务级自动重试字段和执行逻辑，与“失败立即终止、只允许人工重试”冲突。通用路径还保留标准产品不再支持的 `CUSTOM_SQL` 创建、预览和执行能力。重试、重新采集、数据补采和取消尚未共享一套持久化批次/Label 状态机。

### 3.3 数据预检

已符合：预检由接口人工发起；正式同步不复用 `raw_`；重新预检创建新运行；数据库约束限制同链路同时只有一个活动运行；每个数据集共用一张字符串可空 `raw_` 表；本轮已把所有终态原始数据的默认保留期统一为 1 天。

尚未符合：结构检查、状态模型、汇总模型和 API 仍按旧的行级问题方案实现，需整体改造成最终基线中的“结构第一阶段 + 全量扫描 + 字段/组合规则汇总”。本轮修复了现有规则编译器对可空字段非空非法值的漏检，但该编译器后续仍应改为直接生成聚合结果，不再落行级问题。

### 3.4 数据校验

已符合：配置为 Checksum 时不会静默降级为行数校验；任务级配置在完全缺少 `splitPk/upsertKeys` 时会明确拒绝；校验失败的同步门控按 fail-closed 处理。

尚未符合：默认策略与已确认基线相反，当前自动校验和失败阻断默认关闭、回看 2 小时并默认 30 秒复检；Gate 固定使用 `ROW_COUNT` 且只覆盖需要提交水位的任务，不能让核心数据的 `ROW_COUNT_CHECKSUM` 或 `FULL_ONLY` 任务按最终策略阻断收尾。内容 Checksum 的能力判断还接受 `splitPk` 而不是只认合同业务主键，数据集级策略也可保存无主键内容 Checksum。Checksum 数据来源、批次绑定和规范化协议也不满足最终基线；当前实现重查源视图，无法证明校验的是实际写入载荷。全量、修改、删除三类校验仍需按独立语义和历史完成端到端验证。

## 4. 建议实施顺序

1. 先建立 JDK 21/Maven Wrapper 和受控数据库迁移，保证后续结构改造可编译、可升级、可回退。
2. 一次完成业务系统实例、共享链路、统一字段解析、任务关系身份、逻辑删除、唯一约束和任务版本模型。
3. 建立固定的 ODS/RAW 表用途与字段合同，删除模式配置并移除正式同步的静默过滤，再补批次检查点、确定性 Label 和恢复状态机。
4. 重构预检为结构第一阶段、全量游标扫描和纯汇总模型，删除行级问题存储/API。
5. 把 Checksum 移入实际载荷管线，最后重做水位、校验、消息事件的一致性收尾。

## 5. 验证状态

- 迁移完整性：通过，485/485 Java 生产文件内容一致。
- 静态 Review：完成，范围覆盖数据集管理、同步任务创建与执行、数据预检、数据校验。
- 编译与测试：已增加 Maven Wrapper 3.3.4（Maven 3.9.16），使用 Eclipse Temurin 21.0.12 执行 `./mvnw -DskipTests package` 成功；485 个生产源文件完成编译并生成可执行 JAR，构建过程未执行测试。带真实 PostgreSQL 元数据库的 Spring Boot 启动和 `/actuator/health` 验证仍待完成。
- 老库结构：已核对 PostgreSQL 16.14 的 `df_etl` schema-only 快照，包含 55 张表、43 个序列、116 个索引、122 个约束；无数据、角色和授权。老库已不存在 `validation_task`、`dfetl_task`、`task_group`，证明旧 `init.sql` 不能作为当前或新系统基线。快照只作历史证据，新系统使用独立数据库重新建立 Flyway V1。

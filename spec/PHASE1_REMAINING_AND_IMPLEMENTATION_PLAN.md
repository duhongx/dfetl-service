# 阶段 1 剩余 Review 与后续实施规划

> 状态：执行中  
> 日期：2026-08-14  
> 当前基线：`main@b75a251b72cb85ea5619b748f4262157aafe9153`  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 目标逻辑模型：`spec/TARGET_METADATA_MODEL.md`  
> 当前物理字典：
> - `spec/P0_PHYSICAL_TABLE_DICTIONARY.md`
> - `spec/P0_PHYSICAL_TABLE_DICTIONARY_RESOURCES.md`
> - `spec/P0_PHYSICAL_TABLE_DICTIONARY_DATASETS.md`

## 1. 执行原则

1. 当前仍处于阶段 1：目标模型冻结与物理表字典复核。
2. 按业务基线、目标模型、老代码真实查询路径和历史 SQL 审计共同核对，不复制老表和旧状态机。
3. 已经确认的业务规则不重新讨论；能由文档和代码直接判断的字段、索引和外键直接完成。
4. 只有文档未定义、代码无法判断或存在明确冲突时才提出确认问题。
5. 每次只讨论一个问题；确认后立即回写对应文档和 `spec/TASKS.md`，并提交到 `main`。
6. 阶段 1 最终签字前，不创建 Flyway `V1__baseline.sql`，不修改实体、Repository 或数据库结构。
7. 老系统继续连接原 `df_ygt/df_etl` 数据库；新系统只使用完全独立的新 PostgreSQL 数据库。

## 2. 当前已完成

阶段 1 已完成：

- 62 个历史 SQL 文件审计与分类；
- 核心目标逻辑模型 Review；
- 本地账号、审计、系统设置和最小告警模型 Review；
- 外部任务 API 完整 Review；
- Quartz JDBC JobStore 可重建投影 Review；
- 删除识别主键快照与差异分层存储 Review；
- 第一批 P0 支撑对象物理字典；
- 机构、业务系统实例、源数据源和目标 Doris 物理字典；
- 标准数据集、不可变版本、字段合同及数据集策略物理字典。

## 3. 阶段 1 剩余四个工作包

以下四个工作包按顺序执行，每次只讨论一个真实问题。

### 3.1 工作包一：采集链路、任务、治理覆盖和水位物理字典

目标文档：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_ROUTES_TASKS.md
```

目标对象：

```text
collection_route
collection_route_institution
collection_route_version
collection_route_version_institution
route_field_resolution

sync_task
sync_task_version
task_governance_override
task_watermark

预检风险展示的全局默认
预检风险展示的数据集覆盖
预检风险展示的任务覆盖
```

复核重点：

- 未删除采集链路的业务唯一约束；
- 链路覆盖机构必须属于业务系统实例覆盖范围；
- 链路身份、当前可变覆盖集合和不可变版本快照的关系；
- 链路版本 Hash、版本号及同父当前版本约束；
- 标准字段到 JDBC 实际字段名的结构化只读解析快照；
- 未删除任务按“机构 + 数据集”唯一；
- 任务机构、数据集与采集链路的一致性约束；
- 任务身份、不可变任务版本、调度开关及当前水位的边界；
- 三种标准任务组合的受控枚举和数据库约束；
- 预检和校验治理策略的继承/覆盖；
- 消息策略不进入任务级覆盖；
- 每个任务一条当前正式水位；不建立水位历史表。

需要统一的内部枚举至少包括：

```text
FULL_ONLY
FULL_THEN_INCREMENTAL

REPLACE_INSTITUTION_SCOPE
UPSERT

DUPLICATE_KEY
UNIQUE_KEY
```

产品文案可以继续使用“全量清理重载”“首次全量后增量”等中文表达，但数据库、Java 和 API 内部必须使用唯一受控枚举。

完成标准：

- 每张表均有字段、空值、默认值、CHECK、外键、删除行为、唯一约束和查询索引；
- 链路覆盖变化与已有任务的关系明确；
- 当前版本指针均能由数据库保证指向同一父对象的版本；
- 不再依赖旧 `institution_dataset_route`、扁平 `sync_task` 或任务 JSON 作为唯一查询依据。

### 3.2 工作包二：执行、批次、预检、校验和 Outbox 物理字典

目标文档：

```text
spec/P0_PHYSICAL_TABLE_DICTIONARY_EXECUTION.md
```

目标对象：

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

并与以下已设计对象闭环：

```text
delete_snapshot_run
task_delete_snapshot_state
delete_apply_run
```

复核重点：

- 执行操作类型、触发来源、固定任务版本和固定执行窗口；
- 同任务一个活动执行的部分唯一索引；
- 执行状态迁移，失败和取消不推进水位；
- 载入批次真实游标范围、时间范围、机构范围、确定性 Doris Label 和探测结果；
- `load_batch` 只记录当前执行进度，不作为跨执行恢复检查点；
- 同链路一个活动预检；预检执行状态和结果状态分离；
- 预检只保存字段级和组合规则级汇总；
- 校验技术状态与数据一致性结果分离；
- `validation_run.difference_summary JSONB` 只保存小型汇总；
- 每次执行最多一条 `message_outbox`；
- Outbox 的 `PENDING/PUBLISHING/PUBLISHED/DEAD_LETTER` 状态、`available_at`、抢占和恢复索引；
- 不建立执行检查点、校验分段、行级差异、消息明细或投递尝试明细表；
- 日志中心继续关联应用日志与 SeaTunnel 日志，不把完整日志行持续写入 PostgreSQL。

完成标准：

- 同步成功、阻断校验、水位推进和 Outbox 创建的原子边界可直接转为实现；
- 重复请求和多实例执行最终由数据库约束收敛；
- 所有运行历史不因配置对象停用或逻辑删除被级联破坏。

### 3.3 工作包三：全表外键、索引、状态和文档一致性检查

目标文档：

```text
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
```

检查内容：

1. 形成唯一的 P0 PostgreSQL 表、Quartz 表和 Doris 技术表清单；逐项标记“保留、替代、废止、不需要数据库表”。
2. 建立完整外键矩阵，明确 `RESTRICT/SET NULL/CASCADE`，并核对所有外键子列索引。
3. 建立业务唯一性和并发约束矩阵，至少覆盖：
   - 未删除任务唯一；
   - 未删除链路唯一；
   - 同任务一个活动执行；
   - 同链路一个活动预检；
   - 同任务一个活动删除快照；
   - 数据集/链路/任务当前版本同父约束；
   - 每执行一条 Outbox；
   - 每告警事件每渠道一条投递；
   - 外部 client + requestId 幂等；
   - 外部 client + nonce 防重放。
4. 建立唯一状态与枚举矩阵，统一数据库、Java、API 和文档名称。
5. 对照以下文件执行一致性检查：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TASKS.md
spec/TARGET_METADATA_MODEL.md
spec/LEGACY_SQL_AUDIT.md
spec/LEGACY_FUNCTION_ALIGNMENT.md
spec/JAVA_PRODUCTION_MIGRATION_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY*.md
```

重点排除：

- Redis Stream；
- 批量任务模板；
- 机构数据视图；
- 机构层级；
- 数据源组和任务组；
- CUSTOM_SQL 标准任务；
- 自动重试和从失败批次继续；
- 行级预检/校验差异；
- 自动修复和自动修改 Doris；
- 任务级消息配置；
- 异步导出任务表；
- 外部 API 应用层限流表；
- PostgreSQL 一行一个业务键快照。

完成标准：

- 没有同一字段、状态或业务事实在多个对象中重复表达；
- 没有老功能遗漏，也没有已废止对象残留；
- 每个 P0 表均可由物理字典直接生成 DDL。

### 3.4 工作包四：阶段 1 最终 Review 与签字

目标文档：

```text
spec/PHASE1_FINAL_REVIEW.md
```

内容：

- P0 PostgreSQL 表清单及总数；
- Quartz 官方表清单；
- Doris ODS/RAW 和共享技术表边界；
- 已废止旧表与旧状态清单；
- 版本边界、外键、删除行为、唯一约束和并发约束摘要；
- 敏感字段、密钥和部署 Secret 边界；
- 数据库约束与应用层事务约束的责任划分；
- 阶段 2 输入条件；
- 最终一致性检查结果和剩余风险。

同步更新：

```text
spec/PRODUCT_AND_BUSINESS_DECISIONS.md
spec/TARGET_METADATA_MODEL.md
spec/TASKS.md
spec/PHASE1_REVIEW_STATUS.md
```

只有用户明确确认以下结论后，阶段 1 才完成：

```text
目标元数据模型 Review 通过，允许进入阶段 2。
```

## 4. 阶段 2：Flyway V1 与真实启动

阶段 1 签字后执行：

1. 引入 Flyway PostgreSQL，迁移目录固定为 `server/src/main/resources/db/migration`。
2. 固定 `baseline-on-migrate=false`，生产禁止 `clean`，Schema 使用 `df_etl`。
3. 将历史 SQL 移入非 Flyway 扫描目录；不修改已经在任何环境执行过的 Flyway SQL。
4. 依据最终物理字典生成干净的 `V1__baseline.sql`，不复制老 `init.sql` 或老库 Schema。
5. V1 创建全部 P0 表、Quartz 官方表、约束和索引；不写固定管理员密码和真实连接凭据。
6. 同时维护迁移前检查、迁移后验证和失败后的前向修复说明。
7. 首个管理员通过部署 Secret、环境变量或一次性初始化命令创建。
8. 在完全独立的空 PostgreSQL 执行 `migrate/validate`、Spring Boot 启动、健康检查、Quartz 启动和重启验证。
9. 核实老 `df_ygt/df_etl` 数据库未被连接、修改或写入 `flyway_schema_history`。

## 5. 阶段 3：核心领域模型代码整改

按依赖顺序：

1. 机构、业务系统实例、源/目标数据源及关系；
2. 数据集身份、版本、字段和转换合同导入；
3. 采集链路、链路版本、覆盖机构和字段解析快照；
4. 任务身份、任务版本、治理覆盖和水位；
5. 替换旧 Repository 查询和 JSON 主查询路径；
6. 所有调用方切换后删除废止实体、Controller、Service 和页面。

每批同步维护实体、Repository、Service、API DTO、查询、前端类型和文档，并保持 JDK 21 编译通过。

## 6. 阶段 4：Doris 合同、预检和任务创建

顺序：

1. 统一字段转换与规范化组件；
2. ODS/RAW DDL 生成器；
3. Doris 实际元数据读取和合同比较；
4. `_dfetl_key_snapshot/_dfetl_delete_diff` 技术表支持；
5. 标准字段到 JDBC 实际字段解析；
6. 预检状态机、RAW 隔离和 1 天清理；
7. 字段级与组合规则级汇总；
8. 任务创建领域服务和第一个不可变任务版本；
9. 任务创建向导；
10. 执行前运行上下文检查。

## 7. 阶段 5：同步执行与校验闭环

顺序：

1. `sync_execution` 状态机；
2. 单 Reader 游标读取；
3. `load_batch` 与确定性 Doris Label；
4. 全量、首次全量补充增量和日常增量；
5. 机构范围安全清理；
6. `ROW_COUNT` 和 Checksum；
7. 执行成功、水位推进和 Outbox 原子收尾；
8. 取消、重新采集和数据补采；
9. 删除快照、删除差异、dry-run 和人工应用；
10. Quartz 调度投影、启动对账和 misfire 跳过。

## 8. 阶段 6：外部 API 与前端真实集成

外部 API：

- HMAC 独立安全链；
- client 管理与 `ALL/SELECTED` 机构授权；
- 批量 `targets` 和原子目标；
- `BEST_EFFORT/ALL_OR_NOTHING`；
- 所有写操作幂等；
- 旧单机构请求适配；
- 独立 OpenAPI 分组。

前端：

- 移除业务 Mock；
- 接入真实 API；
- 建立稳定 URL；
- 完成机构、实例、数据源、数据集、链路、任务、预检、校验和监控页面；
- 完成账号管理、系统设置和使用文档入口；
- 所有按钮具备加载、成功、失败、确认和真实状态刷新。

## 9. 阶段 7：消息、运维、安全与测试

优先级：

1. RabbitMQ Outbox 发布器；
2. 重试、死信和人工重发；
3. 成功/失败业务审计全覆盖；
4. 日志中心和任务监控；
5. 接口、集成和端到端测试；
6. 前端模块拆分；
7. 告警渠道、规则、事件和投递历史。

告警优先级最低，不阻塞同步主流程。

## 10. 阶段 8：老系统最终替换

最后执行：

- 配置迁移清单和转换程序；
- 脱敏克隆库演练；
- 链路、任务、策略和水位核对；
- 暂停旧调度并冻结配置；
- 迁移必要配置；
- 启动新系统并防止新旧重复同步和重复消息；
- 切流与回退手册。

默认不迁移：

```text
旧执行历史
旧 Quartz 运行状态
旧预检行级明细
旧校验行级差异
旧逐条消息发送明细
旧 PostgreSQL 每键快照
已废止的配置和功能
```

## 11. 当前立即执行顺序

```text
1. 采集链路、任务、治理覆盖、水位物理字典
2. 执行、批次、预检、校验、Outbox 物理字典
3. 全表外键、索引、状态和文档一致性检查
4. 阶段 1 最终 Review 与签字
```

讨论规则：一次只处理一个真实问题；确认后立即回写并提交，然后进入下一问题。
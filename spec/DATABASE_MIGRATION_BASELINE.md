# DFETL 数据库迁移基线与 SQL 维护规范

> 状态：已确认  
> 首次确认：2026-08-13  
> 最近更新：2026-08-17  
> 适用范围：`dfetl-service` PostgreSQL 元数据库  
> 业务优先级：与本文冲突时，以 `PRODUCT_AND_BUSINESS_DECISIONS.md` 和更晚已确认专项 Review 为准。

## 1. 部署与隔离边界

1. 老项目继续连接原 `df_ygt` 数据库的 `df_etl` Schema，直到正式切换。
2. 新项目使用独立、全新的 PostgreSQL 数据库；新系统不连接老库执行调度、同步、预检、校验、水位推进或消息发布。
3. 老数据库不创建 `flyway_schema_history`，不使用 Flyway `baseline` 接管。
4. 新项目最终从干净的 `V1__baseline.sql` 开始；后续只通过新的不可变 Flyway 版本升级。
5. 正式切换只迁移经过明确确认的配置和水位，不整体恢复老元数据库。

## 2. 历史结构只读参考

老系统结构快照：

```text
spec/reference/legacy/df_ygt_df_etl_schema_20260813.sql
```

该文件只用于：

- 核对历史代码依赖；
- 设计老配置到新模型的转换；
- 证明哪些老对象被保留、替代或废止；
- 切换前进行字段和引用映射检查。

不得把旧结构快照直接执行到新数据库，也不得复制后改名为 V1。

## 3. 为什么不能重放历史 SQL

仓库旧 `init.sql`、`migration_*.sql` 和 `rollback_*.sql` 是历史演进过程，不是一条可重复、可验证的线性 Flyway 链。

新 V1 必须从**最终业务模型**重新生成，而不是把旧脚本重新排序执行。

当前 V1 的资源/Route 主线必须是：

```text
institution
+ business_catalog
+ source_datasource（直接属于 institution + business_catalog）
+ target_datasource
+ standard_dataset/version/field
+ collection_route（单机构）
+ collection_route_version
+ route_field_resolution
+ sync_task（固定机构+Dataset身份，当前配置）
+ task_watermark
+ execution/precheck/validation/outbox 等运行对象
```

不得从旧文档恢复已经废止的资源中间层、Route 多机构覆盖或 Task version。

## 4. Flyway 目录和命名

正式运行时只扫描：

```text
server/src/main/resources/db/migration/
  V1__baseline.sql
  V2__add_xxx.sql
  V3__adjust_xxx.sql
```

这里的 V2/V3 只说明命名格式，不代表已经分配的真实版本。

规则：

- `V{递增版本}__{snake_case说明}.sql`；
- 已执行版本不可修改、重命名、调序或删除；
- 修正已发布结构只能新增更高版本；
- `R__*.sql` 只用于真正可安全重复创建的派生对象，不用于有状态业务表；
- 旧脚本必须放在 Flyway 扫描目录之外；
- 每个迁移围绕一个明确、可验证的结构目标。

## 5. V1 基线内容

`V1__baseline.sql` 只在阶段 1 最终签字、前端产品模型和 API 合同稳定后生成。

必须包含：

- `df_etl` Schema；
- 当前 P0 PostgreSQL 表；
- 主键、外键、唯一约束、CHECK、必要索引；
- Quartz JDBC JobStore 官方表；
- 不含秘密的必要基础配置；
- 关键业务约束注释。

V1 不得包含：

- 老系统运行数据、执行历史、Quartz 状态或消息历史；
- 已废止的老任务/校验/批量模板/分组对象；
- 机构树；
- 独立真实部署系统实例及其机构/数据源多对多关系；
- Route 覆盖多机构关系表；
- `sync_task_version` 或任何 `task_version_id`；
- 数据源组和任务组；
- 标准任务 `CUSTOM_SQL`；
- Redis Stream P0 配置；
- 任务级消息配置；
- PostgreSQL Doris 物理表登记/结构版本表；
- `execution_checkpoint`、跨执行恢复检查点；
- `task_watermark_history`；
- 校验分段/行级差异/自动修复等当前不需要对象；
- 固定管理员密码、数据库真实密码、JWT/AES 密钥或 RabbitMQ 部署 Secret；
- 只为兼容老库存在的临时字段。

## 6. 当前关键数据库关系

阶段 1 最终 DDL 至少直接保证：

```text
source_datasource(id,institution_id) UNIQUE
collection_route(source_datasource_id,institution_id)
  FK → source_datasource(id,institution_id)

collection_route active UNIQUE(institution_id,dataset_id)

collection_route_version(id,institution_id,dataset_id) UNIQUE
sync_task(route_version_id,institution_id,dataset_id)
  FK → collection_route_version(id,institution_id,dataset_id)

sync_task active UNIQUE(institution_id,dataset_id)
```

并继续保证：

- 同 Task 一个活动同步 Execution；
- 同 Route 一个活动 Precheck；
- 同 Task 一个活动独立 Validation；
- 同 Execution 最多一条 Message Outbox；
- 外部 API 幂等和 nonce 防重放。

## 7. 每次 SQL 变更维护要求

涉及实体、Repository、唯一性、状态机或配置的变更，必须同一开发批次完成：

1. 新增 Flyway 版本 SQL；
2. 更新 Entity/DTO/Repository/Service；
3. 更新 API contract 和前端类型；
4. 记录前置条件、数据转换、锁和停机风险；
5. 提供迁移后核对 SQL；
6. 说明失败后的应用回退或前向修复；
7. 更新 `TASKS.md` 和相关 spec；
8. 验证空库迁移；
9. 验证上一正式版本升级；
10. 验证 Maven 构建、Spring Boot 启动和 Flyway validate。

禁止：

- 依赖 JPA `ddl-auto` 改生产库；
- 手工执行未入库 SQL；
- 修改已经执行过的 Flyway 文件；
- 用 `IF NOT EXISTS` 掩盖结构不一致；
- 把真实凭据写入 SQL；
- Flyway validate 失败仍启动业务服务。

## 8. 破坏性结构调整

删除字段/表、改类型、收紧非空、替换唯一键等采用：

```text
扩展
→ 数据迁移与核对
→ 应用切换
→ 后续版本收缩
```

Flyway 不依赖自动 down migration。紧急回退优先回退应用并保留兼容结构；无法回退的变更必须提前给出备份、恢复和前向修复方案。

## 9. 环境验证和正式切换

- 开发/测试：新系统独立数据库，可反复从空库验证全部迁移；
- 升级演练：从上一正式版本备份恢复到隔离环境后升级；
- 老系统：只允许只读导出结构或经批准导出待迁移配置；
- 正式切换：停止老系统调度，冻结迁移配置，执行已演练转换，核对后启动新系统，避免双调度和重复写入。

正式迁移配置、水位范围和切换策略在上线专题中单独确认。

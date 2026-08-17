# 老系统功能与新系统目标范围对齐

> 状态：阶段 1 功能边界对齐基线  
> 首次对齐：2026-08-14  
> 最近收口：2026-08-17  
> 老系统代码基线：`duhongx/datax-lite-jdk21@175a15ff6d7f1f3b258a0422420ea672610933a4`  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`

## 1. 对齐规则

本文件只回答“老功能在新产品中保留、重构还是删除”，不再反向定义目标数据模型。

判断顺序：

1. 用户最新确认；
2. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
3. 更晚且已确认的专项 Review；
4. 老系统当前可达代码；
5. 历史 SQL/孤立实体/旧文档。

功能保留不等于旧表名、字段、状态机、Controller 或实现类原样迁移。

## 2. 当前新系统主线

```text
接入资源
  ├─ 机构
  ├─ 业务目录
  ├─ Source datasource（直接属于机构 + 业务目录）
  ├─ Target Doris
  └─ 标准 Dataset
        ↓
单机构 collection_route
        ↓
固定机构+Dataset身份的 sync_task（当前配置）
        ↓
execution / load batch / validation / watermark / RabbitMQ outbox
```

不再建设独立真实部署系统实例、多机构共享 Route 或 Task version。

## 3. 分领域功能对齐

### 3.1 登录、账号、权限和审计

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| 登录、Token、刷新、退出 | 重构保留 | 保留登录态和会话撤销。 |
| 修改密码 | 保留 | 当前账号修改密码。 |
| 单字符串角色/RBAC | 删除 | P0 为少量同权限管理员。 |
| 管理员账号列表/新增/启停/重置密码 | 保留 | 前端入口待产品确认。 |
| 操作审计 | 重构保留 | 统一 `audit_log`，业务写操作成功/失败均审计。 |

### 3.2 首页、监控、日志和帮助

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| 首页态势 | 保留 | 按新资源/运行模型展示。 |
| Task/Execution/Batch 监控 | 重构保留 | 读取当前 Task 和运行快照。 |
| 日志中心 | 保留 | 与操作审计分离。 |
| 内置使用文档 `/docs` | 保留 | 入口位置待确认。 |

### 3.3 机构、业务目录和数据源

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| 机构 CRUD/启停/引用检查 | 重构保留 | 机构为扁平集合。 |
| Source datasource 直接归属机构 | **保留并强化** | 新模型明确 `source_datasource.institution_id`。 |
| Source 业务分类 | 重构为业务目录 | HIS/LIS/PACS 是轻量 `business_catalog`，Source 必须选择一个。 |
| Source/Target CRUD、测试、启停 | 重构保留 | Source 支持 HOST_PORT/JDBC_URL；Target 管理逻辑 Doris + FE。 |
| 数据源组、任务组 | 删除 | 不进入新系统。 |
| 机构数据视图 | 删除 | 不恢复旧页面。 |

当前明确不增加独立系统实例资源对象，也不建设机构/数据源多对多实例关系。

### 3.4 标准 Dataset、字段合同和 Doris

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| 从规范库同步标准 Dataset | 重构保留 | 管理员人工同步，不手工新增。 |
| Dataset 字段/同步/校验/消息配置 | 重构保留 | 按当前 Dataset/version/override 模型。 |
| 医疗字段类型解析/Doris DDL | 重构保留 | 统一字段合同服务 DDL/Reader/Precheck/Checksum。 |
| 通用 JDBC→Doris 类型建议 | 隔离保留 | 不覆盖医疗标准合同。 |
| Doris 人工建表/重建/结构查看 | 重构保留 | 普通同步不自动改表。 |
| 自动扫描并 ALTER 列类型 | 删除 | 不允许自动改生产合同。 |

### 3.5 机构采集 Route

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| `InstitutionDatasetRoute` 单机构采集配置 | **重构保留** | 新模型仍是单机构 Route，不再认为这是缺陷。 |
| Dataset + Source + Schema/Object + Target 映射 | 重构保留 | 形成 `collection_route + collection_route_version`。 |
| 字段大小写解析 | 重构保留 | 形成只读 `route_field_resolution`，字段集合严格相等。 |
| Route 结构核对 | 重构保留 | 独立 `structure_status`，与启停分离。 |
| Route 启停 | 保留 | 结构通过不自动启用。 |
| Route 运行状态/最近同步 | 删除 | 属于 Task/Execution。 |
| 字段重命名/自由转换表达式 | 删除 | 标准任务只允许大小写差异。 |

共享 View 中若包含多机构数据，由各机构 Route 按自身机构编码过滤；不建立一个 Route 的覆盖机构集合。

### 3.6 Task、调度、Execution、Batch、Watermark

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| Task 创建/查询/编辑/启停调度/删除 | 重构保留 | 固定身份 `institution + dataset`，逻辑删除。 |
| Task 当前配置 | 重构保留 | 直接保存在 `sync_task`，不建立 Task version。 |
| Execution/Batch 详情和日志 | 重构保留 | 运行历史保存启动快照。 |
| 同 Task 禁止并发、计划冲突跳过 | 保留 | 不排队追赶。 |
| 自动重试/从失败批次继续 | 删除 | 人工重新采集，新 Execution 从范围起点开始。 |
| 跨执行 checkpoint/reconciliation | 删除 | Doris Label 事实留在 Batch。 |
| Watermark history | 删除 | 当前水位一表，历史从 Execution 追溯。 |
| 标准 Task `CUSTOM_SQL` | 删除 | 复杂逻辑由 Source View/Table 固化。 |
| PolicyRecommend 自动替用户选策略 | 删除 | 三种标准任务由 Dataset 合同确定。 |

### 3.7 Precheck

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| 人工启动、进度、结果 | 重构保留 | 同 Route 最多一个活动 Precheck。 |
| RAW 临时数据 | 重构保留 | 仅服务 Precheck，不作为正式同步来源。 |
| 字段/组合规则检查 | 保留 | 用于发现源端质量问题。 |
| 自动分流/只同步合规行 | 删除 | 正式同步不静默过滤或修复。 |
| 异步导出任务/长期文件 | 删除 | 按最终产品实现处理。 |

### 3.8 Validation、删除识别

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| ROW_COUNT/Checksum/人工重检 | 重构保留 | 正式同步最低严格 ROW_COUNT。 |
| 校验关闭/容差/静默降级 | 删除 | 不允许。 |
| 自动修复 | 删除 | 不自动修改业务数据。 |
| 删除识别和人工应用 | 重构保留 | 按删除专项 Review，识别不等于自动删除。 |

### 3.9 Message

| 老系统能力 | 新系统处置 | 说明 |
| --- | --- | --- |
| RabbitMQ 业务消息 | 保留 | P0 唯一消息通道。 |
| Redis Stream | 删除 | 不进入 P0。 |
| Task 级消息配置 | 删除 | 只存在 Dataset 级。 |
| 重发 | 重构保留 | 按 Execution/Outbox 范围读取 Doris。 |

### 3.10 External API / Quartz / Alert

- External Task API 保留，原子业务目标仍为“机构 + Dataset”；
- Quartz JDBC JobStore 保留为 Task 当前调度配置的可重建投影；
- 告警 channel/rule/event/delivery 保留最小闭环；
- 这些支撑能力不得重新引入已经废止的 Resource/Route 中间层。

## 4. 当前迁移判定

老代码中如果出现以下情况：

- 单机构 Source；
- 单机构 Route；
- Route 直接关联 Source；

这些事实**不再因为旧 8 月 14 日共享实例模型而被判为错误**。是否可复用要按当前 Resource/Route 字段、版本、约束和前端职责重新评估。

真正需要删除/重写的是：

- 旧 Task version/JSON 主查询路径；
- 自动重试、自动修复、标准 CUSTOM_SQL；
- Redis Stream；
- Task 级消息配置；
- 旧批量模板/分组等废止能力。

## 5. 验收

后续 Java/API/前端 Review 必须能用以下主线解释：

```text
机构
→ 机构所属 Source（带业务目录）
→ 单机构 Route
→ 固定机构+Dataset Task
→ Execution 启动快照
```

如果后续实现再次要求额外系统实例资源或 Route 覆盖机构集合，应视为旧模型回流并阻止合入。

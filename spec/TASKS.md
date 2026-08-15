# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：阶段 1「目标模型冻结与物理表字典复核」进行中  
> 最近更新：2026-08-15  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 明确修正文档：各项已确认的 `P0_*_REVIEW.md`

## 0. 文档定位与阶段门槛

发生冲突时按以下顺序处理：

1. `PRODUCT_AND_BUSINESS_DECISIONS.md` 中尚未被后续明确修正的规则；
2. 用户确认后的专项 Review；
3. P0 物理表字典；
4. 本任务清单；
5. 老代码、历史 SQL 和归档文档。

当前阶段只允许修改文档：

- 不创建或固化 `V1__baseline.sql`；
- 不修改实体、Repository 或正式数据库结构；
- 不移动旧 SQL 到 Flyway 扫描目录；
- 不连接、修改、认领或升级老 `df_ygt/df_etl` 数据库；
- 用户明确签字后才进入阶段 2。

---

## 1. 当前确认的核心业务基线

### 1.1 部署和资源

- 一个部署和一套独立 PostgreSQL 元数据库只服务一个医共体，不建立租户表。
- 机构按扁平集合管理。
- 业务系统实例与机构、源数据源均为多对多。
- 源数据源支持 `HOST_PORT/JDBC_URL`，凭据与 URL 分离。
- Doris 表示一个逻辑部署，支持一个或多个 FE，不管理 BE。

### 1.2 数据集和链路

- 标准数据集只允许管理员人工同步，不自动同步、不手工新增。
- 只有规范化定义变化才生成新的 `standard_dataset_version`。
- 标准字段与源字段只允许大小写差异，不支持人工重命名和 `CUSTOM_SQL`。
- 一条采集链路可以覆盖多家机构。
- 当前覆盖机构只来自：

```text
collection_route.current_version_id
→ collection_route_version_institution
```

- 链路覆盖移除机构时，若仍有当前任务使用该链路和机构，则拒绝移除。
- 链路问题可以保存和展示，正式执行遇到真实不可执行合同则失败。

### 1.3 同步任务：固定身份、当前配置直接覆盖

- 一个任务只属于一家机构和一个标准数据集。
- 同一机构、同一数据集只能存在一个未删除任务。
- `institution_id/dataset_id` 是稳定业务身份，任务创建后不可修改。
- 更换机构或数据集时，必须确认没有活动执行，逻辑删除旧任务，再创建新任务。
- 旧任务的水位、执行、批次、校验、Outbox 和审计历史继续归属旧任务，不迁移到新任务。
- `sync_task` 直接保存当前有效执行配置。
- 编辑普通配置直接覆盖原任务，不建立 `sync_task_version`。
- 删除 `sync_task.current_version_id` 及全部 `task_version_id` 引用。
- 用户可以在无活动执行时修改当前采集链路、读取参数和调度配置。
- 存在 `PENDING/RUNNING/LOADING/VALIDATING` 执行时，禁止修改任务配置。
- 编辑被拒绝时返回 `TASK_EXECUTION_ACTIVE`，并展示执行 ID、状态和处理建议。
- 尝试修改机构或数据集时返回 `TASK_IDENTITY_IMMUTABLE`。
- 执行启动与任务编辑必须串行化，不建立待生效配置或双配置状态。
- 系统不替用户判断换链路后是否应重置水位、重新全量或处理删除快照基线。
- 历史执行通过 `sync_execution` 启动快照追溯；任务修改历史通过 `audit_log` 追溯。
- 任务暂停只控制自动调度，暂停后仍可人工运行；暂停和取消当前执行是独立操作。

### 1.4 任务级校验覆盖

- 不建立独立 `task_validation_policy`。
- `sync_task.validation_method_override` 保存任务级覆盖：

```text
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

- `NULL` 表示继承数据集级覆盖或全局默认。
- 不保存 `override_mode`、独立策略 revision、容差、校验回看或校验关闭开关。
- 任务覆盖与其他任务配置共用 `sync_task.revision` 和操作审计。
- 活动执行期间禁止修改。
- 无真实业务主键的数据集不能覆盖为 `ROW_COUNT_CHECKSUM`。

专项 Review：

```text
spec/P0_MUTABLE_TASK_MODEL_REVIEW.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_TASKS_WATERMARK.md
spec/P0_PHYSICAL_TABLE_DICTIONARY_VALIDATION_POLICY.md
```

### 1.5 三种标准任务组合

| 数据集合同 | 任务类型 | 写入方式 | Doris Key 模型 |
| --- | --- | --- | --- |
| 无真实业务主键 | `FULL_ONLY` | `REPLACE_INSTITUTION_SCOPE` | `DUPLICATE_KEY` |
| 有业务主键且有增量字段 | `FULL_THEN_INCREMENTAL` | `UPSERT` | `UNIQUE_KEY` |
| 有业务主键但无增量字段 | `FULL_ONLY` | `UPSERT` | `UNIQUE_KEY` |

- 无主键任务不生成假主键。
- 多机构共享 ODS 时，无主键任务只清理当前机构范围。
- Reader 第一阶段固定单并发，Fetch Size 可配置。

### 1.6 执行、首次全量和水位

- `sync_execution` 表示任务的一次真实运行，并保存启动时任务配置快照。
- 已经开始的执行不被后续任务修改热更新。
- 同一任务禁止并发执行。
- 运行期间到达的新计划触发直接跳过，不追赶、不补跑。
- 第一次运行创建独立 `INITIAL_FULL` 执行。
- 首次全量成功后以开始时间 `T0` 建立初始水位。
- 后续等待下一次正常调度，再创建独立 `INCREMENTAL` 执行。
- 不在首次全量执行内立即追加增量。
- 失败不自动重试、不自动暂停任务、不推进水位。
- 重新采集创建新执行，从范围起点和第 1 批重新读取。
- 数据补采不修改正式水位。
- `task_watermark` 只保存当前值，不保存任务版本或历史表。

### 1.7 `load_batch` 和 Doris Label

- 批次类型从父 `sync_execution.execution_scope` 推导，不保存 `phase`。
- 整次时间或主键范围保存在父执行，不保存 `time_lower/time_upper`。
- 批次只保存实际 Keyset 复合游标，不作为跨执行恢复点。
- 删除 `probe_result`。
- DFETL 批次状态：

```text
PENDING/LOADING/PROBING/SUCCEEDED/FAILED/CANCELLED
```

- Doris 原始状态：

```text
UNKNOWN/PREPARE/COMMITTED/VISIBLE/ABORTED
```

- 只有 `VISIBLE` 且拒绝行数为 0，批次才能成功。
- `Publish Timeout`、客户端超时或响应不明确时只探测原 Label，不自动重投。
- `UNKNOWN` 超时、持续不可查询或长期未达到 `VISIBLE` 时，批次和执行失败。
- 失败信息必须包含批次号、Label、最后状态、探测次数、失败原因和建议动作。

### 1.8 预检和校验

- 预检只能人工启动，每次扫描整条链路。
- 预检只保存字段级和组合规则级汇总，不保存行级详情和样例。
- 预检事实不阻止任务创建和运行。
- 不建立预检策略层级。
- 每次正式同步至少执行严格相等的 `ROW_COUNT`，不能关闭。
- 不支持行数容差和校验 `lookback_hours`。
- 有真实业务主键时可配置 `ROW_COUNT_CHECKSUM`，不允许静默降级。
- 校验失败或不一致时执行失败且水位不推进。
- 人工重新校验生成独立 `validation_run`，不覆盖历史记录和原执行结果。

### 1.9 消息、外部 API 和支撑对象

- 消息只使用 RabbitMQ，配置只存在于数据集级。
- 每次成功执行最多创建一条小型 `message_outbox`。
- Outbox 不保存业务 payload、分页进度和逐条消息。
- 外部任务 API 属于 P0，支持批量 targets 和旧单机构请求。
- 所有外部写操作按 `(client_id, request_id)` 幂等。
- client 支持 `ALL/SELECTED` 机构授权，不物理删除，不做应用层限流。
- 本地账号为简单同权限管理员，不建设 RBAC。
- 告警保留最小事件和投递历史，实施优先级最低。
- Quartz JDBC JobStore 是可重建投影，任务当前配置是唯一调度事实。

---

## 2. 阶段 1 剩余工作

### 工作包 1：采集链路、任务、校验覆盖和水位

状态：**物理字段已完成，正在做一致性收口。**

已完成对象：

```text
collection_route
collection_route_version
collection_route_version_institution
route_field_resolution
sync_task
task_watermark
```

已确认：

- [x] 删除独立 `collection_route_institution`。
- [x] 删除预检策略层级。
- [x] 任务可直接更换链路。
- [x] 任务修改覆盖原任务，不建立 `sync_task_version`。
- [x] 删除全部 `task_version_id`。
- [x] 活动执行期间禁止修改任务配置。
- [x] 删除独立 `task_validation_policy`。
- [x] 任务级校验覆盖合并为 `sync_task.validation_method_override`。
- [x] `institution_id/dataset_id` 创建后不可修改。
- [x] 更换机构或数据集时逻辑删除旧任务并创建新任务。
- [x] 删除 `task_watermark.task_version_id`。
- [x] 校验不可关闭，无容差、无校验回看。

下一项待确认：

- [ ] 是否删除独立 `dataset_validation_policy`，把数据集级校验覆盖合并为 `standard_dataset.validation_method_override`。

待机械清理：

- [ ] 从旧文档删除 `sync_task_version/current_version_id/task_version_id/task_validation_policy`。
- [ ] 清理任务身份可修改及任务历史迁移描述。
- [ ] 核对所有复合外键的父唯一约束。
- [ ] 统一任务和写入枚举名称。

### 工作包 2：执行、批次、预检、校验和 Outbox

状态：**物理字段第一轮完成，继续一致性收口。**

已完成对象：

```text
sync_execution
load_batch
precheck_run
precheck_issue_summary
validation_run
message_outbox
```

已确认：

- [x] 已接受运行请求的技术前检失败保留 `FAILED sync_execution`。
- [x] 首次全量和后续增量为独立执行。
- [x] 删除批次 `phase/time_lower/time_upper/probe_result`。
- [x] Label 不明确只探测原事务，超时失败，不自动重投。
- [x] Label 失败信息必须清晰。
- [x] 日志全文不进入 PostgreSQL。

待一致性检查：

- [ ] 从执行、校验、Outbox 删除 `task_version_id`，改用执行快照。
- [ ] 核对唯一 `SYNC_GATE validation_run` 与执行快照一致性。
- [ ] 核对 Outbox、执行、数据集和机构身份一致性。
- [ ] 与删除快照控制对象完成外键闭环。

### 工作包 3：全表外键、索引、状态和文档一致性

状态：**正在进行。**

主文档：

```text
spec/P0_PHYSICAL_MODEL_CONSISTENCY_REVIEW.md
```

检查清单：

- [ ] 形成唯一 P0 PostgreSQL 表清单。
- [ ] 标记 Quartz 标准表、Doris 技术表和不需要数据库表的能力。
- [ ] 形成完整外键和删除行为矩阵。
- [ ] 检查所有父外键列的反向索引。
- [ ] 检查全部业务唯一约束和并发部分唯一索引。
- [ ] 统一任务、执行、批次、预检、校验、Outbox、告警、外部 API 和删除快照枚举。
- [ ] 清理目标模型和物理字典中的旧表名、旧状态和废止字段。
- [ ] 核对业务基线、历史 SQL 审计和旧 Java 查询路径。

### 工作包 4：阶段 1 最终 Review 与签字

计划新增：

```text
spec/PHASE1_FINAL_REVIEW.md
```

完成条件：

- [ ] P0 PostgreSQL 表清单和数量明确。
- [ ] Doris 业务表和技术表清单明确。
- [ ] 已废止旧表和功能清单明确。
- [ ] 外键、删除行为、唯一性和并发约束无冲突。
- [ ] 敏感字段、密钥和审计边界明确。
- [ ] 应用层约束与数据库约束边界明确。
- [ ] 所有文档一致，没有未决业务问题。
- [ ] 用户明确确认“目标元数据模型 Review 通过，允许进入阶段 2”。

---

## 3. 后续实施里程碑

## M0：工程、数据库与启动基线

### [P0][DB-001] Flyway 和新空库

阶段 1 签字后执行：

- [ ] 引入 Flyway PostgreSQL。
- [ ] 扫描目录固定为 `server/src/main/resources/db/migration`。
- [ ] `baseline-on-migrate=false`，生产禁止 `clean`。
- [ ] 生成干净的 `V1__baseline.sql`。
- [ ] V1 包含全部 P0 表、约束、索引和 Quartz 标准表。
- [ ] 空库执行 `migrate/validate` 并完成 Spring 启动、关闭和重启验证。

### [P0][CFG-001] 运行配置

- [ ] 明确 PostgreSQL、Doris、RabbitMQ、SeaTunnel 依赖和敏感环境变量。
- [ ] 区分开发、测试和生产配置。
- [ ] 首个管理员通过部署 Secret、环境变量或一次性命令初始化。

## M1：核心领域模型

- [ ] 实现机构、业务系统实例、源/目标数据源和多对多关系。
- [ ] 实现数据集同步、不可变数据集版本和字段合同。
- [ ] 实现采集链路、链路版本和字段解析。
- [ ] 实现固定机构/数据集身份、当前配置可变的 `sync_task`、任务级校验覆盖字段和水位。
- [ ] 任务编辑接口不允许修改 `institution_id/dataset_id`；更换身份采用逻辑删除后新建。
- [ ] 任务编辑和执行启动使用同一任务锁，活动执行期间返回 `TASK_EXECUTION_ACTIVE`。
- [ ] 删除废止的任务版本和任务校验策略实体、Repository、DTO 与接口。

## M2：Doris 合同、预检和任务创建

- [ ] 统一 ODS/RAW DDL 生成器。
- [ ] 实现 Doris 实际结构核对。
- [ ] 实现预检状态机和汇总导出。
- [ ] 实现任务创建和无活动执行时的普通配置编辑。
- [ ] 实现执行前上下文快照和技术前检。

## M3：执行、校验、删除识别和调度

- [ ] 实现执行状态机和启动快照。
- [ ] 实现 Keyset 分页、`load_batch`、确定性 Label 和探测。
- [ ] 实现首次全量、正常增量、重新采集和数据补采。
- [ ] 实现严格 `ROW_COUNT/ROW_COUNT_CHECKSUM`。
- [ ] 实现水位原子提交和唯一 Outbox。
- [ ] 实现 Doris 删除快照技术表和人工应用流程。
- [ ] 实现 Quartz 投影重建和并发保护。

## M4：外部 API 和前端真实集成

- [ ] 实现 HMAC、client 管理、机构授权和所有写操作幂等。
- [ ] 移除生产页面 Mock 状态。
- [ ] 建立稳定 URL、统一前端 API 层和真实分页。
- [ ] 接入机构、数据源、数据集、链路、任务、预检、校验和监控页面。
- [ ] 任务编辑被活动执行阻止时，展示执行 ID、状态和处理建议。
- [ ] 任务编辑页面把机构和数据集作为只读身份信息。

## M5：消息、安全和运维

- [ ] 实现 RabbitMQ Outbox 扫描、重试、死信和人工重发。
- [ ] 实现简单管理员账号和成功/失败审计。
- [ ] 实现日志中心和任务监控。
- [ ] 最后实现告警渠道、规则、事件和投递历史。

## M6：主流程稳定后补测试

- [ ] 数据集、链路、任务唯一性和任务普通配置直接编辑。
- [ ] 任务创建后不能修改 `institution_id/dataset_id`。
- [ ] 更换机构或数据集必须逻辑删除旧任务并创建新任务，旧历史不迁移。
- [ ] 活动执行期间全部任务配置均不可修改。
- [ ] `validation_method_override=NULL` 正确继承数据集和全局默认。
- [ ] 无主键任务拒绝 `ROW_COUNT_CHECKSUM` 覆盖。
- [ ] 编辑与执行启动并发时不存在配置穿透。
- [ ] 执行快照不受后续任务修改影响。
- [ ] 首次全量与后续增量独立运行。
- [ ] Label `PREPARE/COMMITTED/VISIBLE/ABORTED/UNKNOWN` 全路径。
- [ ] 校验严格相等、不允许关闭、不允许容差。
- [ ] 水位、Outbox 和成功收尾幂等。
- [ ] 外部 API 批量、幂等和授权。
- [ ] Quartz 投影重建。

---

## 4. 已确认决策索引

| 编号 | 主题 |
| --- | --- |
| D-001 | 单医共体部署和独立新 PostgreSQL |
| D-002 | 机构扁平管理，业务系统实例多对多 |
| D-003 | 数据集定义人工同步和不可变数据集版本 |
| D-004 | 链路覆盖机构版本化 |
| D-005 | 字段只允许大小写解析，不支持重命名和 CUSTOM_SQL |
| D-006 | 任务按机构 + 数据集唯一 |
| D-007 | 三种标准任务组合 |
| D-008 | Reader 第一阶段固定单并发 |
| D-009 | 预检只保存和展示事实，不作门禁 |
| D-010 | 正式同步校验不可关闭，至少 ROW_COUNT |
| D-011 | 行数严格相等，无容差 |
| D-012 | 删除正式校验 lookback_hours |
| D-013 | 首次全量和后续增量为独立执行 |
| D-014 | 失败不自动重试、不自动暂停任务 |
| D-015 | 重新采集和数据补采为独立命令 |
| D-016 | 删除识别使用 Doris 技术表保存大规模键 |
| D-017 | 消息只使用 RabbitMQ，配置只在数据集级 |
| D-018 | 每执行最多一条小型 Outbox |
| D-019 | 外部任务 API 属于 P0，不做应用层限流 |
| D-020 | 简单管理员账号，不建设 RBAC |
| D-021 | 最小告警历史，优先级最低 |
| D-022 | Quartz JobStore 是可重建投影 |
| D-023 | 删除 `load_batch.phase` |
| D-024 | 删除 `load_batch.time_lower/time_upper` |
| D-025 | 删除 `load_batch.probe_result` |
| D-026 | Label 不明确只探测原事务，UNKNOWN 超时后失败 |
| D-027 | Label 失败信息必须清晰、可排查 |
| D-028 | 任务修改直接覆盖 `sync_task`，删除 `sync_task_version` |
| D-029 | 执行、校验、Outbox、水位删除 `task_version_id`，历史由执行快照追溯 |
| D-030 | 活动执行期间禁止修改任务配置，不建立待生效配置 |
| D-031 | 删除 `task_validation_policy`，任务级校验覆盖合并到 `sync_task.validation_method_override` |
| D-032 | `institution_id/dataset_id` 是任务固定身份，创建后不可修改；更换身份时删除旧任务并新建 |

---

## 5. 完成定义

阶段 1 完成必须满足：

- 所有 P0 表、字段、外键、唯一约束和索引无冲突；
- 不存在仍引用旧表、旧状态或废止功能的目标设计；
- 任务固定业务身份、当前配置覆盖、活动执行编辑边界、校验覆盖和执行快照职责清晰；
- 文档一致；
- 用户明确签字后才允许创建 Flyway V1。

后续实施任务完成还必须满足：

- Java 构建通过；
- 空库迁移和启动通过；
- 前端使用真实接口；
- 成功、失败、重复请求、并发和人工恢复路径明确；
- 危险操作具备二次确认和审计；
- 敏感信息不进入响应、审计和普通日志。

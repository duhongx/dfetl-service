# DFETL 迁移整改任务清单

> 仓库：`duhongx/dfetl-service`  
> 分支：`main`  
> 状态：阶段 1「目标模型冻结与物理表字典复核」进行中  
> 最近更新：2026-08-15  
> 最终业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> 明确修正文档：用户确认后的各项 `P0_*_REVIEW.md`

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

设计原则：

- 系统负责提供功能、固定每次运行输入并保证结果可解释；
- 只限制会导致数据写入、校验范围或最终结果不确定的真实冲突；
- 不会影响当前运行结果且已有启动快照隔离的操作，由用户自行决定顺序；
- 不为此建设待生效配置、自动迁移、排队或过程编排状态机。

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

- 不建立独立 `collection_route_institution`。
- 链路覆盖移除机构时，若仍有当前任务使用该链路和机构，则拒绝移除。
- 链路问题可以保存和展示，正式执行遇到真实不可执行合同则失败。

### 1.3 同步任务：固定身份、当前配置覆盖

- 一个任务只属于一家机构和一个标准数据集。
- 同一机构、同一数据集只能存在一个未删除任务。
- `institution_id/dataset_id` 是稳定业务身份，任务创建后不可修改。
- 更换机构或数据集时，确认没有活动同步执行，逻辑删除旧任务，再创建新任务。
- 旧任务的水位、执行、批次、校验、Outbox 和审计历史继续归属旧任务，不迁移。
- `sync_task` 直接保存当前有效执行配置。
- 编辑普通配置直接覆盖原任务，不建立 `sync_task_version`。
- 删除 `sync_task.current_version_id` 及全部 `task_version_id` 引用。
- 用户可以修改当前链路、数据集合同版本、读取参数和调度配置。
- 存在 `PENDING/RUNNING/LOADING/VALIDATING sync_execution` 时，禁止修改任务配置。
- 编辑被活动同步执行拒绝时返回 `TASK_EXECUTION_ACTIVE`，并展示执行 ID、状态和建议。
- 活动独立校验不阻止任务普通配置编辑；当前校验继续使用启动快照。
- 校验启动与任务编辑同时发生时，通过短事务锁确定编辑前或编辑后的完整快照。
- 尝试修改机构或数据集时返回 `TASK_IDENTITY_IMMUTABLE`。
- 系统不替用户判断换链路后是否应重置水位、重新全量或处理删除快照基线。
- 历史执行和历史校验通过各自启动快照追溯；任务修改历史通过 `audit_log` 追溯。
- 任务暂停只控制自动调度；暂停和取消当前执行是独立操作。

### 1.4 校验覆盖存储

```text
system_setting[validation.default_method]
standard_dataset.validation_method_override
sync_task.validation_method_override
```

数据集和任务覆盖允许：

```text
NULL
ROW_COUNT
ROW_COUNT_CHECKSUM
```

全局设置允许：

```text
ROW_COUNT
ROW_COUNT_CHECKSUM
```

注册默认值：

```text
ROW_COUNT
```

固定规则：

- `NULL` 表示继承，不保存额外 `override_mode`。
- 不建立 `global_validation_policy/dataset_validation_policy/task_validation_policy`。
- 任务覆盖使用 `sync_task.revision`。
- 数据集覆盖使用 `standard_dataset.revision`。
- 全局覆盖使用 `system_setting.revision`；设置行缺失时使用注册默认值。
- 数据集定义同步不得覆盖管理员设置。
- 无真实业务主键的数据集最终只能使用 `ROW_COUNT`。

解析顺序：

```text
任务覆盖
→ 数据集覆盖
→ system_setting[validation.default_method]
→ 注册默认值 ROW_COUNT
→ 数据集合同能力强制
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

- `sync_execution` 表示任务的一次真实运行，并保存启动时完整配置快照。
- 执行直接保存任务 revision、机构、数据集版本、链路版本、读取和写入合同、校验来源及消息策略快照。
- 不建立 `sync_task_version`，执行不保存 `task_version_id`。
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

- 批次类型、整次范围、机构身份和 Checksum 协议从父执行取得。
- 不保存 `phase/time_lower/time_upper/probe_result/institution_code/checksum_protocol_version`。
- 批次只保存实际 Keyset 游标、行数、载荷摘要、Label 和事务状态。
- `committed_at` 更名为 `visible_at`，只在确认 Doris `VISIBLE` 时写入。
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
- 有真实业务主键时可配置 `ROW_COUNT_CHECKSUM`，不允许执行中静默降级。
- 校验失败或不一致时执行失败且水位不推进。
- 人工重新校验生成独立 `validation_run`，不覆盖历史执行结果。
- `validation_run` 不保存 `task_version_id`；独立校验保存 `context_snapshot/range_snapshot`。
- `difference_count` 只在能够准确计算时保存；整次 Checksum 不一致但无法推导差异行数时允许为空。
- 同一任务同步执行与独立校验互斥；同步自身 `SYNC_GATE` 除外。
- 同一任务同一时间最多一条活动独立校验。
- 定期治理冲突时跳过本次触发，不创建 `SKIPPED validation_run`。
- 活动独立校验不冻结任务配置；本次校验继续使用启动快照。

### 1.9 消息、外部 API 和支撑对象

- 消息只使用 RabbitMQ，配置只存在于数据集级。
- 每次成功执行最多创建一条小型 `message_outbox`。
- Outbox 不保存 `task_version_id`、业务 payload、分页进度和逐条消息。
- Outbox 关联原执行，并复制任务、数据集、机构、消息策略和发布范围快照。
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

已确认：

- [x] 删除独立 `collection_route_institution`。
- [x] 删除预检策略层级。
- [x] 任务可直接更换链路。
- [x] 任务修改覆盖原任务，不建立 `sync_task_version`。
- [x] 删除全部 `task_version_id`。
- [x] 活动同步执行期间禁止修改任务配置。
- [x] 活动独立校验期间允许修改普通任务配置。
- [x] `institution_id/dataset_id` 创建后不可修改。
- [x] 删除三张独立校验策略表。
- [x] 校验覆盖合并到系统设置、数据集和任务字段。
- [x] 删除 `task_watermark.task_version_id`。
- [x] 校验不可关闭，无容差、无校验回看。

待机械清理：

- [ ] 从早期文档删除旧任务版本和三张校验策略表。
- [ ] 清理任务身份可修改、独立校验冻结任务配置等旧描述。
- [ ] 核对所有复合外键的父唯一约束。
- [ ] 统一任务和写入枚举名称。

### 工作包 2：执行、批次、预检、校验和 Outbox

状态：**当前物理字典已经按可变任务模型重写，继续完成外键闭环。**

已确认并完成：

- [x] 已接受运行请求的技术前检失败保留 `FAILED sync_execution`。
- [x] 首次全量和后续增量为独立执行。
- [x] `sync_execution` 删除 `task_version_id` 并保存启动快照。
- [x] `validation_run` 删除 `task_version_id/policy_snapshot` 并保存校验上下文快照。
- [x] `message_outbox` 删除 `task_version_id`，按原执行快照创建。
- [x] 删除批次 `phase/time_lower/time_upper/probe_result`。
- [x] 删除批次重复的 `institution_code/checksum_protocol_version`。
- [x] `committed_at` 更名为 `visible_at`。
- [x] Label 不明确只探测原事务，超时失败，不自动重投。
- [x] 同一任务同步执行与独立校验互斥，`SYNC_GATE` 除外。
- [x] 同一任务最多一条活动独立校验。
- [x] 活动独立校验不阻止任务普通配置编辑。
- [x] 日志全文不进入 PostgreSQL。

待一致性检查：

- [ ] 完成 `delete_snapshot_run/task_delete_snapshot_state/validation_run/delete_apply_run` 外键闭环。
- [ ] 复核 Outbox 对 `FULL/INCREMENTAL/BACKFILL` 等执行范围的发布映射。
- [ ] 核对所有父外键列的反向索引。

### 工作包 3：全表外键、索引、状态和文档一致性

状态：**正在进行。**

- [ ] 形成唯一 P0 PostgreSQL 表清单。
- [ ] 标记 Quartz 标准表、Doris 技术表和不需要数据库表的能力。
- [ ] 形成完整外键和删除行为矩阵。
- [ ] 检查全部业务唯一约束和并发部分唯一索引。
- [ ] 统一任务、执行、批次、预检、校验、Outbox、告警、外部 API 和删除快照枚举。
- [ ] 清理目标模型和早期物理字典中的旧表名、旧状态和废止字段。
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
- [ ] 注册 `validation.default_method`，默认 `ROW_COUNT`。
- [ ] 首个管理员通过部署 Secret、环境变量或一次性命令初始化。

## M1：核心领域模型

- [ ] 实现机构、业务系统实例、源/目标数据源和多对多关系。
- [ ] 实现数据集同步、不可变数据集版本、字段合同和数据集级校验覆盖字段。
- [ ] 实现采集链路、链路版本和字段解析。
- [ ] 实现固定身份、当前配置可变的 `sync_task`、任务级覆盖字段和水位。
- [ ] 任务编辑接口不允许修改 `institution_id/dataset_id`。
- [ ] 活动同步执行期间禁止编辑；活动独立校验期间允许普通配置编辑。
- [ ] 删除废止的任务版本和三张独立校验策略实体、Repository、DTO 与接口。

## M2：Doris 合同、预检和任务创建

- [ ] 统一 ODS/RAW DDL 生成器。
- [ ] 实现 Doris 实际结构核对。
- [ ] 实现预检状态机和汇总导出。
- [ ] 实现任务创建和普通配置直接编辑。
- [ ] 实现执行和独立校验启动快照及技术前检。

## M3：执行、校验、删除识别和调度

- [ ] 实现 `sync_execution` 受控快照字段和状态机。
- [ ] 实现 Keyset 分页、精简 `load_batch`、确定性 Label 和探测。
- [ ] API/Java/Vue 统一使用 `visibleAt`，不再使用误导性的 `committedAt`。
- [ ] 实现首次全量、正常增量、重新采集和数据补采。
- [ ] 实现严格 `ROW_COUNT/ROW_COUNT_CHECKSUM`。
- [ ] 实现独立校验上下文快照、活动唯一索引和运行互斥。
- [ ] 实现水位原子提交和唯一 Outbox。
- [ ] 实现 Doris 删除快照技术表和人工应用流程。
- [ ] 实现 Quartz 投影重建和并发保护。

## M4：外部 API 和前端真实集成

- [ ] 实现 HMAC、client 管理、机构授权和所有写操作幂等。
- [ ] 移除生产页面 Mock 状态。
- [ ] 建立稳定 URL、统一前端 API 层和真实分页。
- [ ] 接入机构、数据源、数据集、链路、任务、预检、校验和监控页面。
- [ ] 任务编辑页面把机构和数据集作为只读身份信息。
- [ ] 活动同步执行阻止编辑时展示执行 ID、状态和建议。
- [ ] 活动独立校验期间不禁用任务普通配置编辑。
- [ ] 批次详情展示 `status/dorisState/visibleAt` 和清晰错误信息。
- [ ] 系统设置页面提供默认校验方式选项。

## M5：消息、安全和运维

- [ ] 实现 RabbitMQ Outbox 扫描、重试、死信和人工重发。
- [ ] 实现简单管理员账号和成功/失败审计。
- [ ] 实现日志中心和任务监控。
- [ ] 最后实现告警渠道、规则、事件和投递历史。

## M6：主流程稳定后补测试

- [ ] 任务固定身份和普通配置编辑。
- [ ] 活动同步执行期间不能编辑；活动独立校验期间可以编辑。
- [ ] 执行和独立校验快照不受后续任务修改影响。
- [ ] 校验启动与任务编辑并发时只采用完整的编辑前或编辑后配置。
- [ ] 任务、数据集和全局校验方式继承正确。
- [ ] 首次全量与后续增量独立运行。
- [ ] 执行记录不包含 `task_version_id`，历史仍可完整解释。
- [ ] `load_batch` 不包含重复范围、机构、协议和探测字段。
- [ ] `PREPARE/COMMITTED/VISIBLE/ABORTED/UNKNOWN` 全路径。
- [ ] 只有 `VISIBLE + rejected=0` 批次成功并写 `visibleAt`。
- [ ] 同步、独立校验和第二条独立校验的互斥正确。
- [ ] `SYNC_GATE` 不被独立校验唯一索引错误阻止。
- [ ] Checksum 不一致且无法计算差异行数时，`difference_count` 可以为空并提供汇总。
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
| D-029 | 执行、校验、Outbox、水位删除 `task_version_id`，历史由运行快照追溯 |
| D-030 | 活动同步执行期间禁止修改任务配置 |
| D-031 | 删除 `task_validation_policy`，任务级覆盖合并到 `sync_task` |
| D-032 | `institution_id/dataset_id` 是任务固定身份 |
| D-033 | 删除 `dataset_validation_policy`，数据集级覆盖合并到 `standard_dataset` |
| D-034 | 删除 `global_validation_policy`，全局默认改为系统设置 |
| D-035 | 同一任务同步执行与独立校验互斥，`SYNC_GATE` 除外 |
| D-036 | 同一任务最多一条活动独立校验 |
| D-037 | 活动独立校验不阻止任务普通配置编辑 |
| D-038 | 执行、校验和 Outbox 使用启动快照，不再依赖任务版本 |
| D-039 | 批次删除重复机构和协议字段，确认可见时间统一为 `visible_at` |
| D-040 | 无法准确推导差异行数时 `validation_run.difference_count` 允许为空 |

---

## 5. 完成定义

阶段 1 完成必须满足：

- 所有 P0 表、字段、外键、唯一约束和索引无冲突；
- 不存在仍引用旧表、旧状态或废止功能的目标设计；
- 任务固定身份、当前配置覆盖和运行快照职责清晰；
- 校验覆盖层级及其存储位置唯一；
- 同任务运行操作互斥和独立校验并发边界明确；
- 对不影响当前结果的用户操作不增加无意义限制；
- 文档一致；
- 用户明确签字后才允许创建 Flyway V1。

后续实施任务还必须满足：

- Java 构建通过；
- 空库迁移和启动通过；
- 前端使用真实接口；
- 成功、失败、重复请求、并发和人工恢复路径明确；
- 危险操作具备二次确认和审计；
- 敏感信息不进入响应、审计和普通日志。

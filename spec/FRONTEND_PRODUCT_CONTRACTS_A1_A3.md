# DFETL 前端产品合同：A1–A3

> 状态：`CURRENT`  
> 确认日期：2026-08-17  
> 适用范围：A1 数据预检问题明细页面、A2 无主键机构范围替换前端语义、A3 页面—操作—权限—审计矩阵  
> 产品状态：`CONFIRMED`  
> 前端实现状态：`IMPLEMENTED`；A3 Mock 产品行为已通过 ESLint 与 Next.js 生产构建，真实 API 接入未完成  
> 后端与物理模型状态：`FROZEN_FOR_IMPLEMENTATION`；实施授权为 `YES`，OpenAPI 已生成，Java 后端仍未实现  
> 实施边界：页面、交互、权限、审计、REST API 和 C1–C3 物理模型均已冻结；后续实现必须匹配 OpenAPI，真实接口、数据库和外部组件仍需逐项验证。

## 1. 文档优先级和适用规则

发生冲突时按以下顺序处理：

1. 用户最新明确确认；
2. `CURRENT_CONFIRMED_PROCESS_RULES.md`；
3. 本文件中 A1–A3 的产品交互合同；
4. `PRODUCT_AND_BUSINESS_DECISIONS.md`；
5. `TARGET_METADATA_MODEL.md`；
6. `TASKS.md`；
7. 当前前端 Mock、旧代码和历史归档材料。

本文明确覆盖当前前端中以下过期表达：

- “预检只展示汇总、不展示问题记录明细”；
- “无主键任务使用 TRUNCATE/清空表”的用户文案；
- “业务目录 + Source 单机构归属 + 单机构 Route + 无 Task Version”的旧页面模型；
- “技术模型已冻结”的阶段文案；
- “账号只启停、不建设权限”的旧原型结论。

当前代码可作为组件和视觉实现参考，但不能覆盖本文产品合同。

---

# A1：数据预检问题明细页面合同

## 2. 产品目标

数据预检的直接使用者需要完成以下闭环：

```text
发起整条采集链路预检
→ 查看结构和质量问题汇总
→ 定位具体问题记录
→ 查看该记录的具体非法字段和原因
→ 按筛选条件导出整改清单
→ 由源数据提供方修改真实源表/视图
→ 创建新的预检运行重新验证
```

问题汇总用于统计、筛选和趋势分析；问题明细用于实际整改。两者必须同时存在，不能相互替代。

预检不提供脱离问题上下文的随机“数据样例”页。问题记录明细不是样例，而是本次完整预检实际发现的问题事实。

## 3. 页面层级和稳定 URL

### 3.1 数据预检列表

```text
/tasks/precheck
```

列表的顶层对象是“采集链路”，不是某一次预检运行。每条链路展示最新运行信息，并提供进入详情和启动预检的入口。

### 3.2 采集链路预检详情

```text
/tasks/precheck/{routeId}
```

固定页签：

1. **基本配置**：数据集、数据集版本、业务系统实例、源数据源、源对象、目标 Doris、覆盖机构、字段解析合同版本；
2. **运行记录**：该链路所有预检运行，按时间倒序；
3. **质量趋势**：可基于长期汇总展示最近运行的通过率、问题记录数和规则趋势；P0 可先以表格实现，不要求图表。

### 3.3 单次预检运行详情

```text
/tasks/precheck/{routeId}/runs/{runId}
```

固定页签：

1. **运行概览**；
2. **问题汇总**；
3. **问题明细**。

运行详情必须使用 URL 中的 `routeId + runId` 重新加载上下文，不能复用上一次选中的运行。

## 4. 数据预检列表合同

### 4.1 筛选条件

- 数据集编码或名称；
- 业务系统实例；
- 源数据源；
- 源对象；
- 覆盖机构；
- 最新技术状态；
- 最新业务结果；
- 最近运行时间。

### 4.2 列表字段

| 字段 | 说明 |
| --- | --- |
| 采集链路 | Route ID、数据集、源对象 |
| 业务系统实例 | 实例名称和编码 |
| 数据源 | Source 名称和数据库类型 |
| 覆盖机构 | 机构数量，支持展开查看 |
| 最新技术状态 | `PENDING/EXTRACTING/VALIDATING/COMPLETED/FAILED/CANCELLED` |
| 最新结果 | `PASS/ISSUES`；技术失败时为空 |
| 问题记录数 | 去重后的问题源记录数量 |
| 问题项数 | 字段问题和组合规则问题实例总数 |
| 最近运行 | 开始时间、完成时间 |
| 操作 | 详情、运行 |

不得继续使用含义不明确的单一“问题数”。页面和 DTO 必须区分：

- `problemRecordCount`：存在一个或多个问题的源记录数；
- `problemItemCount`：字段或组合规则问题实例数；
- `affectedInstitutionCount`：受影响机构数。

### 4.3 页面操作

- **运行**：人工创建新的预检运行；
- **批量运行**：对用户选中的多条不同链路分别创建运行；同链路互斥仍由服务端最终保证；
- **详情**：进入链路预检详情；
- 不提供自动调度开关。

## 5. 运行概览合同

运行概览必须展示：

- Run ID；
- Route ID 和 Route Version；
- Dataset ID、Dataset Version、合同 Hash；
- 源对象结构 Hash；
- 覆盖机构；
- 启动方式和操作者；
- 技术状态、当前阶段、真实进度；
- 结果状态；
- 提取行数、检查行数；
- 问题记录数、问题项数；
- 开始、完成、取消或失败时间；
- 原始预检数据和问题明细的到期时间；
- 清理状态；
- 失败原因。

技术状态和业务结果必须分开：

```text
COMPLETED + PASS
COMPLETED + ISSUES
FAILED + null
CANCELLED + null
```

发现不合格数据不是技术失败。

## 6. 问题汇总合同

### 6.1 汇总维度

问题汇总至少支持：

- 机构；
- 规则作用域：`STRUCTURE/FIELD/COMPOSITE`；
- 标准字段；
- 规则编码和规则版本；
- 问题类型；
- 影响记录数；
- 问题项数；
- 实际偏差指标。

### 6.2 汇总字段

| 字段 | 示例 |
| --- | --- |
| 机构 | 县人民医院 |
| 作用域 | FIELD |
| 字段 | `SHENFENZH` 身份证号 |
| 规则 | `MAX_LENGTH` v3 |
| 检查记录数 | 1,200,000 |
| 影响记录数 | 2,143 |
| 问题项数 | 2,143 |
| 实际偏差 | 最大长度 32，要求不超过 18 |
| 操作 | 查看问题明细 |

点击“查看问题明细”后，自动切换到问题明细页签，并带入当前机构、字段和规则筛选条件。

## 7. 问题明细合同

### 7.1 顶层展示单位

问题明细表以“问题源记录”为一行，而不是一个字段问题一行。同一条源记录有多个非法字段时，在展开区域统一展示。

这样能够直接回答：

> 这条记录一共有几个字段不合法，每个字段为什么不合法？

### 7.2 顶层列表字段

| 字段 | 说明 |
| --- | --- |
| 机构 | 标准机构编码和名称 |
| 记录定位 | 业务主键或本次运行定位符 |
| 定位类型 | `BUSINESS_KEY` 或 `RUN_SCOPED` |
| 问题字段数 | 去重字段数量 |
| 问题项数 | 字段和组合规则问题数量 |
| 问题摘要 | 前几个问题类型，超出部分显示数量 |
| 敏感标识 | 是否包含敏感字段或值 |
| 检查时间 | 本次运行内检查时间 |
| 操作 | 展开详情、查看原值、导出本记录 |

### 7.3 展开后的问题项

每个问题项至少展示：

- 作用域：`FIELD/COMPOSITE`；
- 标准字段编码和名称，组合规则可包含多个字段；
- 规则编码和规则版本；
- 实际值或脱敏值；
- 期望合同；
- 明确的问题原因；
- 实际偏差指标；
- 是否为敏感字段。

示例：

```text
记录：机构 123456 + 患者编号 10002341

SHENFENZH
实际值：320************123456
期望：长度不超过 18
原因：实际长度为 20

CHUSHENGRQ
实际值：2026-13-40
期望：合法 DATE
原因：月份和日期超出有效范围
```

### 7.4 记录定位方式

#### 有真实业务主键

使用：

```text
机构代码 + 标准业务主键字段值
```

联合主键按数据集合同中的稳定顺序展示。

#### 无真实业务主键

使用只在本次预检运行内有效的定位信息，例如：

- `runRowId`；
- `rowFingerprint`；
- 用于人工识别的有限字段摘要。

页面必须显示“本次运行定位符”，不能标注为业务主键。

运行内定位符不得用于：

- 正式同步业务主键；
- Doris `UNIQUE KEY`；
- 增量游标；
- 删除对账；
- `ROW_COUNT_CHECKSUM` 逐行对齐；
- 后续运行之间的稳定记录身份。

### 7.5 筛选、分页和排序

必须支持：

- 机构；
- 字段；
- 规则；
- 问题类型；
- 作用域；
- 业务主键或运行内定位符；
- 是否包含敏感字段；
- 是否存在组合规则问题；
- 问题字段数范围；
- 默认按机构、记录定位稳定排序；
- 服务端分页和每页条数。

页面筛选、URL 查询参数和导出条件必须一致。刷新页面后筛选条件不能丢失。

## 8. 脱敏、查看原值和导出

### 8.1 默认展示

- 普通明细查看默认返回和显示脱敏值；
- 密码、密钥等连接凭据永不进入预检结果；
- 审计日志不得保存完整敏感业务值。

### 8.2 查看原值

- 每次只对明确字段或记录执行；
- 需要独立权限 `precheck.detail.reveal`；
- 需要敏感操作确认；
- 必须记录审计事件 `PRECHECK_DETAIL_VALUE_REVEAL`；
- 审计只记录 Run、记录定位、字段、操作者和结果，不保存原值；
- 原值只在当前页面会话中短暂展示，页面刷新后重新脱敏；
- P0 不提供“一键显示全部原值”。

### 8.3 导出类型

固定提供：

1. **导出问题汇总**；
2. **导出问题明细**。

导出问题明细时必须明确：

- 当前筛选条件；
- 预计记录数；
- 导出格式；
- 使用脱敏值还是原值；
- 文件有效期；
- 操作审计。

权限：

- 汇总导出：`precheck.summary.export`；
- 脱敏明细导出：`precheck.detail.export`；
- 原值明细导出：同时需要 `precheck.detail.export_sensitive`。

具体同步下载、异步任务、阈值和文件存储属于后续物理/API Review。前端合同固定为：无论采用哪种后端方式，用户都能看到生成中、成功、失败、过期状态，并能确认导出范围。

## 9. 保留期和到期状态

预检运行记录和汇总长期保留；问题明细和用于还原问题上下文的原始预检数据限期保留。

页面状态：

| 状态 | 页面行为 |
| --- | --- |
| `AVAILABLE` | 明细可查询、可导出 |
| `EXPIRING` | 显示明确到期时间和提示 |
| `CLEANING` | 禁止新导出，已打开页面提示正在清理 |
| `EXPIRED` | 问题明细页签不可查询；汇总继续可见；显示实际清理时间 |
| `CLEAN_FAILED` | 运维可见清理失败告警；普通用户不误认为数据仍永久保留 |

默认保留期、最大保留期和存储介质仍待物理模型 Review，当前前端不得写死“1 天”。

## 10. 运行操作和并发

- 预检只能人工启动；
- 同一 Route 同时只能有一个活动运行；
- 不同 Route 的并发受全局参数限制；
- 重新预检创建新 Run，不覆盖历史；
- 运行中可由具备权限的用户取消；
- 技术失败或整改后都通过“重新运行”创建新 Run；
- 不提供“修复问题”“标记已处理”“覆盖原结果”等平台内脏数据工作流。

## 11. A1 验收场景

1. 同一问题记录存在三个非法字段，页面以一条记录展示并可展开三个问题项；
2. 有真实业务主键的数据集按机构代码和联合业务主键定位；
3. 无主键数据集显示运行内定位符，并明确不能用于正式同步；
4. 普通用户只能看到脱敏值；具备权限的用户查看原值后产生审计记录；
5. 从某条汇总下钻后，明细筛选与汇总条件一致；
6. 明细导出与当前筛选、脱敏方式一致；
7. 重新预检不会覆盖旧运行；
8. 明细到期后汇总仍可查询，页面不会显示成“0 条问题”；
9. 多机构链路可以按机构筛选明细，但运行范围仍是整条链路；
10. 页面刷新、深链和浏览器前进后退不会串用其他 Run。

---

# A2：无主键机构范围替换的前端语义

## 12. 冻结的业务合同

无真实业务主键的数据集固定使用：

```text
FULL_ONLY + REPLACE_INSTITUTION_SCOPE + DUPLICATE_KEY
```

用户可见语义：

```text
每次全量 + 替换当前机构范围
```

`REPLACE_INSTITUTION_SCOPE` 是业务写入语义，不是可编辑选项，也不是物理 SQL 命令名称。

## 13. 统一术语

| 技术值 | 用户主文案 | 辅助说明 |
| --- | --- | --- |
| `FULL_ONLY` | 每次全量 | 每次读取当前机构的完整源数据 |
| `REPLACE_INSTITUTION_SCOPE` | 替换当前机构范围 | 只替换本任务所属机构，不影响共享表中其他机构 |
| `DUPLICATE_KEY` | DUPLICATE KEY | 仅在高级技术信息中展示 |

以下文案不得出现在普通用户界面：

- TRUNCATE；
- 清空整表；
- DROP_DATA；
- 删除目标表全部数据；
- 无主键追加；
- 生成假主键。

## 14. 各页面固定展示

### 14.1 数据集详情

在同步策略或字段合同区域展示：

```text
数据集没有真实业务主键。
标准任务固定为“每次全量、替换当前机构范围”。
系统不会生成 Hash、自增 ID 或 row_number() 作为假主键。
```

### 14.2 任务创建向导

执行合同只读卡片：

| 项目 | 展示值 |
| --- | --- |
| 数据范围 | 当前机构全量 |
| 任务类型 | 每次全量 |
| 写入方式 | 替换当前机构范围 |
| Doris 键模型 | DUPLICATE KEY |
| 增量字段 | 无 |
| 内容校验 | 不支持逐行业务键 Checksum；固定 ROW_COUNT |

用户不能将其改为 UPSERT、APPEND、增量或整表清空。

### 14.3 任务列表和详情

主文案显示：

```text
每次全量 · 替换当前机构范围
```

技术详情可显示：

```text
FULL_ONLY / REPLACE_INSTITUTION_SCOPE / DUPLICATE_KEY
```

### 14.4 手工运行确认

确认弹窗必须展示：

- 任务；
- 医疗机构编码和名称；
- 数据集；
- 源对象；
- Doris 目标库表；
- 本次范围：当前机构完整数据；
- 明确提示“不会清空整张共享表，不影响其他机构”。

固定提示：

> 本次将重新读取【机构名称】的完整源数据，并在写入和阻断校验满足成功条件后，替换 Doris 中该机构的数据范围。共享表内其他机构数据不受影响。

### 14.5 重新采集确认

无主键任务的重新采集仍然是机构范围替换：

> 将创建新的重新采集执行，从当前机构范围起点重新读取全部数据。不会从历史成功批次续跑，也不会清空其他机构数据。

无主键任务不提供“UPSERT/清空重建”二选一，因为它没有可用于 UPSERT 的真实业务主键。

### 14.6 执行详情

展示：

- 业务写入语义；
- 当前机构范围；
- 实际物理策略快照（物理方案确认后只读展示）；
- 替换前目标范围统计；
- 新装载范围统计；
- 范围切换结果；
- 校验结果；
- 其他机构未受影响的验证结果或执行证据。

物理策略不能作为用户可编辑参数。

## 15. 状态和反馈文案

| 阶段 | 用户文案 |
| --- | --- |
| 读取中 | 正在读取当前机构完整源数据 |
| 临时装载中 | 正在准备新的机构范围数据，现有正式数据继续可用 |
| 校验中 | 正在校验本次当前机构完整范围 |
| 范围切换中 | 正在替换当前机构范围 |
| 成功 | 当前机构范围替换成功；其他机构未受影响 |
| 替换前失败 | 本次替换未生效，正式数据保持原状态 |
| 最终状态不明确 | 当前范围最终状态待核对，禁止再次运行；请进入执行详情查看核对结果 |

“最终状态不明确”的具体执行状态字段在 Doris 物理方案 Review 中确定，但前端不得把不明确结果显示为成功。

## 16. 权限和审计

- 普通运行：`sync_task.run`，审计 `SYNC_TASK_MANUAL_RUN`；
- 重新采集：`sync_task.recollect`，审计 `SYNC_TASK_RECOLLECT`；
- 取消执行：`sync_execution.cancel`，审计 `SYNC_EXECUTION_CANCEL`；
- 查看物理执行策略：`sync_execution.view`；
- 任何整表破坏操作不属于本业务合同，不提供前端入口。

审计必须保存任务、任务版本、机构、数据集、Route Version、执行范围、触发来源、结果和错误；不得只记录“点击了运行”。

## 17. A2 验收场景

1. 任务创建、列表、详情和运行确认均不出现 TRUNCATE/DROP_DATA；
2. 无主键任务无法选择增量、UPSERT、APPEND 或内容 Checksum；
3. 运行确认明确显示当前机构和共享表隔离范围；
4. 重新采集不显示从失败批次继续；
5. 执行失败时不能显示“当前机构范围已替换”；
6. 后端尚未确认物理策略时，前端不虚构“原子分区替换已实现”；
7. 任何执行结果都能从审计中追溯机构范围和任务版本。

---

# A3：完整页面—操作—权限—审计矩阵

## 18. 目标信息架构

### 18.1 左侧主导航

```text
运行概览

接入资源
  机构管理
  业务系统实例
  数据源管理
  数据集管理

任务中心
  数据同步
  数据预检
  任务监控

数据校验
  校验总览
  校验工作台

运维管理
  告警通知
  日志中心
  操作审计

系统设置
  全局参数
  医共体数据模型
  校验策略
  Doris 建表
  外部授权
  类型映射规则
  账号与权限
```

顶部用户菜单：

- 个人中心；
- 修改密码；
- 告警偏好；
- 退出登录。

顶部 Help 进入使用文档。

### 18.2 不作为主菜单的深层页面

- 数据集详情；
- 采集链路列表和详情；
- 任务创建向导；
- 任务详情；
- 预检链路详情；
- 预检运行详情；
- Execution 详情；
- Validation 运行详情；
- 告警事件详情；
- 日志详情；
- 审计详情；
- Doris DDL 预览和差异详情。

### 18.3 明确删除或替换

- 删除“业务目录”主数据页面；
- 删除“采集关系”独立导航组；
- “机构采集路由”统一改为“采集链路”；
- Source 和 Target 合并为“数据源管理”的两个页签；
- 增加业务系统实例页面；
- 增加校验策略和 Doris 建表页面；
- 账号权限模型进入支撑对象 Review，但前端权限点从本矩阵开始统一使用。

## 19. 权限模型通则

### 19.1 权限代码

权限采用：

```text
domain.action
```

例如：

```text
precheck.view
precheck.run
precheck.detail.reveal
sync_task.recollect
```

本文冻结权限点语义，不冻结角色名称。后续角色只是权限集合，不能把前端按钮直接写死为“管理员可见”。

### 19.2 前端行为

- 没有页面查看权限：菜单不显示，直接访问 URL 返回 403 页面；
- 有查看权限但没有操作权限：页面可见，操作按钮隐藏或禁用并说明原因；
- 敏感值权限与普通明细查看权限分离；
- 导出权限与普通查看权限分离；
- 前端权限控制只改善交互，服务端仍必须独立校验；
- 因状态冲突导致的禁止操作与权限不足必须使用不同提示；
- 任何按钮必须作用于当前行、当前 Route、当前 Task、当前 Run 或当前 Execution，不复用上次选中上下文。

## 20. 确认等级

| 等级 | 含义 |
| --- | --- |
| `—` | 不需要确认 |
| `C1` | 普通命令确认，展示对象、范围和影响 |
| `C2` | 危险操作二次确认，要求填写原因或确认文本 |
| `S1` | 敏感数据查看/导出确认，展示合规责任和审计提示 |

## 21. 审计通则

### 21.1 必须审计的操作

- 所有新增、修改、启停和删除；
- 所有运行、取消、重新采集、补采、重新校验；
- 水位重置、Doris 建表或重建、删除应用；
- 查看敏感原值；
- 所有导出；
- 密码、Secret 和凭据变更；
- 权限分配；
- 成功和失败都记录。

普通列表查看不进入业务审计，但进入访问日志。敏感值查看、审计导出和安全配置查看必须进入业务审计。

### 21.2 审计字段

- audit ID；
- 操作者 ID、用户名和显示名；
- 来源：Web、External API、Scheduler、System；
- 权限代码；
- 操作事件；
- 对象类型、对象 ID 和版本；
- 机构、数据集、Route、Task、Run、Execution 等业务上下文；
- 操作原因；
- 前后值摘要；
- 范围和筛选快照；
- 请求 ID；
- IP、User Agent；
- 结果、错误码和错误摘要；
- 时间。

审计不得保存密码、Secret、数据库完整凭据或预检敏感原值。

---

## 22. 页面—操作—权限—审计矩阵

### 22.1 运行概览

| 操作 | 权限 | 确认 | 审计 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看概览 | `dashboard.view` | — | — | 只展示有权限范围内的汇总 |
| 下钻 Execution | `sync_execution.view` | — | — | 携带 Execution ID |
| 下钻异常、预检或校验 | 对应资源 `view` | — | — | 不复用上次选中对象 |

### 22.2 机构管理

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看列表/详情 | `institution.view` | — | — | 机构为扁平集合 |
| 新增机构 | `institution.create` | — | `INSTITUTION_CREATE` | 编码全局唯一 |
| 编辑机构 | `institution.update` | — | `INSTITUTION_UPDATE` | 编码变更需检查引用 |
| 启用/停用 | `institution.status` | C1 | `INSTITUTION_STATUS_CHANGE` | 停用不删除历史 |
| 删除未引用机构 | `institution.delete` | C2 | `INSTITUTION_DELETE` | 有实例、Route、Task 引用时拒绝 |
| 查看关联实例/链路/任务 | `institution.view` | — | — | 只读下钻 |

### 22.3 业务系统实例

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看实例 | `system_instance.view` | — | — | 显示覆盖机构和数据源 |
| 新增实例 | `system_instance.create` | — | `SYSTEM_INSTANCE_CREATE` | 厂商不是唯一边界 |
| 编辑属性 | `system_instance.update` | — | `SYSTEM_INSTANCE_UPDATE` | 版本、类型和说明可编辑 |
| 维护覆盖机构 | `system_instance.bind_institution` | C1 | `SYSTEM_INSTANCE_INSTITUTIONS_UPDATE` | 支持多机构 |
| 维护源数据源关系 | `system_instance.bind_datasource` | C1 | `SYSTEM_INSTANCE_DATASOURCES_UPDATE` | 纯关联，不配置优先级 |
| 启用/停用 | `system_instance.status` | C1 | `SYSTEM_INSTANCE_STATUS_CHANGE` | 停用不删除历史 |
| 删除未引用实例 | `system_instance.delete` | C2 | `SYSTEM_INSTANCE_DELETE` | 有 Route/Task 引用时拒绝 |

### 22.4 数据源管理——源数据源

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看列表/详情 | `datasource.view` | — | — | 密码永不回显 |
| 新增 Source | `datasource.source.create` | — | `SOURCE_DATASOURCE_CREATE` | 支持 HOST_PORT/JDBC_URL |
| 编辑 Source | `datasource.source.update` | — | `SOURCE_DATASOURCE_UPDATE` | Secret 变更只记录“已变更” |
| 测试连接 | `datasource.test` | C1 | `SOURCE_DATASOURCE_TEST` | 测试结果不自动启停 |
| 复制配置 | `datasource.source.create` | — | `SOURCE_DATASOURCE_COPY` | 不复制密码明文 |
| 启用/停用 | `datasource.status` | C1 | `SOURCE_DATASOURCE_STATUS_CHANGE` | 影响后续运行，需要范围提示 |
| 轮换凭据 | `datasource.credential.rotate` | S1 | `SOURCE_DATASOURCE_CREDENTIAL_ROTATE` | 不记录凭据值 |
| 删除未引用 Source | `datasource.delete` | C2 | `SOURCE_DATASOURCE_DELETE` | 有实例/Route 历史引用时拒绝 |

### 22.5 数据源管理——目标 Doris

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看列表/详情 | `datasource.view` | — | — | 显示逻辑部署和 FE |
| 新增 Target | `datasource.target.create` | — | `TARGET_DATASOURCE_CREATE` | 支持多个 FE Endpoint |
| 编辑 Target/Endpoint | `datasource.target.update` | — | `TARGET_DATASOURCE_UPDATE` | 不管理 BE |
| 单点测试 | `datasource.test` | C1 | `TARGET_ENDPOINT_TEST` | 记录 Endpoint |
| 聚合测试 | `datasource.test` | C1 | `TARGET_DATASOURCE_TEST` | 测试所有启用 FE |
| 启用/停用 Target/FE | `datasource.status` | C1 | `TARGET_DATASOURCE_STATUS_CHANGE` | 展示受影响 Route/Task |
| 轮换凭据 | `datasource.credential.rotate` | S1 | `TARGET_DATASOURCE_CREDENTIAL_ROTATE` | 不记录凭据值 |
| 删除未引用 Target | `datasource.delete` | C2 | `TARGET_DATASOURCE_DELETE` | 有 Route/历史引用时拒绝 |

### 22.6 数据集管理

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看列表/详情/字段 | `dataset.view` | — | — | 数据集不可手工新增 |
| 人工同步规范定义 | `dataset.sync_definition` | C1 | `DATASET_DEFINITION_SYNC` | 展示变更差异和结果 |
| 查看定义差异 | `dataset.view` | — | — | 新旧版本只读对比 |
| 修改同步默认策略 | `dataset.policy.sync.update` | C1 | `DATASET_SYNC_POLICY_UPDATE` | 不热更新已有 Task 执行合同 |
| 修改校验策略 | `dataset.policy.validation.update` | C1 | `DATASET_VALIDATION_POLICY_UPDATE` | 无主键禁用 Checksum |
| 修改消息策略 | `dataset.policy.message.update` | C1 | `DATASET_MESSAGE_POLICY_UPDATE` | 只允许三个确认数据集 |
| 查看消息策略 | `dataset.view` | — | — | 不提供 Task 覆盖 |
| 进入采集链路 | `route.view` | — | — | 携带 datasetId |

### 22.7 采集链路

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看链路/版本 | `route.view` | — | — | 显示多机构覆盖和字段解析 |
| 新增链路 | `route.create` | — | `COLLECTION_ROUTE_CREATE` | 选择实例、Source、对象、Target、机构集合 |
| 编辑并创建新版本 | `route.version.create` | C1 | `COLLECTION_ROUTE_VERSION_CREATE` | 内容未变化不生成版本 |
| 查看字段解析 | `route.view` | — | — | 只读，不提供重命名 |
| 进入数据同步 | `sync_task.view` | — | — | 携带 routeId/datasetId/institutionId |
| 进入数据预检 | `precheck.view` | — | — | 携带 routeId |
| 删除链路 | `route.delete` | C2 | `COLLECTION_ROUTE_DELETE` | 未删除 Task 引用时拒绝 |

### 22.8 数据同步任务列表、创建和详情

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看任务 | `sync_task.view` | — | — | 按机构+数据集唯一 |
| 创建任务 | `sync_task.create` | C1 | `SYNC_TASK_CREATE` | 一次创建身份和首个版本 |
| 查看版本和差异 | `sync_task.view` | — | — | 历史版本不可变 |
| 创建新任务版本 | `sync_task.version.create` | C1 | `SYNC_TASK_VERSION_CREATE` | 展示前后差异 |
| 暂停/恢复调度 | `sync_task.schedule` | C1 | `SYNC_TASK_SCHEDULE_CHANGE` | 暂停不禁止人工运行 |
| 人工运行 | `sync_task.run` | C1 | `SYNC_TASK_MANUAL_RUN` | 展示执行范围和版本 |
| 重新采集 | `sync_task.recollect` | C1 | `SYNC_TASK_RECOLLECT` | 新 Execution，从范围起点和 Batch 1 开始 |
| 数据补采 | `sync_task.backfill` | C1 | `SYNC_TASK_BACKFILL` | 必须填写历史时间或业务键范围 |
| 重置/清除水位 | `sync_task.watermark.reset` | C2 | `SYNC_TASK_WATERMARK_RESET` | 记录前后值和原因 |
| 逻辑删除 Task | `sync_task.delete` | C2 | `SYNC_TASK_DELETE` | 活动执行时拒绝，历史保留 |
| 查看 Execution 历史 | `sync_execution.view` | — | — | 按当前 Task 过滤 |
| 查看消息 Outbox | `message_outbox.view` | — | — | 只读运行事实 |
| 人工重发 Outbox | `message_outbox.retry` | C1 | `MESSAGE_OUTBOX_RETRY` | 不回滚同步和水位 |

### 22.9 任务监控和 Execution 详情

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看监控 | `sync_execution.view` | — | — | 支持自动刷新或 SSE |
| 查看 Execution 详情 | `sync_execution.view` | — | — | 使用执行快照，不回读当前配置冒充历史 |
| 查看 Batch/Label | `sync_execution.view` | — | — | 展示最终状态和探测结果 |
| 查看运行日志 | `log.view` | — | — | 携带 executionId |
| 导出执行记录 | `sync_execution.export` | C1 | `SYNC_EXECUTION_EXPORT` | 导出范围进入审计 |
| 取消活动 Execution | `sync_execution.cancel` | C1 | `SYNC_EXECUTION_CANCEL` | 不推进水位、不修改调度开关 |

### 22.10 数据预检

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看链路预检列表 | `precheck.view` | — | — | 顶层对象为 Route |
| 查看链路详情/运行历史 | `precheck.view` | — | — | 使用 routeId 深链 |
| 启动单条 Route 预检 | `precheck.run` | C1 | `PRECHECK_RUN_CREATE` | 同 Route 活动运行时拒绝 |
| 批量启动预检 | `precheck.run_batch` | C1 | `PRECHECK_RUN_BATCH_CREATE` | 每条 Route 独立审计或关联批次号 |
| 取消活动运行 | `precheck.cancel` | C1 | `PRECHECK_RUN_CANCEL` | 保留运行记录 |
| 重新运行 | `precheck.run` | C1 | `PRECHECK_RUN_CREATE` | 创建新 Run，不覆盖历史 |
| 查看问题汇总 | `precheck.summary.view` | — | — | 汇总长期可见 |
| 导出问题汇总 | `precheck.summary.export` | C1 | `PRECHECK_SUMMARY_EXPORT` | 保存筛选快照 |
| 查看脱敏问题明细 | `precheck.detail.view` | — | — | 默认脱敏 |
| 查看单个原值 | `precheck.detail.reveal` | S1 | `PRECHECK_DETAIL_VALUE_REVEAL` | 审计不保存原值 |
| 导出脱敏问题明细 | `precheck.detail.export` | C1 | `PRECHECK_DETAIL_EXPORT` | 当前筛选条件 |
| 导出原值问题明细 | `precheck.detail.export_sensitive` | S1 | `PRECHECK_DETAIL_SENSITIVE_EXPORT` | 独立高权限 |

### 22.11 校验总览和工作台

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看总览/运行详情 | `validation.view` | — | — | 状态和结果分开 |
| 人工全量校验 | `validation.run` | C1 | `VALIDATION_RUN_CREATE` | 使用当前任务版本快照 |
| 人工修改窗口校验 | `validation.run` | C1 | `VALIDATION_RUN_CREATE` | 明确 `[lower, upper)` |
| 人工重新校验 | `validation.recheck` | C1 | `VALIDATION_RECHECK_CREATE` | 复用原 Execution 固定上下文 |
| 运行删除对账 | `validation.delete_reconciliation.run` | C1 | `DELETE_RECONCILIATION_RUN` | 不自动删除 ODS |
| 导出差异汇总 | `validation.export` | C1 | `VALIDATION_DIFFERENCE_EXPORT` | 不导出未经确认的逐行差异模型 |
| Delete Apply Dry Run | `validation.delete_apply.dry_run` | C1 | `DELETE_APPLY_DRY_RUN` | 只生成计划 |
| 应用删除差异 | `validation.delete_apply.execute` | C2 | `DELETE_APPLY_EXECUTE` | 成功 Dry Run、阈值、二次确认、审计 |

### 22.12 告警通知

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看事件/投递详情 | `alert.view` | — | — | 支持下钻业务对象 |
| 重试失败投递 | `alert.delivery.retry` | C1 | `ALERT_DELIVERY_RETRY` | 不修改原告警事实 |
| 新增/编辑规则 | `alert.rule.manage` | — | `ALERT_RULE_CREATE/UPDATE` | 展示适用范围 |
| 启用/停用规则 | `alert.rule.status` | C1 | `ALERT_RULE_STATUS_CHANGE` | 不删除历史事件 |
| 删除未引用规则 | `alert.rule.delete` | C2 | `ALERT_RULE_DELETE` | 保留历史快照 |
| 新增/编辑通道 | `alert.channel.manage` | — | `ALERT_CHANNEL_CREATE/UPDATE` | Secret 不进入审计 |
| 测试通道 | `alert.channel.test` | C1 | `ALERT_CHANNEL_TEST` | 显示测试中、成功和失败 |
| 启用/停用通道 | `alert.channel.status` | C1 | `ALERT_CHANNEL_STATUS_CHANGE` | 展示受影响规则 |
| 删除未引用通道 | `alert.channel.delete` | C2 | `ALERT_CHANNEL_DELETE` | 有规则引用时拒绝 |

### 22.13 日志中心

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看和筛选日志 | `log.view` | — | — | 默认脱敏 |
| 查看日志详情 | `log.view` | — | — | 保留业务上下文 |
| 下载日志 | `log.export` | C1 | `LOG_EXPORT` | 筛选条件和数量进入审计 |
| 查看安全受限日志 | `log.sensitive.view` | S1 | `LOG_SENSITIVE_VIEW` | 不展示凭据 |

### 22.14 操作审计

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看审计 | `audit.view` | — | `AUDIT_LOG_VIEW`（安全访问记录） | 审计不可修改 |
| 查看审计详情 | `audit.view` | — | `AUDIT_LOG_DETAIL_VIEW` | 敏感前后值继续脱敏 |
| 导出审计 | `audit.export` | S1 | `AUDIT_LOG_EXPORT` | 审计导出本身也被审计 |

### 22.15 全局参数

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看参数 | `setting.view` | — | — | 显示当前值、来源、修订号 |
| 修改调度/并发/保留/Outbox 参数 | `setting.global.update` | C1 | `GLOBAL_SETTING_UPDATE` | 保留前后值和影响说明 |
| 恢复默认值 | `setting.global.update` | C2 | `GLOBAL_SETTING_RESET` | 明确影响后续运行 |

### 22.16 医共体数据模型

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看配置/同步历史 | `registry.view` | — | — | 密码掩码 |
| 修改连接配置 | `registry.update` | C1 | `REGISTRY_CONFIG_UPDATE` | Secret 不进入审计 |
| 测试连接 | `registry.test` | C1 | `REGISTRY_CONNECTION_TEST` | 不自动同步 |
| 人工同步 Dataset | `dataset.sync_definition` | C1 | `DATASET_DEFINITION_SYNC` | 展示差异和结果 |

### 22.17 校验策略

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看全局校验策略 | `validation_policy.view` | — | — | 默认 ROW_COUNT、零容差 |
| 修改全局默认 | `validation_policy.update` | C1 | `GLOBAL_VALIDATION_POLICY_UPDATE` | 不影响运行中执行 |
| 恢复默认 | `validation_policy.update` | C2 | `GLOBAL_VALIDATION_POLICY_RESET` | 展示下一批生效范围 |

### 22.18 Doris 建表

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看期望/实际结构 | `doris_table.view` | — | — | 直接读取 Doris 元数据 |
| 刷新结构比较 | `doris_table.view` | — | — | 不自动改表 |
| 预览 DDL | `doris_table.ddl.preview` | — | — | 使用统一合同生成器 |
| 创建缺失表 | `doris_table.create` | C2 | `DORIS_TABLE_CREATE` | 显示目标库表和 DDL |
| 重建表/范围 | `doris_table.rebuild` | C2 | `DORIS_TABLE_REBUILD` | 影响范围、备份/恢复说明 |
| 查看执行结果 | `doris_table.view` | — | — | 保存操作和 Doris 返回摘要 |

### 22.19 外部授权

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看 Client/请求日志 | `external_client.view` | — | — | Secret 永不回显 |
| 新增 Client | `external_client.create` | C1 | `EXTERNAL_CLIENT_CREATE` | 首次 Secret 只显示一次 |
| 编辑授权范围 | `external_client.update` | C1 | `EXTERNAL_CLIENT_SCOPE_UPDATE` | ALL/SELECTED 使用稳定机构 ID |
| 启用/停用 | `external_client.status` | C1 | `EXTERNAL_CLIENT_STATUS_CHANGE` | 立即影响新请求 |
| 重置 Secret | `external_client.secret.reset` | S1 | `EXTERNAL_CLIENT_SECRET_RESET` | 旧 Secret 失效 |
| 删除未使用 Client | `external_client.delete` | C2 | `EXTERNAL_CLIENT_DELETE` | 有历史请求时优先停用 |

### 22.20 类型映射规则

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看映射和合同 | `type_mapping.view` | — | — | 区分医疗合同与 Generic Mapping |
| 新增 Generic Mapping | `type_mapping.generic.create` | — | `GENERIC_TYPE_MAPPING_CREATE` | 只用于诊断建议 |
| 编辑/启停 Generic Mapping | `type_mapping.generic.update` | C1 | `GENERIC_TYPE_MAPPING_UPDATE` | 不改写历史执行 |
| 删除未引用 Generic Mapping | `type_mapping.generic.delete` | C2 | `GENERIC_TYPE_MAPPING_DELETE` | 有引用时拒绝 |
| 发布医疗转换合同新版本 | `type_mapping.contract.publish` | C2 | `FIELD_CONVERSION_CONTRACT_PUBLISH` | 已引用版本不可原地修改 |

### 22.21 账号与权限

> 账号、角色和授权表的物理模型仍在 P0 支撑对象 Review 中；以下产品权限点和审计行为先行冻结。

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看账号/角色 | `security.account.view` | — | `SECURITY_ACCOUNT_VIEW` | 安全访问记录 |
| 新增账号 | `security.account.create` | C1 | `ACCOUNT_CREATE` | 初始密码策略 |
| 编辑账号资料 | `security.account.update` | — | `ACCOUNT_UPDATE` | 用户名变更需限制 |
| 启用/停用账号 | `security.account.status` | C2 | `ACCOUNT_STATUS_CHANGE` | 禁止停用当前自己和最后管理员 |
| 重置密码 | `security.account.password.reset` | S1 | `ACCOUNT_PASSWORD_RESET` | 使既有会话失效 |
| 分配角色/权限 | `security.permission.assign` | C2 | `ACCOUNT_PERMISSION_ASSIGN` | 保存前后权限集合 |
| 创建/编辑角色 | `security.role.manage` | C1 | `ROLE_CREATE/UPDATE` | 角色只是权限集合 |
| 删除未引用角色 | `security.role.delete` | C2 | `ROLE_DELETE` | 有账号引用时拒绝 |

### 22.22 个人中心、退出和帮助

| 操作 | 权限 | 确认 | 审计事件 | 产品要求 |
| --- | --- | --- | --- | --- |
| 查看/修改个人资料 | 登录用户 | — | `PROFILE_UPDATE`（修改时） | 只修改本人 |
| 修改密码 | 登录用户 | S1 | `PASSWORD_CHANGE` | 当前密码校验、会话失效 |
| 修改告警偏好 | 登录用户 | — | `USER_ALERT_PREFERENCE_UPDATE` | 只影响本人 |
| 退出登录 | 登录用户 | C1 | `LOGOUT` | 清理会话并回登录页 |
| 查看帮助 | 登录用户 | — | — | 固定 `/docs` |

---

## 23. A3 前端实施结果

截至 2026-08-17，以下产品行为已经在前端 Mock 中完成：

- 业务系统实例、实例—机构和实例—Source 多对多；
- 多机构采集链路和不可变 Route Version；
- 稳定 Task 身份、不可变 Task Version 和固定 Execution 上下文；
- Route → Precheck Run → 汇总/明细、脱敏、原值查看与导出；
- 无主键“每次全量 · 替换当前机构范围”文案和确认；
- `domain.action` 权限、403、C1/C2/S1 确认和前端审计 Mock；
- 告警事件/规则/通道/投递重试；
- 日志筛选、分页、详情、安全受限查看和导出任务；
- 审计筛选、分页、详情和敏感导出；
- 全局参数、医共体数据模型、校验策略的 Revision、保存和恢复默认；
- Doris 实际/期望结构比较、DDL 预览、创建/重建和历史；
- External Client 创建、授权范围、启停、一次性 Secret、重置和请求日志；
- Generic Mapping 与医疗字段转换合同版本；
- 账号、角色、权限分配、账号启停、密码重置和角色删除保护；
- 个人资料、修改密码和退出交互。

真实服务端接口合同已经冻结在：

```text
spec/FRONTEND_API_CONTRACT_V1.md
```

该合同覆盖统一响应、分页、Revision/ETag、幂等、权限、审计、错误码、Export Job、轮询/SSE 和全部页面接口。前端实现完成不代表 Java、PostgreSQL、Doris、RabbitMQ 或 Flyway 已完成。

二次操作级反查已补齐：Source/Target 复制、状态、凭据轮换、删除引用保护；数据集五分区详情；Route 版本与字段解析；全部核心表格分页；预检批量启动与取消；Execution/Validation 导出；删除对账、Dry Run 和真实 Apply。

## 24. A3 通用验收条件

1. 每个可见按钮在矩阵中有唯一操作、权限和审计定义；
2. 无权限时前端和服务端行为一致；
3. 危险操作展示当前对象、机构、数据范围和影响；
4. 所有命令均处理成功、失败、处理中、冲突和无权限状态；
5. 保存操作真实更新状态，不只显示 Toast；
6. 列表筛选、分页和总数一致；
7. 详情页面使用 URL 资源 ID，刷新后上下文正确；
8. 操作永远作用于当前行或当前详情对象；
9. 敏感值默认脱敏，原值查看和导出被独立授权和审计；
10. 审计记录成功和失败，但不保存 Secret 或敏感原值；
11. 当前代码中的旧模型页面和文案在前端实施阶段全部移除；
12. A1–A3 产品合同完成不代表数据库、API 或 Doris 物理设计已经冻结。

## 25. 本工作包完成状态和下一步

| 工作项 | 状态 | 结果 |
| --- | --- | --- |
| A1 数据预检问题明细页面合同 | `CONFIRMED + IMPLEMENTED` | Route/Run 层级、问题汇总与明细、筛选、脱敏、原值查看、导出和到期状态已在前端 Mock 实现 |
| A2 无主键机构范围替换前端语义 | `CONFIRMED + IMPLEMENTED` | “每次全量 · 替换当前机构范围”的只读合同、运行确认、状态反馈和审计已实现 |
| A3 页面—操作—权限—审计矩阵 | `CONFIRMED + IMPLEMENTED` | 页面操作、`domain.action` 权限、C1/C2/S1 确认、审计、分页、详情和主要恢复路径已实现 |
| 前端代码实施 | `IMPLEMENTED` | 已通过 ESLint 与 Next.js 生产构建；当前仍使用 Mock 数据和前端状态 |
| REST API 合同 | `FROZEN_FOR_IMPLEMENTATION` | `FRONTEND_API_CONTRACT_V1.md` 已覆盖统一响应、分页、Revision、幂等、权限、审计、错误码、Export Job 和长任务状态 |
| Java 后端与服务端鉴权/审计 | `NOT_IMPLEMENTED` | 尚未依据 API 合同创建 Controller、DTO、Service、鉴权和服务端审计实现 |
| PostgreSQL / Doris / RabbitMQ 物理实现 | `IN_REVIEW` | 继续受目标元数据模型、预检明细介质和机构范围原子替换方案约束 |
| Flyway V1 | `NOT_AUTHORIZED` | 目标模型最终签字前不得创建或固化 |

下一工作包：

```text
C1：确认预检问题明细的物理存储、保留、查询和导出方案
C2：验证并冻结 Doris REPLACE_INSTITUTION_SCOPE 的机构范围原子替换方案
C3：完成账号权限、告警、外部 API、Quartz 等 P0 支撑对象及物理表字典 Review
C4：目标模型签字并取得实施授权后，依据 FRONTEND_API_CONTRACT_V1.md 生成 OpenAPI 和后端接口实现
```

状态边界：前端产品行为已经稳定，API 合同已经形成，但端到端系统仍未完成，不能标记为 `VERIFIED`。

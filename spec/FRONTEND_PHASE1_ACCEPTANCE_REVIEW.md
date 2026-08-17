# Phase 1 前端页面 / 导航 / 交互 / 文案验收 Review

> 状态：源码级产品整改已完成；运行级 lint/build 仍待真实执行  
> 日期：2026-08-17  
> 分支：`duhongx/dfetl-service/main`  
> 技术模型：`spec/PHASE1_FINAL_REVIEW.md`  
> 限制：本文只验收前端产品模型；不授权 Flyway V1 / Java 后端实施。

## 1. 验收结论

当前分两层结论：

```text
FRONTEND_PRODUCT_SOURCE_REVIEW = PASS
FRONTEND_RUNTIME_BUILD_VERIFICATION = BLOCKED_BY_GITHUB_ACTIONS_BUDGET
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_RUNTIME_VERIFICATION
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

`FRONTEND_PRODUCT_SOURCE_REVIEW = PASS` 表示：

- 当前激活前端的导航、URL、页面对象、交互入口和文案已经按冻结 Spec 收口；
- 不再把旧 Business System Instance / 多机构 Route / Task Version / Validation Policy 等废弃模型作为 Current UI；
- P-002 / P-003 已实际落到当前前端信息架构；
- 核心危险操作有明确确认边界；
- 运行历史展示使用 Execution Snapshot 语义，不以当前 Task 配置覆盖历史。

它**不等于** `npm run lint` / `npm run build` 已通过。

## 2. 当前前端信息架构

左侧导航固定：

```text
运行概览

接入资源
├── 医疗机构
├── 业务目录
├── 源端数据源
├── 目标端数据源
└── 医共体标准

采集关系
└── 机构采集路由

任务中心
├── 同步任务
├── 数据预检
└── 执行监控

数据校验
├── 校验总览
└── 校验工作台

运维管理
├── 告警通知
├── 日志中心
└── 操作审计

系统设置
├── 全局参数
├── 规范库配置
├── 外部授权
├── 类型映射
└── 账号管理
```

顶部右侧：

```text
Help → /docs
当前用户 → 个人中心
```

左侧导航**不增加“使用文档”**。

## 3. 真实 URL

当前路由：

```text
/dashboard
/access-resources/institutions
/access-resources/business-catalogs
/access-resources/source-datasources
/access-resources/target-datasources
/access-resources/datasets
/routes
/tasks/sync
/tasks/sync/:taskId
/tasks/precheck
/tasks/precheck/:runId
/tasks/executions
/tasks/executions/:executionId
/validation/overview
/validation/workbench
/operations/alerts
/operations/logs
/operations/audit
/settings/global
/settings/medical-registry
/settings/external-api
/settings/type-mapping
/settings/accounts
/docs
```

导航使用 Next App Router `usePathname + useRouter`，不再手工维护 `window.history.pushState`；Catch-all Route 用于直接刷新业务 URL。

## 4. 接入资源页面

### 4.1 医疗机构

已收口：

- 扁平机构；
- 稳定 Code；
- 新增/编辑/启停；
- 无引用可物理删除；
- 有 Source / Route / Task 引用时提示只能停用；
- 不显示父机构、业务系统实例或 Source 列。

### 4.2 业务目录

已收口：

- HIS / LIS / PACS / EMR 轻量分类；
- 新增/编辑/启停；
- Source 引用保护；
- 不表示真实业务系统部署实例。

### 4.3 Source Datasource

已收口：

- 一个 Source 唯一归属一家机构；
- 一个 Source 唯一归属一个 Business Catalog；
- `HOST_PORT / JDBC_URL` 条件表单；
- DB Type / Credential / Test / Enable 独立；
- 已被 Route 引用后 Institution / Business Catalog 在编辑器中锁定；若需改变归属应新建 Source；
- Password 不回显明文。

### 4.4 Target Datasource

已收口：

- 全局 Doris Target；
- 多 FE Endpoint；
- Endpoint/Target Test；
- Test Result 与业务启停独立；
- Password 不回显明文；
- 被历史引用时删除保护。

## 5. Dataset / Doris

`医共体标准` 页面已收口：

- 无普通“新增 Dataset”；
- 只允许“从规范库同步”；
- 展示当前 Version、字段数、业务键数、增量字段、Validation Override、Schedule Default、Message Enabled；
- 相同历史 Definition Hash 的复用语义在文案中明确；
- 普通同步**不自动创建/重建 Doris**；
- 提供明确的 `检查/创建 Doris ODS/RAW` 用户触发入口；
- 提供明确的 `重建 Doris ODS/RAW` 危险操作入口及确认。

## 6. Institution Route

已收口：

- 独立 Route 页面；
- 固定单机构；
- Dataset + Source + Schema/Object + Target；
- Business Catalog 由 Source 只读继承；
- Source 下拉只显示当前 Institution 所属 Source；
- Route `status` 与 `structure_status` 独立；
- Structure Check 不自动 Enable；
- 即使业务状态 ENABLED、结构 OUTDATED/FAILED，UI 仍可表达该事实；Task Gate 才拒绝不可用 Route；
- Route 配置编辑后生成/复用不可变 Route Version 的语义明确，并要求重新结构核对；
- Route 逻辑删除从 Current 列表移除，不把历史 Version/Execution/Precheck 当作级联删除对象。

## 7. Sync Task / Watermark / Operations

已收口：

- Task 固定业务身份 = Institution + Dataset；
- 无 Task Version / 发布 / 回退 / 机构组；
- 创建/编辑只允许选择同 Institution + Dataset 且 `ENABLED + PASSED` 的 Route；
- Task Kind / Write Mode / Doris Key Model / Incremental Field 由 Dataset 合同推导，只读展示；
- `MANUAL / EVERY_N_HOURS / CRON` 条件调度表单；
- `schedule_enabled` 与 `MANUAL` 保持独立；
- 无真实业务主键时前端阻止 `ROW_COUNT_CHECKSUM`；
- 活动 `sync_execution` 时阻止 Task 当前配置编辑、再次运行和逻辑删除；
- Pause 只改变 Schedule，不取消当前 Execution；
- Watermark 展示；
- Clear Watermark 有危险确认；
- Backfill 明确不推进正式 Watermark；
- Recollect 明确创建新的 Execution，从范围起点 / Batch 1 重读。

## 8. Execution / Batch / Snapshot

已新增真实 Execution Detail URL：

```text
/tasks/executions/:executionId
```

详情已展示：

- Execution Operation / Trigger / Scope / Range；
- Source / Loaded / Rejected Count；
- Load Batch；
- Doris Label；
- Doris Raw State；
- `COMMITTED != SUCCEEDED`；
- 只有 `VISIBLE + rejected=0` 才视为 Batch 成功；
- SYNC_GATE；
- Message Outbox；
- Task Revision / Dataset Version / Route Version；
- Validation Method / Source / Checksum Protocol；
- 非 Secret Source Runtime Snapshot；
- 非 Secret Target Runtime Snapshot；
- Message Policy Snapshot。

历史 Execution 详情不通过当前 Task 重算历史配置。

## 9. Precheck

已收口：

- 仅人工入口；
- 不出现自动调度；
- 同一 Route 只能一个活动 Run 的文案/交互边界明确；
- Precheck Run 独立于正式 Sync；
- 修复 Source 数据后重新创建新的 Precheck；
- Detail 只展示 `STRUCTURE / FIELD / COMPOSITE` Summary；
- 不展示/保存行号、业务键、原始值、修复值或样例；
- RAW 1 天清理、PostgreSQL Run/Summary 保留的文案明确。

## 10. Validation

已收口：

- Overview / Workbench 分离；
- `SYNC_GATE / MANUAL / MANUAL_RECHECK / SCHEDULED`；
- `ROW_COUNT / ROW_COUNT_CHECKSUM / DELETE_KEY_DIFF`；
- `DELETE_KEY_DIFF` 来源 FIXED；
- MISMATCH 为完成结果而非技术 FAILED；
- 无 Validation Disable；
- 无容差；
- 无 Validation Lookback；
- 无 Auto Revalidate / Auto Repair / Fail Block / Override Mode；
- Manual Recheck 明确使用原 Execution Context；
- Delete Reconciliation 有独立显示，并进入 Task 删除治理。

## 11. Message Outbox

Task Detail 已提供：

- Event ID；
- Execution；
- FULL / INCREMENTAL；
- Routing Key；
- Attempt；
- Published / Dead Letter 等状态；
- PUBLISHED / DEAD_LETTER 的人工重发入口。

人工重发固定：

```text
沿用 Event ID
→ 重置本轮 Attempt
→ 重新读取当前 Doris
→ 不修改 Execution
→ 不修改 Watermark
→ 不修改 Task Schedule
```

UI 不保存业务 Payload / 分页进度 / Attempt 明细。

## 12. Delete Snapshot / Reconciliation / Apply

Task Detail 已提供删除治理区域：

- 创建 Delete Snapshot；
- 显示 Baseline / Candidate / Difference；
- Delete Reconciliation 由 Validation Workbench 可追入 Task；
- Dry Run；
- Real Apply；
- Apply History。

真实 Apply 前端 Gate：

```text
DELETE_RECONCILIATION = COMPLETED + MISMATCH
AND difference_count > 0
AND 当前 Validation 已成功完成至少一次 Dry Run
AND 不存在同 Validation 的 PENDING/RUNNING/SUCCEEDED Real Apply
→ 第一次风险确认
→ 第二次最终确认
→ 才允许 Real Apply
```

P-004 的具体风险阈值尚未确认，因此前端明确提示“实现前确认”，不虚构默认阈值。

## 13. Alert / Log / Audit

### Alert

- Event / Rule / Channel 三个 Tab；
- Rule 支持 ALL / TASK Scope；
- Condition Operator；
- Severity；
- Rule ↔ Channel 多选；
- DINGTALK / WECOM；
- TEXT / MARKDOWN；
- Channel Test；
- Rule 可物理删除；
- Channel 被 Rule 引用时前端阻止删除；
- 历史 Event/Delivery 的 Snapshot 语义保留。

### Log

明确不记录：

```text
DB / RabbitMQ / API Secret
Authorization Header
HMAC Signature
未脱敏连接信息
```

### Audit

展示 Actor / Source / Operation / Target / Result / Time，和 `LOCAL_USER/EXTERNAL_CLIENT/SCHEDULER/SYSTEM` 产品语义一致。

## 14. System Settings

### Global Settings

只保留：

- 调度默认；
- `validation.default_method`；
- Precheck 全局并发。

不恢复旧 Validation Policy、Enable、Tolerance、Lookback、Auto Revalidate。

### Registry

- Registry Connection；
- Test；
- Manual Dataset Sync；
- Password Mask。

### External API

- Stable `client_id`；
- 可重复 `client_name`；
- ALL / SELECTED；
- SELECTED Institution 多选；
- Enable/Disable；
- Secret Reset；
- 不建设应用层 Rate Limit/Quota UI。

### Generic JDBC Mapping

- Profile / Version / Rule Code；
- Source DB / Type Pattern；
- Doris 建议类型；
- PASS/WARN/REJECT；
- 明确只用于诊断，不覆盖医疗 Field Contract。

## 15. P-002 Account / Profile

最终前端入口：

```text
系统设置 → 账号管理
```

账号管理已有：

- 列表；
- 新增；
- 启用/停用；
- 重置密码；
- 当前登录账号禁止停用自己；
- 最后一个启用账号禁止停用；
- 不提供物理删除；
- 不建设 RBAC / Role / Permission / Institution Data Permission。

顶部当前用户进入“个人中心”：

- 只维护当前账号 Display Name；
- 可修改当前账号密码；
- 修改密码要求 Current Password + New Password + Confirm；
- 修改密码成功后既有 Refresh Token 必须失效；
- 个人中心不替代系统级账号管理。

## 16. P-003 Help / Docs

最终入口：

```text
顶部右侧 Help → /docs
```

已满足：

- `/docs` 是真实 Route；
- 左侧无“使用文档”；
- Docs 汇总配置主线、运行边界和危险操作；
- 危险操作包括 Clear Watermark / Delete Apply / Doris Rebuild / Secret Reset。

## 17. 废弃模型防回归

Current UI 不得重新引入：

```text
Business System Instance
Business System Instance ↔ Institution/DataSource
Multi-Institution Route
Institution Group
Task Version
Task Release / Rollback
Global/Dataset/Task Validation Policy Table
Validation Disable
Validation Tolerance
Validation Lookback
Task-level Message Policy
Redis Stream
Standard Task CUSTOM_SQL
RBAC
```

源码中如果出现这些词，只能是“明确说明不支持/不再出现”的负向产品说明，不得作为可编辑字段、菜单、Page State 或当前数据模型。

## 18. 构建与运行验证

已增加：

```text
.github/workflows/frontend-check.yml
```

执行：

```text
npm ci
npm run lint
npm run build
```

第一次真实 GitHub Actions Run 已创建，但 GitHub 明确返回：

```text
The job was not started because an Actions budget is preventing further use.
```

因此当前不能把：

```text
npm run lint = PASS
npm run build = PASS
```

写入验收结论。

本地当前环境没有可用 npm 依赖缓存且不能联网安装依赖，也无法替代执行真实 Next Build。

## 19. G-001 前剩余门槛

当前产品源码侧已完成整改；G-001 前仍需：

```text
1. GitHub Actions 预算恢复，或其他具有完整 web/node_modules 的受控环境执行
   npm ci
   npm run lint
   npm run build

2. 实际启动 Next 前端，逐 URL 浏览器检查：
   - Layout / responsive
   - Refresh / Back / Forward
   - Modal / Form / Dangerous Confirm
   - Empty / Error / Loading / Disabled State

3. 只有上述运行级验收通过后，才能将：
   FRONTEND_RUNTIME_BUILD_VERIFICATION = PASS
   PHASE1_OVERALL = PASS（仍需用户 G-001 明确签字）
```

在此之前继续保持：

```text
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

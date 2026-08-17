# Phase 1 前端产品验收 Review

> 状态日期：2026-08-17  
> 分支：`duhongx/dfetl-service/main`  
> 业务基线：`spec/PRODUCT_AND_BUSINESS_DECISIONS.md`  
> Final Review：`spec/PHASE1_FINAL_REVIEW.md`

## 1. 当前验收状态

```text
FRONTEND_PRODUCT_SOURCE_REVIEW = IN_PROGRESS
FRONTEND_RUNTIME_BUILD_VERIFICATION = BLOCKED_BY_GITHUB_ACTIONS_BUDGET
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

当前不能把“页面已经可见”解释为前端 100% 验收完成，也不能把 GitHub Actions 因预算无法启动解释为 lint/build 通过。

## 2. 路由与信息架构

当前现行前端只保留：

```text
web/app/etl/app-shell-final.tsx
web/app/etl/editor-panel-v2.tsx
web/app/etl/dataset-policy-editor.tsx
web/app/etl/operation-editor.tsx
web/app/etl/runtime-panels.tsx
```

已删除旧的：

```text
app-shell.tsx
app-shell-v2.tsx
editor-panel.tsx
```

从而不再保留第二套旧菜单/旧模型实现。

路由规则：

- `/` 显式 redirect 到 `/dashboard`；
- 现行业务 URL 使用显式 Next.js App Router Route，支持直接刷新/回退；
- 未定义的顶层路径由 Catch-all Route `notFound()`，不再静默回 Dashboard；
- Task / Execution / Precheck Detail 使用显式动态 Route；
- P-002 固定：`系统设置 → 账号管理`；
- P-003 固定：`顶部右侧 Help → /docs`；
- 左侧业务导航不增加“使用文档”。

## 3. 已完成的产品模型清理

当前前端不再出现：

```text
Business System Instance
Multi-Institution Route
Task Version
Global/Dataset/Task Validation Policy 表述
Validation Disable / Tolerance / Lookback / Override Mode
Task-level Message Policy
Redis Stream
RBAC
Standard Task CUSTOM_SQL
Institution Tree
```

主线固定为：

```text
Institution + Business Catalog
→ Source / Target
→ Standard Dataset + Immutable Dataset Version
→ Single-Institution Route + Immutable Route Version
→ Sync Task Current Config
→ Execution / Validation Runtime Snapshot
```

## 4. Resource 页面整改状态

### 4.1 Institution / Business Catalog

已完成：

- 扁平 Institution；
- Stable Code；
- CRUD/启停；
- 未引用可物理删除，有引用时提示只能停用；
- Business Catalog 只是 HIS/LIS/PACS/EMR 轻量分类。

### 4.2 Source Datasource

已完成：

- 一个 Source 固定归属一家 Institution + 一个 Business Catalog；
- 被 Route 引用后归属机构/业务目录在编辑器中锁定；
- HOST_PORT / JDBC_URL 跨字段校验；
- Port `1..65535`；
- SSL / Read Only；
- Query / Connect / Socket Timeout；
- Pool Max Size；
- Password 不回显明文；
- Source Test Status 与业务 ENABLED/DISABLED 独立；
- 测试时间/错误摘要展示；
- 编辑连接配置后 Test Status 回到 UNTESTED；
- Route Source 不能跨 Institution。

### 4.3 Target Datasource / FE

已完成：

- Target 为全局资源；
- FE 不是固定两个输入，而是可动态新增/移除；
- 同 Target 下 `(host,query_port)` 前端唯一校验；
- Query / HTTP Port `1..65535`；
- FE 可独立启停；
- FE 单点 Test；
- Target 聚合 Test；
- Endpoint/Target 测试时间与错误摘要；
- Target/FE 启停与 Test Result 保持独立；
- 未引用 Target 可物理删除，有引用时只能停用。

## 5. Dataset / Route / Task

### 5.1 Dataset

已完成：

- Dataset 不提供手工新增；
- 只保留“从规范库同步”；
- Doris ODS/RAW 检查/创建和重建为显式入口；
- 普通同步不会自动建表/重建；
- Validation Override 只有 `INHERIT/ROW_COUNT/ROW_COUNT_CHECKSUM`；
- Dataset Message Policy 仅 Dataset 级；
- Message Routing Key 使用冻结的下划线值；
- Dataset Policy Editor 已校验 Schedule/Timezone/Message Rate/Page Size 的合法性。

仍需收口：

```text
Dataset Sync Policy / Message Policy 的完整当前值
→ 保存后重新打开必须保持
→ 新建 Task 必须真正读取其 fetch/delay/lookback/schedule 默认
```

当前不能因为页面能编辑就把该项判定为完成。

### 5.2 Route

已完成：

- 固定 `Institution + Dataset` 单机构 Route；
- Source 只允许当前 Institution；
- Business Catalog 由 Source 只读带出；
- Source/Schema/Object/Target 可编辑；
- 编辑后结构状态变 OUTDATED；
- Structure Status 与 Business Status 独立；
- Task 只允许使用当前 `ENABLED + PASSED` Route；
- 逻辑删除保留历史语义。

### 5.3 Task Current Config

已完成：

- Task 固定身份为 Institution + Dataset；
- 无 Task Version；
- Fetch Size / Upper Bound Delay / Lookback 已成为真实当前配置；
- Schedule Mode / Interval / 最终 Cron / Timezone / Source 已成为真实当前配置；
- EVERY_N_HOURS 生成并固化确定性的错峰 Cron；
- Task Detail 展示实际当前参数，不再只显示一条 Schedule Label；
- Task 编辑存在活动 Execution 时拒绝；
- Task 手动运行同时拒绝活动 Execution 和活动独立 Validation；
- Task Run Gate 检查 Dataset/Route/Source/Target/启用 FE；
- Backfill 不推进正式 Watermark；
- Recollect 创建新 Execution；
- 清除 Watermark 有危险确认；
- Task 逻辑删除不级联历史。

## 6. Precheck / Validation / Execution

### Precheck

已完成：

- 只人工启动；
- 同 Route 活动 Precheck 互斥；
- STRUCTURE/FIELD/COMPOSITE Summary；
- 不展示行级业务键/样例/原始值；
- 正式同步仍重新读取真实 Source；
- RAW 1 天清理文案明确。

### Validation

已完成：

- `COMPLETED + PASS/MISMATCH` 与技术 FAILED 分开；
- SYNC_GATE / MANUAL_RECHECK / 独立 Validation / DELETE_RECONCILIATION 分开；
- 独立 Validation 与同步启动互斥；
- 独立 Validation 之间互斥；
- 无真实业务主键时禁用 ROW_COUNT_CHECKSUM；
- DELETE_RECONCILIATION 固定 DELETE_KEY_DIFF / FIXED；
- 不出现关闭/容差/Validation Lookback 等旧配置。

### Execution Snapshot

已完成：

- 历史详情不回读当前 Task 冒充快照；
- 展示 Task Revision / Institution / Dataset Version / Route Version；
- 展示本次实际 Task Kind / Write Mode / Key Model / Incremental Field；
- 展示本次 Fetch Size / Delay / Lookback；
- Source Runtime Snapshot 展示 DB/Connection Mode/Endpoint/Username/SSL/ReadOnly/Timeout；
- Target Runtime Snapshot 展示结构化 FE Endpoint；
- Message Policy Snapshot 展示本次生效的 Source/Tenant/Routing Key/Topic/Key Template/Rate/Page Size；
- 不展示数据库/RabbitMQ/API Secret；
- Checksum Protocol 只在 ROW_COUNT_CHECKSUM 时存在。

## 7. Outbox / Delete Governance

已完成：

- Outbox 不保存业务 Payload/分页进度；
- FULL / INCREMENTAL 发布范围映射；
- 人工重发沿用 Event ID；
- 重发重新读取当前 Doris；
- 重发不改原 Execution/Watermark/Task 调度；
- Delete Snapshot 活动互斥；
- DELETE_RECONCILIATION 发现差异不自动删 ODS；
- Dry Run 可重复；
- 真实 Delete Apply 必须先成功 Dry Run；
- 二次危险确认；
- 已有 PENDING/RUNNING/SUCCEEDED 的真实 Apply 禁止重复发起；
- P-004 风险阈值没有在前端虚构默认值。

## 8. Support / Settings

已完成：

- Alert Rule ↔ Channel 使用稳定 Channel ID，不依赖可编辑展示名称；
- Alert Channel 当前仍被 Rule 引用时阻止删除；
- External Client SELECTED 内部使用 Institution Code/ID，不依赖展示名称；
- Generic JDBC Mapping 可物理删除；
- Account 入口/能力与 P-002 一致；
- 当前账号不能停用自己；
- 最后一个启用账号不能停用；
- Secret/Password Reset 有危险确认；
- `/docs` 与 P-003 一致。

仍需收口：

```text
Global Settings / Registry Settings
→ Port、Interval、Concurrency 等输入的范围校验
→ 不能继续使用“任意输入 + 点击后只 toast”的假保存交互
```

## 9. 仍需完成的 Source Review 项

当前只保留以下源码级阻塞：

1. Dataset Policy 完整当前值往返保存，并作为新 Task 默认输入；
2. Global/Registry Settings 前端范围校验和明确保存错误态；
3. 对本轮大型 TypeScript/TSX 改动做最终静态断引用扫描。

完成这三项后，才可以把：

```text
FRONTEND_PRODUCT_SOURCE_REVIEW = PASS
```

## 10. Runtime / Build 验证

GitHub Workflow：

```text
.github/workflows/frontend-check.yml
npm ci
npm run lint
npm run build
```

当前 Workflow 因 GitHub Actions 预算/额度原因无法启动 Job，步骤没有真正执行。因此：

```text
FRONTEND_RUNTIME_BUILD_VERIFICATION = BLOCKED_BY_GITHUB_ACTIONS_BUDGET
```

在恢复 Actions 预算或获得等价可执行依赖环境前：

- 不声明 `npm run lint` 已通过；
- 不声明 `npm run build` 已通过；
- 不把“Workflow 未运行”解释成源码失败；
- 也不把它解释成验证通过。

## 11. Phase 1 门槛

当前仍保持：

```text
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

下一步：

```text
完成第 9 节剩余 Source Review
→ 补齐 lint/build 运行验证
→ 逐页核对真实 URL、空态、错误态、危险确认
→ Frontend 与冻结 Spec 100% 一致
→ G-001 最终签字
```

在此之前不得创建/固化 Flyway V1，也不得推进 Java Entity/Repository/Service 批量整改。

# Frontend Remediation Progress — 2026-08-17

> 分支：`main`  
> 目标：前端页面、导航、交互和文案与已冻结 Phase 1 Spec 100% 对齐。  
> 后端门槛：`DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED`。

## 1. 已完成源码整改

### Router / IA

- `/` 显式进入 `/dashboard`。
- 固定业务页面使用显式 App Router Route，支持直接刷新/回退。
- Task / Precheck / Execution Detail 使用显式动态 URL。
- 未知顶层 URL 返回 404，不再静默落 Dashboard。
- P-002：`系统设置 → 账号管理`。
- P-003：`顶部右侧 Help → /docs`，左侧无“使用文档”。
- 删除旧 `app-shell.tsx` / `app-shell-v2.tsx` / `editor-panel.tsx`，只保留当前模型实现。

### Resource

- Institution / Business Catalog CRUD、启停、引用保护。
- Source 固定一个 Institution + Business Catalog；被 Route 引用后归属字段锁定。
- Source HOST_PORT / JDBC_URL、Port、SSL、Read Only、Timeout、Pool 等当前连接配置补齐。
- Source Test Status 与业务状态独立，显示测试时间/错误摘要。
- Target FE 改为动态增删，不再固定两个 FE。
- Target FE 支持独立启停、单点测试；Target 支持聚合测试。
- Target/FE 测试时间与错误摘要补齐。

### Route / Task

- Route 固定单机构；Business Catalog 由 Source 只读带出。
- Route Source 前端阻止跨 Institution。
- Route Structure Status 与 Business Status 独立。
- Task 只允许绑定同 Institution+Dataset 且 ENABLED+PASSED 的 Route。
- Task 当前配置补齐 Fetch Size / Delay / Lookback / Schedule Mode / Interval / Final Cron / Timezone / Source。
- EVERY_N_HOURS 使用稳定错峰算法生成最终 Cron。
- Task 编辑存在活动 Execution 时拒绝。
- 手动运行同时检查活动 Execution 和活动独立 Validation。
- Backfill / Recollect / Clear Watermark 边界和危险确认已收口。

### Validation / Runtime / Snapshot

- 独立 Validation 与同步启动互斥；独立 Validation 之间互斥。
- Manual Recheck 固定基于历史 Execution，不读当前 Task 冒充原快照。
- Execution Detail 展示实际 Task 参数。
- Source Runtime Snapshot 展示 DB/Connection Mode/Endpoint/Username/SSL/ReadOnly/Timeout。
- Target Runtime Snapshot 展示结构化 FE Endpoint。
- Message Policy Snapshot 展示本次生效的非 Secret 参数。
- Snapshot 页面不展示数据库/RabbitMQ/API Secret。

### Outbox / Delete / Support

- Outbox 重发沿用 Event ID、重读当前 Doris、不改原 Execution/Watermark/调度。
- Delete Snapshot / Dry Run / Real Apply / 二次确认 / 重复真实 Apply 防护已落实。
- P-004 风险阈值没有虚构默认值。
- Alert Rule ↔ Channel 使用稳定 Channel ID。
- External Client SELECTED 使用 Institution Code/ID。
- Generic JDBC Mapping 增加物理删除入口。
- Account 管理与 P-002 一致，不建设 RBAC。

## 2. 当前仍未关闭的 Source Review 项

### A. Dataset Policy 完整往返

必须最终保证：

```text
Dataset Sync/Message Policy 完整当前值
→ 保存
→ 重新打开仍保持
→ 新建 Task 真正读取 fetch/delay/lookback/schedule 默认
→ Dataset=INHERIT 时读取 Global Default
```

不能只保存 `scheduleDefault` 展示字符串和 `messageEnabled` 开关。

### B. Global / Registry Settings 输入校验

必须最终保证：

- Schedule interval 合法；
- CRON 必填；
- 非 MANUAL Timezone 必填；
- Precheck 全局并发为正整数；
- Registry Host/DB/User 必填；
- Registry Port `1..65535`；
- Password 留空表示不修改且不回显明文；
- 保存失败显示明确前端错误，不使用“任意输入 + 只 toast”的假保存。

### C. 最终断引用 / 类型扫描

完成 A/B 后重新检查：

```text
旧 Shell / Editor import
旧 Snapshot 字段
Source/Target 新必填字段对象字面量
TaskRow 新字段调用点
Alert Channel Name 关系
External Institution Name 关系
```

## 3. Build / Runtime Verification

Workflow：

```text
.github/workflows/frontend-check.yml
npm ci
npm run lint
npm run build
```

GitHub Actions 当前因预算/额度无法启动 Job，因此不得宣称 Actions 已验证通过。

如果本地私有仓库凭据与 npm 依赖环境可用，应补跑：

```text
npm ci
npm run lint
npm run build
启动 Next Server
检查全部固定业务 URL / 动态详情 URL
确认未知 URL = 404
```

## 4. 当前结论

```text
FRONTEND_PRODUCT_SOURCE_REVIEW = IN_PROGRESS
FRONTEND_RUNTIME_BUILD_VERIFICATION = BLOCKED_BY_EXECUTION_ENVIRONMENT
PHASE1_OVERALL = BLOCKED_BY_FRONTEND_ACCEPTANCE
DATABASE_BACKEND_IMPLEMENTATION = NOT_AUTHORIZED
```

完成第 2 节三项源码收口，并补齐运行验证后，才能更新前端 100% 验收结论并进入 G-001 最终签字。

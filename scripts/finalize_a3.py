from __future__ import annotations

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def update_app_shell() -> None:
    path = Path("web/app/etl/app-shell-final.tsx")
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        'import { hasPermission, pagePermission, resolvePermissions } from "./permissions";\nimport { parseLocation, pathFor } from "./routing";',
        'import { hasPermission, pagePermission, resolvePermissions } from "./permissions";\nimport {\n'
        '  AlertsManagementPage,\n'
        '  AuditManagementPage,\n'
        '  DorisTablesPage,\n'
        '  ExternalClientsPage,\n'
        '  GlobalSettingsPage,\n'
        '  LogsManagementPage,\n'
        '  RegistrySettingsPage,\n'
        '  SecurityManagementPage,\n'
        '  TypeMappingPage,\n'
        '  ValidationPolicyPage,\n'
        '} from "./a3-management-pages";\n'
        'import { parseLocation, pathFor } from "./routing";',
        "AppShell A3 imports",
    )

    text = replace_once(
        text,
        '  const [externalClients] = useState(externalClientSeed);',
        '  const [externalClients, setExternalClients] = useState(externalClientSeed);',
        "External Client state setter",
    )

    start = '  const alertsPage = () =>'
    end = '  const docsPage = () =>'
    if text.count(start) != 1 or text.count(end) != 1:
        raise RuntimeError("A3 page replacement markers are not unique")
    before, remainder = text.split(start, 1)
    _, after = remainder.split(end, 1)
    management_block = "\n".join(
        [
            '  const managementProps = { can, deny, ask, setToast, recordAudit };',
            '  const alertsPage = () => <AlertsManagementPage events={alertSeed} tasks={tasks} {...managementProps}/>;',
            '  const logsPage = () => <LogsManagementPage {...managementProps}/>;',
            '  const auditPage = () => <AuditManagementPage rows={auditRows} {...managementProps}/>;',
            '  const globalSettingsPage = () => <GlobalSettingsPage {...managementProps}/>;',
            '  const registrySettingsPage = () => <RegistrySettingsPage {...managementProps}/>;',
            '  const validationPolicyPage = () => <ValidationPolicyPage {...managementProps}/>;',
            '  const dorisTablesPage = () => <DorisTablesPage datasets={datasets} {...managementProps}/>;',
            '  const externalApiPage = () => <ExternalClientsPage clients={externalClients} setClients={setExternalClients} institutions={institutions} {...managementProps}/>;',
            '  const mappingPage = () => <TypeMappingPage {...managementProps}/>;',
            '  const securityPage = () => <SecurityManagementPage accounts={accounts} setAccounts={setAccounts} roles={roles} setRoles={setRoles} currentAccountId={currentAccountId} openCreate={(kind) => openCreate(kind)} openEdit={(kind,id,initial) => openEdit(kind,id,initial)} {...managementProps}/>;',
            '',
            '',
        ]
    )
    text = before + management_block + end + after

    text = replace_once(text, '      case "globalSettings": return settingsPage("global");', '      case "globalSettings": return globalSettingsPage();', "Global settings render")
    text = replace_once(text, '      case "registrySettings": return settingsPage("registry");', '      case "registrySettings": return registrySettingsPage();', "Registry render")
    text = replace_once(text, '      case "validationPolicy": return settingsPage("validation");', '      case "validationPolicy": return validationPolicyPage();', "Validation policy render")
    text = replace_once(text, '      case "dorisTables": return settingsPage("doris");', '      case "dorisTables": return dorisTablesPage();', "Doris tables render")

    text = replace_once(
        text,
        '<span className="top-status"><i/>Mock 数据</span><select value={currentAccountId} onChange={(event) => setCurrentAccountId(event.target.value)} aria-label="切换模拟账号">{accounts.filter((item) => item.enabled).map((item) => <option key={item.id} value={item.id}>{item.username}</option>)}</select><button type="button" className="user" onClick={() => openEdit("profile",currentAccountId,{username:currentAccount?.username ?? "",displayName:currentAccount?.displayName ?? "",currentPassword:"",newPassword:"",confirmPassword:""})}><UserOutlined/><span>{currentAccount?.displayName ?? "未登录"}</span></button>',
        '<span className="top-status"><i/>Mock 数据</span><select value={currentAccountId} onChange={(event) => setCurrentAccountId(event.target.value)} aria-label="切换模拟账号"><option value="">未登录</option>{accounts.filter((item) => item.enabled).map((item) => <option key={item.id} value={item.id}>{item.username}</option>)}</select>{currentAccount && <button type="button" className="user" onClick={() => openEdit("profile",currentAccountId,{username:currentAccount.username,displayName:currentAccount.displayName,currentPassword:"",newPassword:"",confirmPassword:""})}><UserOutlined/><span>{currentAccount.displayName}</span></button>}{currentAccount && <button type="button" className="help" onClick={() => ask("退出登录","确认退出当前会话？",() => { recordAudit("session.logout","LOGOUT",currentAccountId,"SUCCESS","session cleared"); setCurrentAccountId(""); setToast("已退出登录"); },false,"确认退出")}>退出</button>}',
        "Profile and logout controls",
    )

    text = replace_once(
        text,
        '<footer><strong>阶段 1 · IN_PROGRESS</strong><small>前端产品合同实施中；数据库和后端仍未授权</small></footer>',
        '<footer><strong>A3 前端产品行为已稳定</strong><small>REST API Contract V1 已冻结；后端与数据库仍未实施</small></footer>',
        "Sidebar status",
    )

    text = replace_once(
        text,
        '<li>敏感查看、导出和权限分配必须审计。</li></ul>',
        '<li>敏感查看、导出和权限分配必须审计。</li><li>真实 REST API 边界见 <code>spec/FRONTEND_API_CONTRACT_V1.md</code>。</li></ul>',
        "Docs API contract link",
    )

    path.write_text(text, encoding="utf-8")


def update_tasks() -> None:
    path = Path("spec/TASKS.md")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '> A1–A3 产品合同：`CONFIRMED`；前端代码实施：`PENDING`；权威合同见 `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md`  ',
        '> A1–A3 产品合同：`CONFIRMED`；前端产品交互：`IMPLEMENTED`，真实 API/后端：`NOT_IMPLEMENTED`；权威合同见 `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` 和 `spec/FRONTEND_API_CONTRACT_V1.md`  ',
        "TASKS top frontend status",
    )
    text = replace_once(
        text,
        '> 前端实现状态：`PENDING`  ',
        '> 前端实现状态：`IMPLEMENTED`；A3 页面交互、权限、确认、审计和分页已完成，真实 API 接入未完成  ',
        "TASKS package status",
    )
    for code in ("B1", "B2", "B3", "B4", "B5"):
        old = f'- [ ] `{code}`：'
        new = f'- [x] `{code}`：'
        if old not in text:
            raise RuntimeError(f"TASKS {code} unchecked marker not found")
        text = text.replace(old, new, 1)
    text = replace_once(
        text,
        '- [x] `B5`：按 A3 矩阵逐页消除空按钮和旧模型交互。',
        '- [x] `B5`：按 A3 矩阵逐页消除空按钮和旧模型交互。\n'
        '- [x] `API-001`：生成并冻结 `spec/FRONTEND_API_CONTRACT_V1.md`，覆盖分页、Revision、幂等、权限、审计、导出任务和长任务状态。',
        "TASKS API contract task",
    )
    text = replace_once(
        text,
        'A1–A3 勾选完成只表示产品合同完成；B1–B5 未完成前，不得将前端状态标记为 `IMPLEMENTED` 或 `VERIFIED`。',
        'A1–A3 与 B1–B5 已完成前端 Mock 产品行为实现，并以 ESLint 和 Next.js 生产构建验证；真实 REST API、服务端鉴权/审计、数据库和外部组件尚未实施，因此只能标记前端产品交互为 `IMPLEMENTED`，不能标记端到端系统为 `VERIFIED`。',
        "TASKS completion boundary",
    )
    path.write_text(text, encoding="utf-8")


def update_frontend_contract() -> None:
    path = Path("spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '> 前端实现状态：`NOT_IMPLEMENTED`  ',
        '> 前端实现状态：`IMPLEMENTED`；A3 Mock 产品行为已通过 ESLint 与 Next.js 生产构建，真实 API 接入未完成  ',
        "Frontend contract status",
    )
    start = '## 23. 当前前端实现差异清单\n'
    end = '\n## 24. A3 通用验收条件'
    if text.count(start) != 1 or text.count(end) != 1:
        raise RuntimeError("Frontend contract section 23 markers are not unique")
    before, remainder = text.split(start, 1)
    _, after = remainder.split(end, 1)
    section = "\n".join(
        [
            '## 23. A3 前端实施结果',
            '',
            '截至 2026-08-17，以下产品行为已经在前端 Mock 中完成：',
            '',
            '- 业务系统实例、实例—机构和实例—Source 多对多；',
            '- 多机构采集链路和不可变 Route Version；',
            '- 稳定 Task 身份、不可变 Task Version 和固定 Execution 上下文；',
            '- Route → Precheck Run → 汇总/明细、脱敏、原值查看与导出；',
            '- 无主键“每次全量 · 替换当前机构范围”文案和确认；',
            '- `domain.action` 权限、403、C1/C2/S1 确认和前端审计 Mock；',
            '- 告警事件/规则/通道/投递重试；',
            '- 日志筛选、分页、详情、安全受限查看和导出任务；',
            '- 审计筛选、分页、详情和敏感导出；',
            '- 全局参数、医共体数据模型、校验策略的 Revision、保存和恢复默认；',
            '- Doris 实际/期望结构比较、DDL 预览、创建/重建和历史；',
            '- External Client 创建、授权范围、启停、一次性 Secret、重置和请求日志；',
            '- Generic Mapping 与医疗字段转换合同版本；',
            '- 账号、角色、权限分配、账号启停、密码重置和角色删除保护；',
            '- 个人资料、修改密码和退出交互。',
            '',
            '真实服务端接口合同已经冻结在：',
            '',
            '```text',
            'spec/FRONTEND_API_CONTRACT_V1.md',
            '```',
            '',
            '该合同覆盖统一响应、分页、Revision/ETag、幂等、权限、审计、错误码、Export Job、轮询/SSE 和全部页面接口。前端实现完成不代表 Java、PostgreSQL、Doris、RabbitMQ 或 Flyway 已完成。',
            '',
        ]
    )
    text = before + section + end + after
    path.write_text(text, encoding="utf-8")


def update_spec_index() -> None:
    path = Path("spec/README.md")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '| A1–A3 前端产品合同 | `CONFIRMED` | 预检明细页面、无主键范围替换前端语义、页面—操作—权限—审计矩阵已确认；前端代码尚未实施。 |',
        '| A1–A3 前端产品合同 | `IMPLEMENTED` | A3 Mock 产品行为已完成并通过 ESLint/Next.js 生产构建；真实 API 和服务端尚未实施。 |',
        "README frontend contract status",
    )
    text = replace_once(
        text,
        '| 前端产品整改 | `PENDING` | 下一工作包按 A1–A3 合同整改路由、菜单、页面、权限公共层和旧模型交互。 |',
        '| 前端产品整改 | `IMPLEMENTED` | 页面、操作、权限、确认、审计、分页和主要失败/空状态已完成；真实 API 接入进入下一阶段。 |',
        "README frontend remediation status",
    )
    text = replace_once(
        text,
        '| `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` | 当前有效、产品合同 | 新增 | 冻结 A1 预检问题明细页面、A2 无主键范围替换前端语义和 A3 页面—操作—权限—审计矩阵；代码尚未实施。 |',
        '| `spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md` | 当前有效、产品合同 | 新增并持续维护 | 冻结 A1–A3 产品合同；前端 Mock 行为已实现，服务端仍待实施。 |\n'
        '| `spec/FRONTEND_API_CONTRACT_V1.md` | 当前有效、API 合同 | 新增 | 冻结页面到 REST API、分页、Revision、幂等、权限、审计、错误码、导出任务和长任务状态合同。 |',
        "README API contract index",
    )
    if "## 12. 2026-08-17 A3 交互与 API 合同完成" not in text:
        text += "\n" + "\n".join(
            [
                '## 12. 2026-08-17 A3 交互与 API 合同完成',
                '',
                '本次完成 A3 剩余逐页交互，并新增 `FRONTEND_API_CONTRACT_V1.md`。前端产品行为已经稳定，可以进入 Mock Service → 真实 API 的逐领域替换阶段。',
                '',
                '状态边界：',
                '',
                '```text',
                '前端产品交互：IMPLEMENTED',
                'REST API 合同：FROZEN_FOR_IMPLEMENTATION',
                'Java 后端接口：NOT_IMPLEMENTED',
                '服务端鉴权与审计：NOT_IMPLEMENTED',
                'PostgreSQL / Doris / RabbitMQ：NOT_IMPLEMENTED',
                'Flyway V1：NOT_AUTHORIZED',
                '```',
                '',
            ]
        )
    path.write_text(text, encoding="utf-8")


def verify() -> None:
    shell = Path("web/app/etl/app-shell-final.tsx").read_text(encoding="utf-8")
    assert "AlertsManagementPage" in shell
    assert "settingsPage(" not in shell
    assert "ExternalClientsPage" in shell
    assert "SecurityManagementPage" in shell
    assert "退出登录" in shell
    assert "FRONTEND_API_CONTRACT_V1.md" in Path("spec/README.md").read_text(encoding="utf-8")
    assert '- [x] `B5`' in Path("spec/TASKS.md").read_text(encoding="utf-8")


def main() -> None:
    update_app_shell()
    update_tasks()
    update_frontend_contract()
    update_spec_index()
    verify()


if __name__ == "__main__":
    main()

"use client";

import { useEffect, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import {
  AlertOutlined,
  ApartmentOutlined,
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HomeOutlined,
  LinkOutlined,
  LockOutlined,
  MonitorOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  SwapOutlined,
  SyncOutlined,
  TableOutlined,
  ToolOutlined,
  UserOutlined,
} from "@ant-design/icons";
import type { AppPage } from "./etl/model";
import {
  accountSeed,
  alertSeed,
  auditSeed,
  businessCatalogSeed,
  datasetSeed,
  executionSeed,
  externalClientSeed,
  institutionsSeed,
  precheckSeed,
  routeSeed,
  sourceSeed,
  targetSeed,
  taskSeed,
  validationSeed,
} from "./etl/mock-data";
import { parseLocation, pathFor } from "./etl/routing";

type NavItem = { page: AppPage; label: string; icon: ReactNode };
type ModalState = { title: string; message: ReactNode; confirmLabel?: string; onConfirm?: () => void; danger?: boolean } | null;

const navGroups: Array<{ title: string; icon: ReactNode; items: NavItem[] }> = [
  { title: "接入资源", icon: <DatabaseOutlined />, items: [
    { page: "institutions", label: "医疗机构", icon: <ApartmentOutlined /> },
    { page: "businessCatalogs", label: "业务目录", icon: <TableOutlined /> },
    { page: "sourceDatasources", label: "源端数据源", icon: <CloudServerOutlined /> },
    { page: "targetDatasources", label: "目标端数据源", icon: <DatabaseOutlined /> },
    { page: "datasets", label: "医共体标准", icon: <FileTextOutlined /> },
  ] },
  { title: "采集关系", icon: <LinkOutlined />, items: [
    { page: "routes", label: "机构采集路由", icon: <LinkOutlined /> },
  ] },
  { title: "任务中心", icon: <SwapOutlined />, items: [
    { page: "tasks", label: "同步任务", icon: <SwapOutlined /> },
    { page: "precheck", label: "数据预检", icon: <FileSearchOutlined /> },
    { page: "monitor", label: "执行监控", icon: <MonitorOutlined /> },
  ] },
  { title: "数据校验", icon: <SafetyCertificateOutlined />, items: [
    { page: "validationOverview", label: "校验总览", icon: <SafetyCertificateOutlined /> },
    { page: "validationWorkbench", label: "校验工作台", icon: <FileSearchOutlined /> },
  ] },
  { title: "运维管理", icon: <ToolOutlined />, items: [
    { page: "alerts", label: "告警通知", icon: <AlertOutlined /> },
    { page: "logs", label: "日志中心", icon: <FileTextOutlined /> },
    { page: "audit", label: "操作审计", icon: <FileSearchOutlined /> },
  ] },
  { title: "系统设置", icon: <SettingOutlined />, items: [
    { page: "globalSettings", label: "全局参数", icon: <SettingOutlined /> },
    { page: "registrySettings", label: "规范库配置", icon: <DatabaseOutlined /> },
    { page: "externalApi", label: "外部授权", icon: <ApiOutlined /> },
    { page: "mappingRules", label: "类型映射", icon: <ToolOutlined /> },
    { page: "accounts", label: "账号管理", icon: <UserOutlined /> },
  ] },
];

const titles: Partial<Record<AppPage, [string, string]>> = {
  dashboard: ["运行概览", "查看接入、Route、同步、预检、校验和告警的当前状态。"],
  institutions: ["医疗机构", "扁平维护医共体机构；不建立父子机构、业务系统实例或厂商编码映射。"],
  businessCatalogs: ["业务目录", "维护 HIS / LIS / PACS / EMR 等轻量业务分类。"],
  sourceDatasources: ["源端数据源", "每个 Source 只归属一家机构和一个业务目录。"],
  targetDatasources: ["目标端数据源", "维护全局 Doris 逻辑目标和多个 FE 端点。"],
  datasets: ["医共体标准", "Dataset 只允许从规范库人工同步，不提供手工新增。"],
  routes: ["机构采集路由", "机构 + Dataset + Source + Schema/Object + Target；业务目录由 Source 只读带出。"],
  tasks: ["同步任务", "固定身份为机构 + Dataset；直接编辑当前配置，不建立 Task Version。"],
  taskDetail: ["同步任务详情", "查看当前配置、水位、运行操作和历史 Execution。"],
  precheck: ["数据预检", "仅人工启动；用于发现真实源数据质量问题，与正式同步严格分离。"],
  precheckDetail: ["预检运行详情", "仅展示 STRUCTURE / FIELD / COMPOSITE 汇总，不保存行级样例。"],
  monitor: ["执行监控", "失败不自动重试、不自动暂停、不推进正式水位。"],
  validationOverview: ["校验总览", "正式同步最低严格 ROW_COUNT；MISMATCH 是结果，不是技术 FAILED。"],
  validationWorkbench: ["校验工作台", "人工校验/重新校验；不提供关闭、容差、Lookback 或自动修复策略。"],
  alerts: ["告警通知", "管理告警事件、规则、通道和投递结果。"],
  logs: ["日志中心", "查看应用、调度、执行和通道日志；敏感信息必须脱敏。"],
  audit: ["操作审计", "查看 Web / External API / Scheduler / System 操作审计。"],
  globalSettings: ["全局参数", "只维护注册参数；Validation 全局仅保留默认方法。"],
  registrySettings: ["规范库配置", "测试规范库连接并人工同步 Dataset。"],
  externalApi: ["外部授权", "维护稳定 Client ID、ALL / SELECTED 机构授权和 Secret 重置。"],
  mappingRules: ["类型映射", "通用 JDBC → Doris 建议仅用于诊断，不覆盖医疗字段合同。"],
  accounts: ["账号管理", "系统设置 → 账号管理；列表、新增、启停、重置密码，不建设 RBAC。"],
  docs: ["使用文档", "顶部右侧 Help 的固定目标；左侧业务导航不增加“使用文档”。"],
};

function Button({ children, icon, onClick, tone = "default", disabled = false }: { children: ReactNode; icon?: ReactNode; onClick?: () => void; tone?: "default" | "primary" | "danger" | "ghost"; disabled?: boolean }) {
  return <button type="button" className={`btn btn-${tone}`} onClick={onClick} disabled={disabled}>{icon}{children}</button>;
}

function Badge({ value }: { value: string | null | undefined }) {
  const text = value ?? "—";
  const good = ["ENABLED", "ACTIVE", "SUCCESS", "PASSED", "SUCCEEDED", "PASS", "READY", "COMPLETED"].includes(text);
  const bad = ["FAILED", "CRITICAL", "MISMATCH"].includes(text);
  const warn = ["PARTIAL", "OUTDATED", "ISSUES", "WARNING", "RUNNING", "LOADING", "VALIDATING", "PENDING"].includes(text);
  return <span className={`badge ${good ? "badge-good" : bad ? "badge-bad" : warn ? "badge-warn" : "badge-muted"}`}>{text}</span>;
}

function PageHeader({ page, actions }: { page: AppPage; actions?: ReactNode }) {
  const [title, description] = titles[page] ?? titles.dashboard!;
  return <div className="page-header"><div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="actions">{actions}</div>}</div>;
}

function SearchBar({ query, setQuery, placeholder = "搜索" }: { query: string; setQuery: (value: string) => void; placeholder?: string }) {
  return <label className="search"><SearchOutlined /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={placeholder} /></label>;
}

function Card({ title, note, actions, children }: { title?: string; note?: string; actions?: ReactNode; children: ReactNode }) {
  return <section className="card">{(title || actions) && <header><div>{title && <h2>{title}</h2>}{note && <p>{note}</p>}</div>{actions && <div className="actions">{actions}</div>}</header>}<div className="card-body">{children}</div></section>;
}

function Table({ headers, rows, empty = "暂无数据" }: { headers: string[]; rows: ReactNode[][]; empty?: string }) {
  return <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row, index) => <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}><div className="empty">{empty}</div></td></tr>}</tbody></table></div>;
}

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} onClick={onChange} className={`switch ${checked ? "is-on" : ""}`}><span /></button>;
}

function Notice({ children, tone = "info" }: { children: ReactNode; tone?: "info" | "warn" | "danger" | "success" }) {
  return <div className={`notice notice-${tone}`}>{children}</div>;
}

function Metric({ label, value, hint, icon }: { label: string; value: number | string; hint: string; icon: ReactNode }) {
  return <article className="metric"><span>{icon}</span><div><small>{label}</small><strong>{value}</strong><p>{hint}</p></div></article>;
}

export default function Home() {
  const pathname = usePathname();
  const router = useRouter();
  const location = parseLocation(pathname);
  const page = location.page;
  const currentId = location.id;
  const [query, setQuery] = useState("");
  const [modal, setModal] = useState<ModalState>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [institutions, setInstitutions] = useState(institutionsSeed);
  const [catalogs, setCatalogs] = useState(businessCatalogSeed);
  const [sources, setSources] = useState(sourceSeed);
  const [targets, setTargets] = useState(targetSeed);
  const [routes, setRoutes] = useState(routeSeed);
  const [tasks, setTasks] = useState(taskSeed);
  const [accounts, setAccounts] = useState(accountSeed);
  const [routeInstitution, setRouteInstitution] = useState("全部机构");
  const [alertTab, setAlertTab] = useState<"events" | "rules" | "channels">("events");

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const navigate = (nextPage: AppPage, id?: string) => {
    router.push(pathFor(nextPage, id));
    setQuery("");
  };

  const filterRows = <T,>(rows: T[], text: (row: T) => string) => {
    const needle = query.trim().toLowerCase();
    return needle ? rows.filter((row) => text(row).toLowerCase().includes(needle)) : rows;
  };

  const ask = (title: string, message: ReactNode, onConfirm?: () => void, danger = false, confirmLabel?: string) => setModal({ title, message, onConfirm, danger, confirmLabel });
  const confirmModal = () => { const action = modal?.onConfirm; setModal(null); action?.(); };
  const prototypeCreate = (name: string) => ask(`新增${name}`, <><p>此页面交互已按冻结 Spec 收口。</p><p>后端 API 尚未授权实现，因此当前前端原型只验证字段、入口、状态和确认逻辑，不伪造持久化接口。</p></>, () => setToast(`${name}新增表单已完成前端交互定义，等待 API Contract。`), false, "确认");
  const prototypeEdit = (name: string) => ask(`编辑${name}`, <><p>编辑只允许当前对象可变字段；稳定 Code/ID、Task 机构+Dataset 身份等不可变字段必须只读。</p></>, () => setToast(`${name}编辑交互已确认。`), false, "保存");

  const toggleInstitution = (code: string) => setInstitutions((rows) => rows.map((row) => row.code === code ? { ...row, status: row.status === "ENABLED" ? "DISABLED" : "ENABLED" } : row));
  const toggleCatalog = (code: string) => setCatalogs((rows) => rows.map((row) => row.code === code ? { ...row, status: row.status === "ENABLED" ? "DISABLED" : "ENABLED" } : row));
  const toggleSource = (code: string) => setSources((rows) => rows.map((row) => row.code === code ? { ...row, status: row.status === "ENABLED" ? "DISABLED" : "ENABLED" } : row));
  const toggleTarget = (code: string) => setTargets((rows) => rows.map((row) => row.code === code ? { ...row, status: row.status === "ENABLED" ? "DISABLED" : "ENABLED" } : row));

  const deleteResource = (kind: "institution" | "catalog" | "source" | "target", code: string) => {
    let referenced = false;
    if (kind === "institution") referenced = sources.some((row) => row.institutionCode === code) || routes.some((row) => row.institutionCode === code) || tasks.some((row) => row.institutionCode === code);
    if (kind === "catalog") referenced = sources.some((row) => row.businessCatalogCode === code);
    if (kind === "source") referenced = routes.some((row) => row.sourceCode === code);
    if (kind === "target") referenced = routes.some((row) => row.targetCode === code);
    if (referenced) return setToast("该资源仍有引用，不能物理删除；请改为停用。历史引用必须保留。");
    if (kind === "institution") setInstitutions((rows) => rows.filter((row) => row.code !== code));
    if (kind === "catalog") setCatalogs((rows) => rows.filter((row) => row.code !== code));
    if (kind === "source") setSources((rows) => rows.filter((row) => row.code !== code));
    if (kind === "target") setTargets((rows) => rows.filter((row) => row.code !== code));
    setToast("未引用资源已物理删除。");
  };

  const testSource = (code: string) => {
    setSources((rows) => rows.map((row) => row.code === code ? { ...row, testStatus: "SUCCESS", lastTestedAt: "刚刚" } : row));
    setToast("连接测试成功；测试结果不会自动改变数据源启停状态。");
  };
  const testTarget = (code: string) => {
    setTargets((rows) => rows.map((row) => row.code === code ? { ...row, testStatus: "SUCCESS", endpoints: row.endpoints.map((endpoint) => ({ ...endpoint, testStatus: "SUCCESS" })) } : row));
    setToast("Doris FE 聚合测试完成；业务启停状态保持不变。");
  };

  const dashboard = () => {
    const activeExecutions = executionSeed.filter((row) => ["PENDING", "RUNNING", "LOADING", "VALIDATING"].includes(row.status)).length;
    const issues = precheckSeed.filter((row) => row.result === "ISSUES").length + validationSeed.filter((row) => row.result === "MISMATCH").length + sources.filter((row) => row.testStatus === "FAILED").length;
    return <>
      <PageHeader page="dashboard" />
      <div className="metrics">
        <Metric label="启用机构" value={institutions.filter((row) => row.status === "ENABLED").length} hint="扁平机构模型" icon={<ApartmentOutlined />} />
        <Metric label="标准 Dataset" value={datasetSeed.filter((row) => row.status === "ACTIVE").length} hint="规范库人工同步" icon={<DatabaseOutlined />} />
        <Metric label="可用 Route" value={routes.filter((row) => row.status === "ENABLED" && row.structureStatus === "PASSED").length} hint="ENABLED + PASSED" icon={<LinkOutlined />} />
        <Metric label="待处理事项" value={issues} hint={`${activeExecutions} 个活动 Execution`} icon={<AlertOutlined />} />
      </div>
      <div className="grid-2">
        <Card title="最近执行" note="成功收尾后才推进 Watermark，并按 Execution 快照生成 Outbox。">
          <div className="list">{executionSeed.slice(0, 4).map((row) => <button key={row.id} onClick={() => navigate("monitor")}><span><strong>{row.taskName}</strong><small>{row.scope} · {row.range}</small></span><Badge value={row.status} /></button>)}</div>
        </Card>
        <Card title="冻结产品主线" note="页面与导航只能沿当前模型实现。">
          <div className="flow"><span>接入资源</span><i>→</i><span>机构采集路由</span><i>→</i><span>同步任务</span><i>→</i><span>预检 / 校验</span></div>
          <Notice tone="success">不再出现 Business System Instance、多机构 Route、Task Version、Validation Policy/Override Mode、Task-level Message Policy。</Notice>
        </Card>
      </div>
    </>;
  };

  const institutionPage = () => {
    const rows = filterRows(institutions, (row) => `${row.code} ${row.name} ${row.type} ${row.region}`);
    return <><PageHeader page="institutions" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("医疗机构")}>新增机构</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索机构编码、名称、类型" />
      <Card><Table headers={["机构编码", "机构名称", "类型", "等级", "区域", "状态", "操作"]} rows={rows.map((row) => [<code key="c">{row.code}</code>, row.name, row.type, row.level, row.region, <span key="s" className="state"><Badge value={row.status} /><Toggle checked={row.status === "ENABLED"} onChange={() => toggleInstitution(row.code)} label="切换机构状态" /></span>, <div key="a" className="row-actions"><Button tone="ghost" icon={<EditOutlined />} onClick={() => prototypeEdit(row.name)}>编辑</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("删除医疗机构", "无引用时允许物理删除；有 Source / Route / Task 引用时只能停用。", () => deleteResource("institution", row.code), true, "确认删除")}>删除</Button></div>])} /></Card>
      <Notice>机构是扁平集合：页面不再展示“上级机构 / 业务系统 / Source”列。</Notice></>;
  };

  const catalogPage = () => {
    const rows = filterRows(catalogs, (row) => `${row.code} ${row.name} ${row.description}`);
    return <><PageHeader page="businessCatalogs" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("业务目录")}>新增目录</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Code、名称" />
      <Card><Table headers={["Code", "名称", "说明", "状态", "Source 引用", "操作"]} rows={rows.map((row) => [<code key="c">{row.code}</code>, row.name, row.description, <span key="s" className="state"><Badge value={row.status} /><Toggle checked={row.status === "ENABLED"} onChange={() => toggleCatalog(row.code)} label="切换目录状态" /></span>, sources.filter((source) => source.businessCatalogCode === row.code).length, <div key="a" className="row-actions"><Button tone="ghost" icon={<EditOutlined />} onClick={() => prototypeEdit(row.name)}>编辑</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("删除业务目录", "存在 Source 引用时必须 RESTRICT，请改为停用。", () => deleteResource("catalog", row.code), true, "确认删除")}>删除</Button></div>])} /></Card>
      <Notice tone="success">业务目录只回答“这个数据库属于哪类业务”；不建设系统实例与机构/数据源多对多。</Notice></>;
  };

  const sourcePage = () => {
    const rows = filterRows(sources, (row) => `${row.code} ${row.name} ${row.institutionName} ${row.businessCatalogCode} ${row.dbType}`);
    return <><PageHeader page="sourceDatasources" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("源端数据源")}>新增 Source</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Source、机构、业务目录、DB 类型" />
      <Card><Table headers={["Code / 名称", "唯一归属机构", "业务目录", "数据库", "连接", "状态", "测试", "操作"]} rows={rows.map((row) => [<span key="n"><strong>{row.name}</strong><small>{row.code}</small></span>, row.institutionName, <Badge key="b" value={row.businessCatalogCode} />, <span key="d"><strong>{row.dbType}</strong><small>{row.database || row.defaultSchema}</small></span>, row.connectionMode === "HOST_PORT" ? `${row.host}:${row.port}` : "JDBC_URL", <span key="s" className="state"><Badge value={row.status} /><Toggle checked={row.status === "ENABLED"} onChange={() => toggleSource(row.code)} label="切换 Source 状态" /></span>, <span key="t"><Badge value={row.testStatus} /><small>{row.lastTestedAt}</small></span>, <div key="a" className="row-actions"><Button tone="ghost" icon={<ReloadOutlined />} onClick={() => testSource(row.code)}>测试</Button><Button tone="ghost" icon={<EditOutlined />} onClick={() => prototypeEdit(row.name)}>编辑</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("删除源端数据源", "一旦进入 Route 历史只能停用；未引用时才允许物理删除。", () => deleteResource("source", row.code), true, "确认删除")}>删除</Button></div>])} /></Card>
      <Notice tone="success">一个 Source = 一个 Institution + 一个 Business Catalog + 一个数据库连接；不存在 institutions[]。</Notice></>;
  };

  const targetPage = () => {
    const rows = filterRows(targets, (row) => `${row.code} ${row.name} ${row.database}`);
    return <><PageHeader page="targetDatasources" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("Doris 目标")}>新增 Target</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Target Code、名称、Database" />
      <div className="stack">{rows.map((row) => <Card key={row.id} title={row.name} note={`${row.code} · Database ${row.database}`} actions={<><Badge value={row.status} /><Badge value={row.testStatus} /><Button tone="ghost" icon={<ReloadOutlined />} onClick={() => testTarget(row.code)}>聚合测试</Button><Button tone="ghost" icon={<EditOutlined />} onClick={() => prototypeEdit(row.name)}>编辑</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("删除目标端数据源", "有 Route / Route Version / Delete Snapshot 引用时只能停用。", () => deleteResource("target", row.code), true, "确认删除")}>删除</Button></>}>
        <div className="endpoint-grid">{row.endpoints.map((endpoint) => <article key={endpoint.id}><div><strong>FE #{endpoint.ordinal}</strong><Badge value={endpoint.testStatus} /></div><p>{endpoint.host}</p><small>Query {endpoint.queryPort} · HTTP {endpoint.httpPort}</small></article>)}</div><div className="state"><Toggle checked={row.status === "ENABLED"} onChange={() => toggleTarget(row.code)} label="切换 Target 状态" /><span>Target 启停与测试结果保持独立。</span></div>
      </Card>)}</div><Notice>Target 是全局资源，不绑定 Institution / Business Catalog；FE 是当前纯配置子对象。</Notice></>;
  };

  const datasetPage = () => {
    const rows = filterRows(datasetSeed, (row) => `${row.code} ${row.name} ${row.category}`);
    return <><PageHeader page="datasets" actions={<Button tone="primary" icon={<SyncOutlined />} onClick={() => setToast("已发起规范库人工同步；相同 definition hash 将复用历史 Version。")}>从规范库同步</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Dataset Code、名称、分类" />
      <Card><Table headers={["Dataset", "状态", "版本", "字段 / 业务键", "增量字段", "Validation 覆盖", "默认调度", "消息", "最近同步"]} rows={rows.map((row) => [<span key="d"><strong>{row.name}</strong><small>{row.code}</small></span>, <Badge key="s" value={row.status} />, `V${row.version}`, `${row.fieldCount} / ${row.businessKeyCount}`, row.incrementalField ?? "—", row.validationOverride, row.scheduleDefault, <Badge key="m" value={row.messageEnabled ? "ENABLED" : "DISABLED"} />, <span key="r"><strong>{row.lastSyncResult}</strong><small>{row.lastSyncedAt}</small></span>])} /></Card>
      <Notice tone="warn">Dataset 不提供“新增”按钮；同步定义不覆盖管理员维护的 Validation Override / Sync Default / Message Policy。</Notice></>;
  };

  const routePage = () => {
    const institutionsForRoute = ["全部机构", ...Array.from(new Set(routes.map((row) => row.institutionName)))];
    const rows = filterRows(routes.filter((row) => routeInstitution === "全部机构" || row.institutionName === routeInstitution), (row) => `${row.institutionName} ${row.datasetCode} ${row.sourceName} ${row.schema} ${row.object}`);
    const checkStructure = (id: string) => { setRoutes((items) => items.map((row) => row.id === id ? { ...row, structureStatus: "PASSED", structureCheckedAt: "刚刚", version: row.version + 1 } : row)); setToast("结构核对通过；Route 不会被自动启用。") };
    const toggleRoute = (id: string) => setRoutes((items) => items.map((row) => row.id === id ? row.structureStatus === "PASSED" ? { ...row, status: row.status === "ENABLED" ? "DISABLED" : "ENABLED" } : row : row));
    return <><PageHeader page="routes" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("机构采集路由")}>新增 Route</Button>} /><div className="toolbar"><SearchBar query={query} setQuery={setQuery} placeholder="搜索机构、Dataset、Source、Object" /><select value={routeInstitution} onChange={(event) => setRouteInstitution(event.target.value)}>{institutionsForRoute.map((item) => <option key={item}>{item}</option>)}</select></div>
      <Card><Table headers={["机构 / Dataset", "Source / 业务目录", "Schema / Object", "Target", "Route Version", "结构状态", "业务状态", "操作"]} rows={rows.map((row) => [<span key="i"><strong>{row.institutionName}</strong><small>{row.datasetCode}</small></span>, <span key="s"><strong>{row.sourceName}</strong><small>{row.businessCatalog} · 只读继承</small></span>, <span key="o"><strong>{row.schema}.{row.object}</strong><small>{row.objectType}</small></span>, row.targetName, `V${row.version}`, <span key="st"><Badge value={row.structureStatus} /><small>{row.structureCheckedAt}</small></span>, <span key="bs" className="state"><Badge value={row.status} /><Toggle checked={row.status === "ENABLED"} onChange={() => row.structureStatus === "PASSED" ? toggleRoute(row.id) : setToast("结构未 PASSED，不能启用 Route。") } label="切换 Route 状态" /></span>, <div key="a" className="row-actions"><Button tone="ghost" icon={<ReloadOutlined />} onClick={() => checkStructure(row.id)}>结构核对</Button><Button tone="ghost" icon={<EditOutlined />} onClick={() => prototypeEdit(row.id)}>编辑</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("逻辑删除 Route", "Route 删除后旧 Version / Execution / Precheck 历史继续保留。", () => { setRoutes((items) => items.map((item) => item.id === row.id ? { ...item, status: "DISABLED" } : item)); setToast("Route 已逻辑删除（原型以停用表示）。") }, true, "确认")}>删除</Button></div>])} /></Card>
      <Notice>Route status 与 structure status 是两个事实；允许 ENABLED + OUTDATED，创建/运行 Task 时再做业务 Gate。</Notice></>;
  };

  const taskPage = () => {
    const rows = filterRows(tasks, (row) => `${row.id} ${row.name} ${row.institutionName} ${row.datasetCode}`);
    return <><PageHeader page="tasks" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("同步任务")}>新增任务</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Task、机构、Dataset" />
      <Card><Table headers={["Task", "固定身份", "Route / Dataset Version", "执行合同", "调度", "Validation", "Watermark", "状态", "操作"]} rows={rows.map((row) => [<button key="n" className="link-cell" onClick={() => navigate("taskDetail", row.id)}><strong>{row.name}</strong><small>{row.id}</small></button>, <span key="i"><strong>{row.institutionName}</strong><small>{row.datasetCode}</small></span>, `Route ${row.routeId}/V${row.routeVersion} · Dataset V${row.datasetVersion}`, <span key="c"><strong>{row.taskKind}</strong><small>{row.writeMode} · {row.keyModel}</small></span>, <span key="sc"><strong>{row.scheduleLabel}</strong><small>{row.scheduleEnabled ? "已启用" : "已暂停"}</small></span>, row.validationOverride, row.watermark ?? "未建立", <Badge key="s" value={row.state} />, <div key="a" className="row-actions"><Button tone="ghost" icon={<PlayCircleOutlined />} onClick={() => setToast("将创建新的 Execution；活动 Execution 时同 Task 新触发会被拒绝/跳过。")}>运行</Button><Button tone="ghost" onClick={() => setTasks((items) => items.map((item) => item.id === row.id ? { ...item, scheduleEnabled: !item.scheduleEnabled } : item))}>{row.scheduleEnabled ? "暂停调度" : "恢复调度"}</Button><Button tone="ghost" icon={<DeleteOutlined />} onClick={() => ask("逻辑删除 Task", "不迁移 Watermark，不删除 Execution / Validation / Outbox / Audit 历史。", () => setTasks((items) => items.map((item) => item.id === row.id ? { ...item, scheduleEnabled: false, state: "DISABLED" } : item)), true, "确认")}>删除</Button></div>])} /></Card>
      <Notice tone="success">页面不出现 Task Version、发布/回退或机构组。机构 + Dataset 创建后不可修改；更换身份必须删除旧 Task 并新建。</Notice></>;
  };

  const taskDetailPage = () => {
    const task = tasks.find((row) => row.id === currentId) ?? tasks[0];
    const history = executionSeed.filter((row) => row.taskId === task.id);
    return <><PageHeader page="taskDetail" actions={<><Button onClick={() => navigate("tasks")}>返回列表</Button><Button tone="primary" icon={<PlayCircleOutlined />} onClick={() => setToast("已准备创建新的 MANUAL Execution。")}>手动运行</Button></>} />
      <div className="grid-2"><Card title="当前配置"><div className="details">{[
        ["固定机构", task.institutionName], ["固定 Dataset", task.datasetCode], ["Dataset Version", `V${task.datasetVersion}`], ["Route Version", `${task.routeId} / V${task.routeVersion}`], ["任务类型", task.taskKind], ["写入方式", `${task.writeMode} / ${task.keyModel}`], ["增量字段", task.incrementalField ?? "无"], ["Validation", task.validationOverride],
      ].map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div></Card>
      <Card title="调度与正式水位"><div className="details"><div><span>调度模式</span><strong>{task.scheduleMode}</strong></div><div><span>当前调度</span><strong>{task.scheduleLabel}</strong></div><div><span>调度开关</span><strong>{task.scheduleEnabled ? "启用" : "暂停"}</strong></div><div><span>正式 Watermark</span><strong>{task.watermark ?? "不存在"}</strong></div></div><div className="actions"><Button onClick={() => ask("数据补采", "补采创建独立 BACKFILL Execution，成功也不推进正式水位。", () => setToast("BACKFILL Execution 参数页已确认。"))}>数据补采</Button><Button onClick={() => ask("重新采集", "Recollect 创建新的 Execution，从范围起点和 Batch 1 重新读取。", () => setToast("RECOLLECT 操作已确认。"))}>重新采集</Button><Button tone="danger" onClick={() => ask("清除正式水位", "清除后下一次 FULL_THEN_INCREMENTAL 正常运行将重新走 INITIAL_FULL。此操作写 Audit，不建立 Watermark History。", () => setTasks((items) => items.map((item) => item.id === task.id ? { ...item, watermark: null } : item)), true, "清除水位")}>清除水位</Button></div></Card></div>
      <Card title="Execution 历史" note="任务后续编辑不会改写已接受 Execution。"><Table headers={["Execution", "Operation", "Trigger", "Scope", "Range", "读取 / 写入 / 拒绝", "状态", "开始时间"]} rows={history.map((row) => [row.id, row.operation, row.trigger, row.scope, row.range, `${row.sourceRows} / ${row.loadedRows} / ${row.rejectedRows}`, <Badge key="s" value={row.status} />, row.startedAt])} /></Card></>;
  };

  const precheckPage = () => {
    const rows = filterRows(precheckSeed, (row) => `${row.id} ${row.institutionName} ${row.datasetCode}`);
    return <><PageHeader page="precheck" actions={<Button tone="primary" icon={<PlayCircleOutlined />} onClick={() => setToast("预检只能人工启动；同一 Route 有活动预检时拒绝第二条运行。")}>人工启动预检</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索机构、Dataset、Run ID" />
      <Card><Table headers={["Run", "Route", "机构 / Dataset", "状态", "结果", "问题汇总", "开始", "操作"]} rows={rows.map((row) => [row.id, row.routeId, <span key="d"><strong>{row.institutionName}</strong><small>{row.datasetCode}</small></span>, <Badge key="s" value={row.status} />, <Badge key="r" value={row.result} />, row.issues, row.startedAt, <Button key="a" tone="ghost" onClick={() => navigate("precheckDetail", row.id)}>详情</Button>])} /></Card>
      <Notice tone="warn">不提供自动调度。修复真实 Source View/Table 后必须创建新的预检运行；正式同步仍从真实源对象重新读取。</Notice></>;
  };

  const precheckDetailPage = () => {
    const row = precheckSeed.find((item) => item.id === currentId) ?? precheckSeed[0];
    const issueRows = row.result === "ISSUES" ? [["FIELD", "NOT_NULL", "JIANCHAXMID", "18,420", "4", "字段存在空值"], ["COMPOSITE", "BUSINESS_KEY_UNIQUE", "联合业务主键", "18,420", "2", "发现重复键"]] : [["STRUCTURE", "FIELD_SET", "全部字段", "—", "0", "字段集合严格一致"], ["FIELD", "TYPE_COMPATIBLE", "全部字段", "—", "0", "字段类型可转换"]];
    return <><PageHeader page="precheckDetail" actions={<Button onClick={() => navigate("precheck")}>返回列表</Button>} /><Card title={`${row.id} · ${row.institutionName}`} note={`${row.datasetCode} · ${row.startedAt} → ${row.finishedAt}`}><div className="details"><div><span>Route</span><strong>{row.routeId}</strong></div><div><span>技术状态</span><Badge value={row.status} /></div><div><span>结果</span><Badge value={row.result} /></div><div><span>问题汇总数</span><strong>{row.issues}</strong></div></div></Card>
      <Card title="问题汇总" note="P0 只保存 STRUCTURE / FIELD / COMPOSITE 汇总。"><Table headers={["Scope", "Rule", "字段/组合", "检查行数", "影响行数", "摘要"]} rows={issueRows} /></Card>
      <Notice>不展示/保存行号、业务键、原始值、修复值或样例数据。RAW 终态保留 1 天后清理，PostgreSQL Run/Summary 永久保留。</Notice></>;
  };

  const monitorPage = () => {
    const rows = filterRows(executionSeed, (row) => `${row.id} ${row.taskName} ${row.datasetCode} ${row.status}`);
    return <><PageHeader page="monitor" /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Execution、Task、Dataset、状态" /><Card><Table headers={["Execution", "Task", "Operation / Trigger", "Scope / Range", "读取 / 写入 / 拒绝", "状态", "开始 / 完成", "操作"]} rows={rows.map((row) => [row.id, <span key="t"><strong>{row.taskName}</strong><small>{row.taskId}</small></span>, `${row.operation} / ${row.trigger}`, <span key="r"><strong>{row.scope}</strong><small>{row.range}</small></span>, `${row.sourceRows} / ${row.loadedRows} / ${row.rejectedRows}`, <Badge key="s" value={row.status} />, <span key="tm"><strong>{row.startedAt}</strong><small>{row.finishedAt}</small></span>, ["PENDING", "RUNNING", "LOADING", "VALIDATING"].includes(row.status) ? <Button key="a" tone="danger" onClick={() => ask("取消当前 Execution", "取消只影响当前 Execution，不修改 Task 调度开关。", () => setToast("取消请求已提交（原型）。"), true, "确认取消")}>取消</Button> : "—"])} /></Card><Notice>计划重叠：存在活动 Execution 时本次 SCHEDULED 触发直接跳过，不排队、不追赶。</Notice></>;
  };

  const validationPage = (workbench: boolean) => {
    const rows = filterRows(validationSeed, (row) => `${row.id} ${row.institutionName} ${row.datasetCode} ${row.method} ${row.result ?? ""}`);
    return <><PageHeader page={workbench ? "validationWorkbench" : "validationOverview"} actions={workbench ? <><Button tone="primary" onClick={() => setToast("已准备 MANUAL 独立校验；同 Task 仅允许一个活动独立校验。")}>人工校验</Button><Button onClick={() => setToast("MANUAL_RECHECK 必须绑定原 Execution，并完全使用父 Execution 上下文。")}>重新校验</Button></> : undefined} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Validation、机构、Dataset、Method" />
      <Card><Table headers={["Validation", "机构 / Dataset", "Scope / Trigger", "Method / Source", "Execution", "Source / Target", "Difference", "状态 / 结果"]} rows={rows.map((row) => [row.id, <span key="d"><strong>{row.institutionName}</strong><small>{row.datasetCode}</small></span>, `${row.scope} / ${row.trigger}`, `${row.method} / ${row.source}`, row.executionId ?? "独立校验", row.sourceRows === null ? "—" : `${row.sourceRows} / ${row.targetRows}`, row.differenceCount ?? "—", <span key="s"><Badge value={row.status} /><Badge value={row.result} /></span>])} /></Card>
      <Notice tone="success">正式同步 Validation 不能关闭、无容差、无 Validation Lookback。ROW_COUNT_CHECKSUM 只在有真实业务主键时可用；DELETE_KEY_DIFF 来源固定 FIXED。</Notice></>;
  };

  const alertsPage = () => {
    const eventRows = filterRows(alertSeed, (row) => `${row.title} ${row.source} ${row.severity}`);
    return <><PageHeader page="alerts" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("告警规则")}>新增规则</Button>} /><div className="tabs">{(["events", "rules", "channels"] as const).map((tab) => <button className={alertTab === tab ? "active" : ""} key={tab} onClick={() => setAlertTab(tab)}>{tab === "events" ? "告警事件" : tab === "rules" ? "告警规则" : "通知通道"}</button>)}</div>
      {alertTab === "events" && <><SearchBar query={query} setQuery={setQuery} placeholder="搜索告警" /><Card><Table headers={["Event", "级别", "告警内容", "来源", "投递", "时间"]} rows={eventRows.map((row) => [row.id, <Badge key="sev" value={row.severity} />, row.title, row.source, <Badge key="s" value={row.status} />, row.time])} /></Card></>}
      {alertTab === "rules" && <Card><Table headers={["规则名称", "范围", "条件", "级别", "通道", "状态", "操作"]} rows={[["同步执行失败", "ALL", "status = FAILED", "CRITICAL", "钉钉运维群", <Badge key="s" value="ENABLED" />, <Button key="a" tone="ghost" onClick={() => prototypeEdit("告警规则")}>编辑</Button>], ["校验发现差异", "TASK", "result = MISMATCH", "WARNING", "企业微信", <Badge key="s" value="ENABLED" />, <Button key="a" tone="ghost" onClick={() => prototypeEdit("告警规则")}>编辑</Button>]]} /></Card>}
      {alertTab === "channels" && <Card><Table headers={["通道", "类型", "格式", "测试状态", "状态", "操作"]} rows={[["钉钉运维群", "DINGTALK", "MARKDOWN", <Badge key="t" value="SUCCESS" />, <Badge key="s" value="ENABLED" />, <Button key="a" tone="ghost" onClick={() => setToast("通道测试成功。")}>测试</Button>], ["企业微信", "WECOM", "TEXT", <Badge key="t" value="UNTESTED" />, <Badge key="s" value="ENABLED" />, <Button key="a" tone="ghost" onClick={() => setToast("通道测试成功。")}>测试</Button>]]} /></Card>}</>;
  };

  const logsPage = () => <><PageHeader page="logs" /><SearchBar query={query} setQuery={setQuery} placeholder="搜索日志消息、模块、请求 ID" /><Card><Table headers={["级别", "模块", "消息", "时间", "请求 ID"]} rows={[["INFO", "execution", "EXE-260817-002 成功收尾，Watermark 已推进", "08:18:16", "req-8d12"], ["ERROR", "datasource", "SRC_ZYY_HIS 连接超时（敏感连接串已脱敏）", "09:04:03", "req-7731"], ["WARN", "validation", "VAL-260816-009 COMPLETED + MISMATCH", "17:29:40", "req-a110"]]} /></Card><Notice>日志不得记录数据库/RabbitMQ/API Secret、Authorization Header、HMAC 签名或未脱敏连接信息。</Notice></>;
  const auditPage = () => <><PageHeader page="audit" /><SearchBar query={query} setQuery={setQuery} placeholder="搜索操作、目标、Actor" /><Card><Table headers={["Audit", "Actor", "来源", "操作", "目标", "结果", "时间"]} rows={filterRows(auditSeed, (row) => `${row.actor} ${row.operation} ${row.target}`).map((row) => [row.id, row.actor, row.source, row.operation, row.target, <Badge key="r" value={row.result} />, row.time])} /></Card></>;

  const globalSettingsPage = () => <><PageHeader page="globalSettings" /><div className="grid-2"><Card title="调度默认"><div className="form-grid"><label><span>默认同步周期</span><select defaultValue="EVERY_N_HOURS"><option>MANUAL</option><option>EVERY_N_HOURS</option><option>CRON</option></select></label><label><span>默认间隔（小时）</span><input defaultValue="4" /></label></div><Button tone="primary" onClick={() => setToast("全局调度默认已保存；只影响后续创建 Task。")}>保存</Button></Card>
    <Card title="Validation 默认"><div className="form-grid"><label><span>validation.default_method</span><select defaultValue="ROW_COUNT"><option>ROW_COUNT</option><option>ROW_COUNT_CHECKSUM</option></select></label></div><Notice>这里只允许默认 Method；不提供 enabled、tolerance、lookback、auto revalidate、fail block 或 override_mode。</Notice><Button tone="primary" onClick={() => setToast("Validation 默认方法已保存。")}>保存</Button></Card></div>
    <Card title="预检并发"><div className="form-grid"><label><span>不同 Route 全局并发上限</span><input defaultValue="4" /></label><label><span>RAW 保留</span><input value="1 天" readOnly /></label></div><Button tone="primary" onClick={() => setToast("预检并发参数已保存。")}>保存</Button></Card></>;

  const registryPage = () => <><PageHeader page="registrySettings" /><Card title="医共体规范库"><div className="form-grid"><label><span>Host</span><input defaultValue="192.168.1.10" /></label><label><span>Port</span><input defaultValue="5432" /></label><label><span>Database</span><input defaultValue="standard_registry" /></label><label><span>Username</span><input defaultValue="df_registry" /></label><label><span>Password</span><input type="password" defaultValue="********" /></label></div><div className="actions"><Button icon={<ReloadOutlined />} onClick={() => setToast("规范库连接测试成功。")}>测试连接</Button><Button tone="primary" icon={<SyncOutlined />} onClick={() => setToast("已发起 Dataset 人工同步。")}>同步 Dataset</Button></div></Card><Notice>规范库同步不自动执行；密码只保存密文并掩码返回，不进入 Execution Snapshot/Audit。</Notice></>;

  const externalApiPage = () => <><PageHeader page="externalApi" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("External Client")}>新增 Client</Button>} /><Card><Table headers={["Client ID", "展示名称", "授权模式", "机构范围", "状态", "操作"]} rows={externalClientSeed.map((row) => [<code key="i">{row.clientId}</code>, row.clientName, row.authorizationMode, row.authorizationMode === "ALL" ? "全部机构" : row.institutions.join("、"), <Badge key="s" value={row.enabled ? "ENABLED" : "DISABLED"} />, <div key="a" className="row-actions"><Button tone="ghost" onClick={() => prototypeEdit(row.clientName)}>编辑授权</Button><Button tone="ghost" icon={<LockOutlined />} onClick={() => ask("重置 Client Secret", "旧 Secret 立即失效，不支持双 Secret 或 Secret History。", () => setToast("Secret 已重置（原型）。"), true, "确认重置")}>重置 Secret</Button></div>])} /></Card><Notice>client_name 可重复，稳定身份只使用 client_id；P0 不做应用层 Rate Limit/Quota。</Notice></>;

  const mappingPage = () => <><PageHeader page="mappingRules" actions={<Button tone="primary" onClick={() => prototypeCreate("类型映射规则")}>新增规则</Button>} /><Card><Table headers={["Profile", "Version", "Rule Code", "Source DB", "Source Type", "Doris 建议", "兼容性", "状态"]} rows={[["generic", "1", "PG_JSONB", "POSTGRESQL", "jsonb", "STRING", <Badge key="c" value="WARN" />, <Badge key="s" value="ENABLED" />], ["generic", "1", "ORA_CLOB", "ORACLE", "CLOB", "STRING", <Badge key="c" value="PASS" />, <Badge key="s" value="ENABLED" />]]} /></Card><Notice tone="warn">Generic Mapping 只用于非标准/诊断场景；医疗标准字段必须使用版本化 Field Conversion Contract，不能被此页热更新覆盖。</Notice></>;

  const accountsPage = () => {
    const rows = filterRows(accounts, (row) => `${row.username} ${row.displayName}`);
    const toggle = (id: string) => setAccounts((items) => items.map((row) => row.id === id ? { ...row, enabled: !row.enabled } : row));
    return <><PageHeader page="accounts" actions={<Button tone="primary" icon={<PlusOutlined />} onClick={() => prototypeCreate("管理员账号")}>新增账号</Button>} /><SearchBar query={query} setQuery={setQuery} placeholder="搜索用户名、显示名称" /><Card><Table headers={["用户名", "显示名称", "状态", "最近登录", "创建时间", "操作"]} rows={rows.map((row) => [<code key="u">{row.username}</code>, row.displayName, <span key="s" className="state"><Badge value={row.enabled ? "ENABLED" : "DISABLED"} /><Toggle checked={row.enabled} onChange={() => row.username === "admin" ? setToast("当前登录账号不能停用自己；最后一个启用账号也不能停用。") : toggle(row.id)} label="切换账号状态" /></span>, row.lastLoginAt, row.createdAt, <Button key="a" tone="ghost" icon={<LockOutlined />} onClick={() => ask("重置密码", "重置密码后既有 Refresh Token 必须失效。", () => setToast("密码重置流程已确认。"), true, "确认重置")}>重置密码</Button>])} /></Card><Notice tone="success">P-002 已落页：系统设置 → 账号管理。账号只启停、不物理删除；不建设角色、权限或机构数据权限。</Notice></>;
  };

  const docsPage = () => <><PageHeader page="docs" /><div className="grid-2"><Card title="配置路径"><ol className="doc-list"><li>维护医疗机构、业务目录、Source、Target。</li><li>从规范库人工同步 Dataset。</li><li>按机构创建 Route 并完成结构核对。</li><li>必要时人工预检并由数据提供方修复源数据。</li><li>创建 Task，运行正式同步并通过 SYNC_GATE。</li></ol></Card><Card title="运行边界"><ul className="doc-list"><li>失败不自动重试/暂停/推进水位。</li><li>Backfill 不修改正式 Watermark。</li><li>Recollect 创建全新 Execution。</li><li>Precheck 不作为正式同步数据源。</li><li>消息只使用 Dataset 级 RabbitMQ Policy。</li></ul></Card></div><Card title="危险操作"><Table headers={["操作", "要求", "结果"]} rows={[["清除 Watermark", "明确确认 + Audit", "下次正常运行重新 INITIAL_FULL"], ["Delete Apply", "Dry Run + 二次确认 + 风险阈值", "只应用明确删除差异"], ["重置 Secret/密码", "明确确认", "旧凭据立即失效"]]} /></Card><Notice tone="success">P-003 已落页：顶部右侧 Help → /docs；左侧业务导航没有“使用文档”。</Notice></>;

  const render = () => {
    switch (page) {
      case "dashboard": return dashboard();
      case "institutions": return institutionPage();
      case "businessCatalogs": return catalogPage();
      case "sourceDatasources": return sourcePage();
      case "targetDatasources": return targetPage();
      case "datasets": return datasetPage();
      case "routes": return routePage();
      case "tasks": return taskPage();
      case "taskDetail": return taskDetailPage();
      case "precheck": return precheckPage();
      case "precheckDetail": return precheckDetailPage();
      case "monitor": return monitorPage();
      case "validationOverview": return validationPage(false);
      case "validationWorkbench": return validationPage(true);
      case "alerts": return alertsPage();
      case "logs": return logsPage();
      case "audit": return auditPage();
      case "globalSettings": return globalSettingsPage();
      case "registrySettings": return registryPage();
      case "externalApi": return externalApiPage();
      case "mappingRules": return mappingPage();
      case "accounts": return accountsPage();
      case "docs": return docsPage();
      default: return dashboard();
    }
  };

  const activeNavPage = page === "taskDetail" ? "tasks" : page === "precheckDetail" ? "precheck" : page;

  return <div className="app-shell">
    <aside className="sidebar">
      <button className="brand" onClick={() => navigate("dashboard")}><span>DF</span><div><strong>DFETL</strong><small>医共体数据采集平台</small></div></button>
      <button className={`nav-home ${activeNavPage === "dashboard" ? "active" : ""}`} onClick={() => navigate("dashboard")}><HomeOutlined />运行概览</button>
      <nav>{navGroups.map((group) => <section key={group.title}><h3>{group.icon}{group.title}</h3>{group.items.map((item) => <button key={item.page} className={activeNavPage === item.page ? "active" : ""} onClick={() => navigate(item.page)}>{item.icon}<span>{item.label}</span></button>)}</section>)}</nav>
      <footer><strong>技术模型已冻结</strong><small>数据库/后端实施待 G-001 授权</small></footer>
    </aside>
    <div className="main-shell">
      <header className="topbar"><div><strong>前端产品验收</strong><span>当前仅核对页面、导航、交互与文案</span></div><div className="top-actions"><button className="help" onClick={() => navigate("docs")}><QuestionCircleOutlined />Help</button><span className="top-status"><i />原型数据</span><button className="user"><UserOutlined /><span>admin</span></button></div></header>
      <main>{render()}</main>
    </div>
    {toast && <div className="toast">{toast}</div>}
    {modal && <div className="modal-mask" role="presentation" onMouseDown={() => setModal(null)}><section className="modal" role="dialog" aria-modal="true" aria-label={modal.title} onMouseDown={(event) => event.stopPropagation()}><header><h2>{modal.title}</h2><button onClick={() => setModal(null)}>×</button></header><div className="modal-body">{modal.message}</div><footer><Button onClick={() => setModal(null)}>取消</Button>{modal.onConfirm && <Button tone={modal.danger ? "danger" : "primary"} onClick={confirmModal}>{modal.confirmLabel ?? "确认"}</Button>}</footer></section></div>}
  </div>;
}

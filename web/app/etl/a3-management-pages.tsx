"use client";

import {
  useMemo,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from "react";
import type {
  AccountRow,
  AlertEvent,
  AuditRow,
  Dataset,
  ExternalClient,
  Institution,
  RoleRow,
} from "./model";

type Tone = "default" | "primary" | "danger" | "ghost";
type ConfirmAction = (
  title: string,
  message: ReactNode,
  onConfirm?: () => void,
  danger?: boolean,
  confirmLabel?: string,
) => void;
type AuditRecorder = (
  permissionCode: string,
  operation: string,
  target: string,
  result: "SUCCESS" | "FAILED",
  detail: string,
) => void;

type CommonProps = {
  can: (permission: string) => boolean;
  deny: (permission: string) => void;
  ask: ConfirmAction;
  setToast: (message: string) => void;
  recordAudit: AuditRecorder;
};

type ExportJob = {
  id: string;
  kind: string;
  scope: string;
  rowCount: number;
  status: "GENERATING" | "SUCCEEDED" | "FAILED" | "EXPIRED";
  createdAt: string;
};

function Button({
  children,
  onClick,
  tone = "default",
  disabled = false,
}: {
  children: ReactNode;
  onClick?: () => void;
  tone?: Tone;
  disabled?: boolean;
}) {
  return <button type="button" className={`btn btn-${tone}`} onClick={onClick} disabled={disabled}>{children}</button>;
}

function PButton({
  permission,
  can,
  children,
  ...props
}: {
  permission: string;
  can: (permission: string) => boolean;
  children: ReactNode;
  onClick?: () => void;
  tone?: Tone;
  disabled?: boolean;
}) {
  return can(permission) ? <Button {...props}>{children}</Button> : null;
}

function Badge({ value }: { value: string | null | undefined }) {
  const text = value ?? "—";
  const good = ["ENABLED", "SUCCESS", "SUCCEEDED", "PASS", "MATCHED", "ACTIVE", "CURRENT"].includes(text);
  const bad = ["FAILED", "CRITICAL", "MISMATCH", "DISABLED", "EXPIRED"].includes(text);
  const warn = ["WARNING", "PENDING", "SENDING", "GENERATING", "UNTESTED", "PARTIAL", "MISSING"].includes(text);
  return <span className={`badge ${good ? "badge-good" : bad ? "badge-bad" : warn ? "badge-warn" : "badge-muted"}`}>{text}</span>;
}

function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <div className="page-header"><div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="actions">{actions}</div>}</div>;
}

function Card({ title, note, actions, children }: { title?: string; note?: string; actions?: ReactNode; children: ReactNode }) {
  return <section className="card">{(title || actions) && <header><div>{title && <h2>{title}</h2>}{note && <p>{note}</p>}</div>{actions && <div className="actions">{actions}</div>}</header>}<div className="card-body">{children}</div></section>;
}

function Notice({ children, tone = "info" }: { children: ReactNode; tone?: "info" | "warn" | "danger" | "success" }) {
  return <div className={`notice notice-${tone}`}>{children}</div>;
}

function SearchBar({ query, setQuery, placeholder = "搜索" }: { query: string; setQuery: (value: string) => void; placeholder?: string }) {
  return <label className="search"><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={placeholder} /></label>;
}

function Table({ headers, rows, empty = "暂无数据" }: { headers: string[]; rows: ReactNode[][]; empty?: string }) {
  return <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row, index) => <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}><div className="empty">{empty}</div></td></tr>}</tbody></table></div>;
}

function Tabs({ tabs, active, onChange }: { tabs: Array<{ key: string; label: string }>; active: string; onChange: (key: string) => void }) {
  return <div className="tabs">{tabs.map((tab) => <button type="button" key={tab.key} className={active === tab.key ? "active" : ""} onClick={() => onChange(tab.key)}>{tab.label}</button>)}</div>;
}

function Pager({ page, pageSize, total, onPageChange, onPageSizeChange }: { page: number; pageSize: number; total: number; onPageChange: (page: number) => void; onPageSizeChange: (size: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const current = Math.min(page, pages);
  return <div className="toolbar" style={{ alignItems: "center", margin: "12px 0" }}><span style={{ fontSize: 11, color: "var(--muted)" }}>共 {total} 条 · 第 {current}/{pages} 页</span><div className="actions"><select value={pageSize} onChange={(event) => { onPageSizeChange(Number(event.target.value)); onPageChange(1); }}><option value={5}>5 / 页</option><option value={10}>10 / 页</option><option value={20}>20 / 页</option></select><Button disabled={current <= 1} onClick={() => onPageChange(current - 1)}>上一页</Button><Button disabled={current >= pages} onClick={() => onPageChange(current + 1)}>下一页</Button></div></div>;
}

function slicePage<T>(rows: T[], page: number, pageSize: number): T[] {
  const pages = Math.max(1, Math.ceil(rows.length / pageSize));
  const current = Math.min(page, pages);
  return rows.slice((current - 1) * pageSize, current * pageSize);
}

function newExportJob(kind: string, scope: string, rowCount: number): ExportJob {
  return { id: `EXP-${Date.now()}`, kind, scope, rowCount, status: "SUCCEEDED", createdAt: "刚刚" };
}

function ExportJobs({ jobs }: { jobs: ExportJob[] }) {
  if (!jobs.length) return null;
  return <Card title="导出任务" note="产品合同支持生成中、成功、失败和已过期状态。"><Table headers={["任务","类型","范围","记录数","状态","创建时间"]} rows={jobs.map((job) => [job.id, job.kind, job.scope, job.rowCount, <Badge key="s" value={job.status} />, job.createdAt])} /></Card>;
}

type AlertRuleRow = {
  id: string;
  name: string;
  scope: "ALL" | "TASK";
  taskId: string;
  metric: string;
  operator: string;
  value: string;
  severity: "INFO" | "WARNING" | "CRITICAL";
  channelIds: string[];
  enabled: boolean;
};

type AlertChannelRow = {
  id: string;
  name: string;
  type: "DINGTALK" | "WECOM";
  format: "TEXT" | "MARKDOWN";
  endpointMasked: string;
  enabled: boolean;
  testStatus: "UNTESTED" | "SUCCESS" | "FAILED";
};

type AlertDeliveryRow = {
  id: string;
  eventId: string;
  channelId: string;
  status: "PENDING" | "SENDING" | "SUCCEEDED" | "FAILED";
  attempts: number;
  lastError: string;
  updatedAt: string;
};

const initialAlertRules: AlertRuleRow[] = [
  { id: "AR01", name: "同步执行失败", scope: "ALL", taskId: "", metric: "EXECUTION_STATUS", operator: "EQ", value: "FAILED", severity: "CRITICAL", channelIds: ["AC01"], enabled: true },
  { id: "AR02", name: "校验发现差异", scope: "TASK", taskId: "TASK-1001", metric: "VALIDATION_RESULT", operator: "EQ", value: "MISMATCH", severity: "WARNING", channelIds: ["AC02"], enabled: true },
];

const initialAlertChannels: AlertChannelRow[] = [
  { id: "AC01", name: "钉钉运维群", type: "DINGTALK", format: "MARKDOWN", endpointMasked: "https://oapi.dingtalk.com/robot/send?access_token=***", enabled: true, testStatus: "SUCCESS" },
  { id: "AC02", name: "企业微信质控群", type: "WECOM", format: "TEXT", endpointMasked: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=***", enabled: true, testStatus: "UNTESTED" },
];

const initialDeliveries: AlertDeliveryRow[] = [
  { id: "AD01", eventId: "ALT-001", channelId: "AC01", status: "FAILED", attempts: 3, lastError: "HTTP 502", updatedAt: "2026-08-17 09:05" },
  { id: "AD02", eventId: "ALT-002", channelId: "AC02", status: "SUCCEEDED", attempts: 1, lastError: "", updatedAt: "2026-08-16 17:30" },
];

export function AlertsManagementPage({ events, tasks, can, ask, setToast, recordAudit }: CommonProps & { events: AlertEvent[]; tasks: Array<{ id: string; name: string }> }) {
  const [tab, setTab] = useState("events");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [rules, setRules] = useState(initialAlertRules);
  const [channels, setChannels] = useState(initialAlertChannels);
  const [deliveries, setDeliveries] = useState(initialDeliveries);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [ruleForm, setRuleForm] = useState<AlertRuleRow | null>(null);
  const [channelForm, setChannelForm] = useState<AlertChannelRow | null>(null);

  const eventRows = useMemo(() => events.filter((event) => `${event.id} ${event.title} ${event.source} ${event.severity}`.toLowerCase().includes(query.toLowerCase())), [events, query]);
  const ruleRows = useMemo(() => rules.filter((rule) => `${rule.name} ${rule.metric} ${rule.value}`.toLowerCase().includes(query.toLowerCase())), [rules, query]);
  const channelRows = useMemo(() => channels.filter((channel) => `${channel.name} ${channel.type}`.toLowerCase().includes(query.toLowerCase())), [channels, query]);
  const deliveryRows = useMemo(() => deliveries.filter((delivery) => `${delivery.id} ${delivery.eventId} ${delivery.status}`.toLowerCase().includes(query.toLowerCase())), [deliveries, query]);

  const saveRule = () => {
    if (!ruleForm?.name.trim()) return setToast("请填写告警规则名称");
    const exists = rules.some((rule) => rule.id === ruleForm.id);
    setRules((items) => exists ? items.map((item) => item.id === ruleForm.id ? ruleForm : item) : [...items, ruleForm]);
    recordAudit("alert.rule.manage", exists ? "ALERT_RULE_UPDATE" : "ALERT_RULE_CREATE", ruleForm.id, "SUCCESS", `${ruleForm.metric} ${ruleForm.operator} ${ruleForm.value}`);
    setRuleForm(null);
    setToast("告警规则已保存");
  };

  const saveChannel = () => {
    if (!channelForm?.name.trim()) return setToast("请填写通知通道名称");
    const exists = channels.some((channel) => channel.id === channelForm.id);
    setChannels((items) => exists ? items.map((item) => item.id === channelForm.id ? channelForm : item) : [...items, channelForm]);
    recordAudit("alert.channel.manage", exists ? "ALERT_CHANNEL_UPDATE" : "ALERT_CHANNEL_CREATE", channelForm.id, "SUCCESS", "Secret omitted");
    setChannelForm(null);
    setToast("通知通道已保存");
  };

  const retryDelivery = (delivery: AlertDeliveryRow) => {
    ask("重试告警投递", `确认重试 ${delivery.id}？原告警事实不会改变。`, () => {
      setDeliveries((items) => items.map((item) => item.id === delivery.id ? { ...item, status: "SUCCEEDED", attempts: item.attempts + 1, lastError: "", updatedAt: "刚刚" } : item));
      recordAudit("alert.delivery.retry", "ALERT_DELIVERY_RETRY", delivery.id, "SUCCESS", `attempt ${delivery.attempts + 1}`);
      setToast("告警投递重试成功");
    }, false, "确认重试");
  };

  const tabs = [{ key: "events", label: "告警事件" }, { key: "rules", label: "告警规则" }, { key: "channels", label: "通知通道" }, { key: "deliveries", label: "投递记录" }];
  const total = tab === "events" ? eventRows.length : tab === "rules" ? ruleRows.length : tab === "channels" ? channelRows.length : deliveryRows.length;

  return <>
    <PageHeader title="告警通知" description="管理告警事件、规则、通知通道和每次投递结果。" actions={tab === "rules" ? <PButton permission="alert.rule.manage" can={can} tone="primary" onClick={() => setRuleForm({ id: `AR${String(rules.length + 1).padStart(2, "0")}`, name: "", scope: "ALL", taskId: "", metric: "EXECUTION_STATUS", operator: "EQ", value: "FAILED", severity: "WARNING", channelIds: [], enabled: true })}>新增规则</PButton> : tab === "channels" ? <PButton permission="alert.channel.manage" can={can} tone="primary" onClick={() => setChannelForm({ id: `AC${String(channels.length + 1).padStart(2, "0")}`, name: "", type: "DINGTALK", format: "MARKDOWN", endpointMasked: "", enabled: true, testStatus: "UNTESTED" })}>新增通道</PButton> : undefined} />
    <Tabs tabs={tabs} active={tab} onChange={(key) => { setTab(key); setPage(1); setQuery(""); }} />
    <SearchBar query={query} setQuery={setQuery} placeholder="搜索告警、规则、通道或投递" />

    {tab === "events" && <Card><Table headers={["Event", "级别", "告警内容", "来源", "投递汇总", "时间", "操作"]} rows={slicePage(eventRows, page, pageSize).map((event) => { const eventDeliveries = deliveries.filter((delivery) => delivery.eventId === event.id); return [event.id, <Badge key="sev" value={event.severity} />, event.title, event.source, `${eventDeliveries.filter((item) => item.status === "SUCCEEDED").length}/${eventDeliveries.length} 成功`, event.time, <Button key="d" tone="ghost" onClick={() => setSelectedEventId(event.id)}>详情</Button>]; })} /></Card>}
    {tab === "rules" && <Card><Table headers={["规则", "范围", "条件", "级别", "通道", "状态", "操作"]} rows={slicePage(ruleRows, page, pageSize).map((rule) => [rule.name, rule.scope === "ALL" ? "全部任务" : tasks.find((task) => task.id === rule.taskId)?.name ?? rule.taskId, `${rule.metric} ${rule.operator} ${rule.value}`, <Badge key="sev" value={rule.severity} />, rule.channelIds.map((id) => channels.find((channel) => channel.id === id)?.name ?? id).join("、") || "未绑定", <Badge key="s" value={rule.enabled ? "ENABLED" : "DISABLED"} />, <div key="a" className="row-actions"><PButton permission="alert.rule.manage" can={can} tone="ghost" onClick={() => setRuleForm({ ...rule })}>编辑</PButton><PButton permission="alert.rule.status" can={can} tone="ghost" onClick={() => ask("变更规则状态", `确认${rule.enabled ? "停用" : "启用"} ${rule.name}？`, () => { setRules((items) => items.map((item) => item.id === rule.id ? { ...item, enabled: !item.enabled } : item)); recordAudit("alert.rule.status", "ALERT_RULE_STATUS_CHANGE", rule.id, "SUCCESS", rule.enabled ? "DISABLED" : "ENABLED"); })}>{rule.enabled ? "停用" : "启用"}</PButton><PButton permission="alert.rule.delete" can={can} tone="ghost" onClick={() => ask("删除告警规则", "历史事件继续保留规则快照。", () => { setRules((items) => items.filter((item) => item.id !== rule.id)); recordAudit("alert.rule.delete", "ALERT_RULE_DELETE", rule.id, "SUCCESS", rule.name); }, true, "确认删除")}>删除</PButton></div>])} /></Card>}
    {tab === "channels" && <Card><Table headers={["通道", "类型", "格式", "Endpoint", "测试", "状态", "操作"]} rows={slicePage(channelRows, page, pageSize).map((channel) => [channel.name, channel.type, channel.format, channel.endpointMasked || "未配置", <Badge key="t" value={channel.testStatus} />, <Badge key="s" value={channel.enabled ? "ENABLED" : "DISABLED"} />, <div key="a" className="row-actions"><PButton permission="alert.channel.test" can={can} tone="ghost" onClick={() => { setChannels((items) => items.map((item) => item.id === channel.id ? { ...item, testStatus: "SUCCESS" } : item)); recordAudit("alert.channel.test", "ALERT_CHANNEL_TEST", channel.id, "SUCCESS", "Endpoint masked"); setToast("通知通道测试成功"); }}>测试</PButton><PButton permission="alert.channel.manage" can={can} tone="ghost" onClick={() => setChannelForm({ ...channel })}>编辑</PButton><PButton permission="alert.channel.status" can={can} tone="ghost" onClick={() => ask("变更通道状态", `确认${channel.enabled ? "停用" : "启用"} ${channel.name}？`, () => { setChannels((items) => items.map((item) => item.id === channel.id ? { ...item, enabled: !item.enabled } : item)); recordAudit("alert.channel.status", "ALERT_CHANNEL_STATUS_CHANGE", channel.id, "SUCCESS", channel.enabled ? "DISABLED" : "ENABLED"); })}>{channel.enabled ? "停用" : "启用"}</PButton><PButton permission="alert.channel.delete" can={can} tone="ghost" onClick={() => { if (rules.some((rule) => rule.channelIds.includes(channel.id))) return setToast("通知通道仍被告警规则引用"); ask("删除通知通道", "历史投递继续保留通道快照。", () => { setChannels((items) => items.filter((item) => item.id !== channel.id)); recordAudit("alert.channel.delete", "ALERT_CHANNEL_DELETE", channel.id, "SUCCESS", channel.name); }, true, "确认删除"); }}>删除</PButton></div>])} /></Card>}
    {tab === "deliveries" && <Card><Table headers={["Delivery", "Event", "通道", "尝试次数", "状态", "最后错误", "更新时间", "操作"]} rows={slicePage(deliveryRows, page, pageSize).map((delivery) => [delivery.id, delivery.eventId, channels.find((channel) => channel.id === delivery.channelId)?.name ?? delivery.channelId, delivery.attempts, <Badge key="s" value={delivery.status} />, delivery.lastError || "—", delivery.updatedAt, delivery.status === "FAILED" ? <PButton key="a" permission="alert.delivery.retry" can={can} tone="ghost" onClick={() => retryDelivery(delivery)}>重试</PButton> : "—"])} /></Card>}
    <Pager page={page} pageSize={pageSize} total={total} onPageChange={setPage} onPageSizeChange={setPageSize} />

    {selectedEventId && <Card title={`告警详情 · ${selectedEventId}`} actions={<Button tone="ghost" onClick={() => setSelectedEventId(null)}>关闭</Button>}><div className="details"><div><span>告警内容</span><strong>{events.find((event) => event.id === selectedEventId)?.title}</strong></div><div><span>来源</span><strong>{events.find((event) => event.id === selectedEventId)?.source}</strong></div><div><span>级别</span><Badge value={events.find((event) => event.id === selectedEventId)?.severity} /></div><div><span>投递记录</span><strong>{deliveries.filter((delivery) => delivery.eventId === selectedEventId).length}</strong></div></div></Card>}

    {ruleForm && <Card title={rules.some((rule) => rule.id === ruleForm.id) ? "编辑告警规则" : "新增告警规则"} actions={<Button tone="ghost" onClick={() => setRuleForm(null)}>取消</Button>}><div className="form-grid"><label><span>规则名称</span><input value={ruleForm.name} onChange={(event) => setRuleForm({ ...ruleForm, name: event.target.value })} /></label><label><span>范围</span><select value={ruleForm.scope} onChange={(event) => setRuleForm({ ...ruleForm, scope: event.target.value as AlertRuleRow["scope"] })}><option value="ALL">全部任务</option><option value="TASK">指定任务</option></select></label>{ruleForm.scope === "TASK" && <label><span>Task</span><select value={ruleForm.taskId} onChange={(event) => setRuleForm({ ...ruleForm, taskId: event.target.value })}><option value="">请选择</option>{tasks.map((task) => <option key={task.id} value={task.id}>{task.name}</option>)}</select></label>}<label><span>Metric</span><input value={ruleForm.metric} onChange={(event) => setRuleForm({ ...ruleForm, metric: event.target.value })} /></label><label><span>Operator</span><select value={ruleForm.operator} onChange={(event) => setRuleForm({ ...ruleForm, operator: event.target.value })}><option value="EQ">EQ</option><option value="NE">NE</option><option value="GT">GT</option><option value="GTE">GTE</option></select></label><label><span>Value</span><input value={ruleForm.value} onChange={(event) => setRuleForm({ ...ruleForm, value: event.target.value })} /></label><label><span>级别</span><select value={ruleForm.severity} onChange={(event) => setRuleForm({ ...ruleForm, severity: event.target.value as AlertRuleRow["severity"] })}><option value="INFO">INFO</option><option value="WARNING">WARNING</option><option value="CRITICAL">CRITICAL</option></select></label><label><span>通知通道</span><select multiple value={ruleForm.channelIds} onChange={(event) => setRuleForm({ ...ruleForm, channelIds: Array.from(event.target.selectedOptions, (option) => option.value) })}>{channels.map((channel) => <option key={channel.id} value={channel.id}>{channel.name}</option>)}</select></label></div><Button tone="primary" onClick={saveRule}>保存规则</Button></Card>}

    {channelForm && <Card title={channels.some((channel) => channel.id === channelForm.id) ? "编辑通知通道" : "新增通知通道"} actions={<Button tone="ghost" onClick={() => setChannelForm(null)}>取消</Button>}><div className="form-grid"><label><span>通道名称</span><input value={channelForm.name} onChange={(event) => setChannelForm({ ...channelForm, name: event.target.value })} /></label><label><span>类型</span><select value={channelForm.type} onChange={(event) => setChannelForm({ ...channelForm, type: event.target.value as AlertChannelRow["type"] })}><option value="DINGTALK">钉钉</option><option value="WECOM">企业微信</option></select></label><label><span>格式</span><select value={channelForm.format} onChange={(event) => setChannelForm({ ...channelForm, format: event.target.value as AlertChannelRow["format"] })}><option value="TEXT">TEXT</option><option value="MARKDOWN">MARKDOWN</option></select></label><label><span>Endpoint / Secret</span><input value={channelForm.endpointMasked} onChange={(event) => setChannelForm({ ...channelForm, endpointMasked: event.target.value })} /></label></div><Notice>正式接口只返回掩码；Secret 新增或轮换时只提交，不回显，也不进入审计。</Notice><Button tone="primary" onClick={saveChannel}>保存通道</Button></Card>}
  </>;
}

type LogRow = {
  id: string;
  level: "INFO" | "WARN" | "ERROR";
  module: string;
  message: string;
  rawMessage: string;
  time: string;
  requestId: string;
  context: string;
  sensitive: boolean;
};

const logRows: LogRow[] = [
  { id: "LOG-001", level: "INFO", module: "execution", message: "EXE-260817-002 成功收尾，Watermark 已推进", rawMessage: "EXE-260817-002 成功收尾，Watermark 已推进", time: "2026-08-17 08:18:16", requestId: "req-8d12", context: "task=TASK-1001; execution=EXE-260817-002", sensitive: false },
  { id: "LOG-002", level: "ERROR", module: "datasource", message: "SRC_ZYY_HIS 连接超时（连接信息已脱敏）", rawMessage: "SRC_ZYY_HIS jdbc:oracle:thin:@192.168.1.20:1521/HIS user=DFETL 连接超时", time: "2026-08-17 09:04:03", requestId: "req-7731", context: "datasource=S02", sensitive: true },
  { id: "LOG-003", level: "WARN", module: "validation", message: "VAL-260816-009 COMPLETED + MISMATCH", rawMessage: "VAL-260816-009 COMPLETED + MISMATCH", time: "2026-08-16 17:29:40", requestId: "req-a110", context: "validation=VAL-260816-009", sensitive: false },
  { id: "LOG-004", level: "INFO", module: "precheck", message: "PRE-260817-002 COMPLETED + PASS", rawMessage: "PRE-260817-002 COMPLETED + PASS", time: "2026-08-17 08:30:00", requestId: "req-p002", context: "route=R001", sensitive: false },
  { id: "LOG-005", level: "ERROR", module: "rabbitmq", message: "Outbox OUT-001 首次投递失败，等待重试", rawMessage: "Outbox OUT-001 publish confirm timeout; authorization=***", time: "2026-08-17 08:19:02", requestId: "req-mq01", context: "outbox=OUT-001", sensitive: true },
  { id: "LOG-006", level: "INFO", module: "audit", message: "ACCOUNT_PERMISSION_ASSIGN 已记录", rawMessage: "ACCOUNT_PERMISSION_ASSIGN 已记录", time: "2026-08-17 10:02:00", requestId: "req-sec1", context: "account=U02", sensitive: false },
];

export function LogsManagementPage({ can, ask, setToast, recordAudit }: CommonProps) {
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("ALL");
  const [module, setModule] = useState("ALL");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selected, setSelected] = useState<LogRow | null>(null);
  const [revealed, setRevealed] = useState<string[]>([]);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const filtered = useMemo(() => logRows.filter((row) => (level === "ALL" || row.level === level) && (module === "ALL" || row.module === module) && `${row.message} ${row.module} ${row.requestId} ${row.context}`.toLowerCase().includes(query.toLowerCase())), [query, level, module]);
  const modules = Array.from(new Set(logRows.map((row) => row.module)));

  const exportRows = () => ask("下载日志", `按当前筛选导出 ${filtered.length} 条日志。`, () => {
    setJobs((items) => [newExportJob("LOG", `${level}/${module}/${query || "ALL"}`, filtered.length), ...items]);
    recordAudit("log.export", "LOG_EXPORT", "current-filter", "SUCCESS", `${filtered.length} rows`);
    setToast("日志导出任务已创建");
  }, false, "确认导出");

  const reveal = (row: LogRow) => ask("查看安全受限日志", "该操作将被审计；凭据和 Authorization 仍必须掩码。", () => {
    setRevealed((items) => items.includes(row.id) ? items : [...items, row.id]);
    recordAudit("log.sensitive.view", "LOG_SENSITIVE_VIEW", row.id, "SUCCESS", "credential values remain masked");
  }, true, "确认查看");

  return <>
    <PageHeader title="日志中心" description="查看应用、调度、执行和通道日志；默认脱敏，下载和安全受限查看独立授权。" actions={<PButton permission="log.export" can={can} onClick={exportRows}>下载日志</PButton>} />
    <div className="toolbar"><SearchBar query={query} setQuery={setQuery} placeholder="搜索消息、模块、请求 ID、业务上下文" /><div className="actions"><select value={level} onChange={(event) => { setLevel(event.target.value); setPage(1); }}><option value="ALL">全部级别</option><option value="INFO">INFO</option><option value="WARN">WARN</option><option value="ERROR">ERROR</option></select><select value={module} onChange={(event) => { setModule(event.target.value); setPage(1); }}><option value="ALL">全部模块</option>{modules.map((item) => <option key={item} value={item}>{item}</option>)}</select></div></div>
    <Card><Table headers={["日志", "级别", "模块", "消息", "时间", "请求 ID", "操作"]} rows={slicePage(filtered, page, pageSize).map((row) => [row.id, <Badge key="l" value={row.level} />, row.module, row.message, row.time, row.requestId, <Button key="d" tone="ghost" onClick={() => setSelected(row)}>详情</Button>])} /></Card>
    <Pager page={page} pageSize={pageSize} total={filtered.length} onPageChange={setPage} onPageSizeChange={setPageSize} />
    {selected && <Card title={`日志详情 · ${selected.id}`} actions={<Button tone="ghost" onClick={() => setSelected(null)}>关闭</Button>}><div className="details"><div><span>时间 / 级别</span><strong>{selected.time} / {selected.level}</strong></div><div><span>模块 / Request ID</span><strong>{selected.module} / {selected.requestId}</strong></div><div><span>业务上下文</span><strong>{selected.context}</strong></div><div><span>敏感标识</span><Badge value={selected.sensitive ? "SENSITIVE" : "NORMAL"} /></div></div><Notice>{revealed.includes(selected.id) ? selected.rawMessage : selected.message}</Notice>{selected.sensitive && !revealed.includes(selected.id) && <PButton permission="log.sensitive.view" can={can} tone="danger" onClick={() => reveal(selected)}>查看安全受限内容</PButton>}</Card>}
    <ExportJobs jobs={jobs} />
  </>;
}

export function AuditManagementPage({ rows, can, ask, setToast, recordAudit }: CommonProps & { rows: AuditRow[] }) {
  const [query, setQuery] = useState("");
  const [result, setResult] = useState("ALL");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selected, setSelected] = useState<AuditRow | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const filtered = useMemo(() => rows.filter((row) => (result === "ALL" || row.result === result) && `${row.id} ${row.actor} ${row.permissionCode} ${row.operation} ${row.target} ${row.detail}`.toLowerCase().includes(query.toLowerCase())), [rows, query, result]);

  const openDetail = (row: AuditRow) => {
    setSelected(row);
    recordAudit("audit.view", "AUDIT_LOG_DETAIL_VIEW", row.id, "SUCCESS", "detail viewed");
  };

  const exportRows = () => ask("导出操作审计", `按当前筛选导出 ${filtered.length} 条审计记录。审计导出本身也将被审计。`, () => {
    setJobs((items) => [newExportJob("AUDIT", `${result}/${query || "ALL"}`, filtered.length), ...items]);
    recordAudit("audit.export", "AUDIT_LOG_EXPORT", "current-filter", "SUCCESS", `${filtered.length} rows`);
    setToast("审计导出任务已创建");
  }, true, "确认导出");

  return <>
    <PageHeader title="操作审计" description="审计记录不可修改；详情查看和导出自身也进入安全审计。" actions={<PButton permission="audit.export" can={can} tone="danger" onClick={exportRows}>导出审计</PButton>} />
    <div className="toolbar"><SearchBar query={query} setQuery={setQuery} placeholder="搜索 Actor、权限、事件、对象、详情" /><select value={result} onChange={(event) => { setResult(event.target.value); setPage(1); }}><option value="ALL">全部结果</option><option value="SUCCESS">SUCCESS</option><option value="FAILED">FAILED</option></select></div>
    <Card><Table headers={["Audit", "Actor", "来源", "权限", "事件", "目标", "结果", "时间", "操作"]} rows={slicePage(filtered, page, pageSize).map((row) => [row.id, row.actor, row.source, <code key="p">{row.permissionCode}</code>, row.operation, row.target, <Badge key="r" value={row.result} />, row.time, <Button key="d" tone="ghost" onClick={() => openDetail(row)}>详情</Button>])} /></Card>
    <Pager page={page} pageSize={pageSize} total={filtered.length} onPageChange={setPage} onPageSizeChange={setPageSize} />
    {selected && <Card title={`审计详情 · ${selected.id}`} actions={<Button tone="ghost" onClick={() => setSelected(null)}>关闭</Button>}><div className="details"><div><span>Actor / 来源</span><strong>{selected.actor} / {selected.source}</strong></div><div><span>权限 / 事件</span><strong>{selected.permissionCode} / {selected.operation}</strong></div><div><span>目标 / 结果</span><strong>{selected.target} / {selected.result}</strong></div><div><span>时间</span><strong>{selected.time}</strong></div></div><Notice>{selected.detail || "无详情"}</Notice></Card>}
    <ExportJobs jobs={jobs} />
  </>;
}

type GlobalSettingState = {
  scheduleMode: "MANUAL" | "EVERY_N_HOURS" | "CRON";
  scheduleIntervalHours: number;
  precheckConcurrency: number;
  precheckDetailRetentionDays: number;
  exportRetentionHours: number;
  outboxMaxAttempts: number;
  outboxPublishingTimeoutMinutes: number;
};

const defaultGlobalSettings: GlobalSettingState = {
  scheduleMode: "EVERY_N_HOURS",
  scheduleIntervalHours: 4,
  precheckConcurrency: 4,
  precheckDetailRetentionDays: 7,
  exportRetentionHours: 24,
  outboxMaxAttempts: 5,
  outboxPublishingTimeoutMinutes: 10,
};

export function GlobalSettingsPage({ can, ask, setToast, recordAudit }: CommonProps) {
  const [settings, setSettings] = useState(defaultGlobalSettings);
  const [saved, setSaved] = useState(defaultGlobalSettings);
  const [revision, setRevision] = useState(3);
  const dirty = JSON.stringify(settings) !== JSON.stringify(saved);

  const save = () => ask("保存全局参数", "新值只影响后续运行或下一批，不改变已经启动的执行快照。", () => {
    setSaved(settings);
    setRevision((value) => value + 1);
    recordAudit("setting.global.update", "GLOBAL_SETTING_UPDATE", "global", "SUCCESS", `revision ${revision} → ${revision + 1}`);
    setToast("全局参数已保存");
  }, false, "确认保存");

  const reset = () => ask("恢复全局默认值", "将恢复调度、预检保留和 Outbox 参数默认值；运行中执行不受影响。", () => {
    setSettings(defaultGlobalSettings);
    setSaved(defaultGlobalSettings);
    setRevision((value) => value + 1);
    recordAudit("setting.global.update", "GLOBAL_SETTING_RESET", "global", "SUCCESS", `revision ${revision} → ${revision + 1}`);
    setToast("全局参数已恢复默认");
  }, true, "确认恢复默认");

  return <>
    <PageHeader title="全局参数" description="维护后续调度、预检并发与保留、导出文件和 Outbox 默认参数。" actions={<><Badge value={dirty ? "UNSAVED" : "CURRENT"} /><span style={{ fontSize: 11 }}>Revision {revision}</span></>} />
    <div className="grid-2">
      <Card title="调度默认"><div className="form-grid"><label><span>默认同步周期</span><select value={settings.scheduleMode} onChange={(event) => setSettings({ ...settings, scheduleMode: event.target.value as GlobalSettingState["scheduleMode"] })}><option value="MANUAL">MANUAL</option><option value="EVERY_N_HOURS">EVERY_N_HOURS</option><option value="CRON">CRON</option></select></label><label><span>默认间隔（小时）</span><input type="number" min={1} value={settings.scheduleIntervalHours} onChange={(event) => setSettings({ ...settings, scheduleIntervalHours: Number(event.target.value) })} /></label></div></Card>
      <Card title="预检与导出"><div className="form-grid"><label><span>不同 Route 并发上限</span><input type="number" min={1} value={settings.precheckConcurrency} onChange={(event) => setSettings({ ...settings, precheckConcurrency: Number(event.target.value) })} /></label><label><span>问题明细默认保留天数</span><input type="number" min={1} value={settings.precheckDetailRetentionDays} onChange={(event) => setSettings({ ...settings, precheckDetailRetentionDays: Number(event.target.value) })} /></label><label><span>导出文件保留小时</span><input type="number" min={1} value={settings.exportRetentionHours} onChange={(event) => setSettings({ ...settings, exportRetentionHours: Number(event.target.value) })} /></label></div></Card>
      <Card title="Outbox"><div className="form-grid"><label><span>最大投递次数</span><input type="number" min={1} value={settings.outboxMaxAttempts} onChange={(event) => setSettings({ ...settings, outboxMaxAttempts: Number(event.target.value) })} /></label><label><span>PUBLISHING 超时分钟</span><input type="number" min={1} value={settings.outboxPublishingTimeoutMinutes} onChange={(event) => setSettings({ ...settings, outboxPublishingTimeoutMinutes: Number(event.target.value) })} /></label></div></Card>
    </div>
    <Notice>预检问题明细不再固定写死为 1 天；具体值来自版本化全局参数，页面显示当前值、Revision 和生效边界。</Notice>
    <div className="actions"><PButton permission="setting.global.update" can={can} tone="primary" disabled={!dirty} onClick={save}>保存参数</PButton><PButton permission="setting.global.update" can={can} tone="danger" onClick={reset}>恢复默认</PButton></div>
  </>;
}

type RegistryConfig = { host: string; port: string; database: string; username: string; password: string };

export function RegistrySettingsPage({ can, ask, setToast, recordAudit }: CommonProps) {
  const [config, setConfig] = useState<RegistryConfig>({ host: "192.168.1.10", port: "5432", database: "standard_registry", username: "df_registry", password: "" });
  const [revision, setRevision] = useState(2);
  const [testStatus, setTestStatus] = useState<"UNTESTED" | "SUCCESS" | "FAILED">("SUCCESS");
  const [syncHistory, setSyncHistory] = useState([{ id: "REG-SYNC-002", result: "UNCHANGED", created: 0, updated: 0, unchanged: 37, time: "2026-08-17 08:40" }]);

  const save = () => ask("保存医共体数据模型连接", "密码留空表示不轮换；Secret 不进入审计。", () => {
    setRevision((value) => value + 1);
    recordAudit("registry.update", "REGISTRY_CONFIG_UPDATE", "registry", "SUCCESS", `revision ${revision} → ${revision + 1}; secret omitted`);
    setConfig({ ...config, password: "" });
    setToast("连接配置已保存");
  }, false, "确认保存");

  const test = () => {
    setTestStatus("SUCCESS");
    recordAudit("registry.test", "REGISTRY_CONNECTION_TEST", "registry", "SUCCESS", `${config.host}:${config.port}/${config.database}`);
    setToast("连接测试成功");
  };

  const sync = () => ask("人工同步标准数据集", "将读取医共体数据模型并按 definition hash 判断 CREATED / UPDATED / UNCHANGED。", () => {
    const id = `REG-SYNC-${String(syncHistory.length + 3).padStart(3, "0")}`;
    setSyncHistory((items) => [{ id, result: "UNCHANGED", created: 0, updated: 0, unchanged: 37, time: "刚刚" }, ...items]);
    recordAudit("dataset.sync_definition", "DATASET_DEFINITION_SYNC", id, "SUCCESS", "created=0 updated=0 unchanged=37");
    setToast("数据集定义同步完成");
  }, false, "确认同步");

  return <>
    <PageHeader title="医共体数据模型" description="配置标准定义来源；测试连接与人工同步是两个独立命令。" actions={<><span style={{ fontSize: 11 }}>Revision {revision}</span><Badge value={testStatus} /></>} />
    <Card title="连接配置"><div className="form-grid"><label><span>Host</span><input value={config.host} onChange={(event) => setConfig({ ...config, host: event.target.value })} /></label><label><span>Port</span><input value={config.port} onChange={(event) => setConfig({ ...config, port: event.target.value })} /></label><label><span>Database</span><input value={config.database} onChange={(event) => setConfig({ ...config, database: event.target.value })} /></label><label><span>Username</span><input value={config.username} onChange={(event) => setConfig({ ...config, username: event.target.value })} /></label><label><span>Password</span><input type="password" value={config.password} placeholder="留空表示不轮换" onChange={(event) => setConfig({ ...config, password: event.target.value })} /></label></div><div className="actions"><PButton permission="registry.update" can={can} onClick={save}>保存配置</PButton><PButton permission="registry.test" can={can} onClick={test}>测试连接</PButton><PButton permission="dataset.sync_definition" can={can} tone="primary" onClick={sync}>同步数据集</PButton></div></Card>
    <Card title="同步历史"><Table headers={["Run", "结果", "新增", "更新", "未变化", "时间"]} rows={syncHistory.map((row) => [row.id, <Badge key="r" value={row.result} />, row.created, row.updated, row.unchanged, row.time])} /></Card>
  </>;
}

export function ValidationPolicyPage({ can, ask, setToast, recordAudit }: CommonProps) {
  const [method, setMethod] = useState<"ROW_COUNT" | "ROW_COUNT_CHECKSUM">("ROW_COUNT");
  const [savedMethod, setSavedMethod] = useState(method);
  const [revision, setRevision] = useState(4);

  const save = () => ask("保存全局校验策略", "新默认值从下一批继承任务开始生效；运行中执行保持原快照。", () => {
    setSavedMethod(method);
    setRevision((value) => value + 1);
    recordAudit("validation_policy.update", "GLOBAL_VALIDATION_POLICY_UPDATE", "global", "SUCCESS", `${savedMethod} → ${method}`);
    setToast("校验策略已保存");
  }, false, "确认保存");

  const reset = () => ask("恢复校验策略默认值", "恢复为 ROW_COUNT、零容差、Lookback=0、关闭自动复检。", () => {
    setMethod("ROW_COUNT");
    setSavedMethod("ROW_COUNT");
    setRevision((value) => value + 1);
    recordAudit("validation_policy.update", "GLOBAL_VALIDATION_POLICY_RESET", "global", "SUCCESS", "ROW_COUNT");
    setToast("校验策略已恢复默认");
  }, true, "确认恢复");

  return <>
    <PageHeader title="校验策略" description="维护全局默认方法；数据集和 Task Version 可以按已确认规则覆盖。" actions={<span style={{ fontSize: 11 }}>Revision {revision}</span>} />
    <Card title="全局默认校验策略"><div className="form-grid"><label><span>Method</span><select value={method} onChange={(event) => setMethod(event.target.value as typeof method)}><option value="ROW_COUNT">ROW_COUNT</option><option value="ROW_COUNT_CHECKSUM">ROW_COUNT_CHECKSUM</option></select></label><label><span>容差</span><input value="0" readOnly /></label><label><span>Lookback</span><input value="0" readOnly /></label><label><span>自动复检</span><input value="关闭" readOnly /></label></div><Notice>选择 ROW_COUNT_CHECKSUM 不会绕过“数据集必须存在真实业务主键”的服务端校验。</Notice><div className="actions"><PButton permission="validation_policy.update" can={can} tone="primary" disabled={method === savedMethod} onClick={save}>保存</PButton><PButton permission="validation_policy.update" can={can} tone="danger" onClick={reset}>恢复默认</PButton></div></Card>
  </>;
}

type DorisState = { status: "MATCHED" | "MISSING" | "MISMATCH"; updatedAt: string };

export function DorisTablesPage({ datasets, can, ask, setToast, recordAudit }: CommonProps & { datasets: Dataset[] }) {
  const [states, setStates] = useState<Record<string, DorisState>>(() => Object.fromEntries(datasets.map((dataset) => [dataset.code, { status: dataset.code === "ODS_YL_BCCHUYUANJL" ? "MISSING" : "MATCHED", updatedAt: "2026-08-17 09:20" }])));
  const [preview, setPreview] = useState<{ datasetCode: string; ddl: string } | null>(null);
  const [history, setHistory] = useState<Array<{ id: string; datasetCode: string; operation: string; result: string; time: string }>>([]);

  const refresh = () => {
    setStates((current) => Object.fromEntries(Object.entries(current).map(([code, state]) => [code, { ...state, updatedAt: "刚刚" }])));
    setToast("已重新读取 Doris 实际元数据");
  };

  const ddlFor = (dataset: Dataset) => {
    const table = `ods_${dataset.code.toLowerCase().replace(/^ods_/, "")}`;
    const key = dataset.businessKeyCount ? "UNIQUE KEY(`org_code`, `business_key`)" : "DUPLICATE KEY(`org_code`)";
    return `CREATE TABLE ${table} (\n  -- fields generated from Dataset V${dataset.version}\n  \`org_code\` VARCHAR(64) NOT NULL\n) ${key}\nDISTRIBUTED BY HASH(\`org_code\`) BUCKETS AUTO;`;
  };

  const execute = (dataset: Dataset, operation: "CREATE" | "REBUILD") => ask(operation === "CREATE" ? "创建 Doris ODS/RAW" : "重建 Doris ODS/RAW", <><p>Dataset：{dataset.code}</p><p>目标范围：ODS 与 RAW。</p><p>普通同步不会自动执行该操作。</p></>, () => {
    setStates((current) => ({ ...current, [dataset.code]: { status: "MATCHED", updatedAt: "刚刚" } }));
    const id = `DORIS-OP-${Date.now()}`;
    setHistory((items) => [{ id, datasetCode: dataset.code, operation, result: "SUCCESS", time: "刚刚" }, ...items]);
    recordAudit(operation === "CREATE" ? "doris_table.create" : "doris_table.rebuild", operation === "CREATE" ? "DORIS_TABLE_CREATE" : "DORIS_TABLE_REBUILD", dataset.code, "SUCCESS", "DDL hash recorded; credentials omitted");
    setToast(`${operation} 已完成（Mock）`);
  }, true, operation === "CREATE" ? "确认创建" : "确认重建");

  return <>
    <PageHeader title="Doris 建表" description="直接比较合同生成的期望结构与 Doris 实际元数据；普通执行只检查，不自动改表。" actions={<Button onClick={refresh}>刷新结构比较</Button>} />
    <Card><Table headers={["Dataset", "期望 ODS", "期望 RAW", "实际状态", "键模型", "最近检查", "操作"]} rows={datasets.map((dataset) => { const state = states[dataset.code]; return [<span key="d"><strong>{dataset.name}</strong><small>{dataset.code} · V{dataset.version}</small></span>, `ods_${dataset.code.toLowerCase().replace(/^ods_/, "")}`, `raw_${dataset.code.toLowerCase().replace(/^ods_/, "")}`, <Badge key="s" value={state?.status} />, dataset.businessKeyCount ? "UNIQUE KEY" : "DUPLICATE KEY", state?.updatedAt ?? "—", <div key="a" className="row-actions"><PButton permission="doris_table.ddl.preview" can={can} tone="ghost" onClick={() => setPreview({ datasetCode: dataset.code, ddl: ddlFor(dataset) })}>预览 DDL</PButton>{state?.status === "MISSING" ? <PButton permission="doris_table.create" can={can} tone="ghost" onClick={() => execute(dataset, "CREATE")}>创建</PButton> : <PButton permission="doris_table.rebuild" can={can} tone="ghost" onClick={() => execute(dataset, "REBUILD")}>重建</PButton>}</div>]; })} /></Card>
    {preview && <Card title={`DDL 预览 · ${preview.datasetCode}`} actions={<Button tone="ghost" onClick={() => setPreview(null)}>关闭</Button>}><pre style={{ whiteSpace: "pre-wrap", fontSize: 11 }}>{preview.ddl}</pre></Card>}
    {history.length > 0 && <Card title="建表操作历史"><Table headers={["操作", "Dataset", "类型", "结果", "时间"]} rows={history.map((row) => [row.id, row.datasetCode, row.operation, <Badge key="r" value={row.result} />, row.time])} /></Card>}
  </>;
}

type ExternalRequestLog = { id: string; clientId: string; method: string; path: string; status: number; requestId: string; time: string };

export function ExternalClientsPage({ clients, setClients, institutions, can, ask, setToast, recordAudit }: CommonProps & { clients: ExternalClient[]; setClients: Dispatch<SetStateAction<ExternalClient[]>>; institutions: Institution[] }) {
  const [query, setQuery] = useState("");
  const [form, setForm] = useState<ExternalClient | null>(null);
  const [oneTimeSecret, setOneTimeSecret] = useState<string | null>(null);
  const [selectedClientId, setSelectedClientId] = useState<string | null>(null);
  const requestLogs: ExternalRequestLog[] = [
    { id: "API-REQ-001", clientId: "regional-platform", method: "POST", path: "/api/v1/sync-tasks/TASK-1001/executions", status: 202, requestId: "ext-001", time: "2026-08-17 08:17" },
    { id: "API-REQ-002", clientId: "county-audit", method: "GET", path: "/api/v1/validations", status: 200, requestId: "ext-002", time: "2026-08-17 09:10" },
  ];
  const rows = clients.filter((client) => `${client.clientId} ${client.clientName} ${client.authorizationMode}`.toLowerCase().includes(query.toLowerCase()));

  const save = () => {
    if (!form?.clientId.trim() || !form.clientName.trim()) return setToast("Client ID 和展示名称必填");
    if (form.authorizationMode === "SELECTED" && !form.institutions.length) return setToast("SELECTED 必须选择机构范围");
    const exists = clients.some((client) => client.id === form.id);
    if (!exists && clients.some((client) => client.clientId === form.clientId)) return setToast("Client ID 已存在");
    setClients((items) => exists ? items.map((item) => item.id === form.id ? form : item) : [...items, form]);
    if (!exists) setOneTimeSecret(`dfetl_${form.clientId}_${Date.now().toString(36)}`);
    recordAudit(exists ? "external_client.update" : "external_client.create", exists ? "EXTERNAL_CLIENT_SCOPE_UPDATE" : "EXTERNAL_CLIENT_CREATE", form.clientId, "SUCCESS", `${form.authorizationMode}; secret omitted`);
    setForm(null);
    setToast("External Client 已保存");
  };

  return <>
    <PageHeader title="外部授权" description="维护稳定 Client ID、机构授权范围和请求日志；Secret 只在创建或重置后显示一次。" actions={<PButton permission="external_client.create" can={can} tone="primary" onClick={() => setForm({ id: `EC${String(clients.length + 1).padStart(2, "0")}`, clientId: "", clientName: "", authorizationMode: "ALL", institutions: [], enabled: true })}>新增 Client</PButton>} />
    <SearchBar query={query} setQuery={setQuery} placeholder="搜索 Client ID、名称或授权模式" />
    <Card><Table headers={["Client ID", "展示名称", "授权模式", "机构范围", "状态", "请求数", "操作"]} rows={rows.map((client) => [<code key="id">{client.clientId}</code>, client.clientName, client.authorizationMode, client.authorizationMode === "ALL" ? "全部机构" : client.institutions.map((code) => institutions.find((institution) => institution.code === code)?.name ?? code).join("、"), <Badge key="s" value={client.enabled ? "ENABLED" : "DISABLED"} />, requestLogs.filter((log) => log.clientId === client.clientId).length, <div key="a" className="row-actions"><PButton permission="external_client.update" can={can} tone="ghost" onClick={() => setForm({ ...client, institutions: [...client.institutions] })}>编辑授权</PButton><PButton permission="external_client.status" can={can} tone="ghost" onClick={() => ask("变更 Client 状态", `确认${client.enabled ? "停用" : "启用"} ${client.clientId}？`, () => { setClients((items) => items.map((item) => item.id === client.id ? { ...item, enabled: !item.enabled } : item)); recordAudit("external_client.status", "EXTERNAL_CLIENT_STATUS_CHANGE", client.clientId, "SUCCESS", client.enabled ? "DISABLED" : "ENABLED"); })}>{client.enabled ? "停用" : "启用"}</PButton><PButton permission="external_client.secret.reset" can={can} tone="ghost" onClick={() => ask("重置 Client Secret", "旧 Secret 立即失效；新 Secret 只显示一次。", () => { setOneTimeSecret(`dfetl_${client.clientId}_${Date.now().toString(36)}`); recordAudit("external_client.secret.reset", "EXTERNAL_CLIENT_SECRET_RESET", client.clientId, "SUCCESS", "secret omitted"); }, true, "确认重置")}>重置 Secret</PButton><Button tone="ghost" onClick={() => setSelectedClientId(client.clientId)}>请求日志</Button><PButton permission="external_client.delete" can={can} tone="ghost" onClick={() => { if (requestLogs.some((log) => log.clientId === client.clientId)) return setToast("Client 已有请求历史，建议停用而不是删除"); ask("删除 External Client", `确认删除 ${client.clientId}？`, () => { setClients((items) => items.filter((item) => item.id !== client.id)); recordAudit("external_client.delete", "EXTERNAL_CLIENT_DELETE", client.clientId, "SUCCESS", client.clientName); }, true, "确认删除"); }}>删除</PButton></div>])} /></Card>
    {oneTimeSecret && <Notice tone="warn">新 Secret（仅显示一次）：<code>{oneTimeSecret}</code> <Button tone="ghost" onClick={() => setOneTimeSecret(null)}>我已保存</Button></Notice>}
    {selectedClientId && <Card title={`请求日志 · ${selectedClientId}`} actions={<Button tone="ghost" onClick={() => setSelectedClientId(null)}>关闭</Button>}><Table headers={["Request", "Method", "Path", "HTTP", "Request ID", "时间"]} rows={requestLogs.filter((log) => log.clientId === selectedClientId).map((log) => [log.id, log.method, log.path, log.status, log.requestId, log.time])} /></Card>}
    {form && <Card title={clients.some((client) => client.id === form.id) ? "编辑 Client 授权" : "新增 External Client"} actions={<Button tone="ghost" onClick={() => setForm(null)}>取消</Button>}><div className="form-grid"><label><span>Client ID</span><input value={form.clientId} disabled={clients.some((client) => client.id === form.id)} onChange={(event) => setForm({ ...form, clientId: event.target.value })} /></label><label><span>展示名称</span><input value={form.clientName} onChange={(event) => setForm({ ...form, clientName: event.target.value })} /></label><label><span>授权模式</span><select value={form.authorizationMode} onChange={(event) => setForm({ ...form, authorizationMode: event.target.value as ExternalClient["authorizationMode"], institutions: [] })}><option value="ALL">全部机构</option><option value="SELECTED">指定机构</option></select></label>{form.authorizationMode === "SELECTED" && <label><span>机构范围</span><select multiple value={form.institutions} onChange={(event) => setForm({ ...form, institutions: Array.from(event.target.selectedOptions, (option) => option.value) })}>{institutions.map((institution) => <option key={institution.id} value={institution.code}>{institution.code} · {institution.name}</option>)}</select></label>}</div><Button tone="primary" onClick={save}>保存 Client</Button></Card>}
  </>;
}

type MappingRuleRow = { id: string; profile: string; version: string; ruleCode: string; sourceDb: string; sourceType: string; dorisType: string; compatibility: "PASS" | "WARN" | "REJECT"; enabled: boolean };

export function TypeMappingPage({ can, ask, setToast, recordAudit }: CommonProps) {
  const [rules, setRules] = useState<MappingRuleRow[]>([
    { id: "MAP01", profile: "generic", version: "1", ruleCode: "PG_JSONB", sourceDb: "POSTGRESQL", sourceType: "jsonb", dorisType: "STRING", compatibility: "WARN", enabled: true },
    { id: "MAP02", profile: "generic", version: "1", ruleCode: "ORA_CLOB", sourceDb: "ORACLE", sourceType: "CLOB", dorisType: "STRING", compatibility: "PASS", enabled: true },
  ]);
  const [form, setForm] = useState<MappingRuleRow | null>(null);
  const [contractVersions, setContractVersions] = useState([{ version: "DFETL-FIELD-CONTRACT-V1", status: "ACTIVE", publishedAt: "2026-08-01", fieldRules: 14 }]);

  const save = () => {
    if (!form?.ruleCode.trim() || !form.sourceType.trim() || !form.dorisType.trim()) return setToast("请填写完整映射规则");
    const exists = rules.some((rule) => rule.id === form.id);
    setRules((items) => exists ? items.map((item) => item.id === form.id ? form : item) : [...items, form]);
    recordAudit(exists ? "type_mapping.generic.update" : "type_mapping.generic.create", exists ? "GENERIC_TYPE_MAPPING_UPDATE" : "GENERIC_TYPE_MAPPING_CREATE", form.id, "SUCCESS", `${form.sourceDb}:${form.sourceType} → ${form.dorisType}`);
    setForm(null);
    setToast("Generic Mapping 已保存");
  };

  const publish = () => ask("发布医疗字段转换合同新版本", "已被引用的合同版本不可原地修改；本操作创建不可变新版本。", () => {
    const version = `DFETL-FIELD-CONTRACT-V${contractVersions.length + 1}`;
    setContractVersions((items) => [{ version, status: "ACTIVE", publishedAt: "刚刚", fieldRules: 14 }, ...items.map((item) => ({ ...item, status: "HISTORICAL" }))]);
    recordAudit("type_mapping.contract.publish", "FIELD_CONVERSION_CONTRACT_PUBLISH", version, "SUCCESS", "14 rules");
    setToast("医疗字段转换合同新版本已发布");
  }, true, "确认发布");

  return <>
    <PageHeader title="类型映射规则" description="Generic JDBC Mapping 只用于诊断建议；医疗字段合同使用独立、不可变版本。" actions={<><PButton permission="type_mapping.generic.create" can={can} tone="primary" onClick={() => setForm({ id: `MAP${String(rules.length + 1).padStart(2, "0")}`, profile: "generic", version: "1", ruleCode: "", sourceDb: "POSTGRESQL", sourceType: "", dorisType: "STRING", compatibility: "PASS", enabled: true })}>新增 Generic Mapping</PButton><PButton permission="type_mapping.contract.publish" can={can} tone="danger" onClick={publish}>发布合同新版本</PButton></>} />
    <Card title="Generic JDBC Mapping"><Table headers={["Profile", "Version", "Rule Code", "Source DB", "Source Type", "Doris 建议", "兼容性", "状态", "操作"]} rows={rules.map((rule) => [rule.profile, rule.version, rule.ruleCode, rule.sourceDb, rule.sourceType, rule.dorisType, <Badge key="c" value={rule.compatibility} />, <Badge key="s" value={rule.enabled ? "ENABLED" : "DISABLED"} />, <div key="a" className="row-actions"><PButton permission="type_mapping.generic.update" can={can} tone="ghost" onClick={() => setForm({ ...rule })}>编辑</PButton><PButton permission="type_mapping.generic.update" can={can} tone="ghost" onClick={() => { setRules((items) => items.map((item) => item.id === rule.id ? { ...item, enabled: !item.enabled } : item)); recordAudit("type_mapping.generic.update", "GENERIC_TYPE_MAPPING_UPDATE", rule.id, "SUCCESS", rule.enabled ? "DISABLED" : "ENABLED"); }}>{rule.enabled ? "停用" : "启用"}</PButton><PButton permission="type_mapping.generic.delete" can={can} tone="ghost" onClick={() => ask("删除 Generic Mapping", "有历史引用时服务端将拒绝。", () => { setRules((items) => items.filter((item) => item.id !== rule.id)); recordAudit("type_mapping.generic.delete", "GENERIC_TYPE_MAPPING_DELETE", rule.id, "SUCCESS", rule.ruleCode); }, true, "确认删除")}>删除</PButton></div>])} /></Card>
    <Card title="医疗字段转换合同版本"><Table headers={["版本", "状态", "字段规则数", "发布时间"]} rows={contractVersions.map((contract) => [contract.version, <Badge key="s" value={contract.status} />, contract.fieldRules, contract.publishedAt])} /></Card>
    {form && <Card title={rules.some((rule) => rule.id === form.id) ? "编辑 Generic Mapping" : "新增 Generic Mapping"} actions={<Button tone="ghost" onClick={() => setForm(null)}>取消</Button>}><div className="form-grid"><label><span>Profile</span><input value={form.profile} onChange={(event) => setForm({ ...form, profile: event.target.value })} /></label><label><span>Version</span><input value={form.version} onChange={(event) => setForm({ ...form, version: event.target.value })} /></label><label><span>Rule Code</span><input value={form.ruleCode} onChange={(event) => setForm({ ...form, ruleCode: event.target.value })} /></label><label><span>Source DB</span><select value={form.sourceDb} onChange={(event) => setForm({ ...form, sourceDb: event.target.value })}><option>POSTGRESQL</option><option>MYSQL</option><option>ORACLE</option><option>SQLSERVER</option></select></label><label><span>Source Type</span><input value={form.sourceType} onChange={(event) => setForm({ ...form, sourceType: event.target.value })} /></label><label><span>Doris Type</span><input value={form.dorisType} onChange={(event) => setForm({ ...form, dorisType: event.target.value })} /></label><label><span>兼容性</span><select value={form.compatibility} onChange={(event) => setForm({ ...form, compatibility: event.target.value as MappingRuleRow["compatibility"] })}><option>PASS</option><option>WARN</option><option>REJECT</option></select></label></div><Button tone="primary" onClick={save}>保存 Mapping</Button></Card>}
  </>;
}

export function SecurityManagementPage({ accounts, setAccounts, roles, setRoles, currentAccountId, openCreate, openEdit, can, ask, setToast, recordAudit }: CommonProps & { accounts: AccountRow[]; setAccounts: Dispatch<SetStateAction<AccountRow[]>>; roles: RoleRow[]; setRoles: Dispatch<SetStateAction<RoleRow[]>>; currentAccountId: string; openCreate: (kind: "account" | "role") => void; openEdit: (kind: "account" | "role", id: string, initial: Record<string, string | boolean | string[]>) => void }) {
  const [tab, setTab] = useState("accounts");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const accountRows = accounts.filter((account) => `${account.username} ${account.displayName} ${account.roleIds.join(" ")}`.toLowerCase().includes(query.toLowerCase()));
  const roleRows = roles.filter((role) => `${role.code} ${role.name} ${role.permissions.join(" ")}`.toLowerCase().includes(query.toLowerCase()));

  const toggleAccount = (account: AccountRow) => {
    if (account.id === currentAccountId && account.enabled) return setToast("不能停用当前登录账号");
    const adminRoleIds = roles.filter((role) => role.permissions.includes("*")).map((role) => role.id);
    const enabledAdmins = accounts.filter((item) => item.enabled && item.roleIds.some((roleId) => adminRoleIds.includes(roleId)));
    if (account.enabled && account.roleIds.some((roleId) => adminRoleIds.includes(roleId)) && enabledAdmins.length <= 1) return setToast("不能停用最后一个启用管理员");
    ask("变更账号状态", `确认${account.enabled ? "停用" : "启用"} ${account.username}？`, () => {
      setAccounts((items) => items.map((item) => item.id === account.id ? { ...item, enabled: !item.enabled } : item));
      recordAudit("security.account.status", "ACCOUNT_STATUS_CHANGE", account.id, "SUCCESS", account.enabled ? "DISABLED" : "ENABLED");
    }, true, "确认变更");
  };

  const deleteRole = (role: RoleRow) => {
    if (role.builtIn) return setToast("内置角色不能删除");
    if (accounts.some((account) => account.roleIds.includes(role.id))) return setToast("角色仍被账号引用");
    ask("删除角色", `确认删除 ${role.name}？`, () => {
      setRoles((items) => items.filter((item) => item.id !== role.id));
      recordAudit("security.role.delete", "ROLE_DELETE", role.id, "SUCCESS", role.code);
    }, true, "确认删除");
  };

  const total = tab === "accounts" ? accountRows.length : roleRows.length;
  return <>
    <PageHeader title="账号与权限" description="账号关联角色；角色只是权限集合。前端权限控制不替代服务端鉴权。" actions={tab === "accounts" ? <PButton permission="security.account.create" can={can} tone="primary" onClick={() => openCreate("account")}>新增账号</PButton> : <PButton permission="security.role.manage" can={can} tone="primary" onClick={() => openCreate("role")}>新增角色</PButton>} />
    <Tabs tabs={[{ key: "accounts", label: "账号" }, { key: "roles", label: "角色与权限" }]} active={tab} onChange={(key) => { setTab(key); setPage(1); }} />
    <SearchBar query={query} setQuery={setQuery} placeholder="搜索账号、角色或权限代码" />
    {tab === "accounts" ? <Card><Table headers={["用户名", "显示名称", "角色", "状态", "最近登录", "操作"]} rows={slicePage(accountRows, page, pageSize).map((account) => [<code key="u">{account.username}</code>, account.displayName, account.roleIds.map((roleId) => roles.find((role) => role.id === roleId)?.name ?? roleId).join("、"), <Badge key="s" value={account.enabled ? "ENABLED" : "DISABLED"} />, account.lastLoginAt, <div key="a" className="row-actions"><PButton permission="security.account.update" can={can} tone="ghost" onClick={() => openEdit("account", account.id, { username: account.username, displayName: account.displayName, password: "********", confirmPassword: "********", roleIds: account.roleIds, enabled: account.enabled })}>编辑账号</PButton><PButton permission="security.permission.assign" can={can} tone="ghost" onClick={() => openEdit("account", account.id, { username: account.username, displayName: account.displayName, password: "********", confirmPassword: "********", roleIds: account.roleIds, enabled: account.enabled })}>分配角色</PButton><PButton permission="security.account.status" can={can} tone="ghost" onClick={() => toggleAccount(account)}>{account.enabled ? "停用" : "启用"}</PButton><PButton permission="security.account.password.reset" can={can} tone="ghost" onClick={() => ask("重置密码", "既有会话将失效；密码不进入审计。", () => { recordAudit("security.account.password.reset", "ACCOUNT_PASSWORD_RESET", account.id, "SUCCESS", "password omitted"); setToast("密码已重置"); }, true, "确认重置")}>重置密码</PButton></div>])} /></Card> : <Card><Table headers={["角色", "账号数", "权限数", "权限摘要", "内置", "操作"]} rows={slicePage(roleRows, page, pageSize).map((role) => [<button type="button" key="r" className="link-cell" onClick={() => setSelectedRoleId(role.id)}><strong>{role.name}</strong><small>{role.code}</small></button>, accounts.filter((account) => account.roleIds.includes(role.id)).length, role.permissions.includes("*") ? "全部" : role.permissions.length, role.permissions.slice(0, 5).join("、") + (role.permissions.length > 5 ? "…" : ""), role.builtIn ? "是" : "否", <div key="a" className="row-actions"><PButton permission="security.role.manage" can={can} tone="ghost" onClick={() => openEdit("role", role.id, { code: role.code, name: role.name, permissions: role.permissions })}>编辑权限</PButton><PButton permission="security.role.delete" can={can} tone="ghost" onClick={() => deleteRole(role)}>删除</PButton></div>])} /></Card>}
    <Pager page={page} pageSize={pageSize} total={total} onPageChange={setPage} onPageSizeChange={setPageSize} />
    {selectedRoleId && <Card title={`角色权限详情 · ${roles.find((role) => role.id === selectedRoleId)?.name}`} actions={<Button tone="ghost" onClick={() => setSelectedRoleId(null)}>关闭</Button>}><Table headers={["权限代码"]} rows={(roles.find((role) => role.id === selectedRoleId)?.permissions ?? []).map((permission) => [<code key={permission}>{permission}</code>])} /></Card>}
  </>;
}

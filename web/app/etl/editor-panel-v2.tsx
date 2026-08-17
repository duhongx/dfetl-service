"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import type { BusinessCatalog, Dataset, Institution, RouteRow, SourceDataSource, TargetDataSource, TaskRow } from "./model";

export type EditorKind =
  | "institution"
  | "catalog"
  | "source"
  | "target"
  | "route"
  | "task"
  | "account"
  | "profile"
  | "alertRule"
  | "alertChannel"
  | "externalClient"
  | "mapping";

export type EditorRequest = { kind: EditorKind; mode: "create" | "edit"; id?: string; initial?: EditorValues };
export type EditorValue = string | boolean | string[];
export type EditorValues = Record<string, EditorValue>;
export type EditorSavePayload = { kind: EditorKind; mode: "create" | "edit"; id?: string; values: EditorValues };

type Props = {
  request: EditorRequest | null;
  institutions: Institution[];
  catalogs: BusinessCatalog[];
  sources: SourceDataSource[];
  targets: TargetDataSource[];
  datasets: Dataset[];
  routes: RouteRow[];
  tasks: TaskRow[];
  alertChannels: Array<{ id: string; name: string }>;
  onClose: () => void;
  onSave: (payload: EditorSavePayload) => void;
};

function text(values: EditorValues, key: string) { const value = values[key]; return typeof value === "string" ? value : ""; }
function bool(values: EditorValues, key: string) { return values[key] === true; }
function list(values: EditorValues, key: string) { const value = values[key]; return Array.isArray(value) ? value : []; }
function pairs<T>(items: T[], map: (item: T) => [string, string]) { return items.map(map); }
function integerInRange(value: string, min: number, max: number) { const number = Number(value); return value.trim() !== "" && Number.isInteger(number) && number >= min && number <= max; }
function portIsValid(value: string) { return integerInRange(value, 1, 65535); }

function baseValues(request: EditorRequest, props: Props): EditorValues {
  if (request.initial) return request.initial;
  const editing = request.mode === "edit";
  switch (request.kind) {
    case "institution": {
      const row = editing ? props.institutions.find((item) => item.code === request.id) : undefined;
      return { code: row?.code ?? "", name: row?.name ?? "", type: row?.type ?? "综合医院", level: row?.level ?? "二级", region: row?.region ?? "", description: row?.description ?? "", status: row?.status ?? "ENABLED" };
    }
    case "catalog": {
      const row = editing ? props.catalogs.find((item) => item.code === request.id) : undefined;
      return { code: row?.code ?? "", name: row?.name ?? "", description: row?.description ?? "", status: row?.status ?? "ENABLED" };
    }
    case "source": {
      const row = editing ? props.sources.find((item) => item.code === request.id) : undefined;
      return { code: row?.code ?? "", name: row?.name ?? "", institutionCode: row?.institutionCode ?? props.institutions.find((item) => item.status === "ENABLED")?.code ?? "", businessCatalogCode: row?.businessCatalogCode ?? props.catalogs.find((item) => item.status === "ENABLED")?.code ?? "", dbType: row?.dbType ?? "POSTGRESQL", connectionMode: row?.connectionMode ?? "HOST_PORT", host: row?.host ?? "", port: row?.port ?? "5432", database: row?.database ?? "", defaultSchema: row?.defaultSchema ?? "", jdbcUrl: row?.jdbcUrl ?? "", username: row?.username ?? "", password: "", status: row?.status ?? "ENABLED" };
    }
    case "target": {
      const row = editing ? props.targets.find((item) => item.code === request.id) : undefined;
      const endpoints = row?.endpoints.length ? row.endpoints : [{ host: "", queryPort: "9030", httpPort: "8030" }];
      return { code: row?.code ?? "", name: row?.name ?? "", database: row?.database ?? "df_ygt", username: row?.username ?? "df_load", password: "", status: row?.status ?? "ENABLED", description: row?.description ?? "", feHosts: endpoints.map((endpoint) => endpoint.host), feQueryPorts: endpoints.map((endpoint) => endpoint.queryPort), feHttpPorts: endpoints.map((endpoint) => endpoint.httpPort) };
    }
    case "route": {
      const row = editing ? props.routes.find((item) => item.id === request.id) : undefined;
      return { institutionCode: row?.institutionCode ?? props.institutions.find((item) => item.status === "ENABLED")?.code ?? "", datasetCode: row?.datasetCode ?? props.datasets.find((item) => item.status === "ACTIVE")?.code ?? "", sourceCode: row?.sourceCode ?? "", schema: row?.schema ?? "", object: row?.object ?? "", objectType: row?.objectType ?? "VIEW", targetCode: row?.targetCode ?? props.targets.find((item) => item.status === "ENABLED")?.code ?? "", status: row?.status ?? "DISABLED" };
    }
    case "task": {
      const row = editing ? props.tasks.find((item) => item.id === request.id) : undefined;
      const dataset = row ? props.datasets.find((item) => item.code === row.datasetCode) : props.datasets.find((item) => item.status === "ACTIVE");
      return { name: row?.name ?? "", institutionCode: row?.institutionCode ?? props.institutions.find((item) => item.status === "ENABLED")?.code ?? "", datasetCode: row?.datasetCode ?? dataset?.code ?? "", routeId: row?.routeId ?? "", fetchSize: "5000", upperBoundDelayMinutes: dataset?.incrementalField ? "5" : "0", lookbackSeconds: "0", scheduleMode: row?.scheduleMode ?? "MANUAL", scheduleIntervalHours: row?.scheduleMode === "EVERY_N_HOURS" ? "4" : "", scheduleCron: row?.scheduleMode === "CRON" ? row.scheduleLabel : "", scheduleTimezone: "Asia/Shanghai", scheduleEnabled: row?.scheduleEnabled ?? true, validationOverride: row?.validationOverride ?? "INHERIT" };
    }
    case "account": return { username: "", displayName: "", password: "", confirmPassword: "", enabled: true };
    case "profile": return { username: "admin", displayName: "系统管理员", currentPassword: "", newPassword: "", confirmPassword: "" };
    case "alertRule": return { name: "", scopeType: "ALL", taskId: "", metricCode: "EXECUTION_STATUS", conditionOp: "EQ", conditionValue: "FAILED", severity: "WARNING", channels: [], enabled: true };
    case "alertChannel": return { name: "", channelType: "DINGTALK", messageFormat: "MARKDOWN", endpoint: "", secret: "", enabled: true };
    case "externalClient": return { clientId: "", clientName: "", authorizationMode: "ALL", institutions: [], enabled: true };
    case "mapping": return { profileName: "generic", profileVersion: "1", ruleCode: "", sourceDbType: "POSTGRESQL", sourceTypePattern: "", recommendedDorisType: "STRING", compatibilityLevel: "PASS", enabled: true };
  }
}

function Field({ label, hint, required, children }: { label: string; hint?: string; required?: boolean; children: ReactNode }) {
  return <label className="editor-field"><span>{label}{required && <b>*</b>}</span>{children}{hint && <small>{hint}</small>}</label>;
}
function Readonly({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return <div className="editor-readonly"><span>{label}</span><strong>{value || "—"}</strong>{hint && <small>{hint}</small>}</div>;
}

function EditorForm(props: Props & { request: EditorRequest }) {
  const { request } = props;
  const [values, setValues] = useState<EditorValues>(() => baseValues(request, props));
  const [error, setError] = useState("");
  const set = (key: string, value: EditorValue) => setValues((current) => ({ ...current, [key]: value }));
  const setListItem = (key: string, index: number, value: string) => setValues((current) => { const next = [...list(current,key)]; next[index] = value; return { ...current, [key]: next }; });
  const input = (key: string, placeholder = "") => <input value={text(values,key)} placeholder={placeholder} onChange={(event) => set(key,event.target.value)} />;
  const select = (key: string, options: Array<[string,string]>, disabled = false) => <select value={text(values,key)} disabled={disabled} onChange={(event) => set(key,event.target.value)}>{options.map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select>;
  const checkbox = (key: string, label: string) => <label className="check-line"><input type="checkbox" checked={bool(values,key)} onChange={(event) => set(key,event.target.checked)} /><span>{label}</span></label>;
  const editing = request.mode === "edit";
  const institutionCode = text(values,"institutionCode");
  const datasetCode = text(values,"datasetCode");
  const selectedSource = props.sources.find((item) => item.code === text(values,"sourceCode"));
  const selectedDataset = props.datasets.find((item) => item.code === datasetCode);
  const sourceReferenced = request.kind === "source" && editing && props.routes.some((item) => item.sourceCode === request.id);
  const routeSources = props.sources.filter((item) => item.institutionCode === institutionCode && (item.status === "ENABLED" || item.code === text(values,"sourceCode")));
  const usableRoutes = props.routes.filter((item) => item.institutionCode === institutionCode && item.datasetCode === datasetCode && item.status === "ENABLED" && item.structureStatus === "PASSED");
  const taskContract = selectedDataset ? selectedDataset.businessKeyCount === 0 ? { taskKind:"FULL_ONLY",writeMode:"REPLACE_INSTITUTION_SCOPE",keyModel:"DUPLICATE_KEY",incrementalField:"无" } : selectedDataset.incrementalField ? { taskKind:"FULL_THEN_INCREMENTAL",writeMode:"UPSERT",keyModel:"UNIQUE_KEY",incrementalField:selectedDataset.incrementalField } : { taskKind:"FULL_ONLY",writeMode:"UPSERT",keyModel:"UNIQUE_KEY",incrementalField:"无" } : { taskKind:"—",writeMode:"—",keyModel:"—",incrementalField:"—" };
  const feHosts = list(values,"feHosts");
  const feQueryPorts = list(values,"feQueryPorts");
  const feHttpPorts = list(values,"feHttpPorts");

  const addFe = () => setValues((current) => ({ ...current, feHosts:[...list(current,"feHosts"),""], feQueryPorts:[...list(current,"feQueryPorts"),"9030"], feHttpPorts:[...list(current,"feHttpPorts"),"8030"] }));
  const removeFe = (index: number) => setValues((current) => ({ ...current, feHosts:list(current,"feHosts").filter((_,i) => i !== index), feQueryPorts:list(current,"feQueryPorts").filter((_,i) => i !== index), feHttpPorts:list(current,"feHttpPorts").filter((_,i) => i !== index) }));

  const validate = () => {
    const required: Record<EditorKind,string[]> = {
      institution:["code","name"], catalog:["code","name"], source:["code","name","institutionCode","businessCatalogCode","dbType","connectionMode","username"], target:["code","name","database","username"], route:["institutionCode","datasetCode","sourceCode","object","targetCode"], task:["name","institutionCode","datasetCode","routeId","scheduleMode"], account:["username","displayName","password","confirmPassword"], profile:["username","displayName"], alertRule:["name","scopeType","metricCode","conditionOp","conditionValue","severity"], alertChannel:["name","channelType","messageFormat","endpoint"], externalClient:["clientId","clientName","authorizationMode"], mapping:["profileName","profileVersion","ruleCode","sourceDbType","sourceTypePattern","recommendedDorisType","compatibilityLevel"],
    };
    if (required[request.kind].some((key) => !text(values,key).trim())) return "请填写所有必填字段。";
    if (request.kind === "source") {
      if (text(values,"connectionMode") === "HOST_PORT" && ["host","port","database"].some((key) => !text(values,key).trim())) return "HOST_PORT 模式必须填写 Host / Port / Database。";
      if (text(values,"connectionMode") === "HOST_PORT" && !portIsValid(text(values,"port"))) return "Source Port 必须是 1..65535 的整数。";
      if (text(values,"connectionMode") === "JDBC_URL" && !text(values,"jdbcUrl").trim()) return "JDBC_URL 模式必须填写 JDBC URL。";
    }
    if (request.kind === "target") {
      if (!feHosts.length || feHosts.some((host) => !host.trim())) return "Target 至少需要一个完整 FE Endpoint，Host 不能为空。";
      if (feQueryPorts.length !== feHosts.length || feHttpPorts.length !== feHosts.length) return "FE Endpoint 配置不完整。";
      if (feQueryPorts.some((value) => !portIsValid(value)) || feHttpPorts.some((value) => !portIsValid(value))) return "FE Query/HTTP Port 必须是 1..65535 的整数。";
      const identities = feHosts.map((host,index) => `${host.trim().toLowerCase()}:${feQueryPorts[index]}`);
      if (new Set(identities).size !== identities.length) return "同一 Target 下 FE Host + Query Port 不能重复。";
    }
    if (request.kind === "route") {
      if (!routeSources.some((item) => item.code === text(values,"sourceCode"))) return "Route Source 必须属于当前 Institution；不能跨机构引用 Source。";
      const target = props.targets.find((item) => item.code === text(values,"targetCode"));
      if (!target) return "请选择有效 Target。";
      if (!editing && target.status !== "ENABLED") return "新增 Route 只能选择当前 ENABLED 的 Target。";
    }
    if (request.kind === "task") {
      if (!usableRoutes.some((item) => item.id === text(values,"routeId"))) return "Task 只能绑定当前 ENABLED + PASSED 的 Route。";
      if (!integerInRange(text(values,"fetchSize"),1,1000000)) return "Fetch Size 必须是 1..1000000 的整数。";
      if (!integerInRange(text(values,"upperBoundDelayMinutes"),0,1440)) return "Upper Bound Delay 必须是 0..1440 的整数分钟。";
      if (!integerInRange(text(values,"lookbackSeconds"),0,2592000)) return "Lookback 必须是 0..2592000 的整数秒。";
      if (!selectedDataset?.incrementalField && (text(values,"upperBoundDelayMinutes") !== "0" || text(values,"lookbackSeconds") !== "0")) return "无增量字段的 Task 固定 Upper Bound Delay=0、Lookback=0。";
      if (text(values,"scheduleMode") === "EVERY_N_HOURS" && !integerInRange(text(values,"scheduleIntervalHours"),1,8760)) return "EVERY_N_HOURS 的间隔必须是 1..8760 的整数小时。";
      if (text(values,"scheduleMode") === "CRON" && !text(values,"scheduleCron").trim()) return "CRON 模式必须填写 Cron 表达式。";
      if (text(values,"scheduleMode") !== "MANUAL" && !text(values,"scheduleTimezone").trim()) return "EVERY_N_HOURS / CRON 必须填写 Timezone。";
      if (selectedDataset?.businessKeyCount === 0 && text(values,"validationOverride") === "ROW_COUNT_CHECKSUM") return "无真实业务主键的数据集不能选择 ROW_COUNT_CHECKSUM。";
    }
    if (request.kind === "account" && text(values,"password") !== text(values,"confirmPassword")) return "两次输入的密码不一致。";
    if (request.kind === "profile" && text(values,"newPassword") && text(values,"newPassword") !== text(values,"confirmPassword")) return "两次输入的新密码不一致。";
    if (request.kind === "profile" && text(values,"newPassword") && !text(values,"currentPassword")) return "修改密码必须先填写当前密码。";
    if (request.kind === "alertRule" && text(values,"scopeType") === "TASK" && !text(values,"taskId")) return "TASK 范围必须选择 Task。";
    if (request.kind === "externalClient" && text(values,"authorizationMode") === "SELECTED" && list(values,"institutions").length === 0) return "SELECTED 授权至少选择一家机构。";
    if (request.kind === "mapping" && !integerInRange(text(values,"profileVersion"),1,2147483647)) return "Profile Version 必须是正整数。";
    return "";
  };
  const submit = (event: FormEvent) => { event.preventDefault(); const message = validate(); if (message) return setError(message); props.onSave({kind:request.kind,mode:request.mode,id:request.id,values}); };
  const titles: Record<EditorKind,string> = { institution:"医疗机构",catalog:"业务目录",source:"源端数据源",target:"目标端数据源",route:"机构采集路由",task:"同步任务",account:"管理员账号",profile:"个人中心",alertRule:"告警规则",alertChannel:"通知通道",externalClient:"External Client",mapping:"类型映射规则" };

  return <div className="modal-mask" role="presentation" onMouseDown={props.onClose}><form className="modal editor-modal" onSubmit={submit} onMouseDown={(event) => event.stopPropagation()}><header><div><h2>{request.kind === "profile" ? "个人中心" : `${editing ? "编辑" : "新增"}${titles[request.kind]}`}</h2><p>{request.kind === "profile" ? "只维护当前登录账号自身资料和修改密码。" : editing ? "稳定身份字段保持只读；保存修改当前配置。" : "按已冻结产品模型创建当前配置。"}</p></div><button type="button" onClick={props.onClose}>×</button></header><div className="modal-body">
    {request.kind === "institution" && <div className="editor-grid"><Field label="机构编码" required hint={editing ? "创建后不可修改" : "稳定业务 Code"}><input value={text(values,"code")} disabled={editing} onChange={(event) => set("code",event.target.value.toUpperCase())}/></Field><Field label="机构名称" required>{input("name")}</Field><Field label="机构类型">{input("type")}</Field><Field label="机构等级">{input("level")}</Field><Field label="区域">{input("region")}</Field><Field label="状态">{select("status",[["ENABLED","ENABLED"],["DISABLED","DISABLED"]])}</Field><Field label="说明">{input("description")}</Field></div>}
    {request.kind === "catalog" && <div className="editor-grid"><Field label="Code" required hint={editing ? "创建后不可修改" : "例如 HIS / LIS / PACS"}><input value={text(values,"code")} disabled={editing} onChange={(event) => set("code",event.target.value.toUpperCase())}/></Field><Field label="名称" required>{input("name")}</Field><Field label="状态">{select("status",[["ENABLED","ENABLED"],["DISABLED","DISABLED"]])}</Field><Field label="说明">{input("description")}</Field></div>}
    {request.kind === "source" && <><div className="editor-grid"><Field label="Source Code" required hint={editing ? "创建后不可修改" : "稳定 Code"}><input value={text(values,"code")} disabled={editing} onChange={(event) => set("code",event.target.value.toUpperCase())}/></Field><Field label="名称" required>{input("name")}</Field><Field label="唯一归属机构" required hint={sourceReferenced ? "已被 Route 引用，归属机构不可修改；需要变更请新建 Source" : undefined}>{select("institutionCode",pairs(props.institutions.filter((item) => item.status === "ENABLED" || item.code === institutionCode),(item) => [item.code,`${item.name} (${item.code})`]),sourceReferenced)}</Field><Field label="业务目录" required hint={sourceReferenced ? "已被 Route 引用，业务分类不可修改" : undefined}>{select("businessCatalogCode",pairs(props.catalogs.filter((item) => item.status === "ENABLED" || item.code === text(values,"businessCatalogCode")),(item) => [item.code,`${item.code} · ${item.name}`]),sourceReferenced)}</Field><Field label="数据库类型" required>{select("dbType",[["POSTGRESQL","PostgreSQL"],["MYSQL","MySQL"],["ORACLE","Oracle"],["SQLSERVER","SQL Server"]])}</Field><Field label="连接模式" required>{select("connectionMode",[["HOST_PORT","HOST_PORT"],["JDBC_URL","JDBC_URL"]])}</Field></div>{text(values,"connectionMode") === "HOST_PORT" ? <div className="editor-grid"><Field label="Host" required>{input("host")}</Field><Field label="Port" required hint="1..65535">{input("port")}</Field><Field label="Database" required>{input("database")}</Field><Field label="Default Schema">{input("defaultSchema")}</Field></div> : <Field label="JDBC URL" required>{input("jdbcUrl","jdbc:...")}</Field>}<div className="editor-grid"><Field label="Username" required>{input("username")}</Field><Field label="Password" hint={editing ? "留空表示不修改；不会回显明文" : "保存后只返回掩码"}><input type="password" value={text(values,"password")} onChange={(event) => set("password",event.target.value)}/></Field><Field label="状态">{select("status",[["ENABLED","ENABLED"],["DISABLED","DISABLED"]])}</Field></div></>}
    {request.kind === "target" && <><div className="editor-grid"><Field label="Target Code" required><input value={text(values,"code")} disabled={editing} onChange={(event) => set("code",event.target.value.toUpperCase())}/></Field><Field label="名称" required>{input("name")}</Field><Field label="Database" required>{input("database")}</Field><Field label="Username" required>{input("username")}</Field><Field label="Password" hint={editing ? "留空表示不修改" : "不会回显明文"}><input type="password" value={text(values,"password")} onChange={(event) => set("password",event.target.value)}/></Field><Field label="状态">{select("status",[["ENABLED","ENABLED"],["DISABLED","DISABLED"]])}</Field></div><div className="editor-subtitle"><span>FE Endpoints</span><button type="button" className="btn btn-ghost" onClick={addFe}>新增 FE</button></div><div className="stack">{feHosts.map((host,index) => <div className="card" key={`fe-${index}`}><div className="editor-grid"><Field label={`FE #${index + 1} Host`} required><input value={host} onChange={(event) => setListItem("feHosts",index,event.target.value)}/></Field><Field label="Query Port" required hint="1..65535"><input value={feQueryPorts[index] ?? ""} onChange={(event) => setListItem("feQueryPorts",index,event.target.value)}/></Field><Field label="HTTP Port" required hint="1..65535"><input value={feHttpPorts[index] ?? ""} onChange={(event) => setListItem("feHttpPorts",index,event.target.value)}/></Field></div>{feHosts.length > 1 && <button type="button" className="btn btn-ghost" onClick={() => removeFe(index)}>移除 FE</button>}</div>)}</div><div className="editor-note">FE 是 Target 的当前纯配置子对象，可增删；同一 Target 下 Host + Query Port 不能重复，顺序自动生成 ordinal。</div><Field label="说明">{input("description")}</Field></>}
    {request.kind === "route" && <><div className="editor-grid"><Field label="机构" required hint={editing ? "Route 业务身份固定" : "先选机构"}><select value={institutionCode} disabled={editing} onChange={(event) => setValues((current) => ({...current,institutionCode:event.target.value,sourceCode:""}))}>{pairs(props.institutions.filter((item) => item.status === "ENABLED" || item.code === institutionCode),(item) => [item.code,`${item.name} (${item.code})`]).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select></Field><Field label="标准 Dataset" required hint={editing ? "Route 业务身份固定" : "一机构 + Dataset 只允许一条未删除 Route"}>{select("datasetCode",pairs(props.datasets.filter((item) => item.status === "ACTIVE" || item.code === datasetCode),(item) => [item.code,`${item.name} · ${item.code}`]),editing)}</Field><Field label="Source" required>{select("sourceCode",[["","请选择 Source"],...pairs(routeSources,(item) => [item.code,item.name])])}</Field><Readonly label="业务目录" value={selectedSource ? `${selectedSource.businessCatalogCode} · ${selectedSource.businessCatalogName}` : "由 Source 自动带出"} hint="只读，不在 Route 重复维护"/><Field label="Schema">{input("schema")}</Field><Field label="Table / View" required>{input("object")}</Field><Field label="对象类型">{select("objectType",[["TABLE","TABLE"],["VIEW","VIEW"],["MATERIALIZED_VIEW","MATERIALIZED_VIEW"]])}</Field><Field label="Target" required>{select("targetCode",pairs(props.targets.filter((item) => item.status === "ENABLED" || item.code === text(values,"targetCode")),(item) => [item.code,item.name]))}</Field><Field label="业务状态">{select("status",[["DISABLED","DISABLED"],["ENABLED","ENABLED"]])}</Field></div><div className="editor-note">保存后按 contract hash 创建或复用不可变 Route Version，并将当前结构状态标记为需要重新核对；业务状态与结构状态不合并。</div></>}
    {request.kind === "task" && <><div className="editor-grid"><Field label="任务名称" required>{input("name")}</Field><Field label="机构" required hint={editing ? "Task 固定身份，编辑不可修改" : "固定身份一部分"}><select value={institutionCode} disabled={editing} onChange={(event) => setValues((current) => ({...current,institutionCode:event.target.value,routeId:""}))}>{pairs(props.institutions.filter((item) => item.status === "ENABLED" || item.code === institutionCode),(item) => [item.code,`${item.name} (${item.code})`]).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select></Field><Field label="Dataset" required hint={editing ? "Task 固定身份，编辑不可修改" : "固定身份一部分"}><select value={datasetCode} disabled={editing} onChange={(event) => { const nextDataset = props.datasets.find((item) => item.code === event.target.value); setValues((current) => ({...current,datasetCode:event.target.value,routeId:"",upperBoundDelayMinutes:nextDataset?.incrementalField ? "5" : "0",lookbackSeconds:"0",validationOverride:nextDataset?.businessKeyCount === 0 && current.validationOverride === "ROW_COUNT_CHECKSUM" ? "INHERIT" : current.validationOverride})); }}>{pairs(props.datasets.filter((item) => item.status === "ACTIVE" || item.code === datasetCode),(item) => [item.code,`${item.name} · ${item.code}`]).map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select></Field><Field label="可用 Route" required hint="只显示同机构+Dataset 且 ENABLED+PASSED 的 Route">{select("routeId",[["","请选择 Route"],...pairs(usableRoutes,(item) => [item.id,`${item.id} · V${item.version} · ${item.sourceName}`])])}</Field></div><h3 className="editor-subtitle">由 Dataset 合同确定</h3><div className="editor-grid"><Readonly label="Task Kind" value={taskContract.taskKind}/><Readonly label="Write Mode" value={taskContract.writeMode}/><Readonly label="Doris Key Model" value={taskContract.keyModel}/><Readonly label="增量字段" value={taskContract.incrementalField}/></div><h3 className="editor-subtitle">读取与调度</h3><div className="editor-grid"><Field label="Fetch Size" hint="1..1000000">{input("fetchSize")}</Field><Field label="Upper Bound Delay (min)" hint={selectedDataset?.incrementalField ? "0..1440" : "无增量字段固定为 0"}><input value={text(values,"upperBoundDelayMinutes")} disabled={!selectedDataset?.incrementalField} onChange={(event) => set("upperBoundDelayMinutes",event.target.value)}/></Field><Field label="Lookback (sec)" hint={selectedDataset?.incrementalField ? "0..2592000" : "无增量字段固定为 0"}><input value={text(values,"lookbackSeconds")} disabled={!selectedDataset?.incrementalField} onChange={(event) => set("lookbackSeconds",event.target.value)}/></Field><Field label="Schedule Mode">{select("scheduleMode",[["MANUAL","MANUAL"],["EVERY_N_HOURS","EVERY_N_HOURS"],["CRON","CRON"]])}</Field>{text(values,"scheduleMode") === "EVERY_N_HOURS" && <Field label="间隔小时" required hint="1..8760">{input("scheduleIntervalHours")}</Field>}{text(values,"scheduleMode") === "CRON" && <Field label="Cron" required>{input("scheduleCron")}</Field>}{text(values,"scheduleMode") !== "MANUAL" && <Field label="Timezone" required>{input("scheduleTimezone")}</Field>}<Field label="Validation Override">{select("validationOverride",[["INHERIT","INHERIT"],["ROW_COUNT","ROW_COUNT"],["ROW_COUNT_CHECKSUM","ROW_COUNT_CHECKSUM"]])}</Field></div>{checkbox("scheduleEnabled","启用调度（MANUAL 与开关保持正交）")}<div className="editor-note">EVERY_N_HOURS 的最终错峰 Cron 固化到 Task；Dataset Default 只作为创建输入，不热更新已有 Task。</div></>}
    {request.kind === "account" && <div className="editor-grid"><Field label="用户名" required>{input("username")}</Field><Field label="显示名称" required>{input("displayName")}</Field><Field label="初始密码" required><input type="password" value={text(values,"password")} onChange={(event) => set("password",event.target.value)}/></Field><Field label="确认密码" required><input type="password" value={text(values,"confirmPassword")} onChange={(event) => set("confirmPassword",event.target.value)}/></Field>{checkbox("enabled","创建后立即启用")}</div>}
    {request.kind === "profile" && <><div className="editor-grid"><Readonly label="用户名" value={text(values,"username")} hint="个人中心不修改登录身份"/><Field label="显示名称" required>{input("displayName")}</Field></div><h3 className="editor-subtitle">修改密码（可选）</h3><div className="editor-grid"><Field label="当前密码"><input type="password" value={text(values,"currentPassword")} onChange={(event) => set("currentPassword",event.target.value)}/></Field><Field label="新密码"><input type="password" value={text(values,"newPassword")} onChange={(event) => set("newPassword",event.target.value)}/></Field><Field label="确认新密码"><input type="password" value={text(values,"confirmPassword")} onChange={(event) => set("confirmPassword",event.target.value)}/></Field></div><div className="editor-note">修改密码成功后既有 Refresh Token 必须失效。</div></>}
    {request.kind === "alertRule" && <><div className="editor-grid"><Field label="规则名称" required>{input("name")}</Field><Field label="Scope">{select("scopeType",[["ALL","ALL"],["TASK","TASK"]])}</Field>{text(values,"scopeType") === "TASK" && <Field label="Task" required>{select("taskId",[["","请选择 Task"],...pairs(props.tasks,(item) => [item.id,item.name])])}</Field>}<Field label="Metric" required>{select("metricCode",[["EXECUTION_STATUS","EXECUTION_STATUS"],["VALIDATION_RESULT","VALIDATION_RESULT"],["PRECHECK_STATUS","PRECHECK_STATUS"],["OUTBOX_STATUS","OUTBOX_STATUS"],["DELETE_SNAPSHOT_STATUS","DELETE_SNAPSHOT_STATUS"]])}</Field><Field label="Condition Op">{select("conditionOp",[["EQ","EQ"],["NE","NE"],["GT","GT"],["GTE","GTE"],["LT","LT"],["LTE","LTE"]])}</Field><Field label="Condition Value" required>{input("conditionValue")}</Field><Field label="Severity">{select("severity",[["INFO","INFO"],["WARNING","WARNING"],["CRITICAL","CRITICAL"]])}</Field>{checkbox("enabled","启用规则")}</div><div className="choice-list"><strong>通知通道（多选）</strong>{props.alertChannels.length ? props.alertChannels.map((channel) => { const selected = list(values,"channels").includes(channel.id); return <label key={channel.id}><input type="checkbox" checked={selected} onChange={(event) => set("channels",event.target.checked ? [...list(values,"channels"),channel.id] : list(values,"channels").filter((item) => item !== channel.id))}/><span>{channel.name}</span></label> }) : <span>请先创建通知通道。</span>}</div></>}
    {request.kind === "alertChannel" && <div className="editor-grid"><Field label="通道名称" required>{input("name")}</Field><Field label="类型">{select("channelType",[["DINGTALK","DINGTALK"],["WECOM","WECOM"]])}</Field><Field label="消息格式">{select("messageFormat",[["TEXT","TEXT"],["MARKDOWN","MARKDOWN"]])}</Field><Field label="Webhook / Endpoint" required>{input("endpoint")}</Field><Field label="Secret" hint="保存后只显示掩码，不写入日志/Audit"><input type="password" value={text(values,"secret")} onChange={(event) => set("secret",event.target.value)}/></Field>{checkbox("enabled","启用通道")}</div>}
    {request.kind === "externalClient" && <><div className="editor-grid"><Field label="Client ID" required hint="稳定、大小写敏感、不可复用"><input value={text(values,"clientId")} disabled={editing} onChange={(event) => set("clientId",event.target.value)}/></Field><Field label="展示名称" required>{input("clientName")}</Field><Field label="授权模式">{select("authorizationMode",[["ALL","ALL"],["SELECTED","SELECTED"]])}</Field>{checkbox("enabled","启用 Client")}</div>{text(values,"authorizationMode") === "SELECTED" && <div className="choice-list"><strong>授权机构</strong>{props.institutions.map((item) => { const selected = list(values,"institutions").includes(item.code); return <label key={item.code}><input type="checkbox" checked={selected} onChange={(event) => set("institutions",event.target.checked ? [...list(values,"institutions"),item.code] : list(values,"institutions").filter((code) => code !== item.code))}/><span>{item.name} <small>{item.code}</small></span></label> })}</div>}{!editing && <div className="editor-note">创建成功后 Secret 只展示一次；后续只能重置，不支持双 Secret/Secret History。</div>}</>}
    {request.kind === "mapping" && <div className="editor-grid"><Field label="Profile" required>{input("profileName")}</Field><Field label="Profile Version" required hint="正整数">{input("profileVersion")}</Field><Field label="Rule Code" required>{input("ruleCode")}</Field><Field label="Source DB">{select("sourceDbType",[["POSTGRESQL","POSTGRESQL"],["MYSQL","MYSQL"],["ORACLE","ORACLE"],["SQLSERVER","SQLSERVER"]])}</Field><Field label="Source Type Pattern" required>{input("sourceTypePattern")}</Field><Field label="Recommended Doris Type" required>{input("recommendedDorisType")}</Field><Field label="Compatibility">{select("compatibilityLevel",[["PASS","PASS"],["WARN","WARN"],["REJECT","REJECT"]])}</Field>{checkbox("enabled","启用规则")}</div>}
    {error && <div className="editor-error">{error}</div>}
  </div><footer><button type="button" className="btn" onClick={props.onClose}>取消</button><button type="submit" className="btn btn-primary">{request.kind === "profile" ? "保存个人资料" : editing ? "保存修改" : "创建"}</button></footer></form></div>;
}

export function EditorPanelV2(props: Props) {
  if (!props.request) return null;
  return <EditorForm key={`${props.request.kind}:${props.request.mode}:${props.request.id ?? "new"}`} {...props} request={props.request} />;
}

"use client";

import { useMemo, useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import type {
  AccountRow,
  BusinessSystemInstance,
  Dataset,
  Institution,
  RoleRow,
  RouteRow,
  SourceDataSource,
  TargetDataSource,
  TaskRow,
} from "./model";
import { permissionGroups } from "./permissions";

export type EditorKind =
  | "institution"
  | "systemInstance"
  | "source"
  | "target"
  | "route"
  | "task"
  | "taskVersion"
  | "account"
  | "role"
  | "profile"
  | "alertRule"
  | "alertChannel"
  | "externalClient"
  | "mapping";

export type EditorRequest = {
  kind: EditorKind;
  mode: "create" | "edit";
  id?: string;
  initial?: EditorValues;
};

export type EditorValue = string | boolean | string[];
export type EditorValues = Record<string, EditorValue>;
export type EditorSavePayload = {
  kind: EditorKind;
  mode: "create" | "edit";
  id?: string;
  values: EditorValues;
};

type Props = {
  request: EditorRequest | null;
  institutions: Institution[];
  systemInstances: BusinessSystemInstance[];
  sources: SourceDataSource[];
  targets: TargetDataSource[];
  datasets: Dataset[];
  routes: RouteRow[];
  tasks: TaskRow[];
  accounts: AccountRow[];
  roles: RoleRow[];
  alertChannels: Array<{ id: string; name: string }>;
  onClose: () => void;
  onSave: (payload: EditorSavePayload) => void;
};

function text(values: EditorValues, key: string): string {
  const value = values[key];
  return typeof value === "string" ? value : "";
}

function bool(values: EditorValues, key: string): boolean {
  return values[key] === true;
}

function list(values: EditorValues, key: string): string[] {
  const value = values[key];
  return Array.isArray(value) ? value : [];
}

function integerInRange(value: string, min: number, max: number): boolean {
  const number = Number(value);
  return value.trim() !== "" && Number.isInteger(number) && number >= min && number <= max;
}

function portIsValid(value: string): boolean {
  return integerInRange(value, 1, 65535);
}

function selectedValues(event: ChangeEvent<HTMLSelectElement>): string[] {
  return Array.from(event.target.selectedOptions, (option) => option.value);
}

function baseValues(request: EditorRequest, props: Props): EditorValues {
  if (request.initial) return request.initial;
  switch (request.kind) {
    case "institution":
      return { code: "", name: "", type: "综合医院", level: "二级", region: "", description: "", status: "ENABLED" };
    case "systemInstance":
      return { code: "", name: "", systemType: "HIS", vendor: "", productVersion: "", institutionIds: [], datasourceIds: [], status: "ENABLED", description: "" };
    case "source":
      return { code: "", name: "", dbType: "POSTGRESQL", connectionMode: "HOST_PORT", host: "", port: "5432", database: "", defaultSchema: "", jdbcUrl: "", username: "", password: "", sslEnabled: false, readOnly: true, queryTimeoutSeconds: "60", connectTimeoutSeconds: "10", socketTimeoutSeconds: "60", poolMaxSize: "4", status: "ENABLED", description: "" };
    case "target":
      return { code: "", name: "", database: "df_ygt", username: "df_load", password: "", endpoints: "192.168.1.41:9030:8030", status: "ENABLED", description: "" };
    case "route": {
      const instance = props.systemInstances.find((item) => item.status === "ENABLED");
      const sourceId = instance?.datasourceIds[0] ?? "";
      return { datasetCode: props.datasets.find((item) => item.status === "ACTIVE")?.code ?? "", systemInstanceId: instance?.id ?? "", sourceId, schema: "", object: "", objectType: "VIEW", targetId: props.targets.find((item) => item.status === "ENABLED")?.id ?? "", institutionIds: instance?.institutionIds ?? [] };
    }
    case "task":
      return { name: "", institutionId: props.institutions.find((item) => item.status === "ENABLED")?.id ?? "", datasetCode: props.datasets.find((item) => item.status === "ACTIVE")?.code ?? "", routeId: "", fetchSize: "5000", upperBoundDelayMinutes: "5", lookbackSeconds: "0", scheduleMode: "MANUAL", scheduleIntervalHours: "", scheduleCron: "", scheduleTimezone: "Asia/Shanghai", scheduleEnabled: true, validationOverride: "INHERIT", changeSummary: "初始版本" };
    case "taskVersion":
      return { routeId: "", fetchSize: "5000", upperBoundDelayMinutes: "5", lookbackSeconds: "0", scheduleMode: "MANUAL", scheduleIntervalHours: "", scheduleCron: "", scheduleTimezone: "Asia/Shanghai", validationOverride: "INHERIT", changeSummary: "" };
    case "account":
      return { username: "", displayName: "", password: "", confirmPassword: "", roleIds: [], enabled: true };
    case "role":
      return { code: "", name: "", permissions: [] };
    case "profile":
      return { username: "admin", displayName: "系统管理员", currentPassword: "", newPassword: "", confirmPassword: "" };
    case "alertRule":
      return { name: "", scopeType: "ALL", taskId: "", metricCode: "EXECUTION_STATUS", conditionOp: "EQ", conditionValue: "FAILED", severity: "WARNING", channels: [], enabled: true };
    case "alertChannel":
      return { name: "", channelType: "DINGTALK", messageFormat: "MARKDOWN", endpoint: "", secret: "", enabled: true };
    case "externalClient":
      return { clientId: "", clientName: "", authorizationMode: "ALL", institutions: [], enabled: true };
    case "mapping":
      return { profileName: "generic", profileVersion: "1", ruleCode: "", sourceDbType: "POSTGRESQL", sourceTypePattern: "", recommendedDorisType: "STRING", compatibilityLevel: "PASS", enabled: true };
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
  const input = (key: string, placeholder = "", type = "text") => (
    <input type={type} value={text(values, key)} placeholder={placeholder} onChange={(event) => set(key, event.target.value)} />
  );
  const select = (key: string, options: Array<[string, string]>, disabled = false) => (
    <select value={text(values, key)} disabled={disabled} onChange={(event) => set(key, event.target.value)}>
      {options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select>
  );
  const multiSelect = (key: string, options: Array<[string, string]>, hint?: string) => (
    <Field label="" hint={hint}>
      <select multiple value={list(values, key)} onChange={(event) => set(key, selectedValues(event))} style={{ minHeight: 132 }}>
        {options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
    </Field>
  );
  const checkbox = (key: string, label: string) => (
    <label className="check-line"><input type="checkbox" checked={bool(values, key)} onChange={(event) => set(key, event.target.checked)} /><span>{label}</span></label>
  );

  const selectedInstance = props.systemInstances.find((item) => item.id === text(values, "systemInstanceId"));
  const routeSources = props.sources.filter((item) => selectedInstance?.datasourceIds.includes(item.id));
  const routeInstitutions = props.institutions.filter((item) => selectedInstance?.institutionIds.includes(item.id));
  const selectedDataset = props.datasets.find((item) => item.code === text(values, "datasetCode"));
  const selectedInstitutionId = text(values, "institutionId");
  const usableRoutes = props.routes.filter((route) =>
    route.deletedAt === null
    && route.datasetCode === text(values, "datasetCode")
    && route.institutionIds.includes(selectedInstitutionId)
  );
  const taskContract = selectedDataset
    ? selectedDataset.businessKeyCount === 0
      ? { taskKind: "FULL_ONLY", writeMode: "REPLACE_INSTITUTION_SCOPE", keyModel: "DUPLICATE_KEY", incrementalField: "无", validation: "ROW_COUNT" }
      : selectedDataset.incrementalField
        ? { taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: selectedDataset.incrementalField, validation: "可选 ROW_COUNT_CHECKSUM" }
        : { taskKind: "FULL_ONLY", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "无", validation: "可选 ROW_COUNT_CHECKSUM" }
    : { taskKind: "—", writeMode: "—", keyModel: "—", incrementalField: "—", validation: "—" };

  const allPermissions = useMemo(
    () => permissionGroups.flatMap((group) => group.permissions.map((permission) => [permission, `${group.domain} · ${permission}`] as [string, string])),
    [],
  );

  const validate = (): string => {
    const required: Partial<Record<EditorKind, string[]>> = {
      institution: ["code", "name"],
      systemInstance: ["code", "name", "systemType"],
      source: ["code", "name", "dbType", "connectionMode", "username"],
      target: ["code", "name", "database", "username", "endpoints"],
      route: ["datasetCode", "systemInstanceId", "sourceId", "object", "targetId"],
      task: ["name", "institutionId", "datasetCode", "routeId", "scheduleMode"],
      taskVersion: ["routeId", "scheduleMode", "changeSummary"],
      account: ["username", "displayName", "password", "confirmPassword"],
      role: ["code", "name"],
      profile: ["username", "displayName"],
      alertRule: ["name", "scopeType", "metricCode", "conditionOp", "conditionValue", "severity"],
      alertChannel: ["name", "channelType", "messageFormat", "endpoint"],
      externalClient: ["clientId", "clientName", "authorizationMode"],
      mapping: ["profileName", "profileVersion", "ruleCode", "sourceDbType", "sourceTypePattern", "recommendedDorisType", "compatibilityLevel"],
    };
    if ((required[request.kind] ?? []).some((key) => !text(values, key).trim())) return "请填写所有必填字段。";

    if (request.kind === "systemInstance") {
      if (!list(values, "institutionIds").length) return "业务系统实例至少需要关联一家覆盖机构。";
      if (!list(values, "datasourceIds").length) return "业务系统实例至少需要关联一个源数据源。";
    }

    if (request.kind === "source") {
      if (text(values, "connectionMode") === "HOST_PORT" && ["host", "port", "database"].some((key) => !text(values, key).trim())) return "HOST_PORT 模式必须填写 Host、Port 和 Database。";
      if (text(values, "connectionMode") === "HOST_PORT" && !portIsValid(text(values, "port"))) return "Source Port 必须是 1..65535 的整数。";
      if (text(values, "connectionMode") === "JDBC_URL" && !text(values, "jdbcUrl").trim()) return "JDBC_URL 模式必须填写 JDBC URL。";
      if (["queryTimeoutSeconds", "connectTimeoutSeconds", "socketTimeoutSeconds", "poolMaxSize"].some((key) => !integerInRange(text(values, key), 1, 2147483647))) return "Timeout 和 Pool Max Size 必须是正整数。";
    }

    if (request.kind === "target") {
      const endpoints = text(values, "endpoints").split(",").map((item) => item.trim()).filter(Boolean);
      if (!endpoints.length) return "Target 至少需要一个 FE Endpoint。";
      for (const endpoint of endpoints) {
        const [host, queryPort, httpPort] = endpoint.split(":");
        if (!host || !portIsValid(queryPort ?? "") || !portIsValid(httpPort ?? "")) return "FE Endpoint 使用 host:queryPort:httpPort，多项以逗号分隔。";
      }
    }

    if (request.kind === "route") {
      if (!selectedInstance) return "请选择有效业务系统实例。";
      if (!selectedInstance.datasourceIds.includes(text(values, "sourceId"))) return "Route 的源数据源必须属于所选业务系统实例。";
      const institutions = list(values, "institutionIds");
      if (!institutions.length) return "采集链路至少覆盖一家机构。";
      if (institutions.some((id) => !selectedInstance.institutionIds.includes(id))) return "Route 覆盖机构必须是业务系统实例覆盖机构的子集。";
    }

    if (request.kind === "task" || request.kind === "taskVersion") {
      if (!usableRoutes.some((item) => item.id === text(values, "routeId"))) return "请选择覆盖当前机构且匹配当前数据集的采集链路。";
      if (!integerInRange(text(values, "fetchSize"), 1, 1000000)) return "Fetch Size 必须是 1..1000000 的整数。";
      if (!integerInRange(text(values, "upperBoundDelayMinutes"), 0, 1440)) return "Upper Bound Delay 必须是 0..1440 的整数分钟。";
      if (!integerInRange(text(values, "lookbackSeconds"), 0, 2592000)) return "Lookback 必须是 0..2592000 的整数秒。";
      if (text(values, "scheduleMode") === "EVERY_N_HOURS" && !integerInRange(text(values, "scheduleIntervalHours"), 1, 8760)) return "EVERY_N_HOURS 必须填写 1..8760 的整数小时。";
      if (text(values, "scheduleMode") === "CRON" && !text(values, "scheduleCron").trim()) return "CRON 模式必须填写表达式。";
      if (selectedDataset?.businessKeyCount === 0 && text(values, "validationOverride") === "ROW_COUNT_CHECKSUM") return "无真实业务主键的数据集不能配置 ROW_COUNT_CHECKSUM。";
    }

    if (request.kind === "account") {
      if (text(values, "password") !== text(values, "confirmPassword")) return "两次输入的密码不一致。";
      if (!list(values, "roleIds").length) return "账号至少需要一个角色。";
    }

    if (request.kind === "role" && !list(values, "permissions").length) return "角色至少需要一个权限。";
    if (request.kind === "profile" && text(values, "newPassword") && text(values, "newPassword") !== text(values, "confirmPassword")) return "两次输入的新密码不一致。";
    return "";
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const issue = validate();
    if (issue) {
      setError(issue);
      return;
    }
    props.onSave({ kind: request.kind, mode: request.mode, id: request.id, values });
  };

  const title: Record<EditorKind, string> = {
    institution: "医疗机构",
    systemInstance: "业务系统实例",
    source: "源数据源",
    target: "目标 Doris",
    route: "采集链路",
    task: "同步任务",
    taskVersion: "任务新版本",
    account: "账号",
    role: "角色",
    profile: "个人资料",
    alertRule: "告警规则",
    alertChannel: "通知通道",
    externalClient: "外部 Client",
    mapping: "类型映射",
  };

  return <div className="modal-mask" role="presentation" onMouseDown={props.onClose}>
    <form className="modal editor-modal" onSubmit={submit} onMouseDown={(event) => event.stopPropagation()}>
      <header>
        <div><h2>{request.mode === "create" ? "新增" : "编辑"}{title[request.kind]}</h2><p>所有保存操作只更新当前前端 Mock，并保留目标权限和审计语义。</p></div>
        <button type="button" onClick={props.onClose}>×</button>
      </header>
      <div className="modal-body">
        {request.kind === "institution" && <div className="editor-grid">
          <Field label="机构编码" required>{input("code")}</Field>
          <Field label="机构名称" required>{input("name")}</Field>
          <Field label="机构类型">{input("type")}</Field>
          <Field label="等级">{input("level")}</Field>
          <Field label="区域">{input("region")}</Field>
          <Field label="状态">{select("status", [["ENABLED", "启用"], ["DISABLED", "停用"]])}</Field>
          <Field label="说明">{input("description")}</Field>
        </div>}

        {request.kind === "systemInstance" && <>
          <div className="editor-grid">
            <Field label="实例编码" required>{input("code")}</Field>
            <Field label="实例名称" required>{input("name")}</Field>
            <Field label="系统类型" required>{select("systemType", [["HIS", "HIS"], ["LIS", "LIS"], ["PACS", "PACS"], ["EMR", "EMR"], ["OTHER", "其他"]])}</Field>
            <Field label="厂商">{input("vendor")}</Field>
            <Field label="产品版本">{input("productVersion")}</Field>
            <Field label="状态">{select("status", [["ENABLED", "启用"], ["DISABLED", "停用"]])}</Field>
            <Field label="说明">{input("description")}</Field>
          </div>
          <div className="editor-grid">
            <div><h3 className="editor-subtitle">覆盖机构</h3>{multiSelect("institutionIds", props.institutions.map((item) => [item.id, `${item.code} · ${item.name}`]), "支持多选；不维护厂商内部机构代码。")}</div>
            <div><h3 className="editor-subtitle">关联源数据源</h3>{multiSelect("datasourceIds", props.sources.map((item) => [item.id, `${item.code} · ${item.name}`]), "纯关联；不配置用途、主备或优先级。")}</div>
          </div>
        </>}

        {request.kind === "source" && <>
          <div className="editor-grid">
            <Field label="Code" required>{input("code")}</Field>
            <Field label="名称" required>{input("name")}</Field>
            <Field label="数据库类型" required>{select("dbType", [["POSTGRESQL", "PostgreSQL"], ["MYSQL", "MySQL"], ["ORACLE", "Oracle"], ["SQLSERVER", "SQL Server"]])}</Field>
            <Field label="连接方式" required>{select("connectionMode", [["HOST_PORT", "Host / Port"], ["JDBC_URL", "JDBC URL"]])}</Field>
            {text(values, "connectionMode") === "HOST_PORT" ? <>
              <Field label="Host" required>{input("host")}</Field>
              <Field label="Port" required>{input("port")}</Field>
              <Field label="Database" required>{input("database")}</Field>
              <Field label="Default Schema">{input("defaultSchema")}</Field>
            </> : <Field label="JDBC URL" required>{input("jdbcUrl")}</Field>}
            <Field label="Username" required>{input("username")}</Field>
            <Field label="Password" hint="编辑时留空表示不轮换凭据。">{input("password", "", "password")}</Field>
            <Field label="Query Timeout">{input("queryTimeoutSeconds")}</Field>
            <Field label="Connect Timeout">{input("connectTimeoutSeconds")}</Field>
            <Field label="Socket Timeout">{input("socketTimeoutSeconds")}</Field>
            <Field label="Pool Max Size">{input("poolMaxSize")}</Field>
            <Field label="状态">{select("status", [["ENABLED", "启用"], ["DISABLED", "停用"]])}</Field>
            <Field label="说明">{input("description")}</Field>
          </div>
          <div className="actions">{checkbox("sslEnabled", "启用 SSL")}{checkbox("readOnly", "只读连接")}</div>
          <div className="editor-note">Source 不直接归属机构或业务类型。机构覆盖和系统类型由 Business System Instance 关系表达。</div>
        </>}

        {request.kind === "target" && <div className="editor-grid">
          <Field label="Code" required>{input("code")}</Field>
          <Field label="名称" required>{input("name")}</Field>
          <Field label="Database" required>{input("database")}</Field>
          <Field label="Username" required>{input("username")}</Field>
          <Field label="Password">{input("password", "", "password")}</Field>
          <Field label="FE Endpoints" required hint="host:queryPort:httpPort，多项使用逗号分隔。">{input("endpoints", "192.168.1.41:9030:8030,192.168.1.42:9030:8030")}</Field>
          <Field label="状态">{select("status", [["ENABLED", "启用"], ["DISABLED", "停用"]])}</Field>
          <Field label="说明">{input("description")}</Field>
        </div>}

        {request.kind === "route" && <>
          <div className="editor-grid">
            <Field label="标准数据集" required>{select("datasetCode", props.datasets.filter((item) => item.status === "ACTIVE").map((item) => [item.code, `${item.code} · ${item.name}`]))}</Field>
            <Field label="业务系统实例" required>{select("systemInstanceId", props.systemInstances.filter((item) => item.status === "ENABLED" || item.id === text(values, "systemInstanceId")).map((item) => [item.id, `${item.code} · ${item.name}`]))}</Field>
            <Field label="源数据源" required hint="只能选择当前实例已关联的数据源。">{select("sourceId", routeSources.map((item) => [item.id, `${item.code} · ${item.name}`]))}</Field>
            <Field label="目标 Doris" required>{select("targetId", props.targets.map((item) => [item.id, `${item.code} · ${item.name}`]))}</Field>
            <Field label="Schema">{input("schema")}</Field>
            <Field label="源对象" required>{input("object")}</Field>
            <Field label="对象类型">{select("objectType", [["TABLE", "TABLE"], ["VIEW", "VIEW"], ["MATERIALIZED_VIEW", "MATERIALIZED VIEW"]])}</Field>
          </div>
          <h3 className="editor-subtitle">链路覆盖机构</h3>
          {multiSelect("institutionIds", routeInstitutions.map((item) => [item.id, `${item.code} · ${item.name}`]), "必须是业务系统实例覆盖机构的子集；同一共享源对象不按机构复制链路。")}
          <div className="editor-note">采集链路不保存启停状态，也不提供独立结构 Gate。结构、字段集合和字段解析在预检第一阶段核对。</div>
        </>}

        {(request.kind === "task" || request.kind === "taskVersion") && <>
          {request.kind === "task" && <div className="editor-grid">
            <Field label="任务名称" required>{input("name")}</Field>
            <Field label="医疗机构" required>{select("institutionId", props.institutions.filter((item) => item.status === "ENABLED").map((item) => [item.id, `${item.code} · ${item.name}`]))}</Field>
            <Field label="标准数据集" required>{select("datasetCode", props.datasets.filter((item) => item.status === "ACTIVE").map((item) => [item.code, `${item.code} · ${item.name}`]))}</Field>
          </div>}
          {request.kind === "taskVersion" && <div className="editor-grid">
            <Readonly label="任务" value={text(values, "taskName")} />
            <Readonly label="固定身份" value={`${text(values, "institutionName")} / ${text(values, "datasetCode")}`} hint="创建新版本不能改变任务的机构和数据集身份。" />
          </div>}
          <div className="editor-grid">
            <Field label="采集链路" required>{select("routeId", usableRoutes.map((item) => [item.id, `${item.id} · ${item.systemInstanceName} · ${item.sourceName} · ${item.schema}.${item.object}`]))}</Field>
            <Readonly label="任务类型" value={taskContract.taskKind} />
            <Readonly label="写入方式" value={taskContract.writeMode === "REPLACE_INSTITUTION_SCOPE" ? "替换当前机构范围" : taskContract.writeMode} />
            <Readonly label="Doris 键模型" value={taskContract.keyModel} />
            <Readonly label="增量字段" value={taskContract.incrementalField} />
            <Readonly label="内容校验能力" value={taskContract.validation} />
            <Field label="Fetch Size">{input("fetchSize")}</Field>
            <Field label="Upper Bound Delay（分钟）">{input("upperBoundDelayMinutes")}</Field>
            <Field label="Lookback（秒）">{input("lookbackSeconds")}</Field>
            <Field label="调度模式">{select("scheduleMode", [["MANUAL", "人工"], ["EVERY_N_HOURS", "每 N 小时"], ["CRON", "CRON"]])}</Field>
            {text(values, "scheduleMode") === "EVERY_N_HOURS" && <Field label="间隔小时">{input("scheduleIntervalHours")}</Field>}
            {text(values, "scheduleMode") === "CRON" && <Field label="Cron">{input("scheduleCron")}</Field>}
            <Field label="Timezone">{input("scheduleTimezone")}</Field>
            <Field label="Validation">{select("validationOverride", [["INHERIT", "继承"], ["ROW_COUNT", "ROW_COUNT"], ["ROW_COUNT_CHECKSUM", "ROW_COUNT_CHECKSUM"]])}</Field>
            <Field label="变更说明" required>{input("changeSummary")}</Field>
          </div>
          {request.kind === "task" && checkbox("scheduleEnabled", "创建后启用自动调度")}
          <div className="editor-note">保存后创建不可变 Task Version。后续执行固定引用版本；不得原地覆盖历史执行合同。</div>
        </>}

        {request.kind === "account" && <>
          <div className="editor-grid">
            <Field label="Username" required>{input("username")}</Field>
            <Field label="显示名称" required>{input("displayName")}</Field>
            <Field label="初始密码" required>{input("password", "", "password")}</Field>
            <Field label="确认密码" required>{input("confirmPassword", "", "password")}</Field>
          </div>
          <h3 className="editor-subtitle">角色</h3>
          {multiSelect("roleIds", props.roles.map((item) => [item.id, `${item.code} · ${item.name}`]))}
          {checkbox("enabled", "启用账号")}
        </>}

        {request.kind === "role" && <>
          <div className="editor-grid">
            <Field label="角色编码" required>{input("code")}</Field>
            <Field label="角色名称" required>{input("name")}</Field>
          </div>
          <h3 className="editor-subtitle">权限集合</h3>
          {multiSelect("permissions", allPermissions, "角色只是权限集合；前端不把按钮写死为管理员可见。")}
        </>}

        {request.kind === "profile" && <div className="editor-grid">
          <Readonly label="Username" value={text(values, "username")} />
          <Field label="显示名称">{input("displayName")}</Field>
          <Field label="当前密码">{input("currentPassword", "", "password")}</Field>
          <Field label="新密码">{input("newPassword", "", "password")}</Field>
          <Field label="确认新密码">{input("confirmPassword", "", "password")}</Field>
        </div>}

        {request.kind === "alertRule" && <div className="editor-grid">
          <Field label="规则名称" required>{input("name")}</Field>
          <Field label="范围">{select("scopeType", [["ALL", "全部任务"], ["TASK", "指定任务"]])}</Field>
          <Field label="Task">{select("taskId", [["", "不指定"], ...props.tasks.map((item) => [item.id, item.name] as [string, string])])}</Field>
          <Field label="Metric">{input("metricCode")}</Field>
          <Field label="Operator">{input("conditionOp")}</Field>
          <Field label="Value">{input("conditionValue")}</Field>
          <Field label="级别">{select("severity", [["INFO", "INFO"], ["WARNING", "WARNING"], ["CRITICAL", "CRITICAL"]])}</Field>
          <Field label="通知通道">{select("channels", props.alertChannels.map((item) => [item.id, item.name]))}</Field>
          {checkbox("enabled", "启用规则")}
        </div>}

        {request.kind === "alertChannel" && <div className="editor-grid">
          <Field label="通道名称" required>{input("name")}</Field>
          <Field label="类型">{select("channelType", [["DINGTALK", "钉钉"], ["WECOM", "企业微信"]])}</Field>
          <Field label="格式">{select("messageFormat", [["TEXT", "TEXT"], ["MARKDOWN", "MARKDOWN"]])}</Field>
          <Field label="Endpoint" required>{input("endpoint")}</Field>
          <Field label="Secret">{input("secret", "", "password")}</Field>
          {checkbox("enabled", "启用通道")}
        </div>}

        {request.kind === "externalClient" && <div className="editor-grid">
          <Field label="Client ID" required>{input("clientId")}</Field>
          <Field label="展示名称" required>{input("clientName")}</Field>
          <Field label="授权模式">{select("authorizationMode", [["ALL", "全部机构"], ["SELECTED", "指定机构"]])}</Field>
          {text(values, "authorizationMode") === "SELECTED" && <div>{multiSelect("institutions", props.institutions.map((item) => [item.code, `${item.code} · ${item.name}`]))}</div>}
          {checkbox("enabled", "启用 Client")}
        </div>}

        {request.kind === "mapping" && <div className="editor-grid">
          <Field label="Profile" required>{input("profileName")}</Field>
          <Field label="Version" required>{input("profileVersion")}</Field>
          <Field label="Rule Code" required>{input("ruleCode")}</Field>
          <Field label="Source DB">{select("sourceDbType", [["POSTGRESQL", "PostgreSQL"], ["MYSQL", "MySQL"], ["ORACLE", "Oracle"], ["SQLSERVER", "SQL Server"]])}</Field>
          <Field label="Source Type Pattern" required>{input("sourceTypePattern")}</Field>
          <Field label="Doris 建议" required>{input("recommendedDorisType")}</Field>
          <Field label="兼容性">{select("compatibilityLevel", [["PASS", "PASS"], ["WARN", "WARN"], ["REJECT", "REJECT"]])}</Field>
          {checkbox("enabled", "启用规则")}
        </div>}

        {error && <div className="editor-error">{error}</div>}
      </div>
      <footer>
        <button type="button" className="btn" onClick={props.onClose}>取消</button>
        <button type="submit" className="btn btn-primary">保存</button>
      </footer>
    </form>
  </div>;
}

export function EditorPanelV2(props: Props) {
  if (!props.request) return null;
  return <EditorForm {...props} request={props.request} />;
}

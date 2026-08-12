"use client";

import { Fragment, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  AlertOutlined,
  ApartmentOutlined,
  ApiOutlined,
  BellOutlined,
  BookOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CloudServerOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  FieldTimeOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  HomeOutlined,
  LinkOutlined,
  LockOutlined,
  LogoutOutlined,
  MessageOutlined,
  MonitorOutlined,
  MoreOutlined,
  PartitionOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  SwapOutlined,
  SyncOutlined,
  TableOutlined,
  ToolOutlined,
  UpOutlined,
  UserOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import type { AppPage, DataLink, DataSourceTab, Dataset, Institution, MonitorRow, PageState, Panel, PrecheckDetailTab, ProfileTab, SourceDataSource, TargetDataSource, TaskDetailTab, TaskRow, ValidationMode, ValidationRow, ValidationView } from "./etl/model";
import { datasetFieldRows, datasets, getLinks, getTasks, institutionNames, institutions, monitorSeed, sourceDataSourcesSeed, targetDataSourcesSeed, validationSeed } from "./etl/mock-data";
import { parseLocation, pathFor } from "./etl/routing";
import { paginate, Pagination } from "./etl/components/Pagination";
import { DashboardPage } from "./etl/pages/DashboardPage";
import { DocumentationPage } from "./etl/pages/DocumentationPage";
import { OperationsPage } from "./etl/pages/OperationsPage";

const navGroups: Array<{ title: string; icon: ReactNode; items: string[] }> = [
  { title: "接入资源", icon: <DatabaseOutlined />, items: ["机构管理", "数据源管理", "数据集管理"] },
  { title: "任务中心", icon: <SwapOutlined />, items: ["数据同步", "数据预检", "任务监控"] },
  { title: "数据校验", icon: <SafetyCertificateOutlined />, items: ["校验总览", "校验工作台"] },
  { title: "运维管理", icon: <ToolOutlined />, items: ["告警通知", "日志中心", "操作审计"] },
  { title: "系统设置", icon: <SettingOutlined />, items: ["全局参数", "医共体数据模型", "校验策略", "Doris 建表", "外部授权", "类型映射规则"] },
];

const menuIcons: Record<string, ReactNode> = {
  机构管理: <ApartmentOutlined />,
  数据源管理: <CloudServerOutlined />,
  数据集管理: <DatabaseOutlined />,
  数据同步: <SwapOutlined />,
  任务监控: <MonitorOutlined />,
  校验总览: <PartitionOutlined />,
  校验工作台: <SafetyCertificateOutlined />,
  数据预检: <FileSearchOutlined />,
  告警通知: <AlertOutlined />,
  日志中心: <FileTextOutlined />,
  操作审计: <FileSearchOutlined />,
  全局参数: <SettingOutlined />,
  医共体数据模型: <DatabaseOutlined />,
  校验策略: <SafetyCertificateOutlined />,
  "Doris 建表": <DatabaseOutlined />,
  外部授权: <ApiOutlined />,
  类型映射规则: <ToolOutlined />,
};

const panelNames: Record<Panel, string> = {
  basic: "基础信息",
  fields: "字段结构",
  validation: "数据校验",
  message: "消息通知",
  collection: "源端映射",
  sync: "同步策略",
};

function Toggle({ checked, onChange, label }: { checked: boolean; onChange?: () => void; label: string }) {
  return <button className={`toggle ${checked ? "is-on" : ""}`} type="button" role="switch" aria-checked={checked} aria-label={label} onClick={onChange}><span /></button>;
}

function precheckContext(id?: string) {
  if (!id) return null;
  const [datasetCode, linkId] = id.split("--");
  const dataset = datasets.find((item) => item.code === datasetCode);
  const link = dataset ? getLinks(dataset).find((item) => item.id === linkId) : undefined;
  return dataset && link ? { dataset, link, status: "待预检" } : null;
}

export default function Home() {
  const initialRoute = typeof window === "undefined" ? { page: "dashboard" as AppPage } : parseLocation(window.location.pathname);
  const [appPage, setAppPage] = useState<AppPage>(initialRoute.page);
  const [dataSourceTab, setDataSourceTab] = useState<DataSourceTab>("source");
  const [dataSourceDialog, setDataSourceDialog] = useState<{ mode: "create" | "edit"; tab: DataSourceTab; name?: string } | null>(null);
  const [dataSourceDeleteTarget, setDataSourceDeleteTarget] = useState<{ tab: DataSourceTab; name: string } | null>(null);
  const [connectionTest, setConnectionTest] = useState<"idle" | "testing" | "success" | "failed">("idle");
  const [testingDataSource, setTestingDataSource] = useState<string | null>(null);
  const [dataSourceMore, setDataSourceMore] = useState<string | null>(null);
  const [sourceDbType, setSourceDbType] = useState("PostgreSQL");
  const [institutionRows, setInstitutionRows] = useState<Institution[]>(institutions);
  const [sourceDataSources, setSourceDataSources] = useState<SourceDataSource[]>(sourceDataSourcesSeed);
  const [targetDataSources, setTargetDataSources] = useState<TargetDataSource[]>(targetDataSourcesSeed);
  const [institutionDialog, setInstitutionDialog] = useState<{ mode: "create" | "edit"; code?: string } | null>(null);
  const [institutionDelete, setInstitutionDelete] = useState<string | null>(null);
  const [linkCreateDataset, setLinkCreateDataset] = useState<Dataset | null>(null);
  const [linkCreateStep, setLinkCreateStep] = useState(1);
  const [createdLinks, setCreatedLinks] = useState<Record<string, DataLink[]>>({});
  const [linkOverrides, setLinkOverrides] = useState<Record<string, Partial<DataLink>>>({});
  const [deletedLinks, setDeletedLinks] = useState<string[]>([]);
  const [linkDeleteTarget, setLinkDeleteTarget] = useState<{ dataset: Dataset; link: DataLink } | null>(null);
  const [linkDraft, setLinkDraft] = useState({
    name: "", vendor: "东软 HIS", institution: "县人民医院", source: "postgresql-rmyy",
    schema: "his_rmyy", object: "",
  });
  const [linkConfigDraft, setLinkConfigDraft] = useState({ source: "", schema: "", object: "", mappedFields: 0 });
  const [datasetPolicyDialog, setDatasetPolicyDialog] = useState<"sync" | "validation" | "message" | null>(null);
  const [datasetPolicyValues, setDatasetPolicyValues] = useState<Record<string, Record<string, Record<string, string>>>>({});
  const [messageDraftEnabled, setMessageDraftEnabled] = useState(false);
  const [taskDetailTab, setTaskDetailTab] = useState<TaskDetailTab>("basic");
  const [taskValidationOverride, setTaskValidationOverride] = useState(false);
  const [selectedTask, setSelectedTask] = useState<TaskRow>(() => getTasks().find((task) => task.id === initialRoute.id) ?? getTasks()[0]);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("全部状态");
  const [messageEnabled, setMessageEnabled] = useState<Record<string, boolean>>(() => Object.fromEntries(datasets.map((item) => [item.code, item.messageEnabled])));
  const [drawer, setDrawer] = useState<Dataset | null>(null);
  const [drawerLink, setDrawerLink] = useState<DataLink | null>(null);
  const [activePanel, setActivePanel] = useState<Panel>("basic");
  const [expanded, setExpanded] = useState<string[]>([datasets[0].code]);
  const [datasetCategory, setDatasetCategory] = useState("全部业务分类");
  const [datasetPaging, setDatasetPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [fieldPaging, setFieldPaging] = useState<PageState>({ page: 1, pageSize: 10 });
  const [institutionQuery, setInstitutionQuery] = useState("");
  const [institutionType, setInstitutionType] = useState("全部机构类型");
  const [institutionStatus, setInstitutionStatus] = useState("全部状态");
  const [institutionPaging, setInstitutionPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [dataSourceQuery, setDataSourceQuery] = useState("");
  const [dataSourceType, setDataSourceType] = useState("全部数据库类型");
  const [dataSourceStatus, setDataSourceStatus] = useState("全部状态");
  const [dataSourcePaging, setDataSourcePaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [taskQuery, setTaskQuery] = useState("");
  const [taskInstitution, setTaskInstitution] = useState("全部机构 / 机构组");
  const [taskStatus, setTaskStatus] = useState("全部状态");
  const [taskPaging, setTaskPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [monitorPaging, setMonitorPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [validationPaging, setValidationPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [precheckPaging, setPrecheckPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [institutionEnabled, setInstitutionEnabled] = useState<Record<string, boolean>>(() => Object.fromEntries(institutions.map((item) => [item.code, item.enabled])));
  const [sourceEnabled, setSourceEnabled] = useState<Record<string, boolean>>(() => Object.fromEntries(sourceDataSources.map((item) => [item.name, item.enabled])));
  const [targetEnabled, setTargetEnabled] = useState<Record<string, boolean>>(() => Object.fromEntries(targetDataSources.map((item) => [item.name, item.enabled])));
  const [monitorRows, setMonitorRows] = useState<MonitorRow[]>(monitorSeed);
  const [monitorFilter, setMonitorFilter] = useState("全部状态");
  const [monitorQuery, setMonitorQuery] = useState("");
  const [validationOverviewQuery, setValidationOverviewQuery] = useState("");
  const [validationOverviewPaging, setValidationOverviewPaging] = useState<PageState>({ page: 1, pageSize: 5 });
  const [validationRows] = useState<ValidationRow[]>(validationSeed);
  const [validationQuery, setValidationQuery] = useState("");
  const [validationResultFilter, setValidationResultFilter] = useState("全部状态");
  const [validationMethodFilter, setValidationMethodFilter] = useState("全部校验");
  const [validationDrawer, setValidationDrawer] = useState<ValidationRow | null>(null);
  const [validationMode, setValidationMode] = useState<ValidationMode>("full");
  const [validationView, setValidationView] = useState<ValidationView>("result");
  const [precheckDrawer, setPrecheckDrawer] = useState<{ dataset: Dataset; link: DataLink; status?: string } | null>(() => initialRoute.page === "precheckDetail" ? precheckContext(initialRoute.id) : null);
  const [precheckDetailTab, setPrecheckDetailTab] = useState<PrecheckDetailTab>("overview");
  const [precheckFilter, setPrecheckFilter] = useState("全部");
  const [precheckQuery, setPrecheckQuery] = useState("");
  const [precheckInstitution, setPrecheckInstitution] = useState("全部机构");
  const [precheckSource, setPrecheckSource] = useState("全部数据源");
  const [precheckDataset, setPrecheckDataset] = useState("全部数据集");
  const [precheckRunning, setPrecheckRunning] = useState(false);
  const [precheckSelected, setPrecheckSelected] = useState<string[]>([]);
  const [settingEnabled, setSettingEnabled] = useState<Record<string, boolean>>({
    medicalRegistry: true,
    enforceValidation: true,
    autoValidate: true,
    failBlock: true,
    revalidate: true,
    viewRepair: false,
    partition: false,
    typeCheck: false,
    nullableCheck: false,
  });
  const [validationMethod, setValidationMethod] = useState("row_count");
  const [validationTrigger, setValidationTrigger] = useState("after_sync");
  const [validationSettingsForm, setValidationSettingsForm] = useState({ tolerance: "0", retryDelay: "30", lookbackHours: "24" });
  const [partitionGranularity, setPartitionGranularity] = useState("MONTH");
  const [bucketStrategy, setBucketStrategy] = useState("FIXED");
  const [dorisSettingsForm, setDorisSettingsForm] = useState({ mode: "RELAXED", partitionField: "xiugaisj", historyRange: "36", futureRange: "6", fixedBuckets: "10" });
  const [bucketRows, setBucketRows] = useState<[string, string][]>([["< 10 万行", "1"], ["10 万 - 100 万行", "2"], ["100 万 - 1000 万行", "4"], ["1000 万 - 5000 万行", "8"], ["5000 万 - 2 亿行", "16"], ["2 亿 - 10 亿行", "32"], ["> 10 亿行", "64"]]);
  const [externalSettingsTab, setExternalSettingsTab] = useState<"clients" | "guide">("clients");
  const [settingDialog, setSettingDialog] = useState<"external" | "mapping" | null>(null);
  const [externalEditingId, setExternalEditingId] = useState<string | null>(null);
  const [externalResetTarget, setExternalResetTarget] = useState<string | null>(null);
  const [mappingEditingKey, setMappingEditingKey] = useState<string | null>(null);
  const [settingsNotice, setSettingsNotice] = useState("");
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [profileTab, setProfileTab] = useState<ProfileTab>("account");
  const [logoutConfirm, setLogoutConfirm] = useState(false);
  const [sessionActive, setSessionActive] = useState(true);
  const [passwordForm, setPasswordForm] = useState({ current: "", next: "", confirm: "" });
  const [passwordVisible, setPasswordVisible] = useState({ current: false, next: false, confirm: false });
  const [externalClients, setExternalClients] = useState<string[][]>([
    ["partner-his", "医共体 HIS 外部调用方", "启用", "YGT330106H001", "数据同步自动化接入"],
    ["ops-console", "运维自动化平台", "启用", "不限", "任务运行与状态查询"],
  ]);
  const [externalDeleteTarget, setExternalDeleteTarget] = useState<string | null>(null);
  const [mappingFilter, setMappingFilter] = useState("");
  const [mappingPaging, setMappingPaging] = useState<PageState>({ page: 1, pageSize: 20 });
  const [mappingRules, setMappingRules] = useState<string[][]>([
    ["POSTGRESQL", "character varying", "VARCHAR(65533)", "PASS", "字符串类型兼容", "100", "启用"],
    ["POSTGRESQL", "timestamp without time zone", "DATETIME", "PASS", "日期时间类型兼容", "95", "启用"],
    ["MYSQL", "varchar", "VARCHAR(65533)", "PASS", "字符串类型兼容", "100", "启用"],
    ["ORACLE", "NUMBER", "DECIMAL(38,10)", "WARN", "需根据精度确认目标类型", "90", "启用"],
    ["SQLSERVER", "datetime2", "DATETIME", "PASS", "日期时间类型兼容", "90", "启用"],
  ]);
  const [operationsQuery, setOperationsQuery] = useState("");
  const [operationsLevel, setOperationsLevel] = useState("全部级别");
  const [operationsModule, setOperationsModule] = useState("全部模块");
  const [operationsPaging, setOperationsPaging] = useState<PageState>({ page: 1, pageSize: 20 });
  const [savedSettings, setSavedSettings] = useState<Record<string, string>>({});
  const [globalSettings, setGlobalSettings] = useState({ platformName: "东防数据采集系统", fetchSize: "50000", concurrency: "4", retries: "3", retryDelay: "10", connectTimeout: "30", readTimeout: "300", taskTimeout: "3600", syncMode: "truncate" });
  const [registrySettings, setRegistrySettings] = useState({ host: "192.168.1.10", port: "9030", database: "df_ygt", username: "df_admin", password: "****", datasetTable: "dm_shujuji", fieldTable: "dm_shujuyzd", itemTable: "dm_shujuxiang" });
  const [taskRows] = useState<TaskRow[]>(() => getTasks());
  const tasks = useMemo(() => taskRows.filter((task) => !deletedLinks.includes(`${task.dataset.code}:${task.link.id}`)), [deletedLinks, taskRows]);
  const aggregateLinkCount = datasets.reduce((total, item) => total + linksFor(item).length, 0);
  const aggregateFailedLinks = datasets.reduce((total, item) => total + linksFor(item).filter((link) => link.state === "异常").length, 0);
  const editingSource = dataSourceDialog?.tab === "source" ? sourceDataSources.find((item) => item.name === dataSourceDialog.name) : undefined;
  const editingTarget = dataSourceDialog?.tab === "target" ? targetDataSources.find((item) => item.name === dataSourceDialog.name) : undefined;
  const editingInstitution = institutionDialog?.mode === "edit" ? institutionRows.find((item) => item.code === institutionDialog.code) : undefined;
  const editingExternal = externalEditingId ? externalClients.find((item) => item[0] === externalEditingId) : undefined;
  const editingMapping = mappingEditingKey ? mappingRules.find((item) => `${item[0]}:${item[1]}` === mappingEditingKey) : undefined;

  function linksFor(item: Dataset) {
    return [...getLinks(item), ...(createdLinks[item.code] ?? [])].map((link) => ({ ...link, ...(linkOverrides[`${item.code}:${link.id}`] ?? {}) })).filter((link) => !deletedLinks.includes(`${item.code}:${link.id}`));
  }

  function beginLinkCreation(item: Dataset) {
    setLinkCreateDataset(item);
    setLinkCreateStep(1);
    setLinkDraft({
      name: `${item.name}采集链路`, vendor: "东软 HIS", institution: "县人民医院", source: "postgresql-rmyy",
      schema: "his_rmyy", object: item.code.toLowerCase(),
    });
  }

  const visible = useMemo(() => datasets.filter((item) => {
    const matchQuery = `${item.name}${item.code}`.toLowerCase().includes(query.toLowerCase());
    const matchFilter = filter === "全部状态" || item.syncState === filter;
    const matchCategory = datasetCategory === "全部业务分类" || item.category === datasetCategory;
    return matchQuery && matchFilter && matchCategory;
  }), [query, filter, datasetCategory]);
  const pagedDatasets = paginate(visible, datasetPaging);

  const pageLabels: Record<AppPage, string> = {
    dashboard: "首页", institutions: "机构管理", datasources: "数据源管理", datasets: "数据集管理", tasks: "数据同步", taskDetail: "数据同步",
    monitor: "任务监控", validationOverview: "校验总览", validationWorkbench: "校验工作台", precheck: "数据预检", precheckDetail: "数据预检",
    alerts: "告警通知", logs: "日志中心", audit: "操作审计", globalSettings: "全局参数",
    registrySettings: "医共体数据模型", validationSettings: "校验策略", dorisSettings: "Doris 建表", externalApi: "外部授权",
    mappingRules: "类型映射规则", docs: "使用文档",
  };
  const pageLabel = pageLabels[appPage];

  const pageSection = appPage === "dashboard" || appPage === "docs" ? "" : appPage === "tasks" || appPage === "taskDetail" || appPage === "precheck" || appPage === "precheckDetail" || appPage === "monitor" ? "任务中心" : appPage === "validationOverview" || appPage === "validationWorkbench" ? "数据校验" : appPage === "alerts" || appPage === "logs" || appPage === "audit" ? "运维管理" : appPage.endsWith("Settings") || appPage === "mappingRules" || appPage === "externalApi" ? "系统设置" : "接入资源";

  function navigate(target: AppPage, id?: string, replace = false) {
    setAppPage(target);
    const nextPath = pathFor(target, id);
    if (window.location.pathname !== nextPath) window.history[replace ? "replaceState" : "pushState"]({ page: target, id }, "", nextPath);
    setDrawer(null);
    setValidationDrawer(null);
    setPrecheckDrawer(null);
    setDataSourceDialog(null);
    setDataSourceMore(null);
    setInstitutionDialog(null);
    setInstitutionDelete(null);
    setLinkDeleteTarget(null);
    setQuery("");
    setFilter("全部状态");
    setPrecheckSelected([]);
    setUserMenuOpen(false);
    setProfileOpen(false);
  }

  useEffect(() => {
    const onPopState = () => {
      const route = parseLocation(window.location.pathname);
      setAppPage(route.page);
      if (route.page === "taskDetail" && route.id) setSelectedTask(getTasks().find((task) => task.id === route.id) ?? getTasks()[0]);
      setPrecheckDrawer(route.page === "precheckDetail" ? precheckContext(route.id) : null);
      setDrawer(null); setValidationDrawer(null); setDataSourceDialog(null); setDataSourceMore(null); setInstitutionDialog(null);
    };
    window.addEventListener("popstate", onPopState);
    if (window.location.pathname === "/") window.history.replaceState({ page: "dashboard" }, "", pathFor("dashboard"));
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  function notifySettings(message: string) {
    setSettingsNotice(message);
    window.setTimeout(() => setSettingsNotice(""), 2400);
  }

  function markSaved(key: string, message: string) {
    setSavedSettings((current) => ({ ...current, [key]: new Date().toLocaleTimeString("zh-CN", { hour12: false }) }));
    notifySettings(message);
  }

  function downloadOpenApi() {
    const document = JSON.stringify({ openapi: "3.0.3", info: { title: "东防数据采集系统外部接口", version: "1.0.0" }, paths: {} }, null, 2);
    const url = URL.createObjectURL(new Blob([document], { type: "application/json" }));
    const anchor = window.document.createElement("a");
    anchor.href = url;
    anchor.download = "dfetl-openapi.json";
    anchor.click();
    URL.revokeObjectURL(url);
  }

  function simulateConnectionTest(name: string, shouldFail: boolean, failureReason = "请求超时") {
    setConnectionTest("testing");
    setTestingDataSource(name);
    window.setTimeout(() => {
      setConnectionTest(shouldFail ? "failed" : "success");
      setTestingDataSource(null);
      notifySettings(shouldFail ? `${name} 连接失败：${failureReason}` : `${name} 连接成功`);
    }, 700);
  }

  function openProfile(tab: ProfileTab = "account") {
    setProfileTab(tab);
    setProfileOpen(true);
    setUserMenuOpen(false);
  }

  function submitPasswordChange() {
    const { current, next, confirm } = passwordForm;
    if (!current || next.length < 8 || next !== confirm) return;
    setPasswordForm({ current: "", next: "", confirm: "" });
    setPasswordVisible({ current: false, next: false, confirm: false });
    notifySettings("密码已更新，请使用新密码登录");
  }

  function logout() {
    setLogoutConfirm(false);
    setProfileOpen(false);
    setUserMenuOpen(false);
    setSessionActive(false);
  }

  function navTarget(label: string): AppPage | null {
    if (label === "机构管理") return "institutions";
    if (label === "数据源管理") return "datasources";
    if (label === "数据集管理") return "datasets";
    if (label === "数据同步") return "tasks";
    if (label === "任务监控") return "monitor";
    if (label === "校验总览") return "validationOverview";
    if (label === "校验工作台") return "validationWorkbench";
    if (label === "数据预检") return "precheck";
    if (label === "告警通知") return "alerts";
    if (label === "日志中心") return "logs";
    if (label === "操作审计") return "audit";
    if (label === "全局参数") return "globalSettings";
    if (label === "医共体数据模型") return "registrySettings";
    if (label === "校验策略") return "validationSettings";
    if (label === "Doris 建表") return "dorisSettings";
    if (label === "外部授权") return "externalApi";
    if (label === "类型映射规则") return "mappingRules";
    return null;
  }

  function isNavActive(label: string) {
    return label === pageLabel || (label === "数据同步" && appPage === "taskDetail") || (label === "数据预检" && appPage === "precheckDetail");
  }

  function openPanel(item: Dataset, panel: Panel) {
    setDrawer(item);
    setDrawerLink(null);
    setActivePanel(panel);
    if (panel === "fields") setFieldPaging({ page: 1, pageSize: 10 });
  }

  function openLink(item: Dataset, link: DataLink, panel: Panel = "collection") {
    setDrawer(item);
    setDrawerLink(link);
    const [schema, ...objectParts] = link.sourceType.split(".");
    setLinkConfigDraft({ source: link.source, schema, object: objectParts.join("."), mappedFields: link.mappedFields ?? item.fields });
    setActivePanel(panel);
  }

  function openPrecheckTask(item: Dataset, link: DataLink, status = "待预检", tab: PrecheckDetailTab = "overview", run = false) {
    navigate("precheckDetail", `${item.code}--${link.id}`);
    setPrecheckDrawer({ dataset: item, link, status });
    setPrecheckDetailTab(tab);
    setPrecheckRunning(run || status === "执行中");
  }

  function selectPanel(item: Dataset, panel: Panel) {
    if (panel === "collection" && !drawerLink) setDrawerLink(linksFor(item)[0]);
    if (panel === "basic" || panel === "fields" || panel === "validation" || panel === "message") setDrawerLink(null);
    setActivePanel(panel);
  }

  function panelContent(item: Dataset) {
    const activeLink = drawerLink ?? linksFor(item)[0];
    const syncPolicy = datasetPolicyValues[item.code]?.sync ?? {};
    const validationPolicy = datasetPolicyValues[item.code]?.validation ?? {};
    if (activePanel === "basic") return <>
      <div className="panel-heading"><div><h3>基础信息</h3></div><span className="quiet-tag">数据集级</span></div>
      <div className="form-section"><label><span>数据集名称</span><input value={item.name} readOnly /></label><label><span>数据集编码</span><input value={item.code} readOnly /></label><label><span>业务分类</span><input value={item.category} readOnly /></label><label><span>标准目标表</span><input value={`df_ygt.${item.code.toLowerCase()}`} readOnly /></label><label><span>数据来源</span><input value="医共体数据模型" readOnly /></label></div>
      <div className="dataset-impact"><article><span>覆盖机构</span><strong>12</strong><small>家医疗机构</small></article><article><span>采集链路</span><strong>4</strong><small>套厂商结构</small></article><article><span>标准字段</span><strong>{item.fields}</strong><small>{item.primaryKeys} 个主键</small></article></div>
    </>;

    if (activePanel === "fields") return <>
      <div className="panel-heading"><div><h3>字段结构</h3></div><div className="panel-actions"><span className="quiet-tag">{item.fields} 个字段</span></div></div>
      <div className="dataset-field-scroll">
        <table className="dataset-field-table"><thead><tr><th>顺序</th><th>字段编码</th><th>字段名称</th><th>标准格式</th><th>Doris 类型</th><th>主键</th><th>必填</th></tr></thead><tbody>
          {paginate(Array.from({ length: item.fields }, (_, index) => { const base = datasetFieldRows[index % datasetFieldRows.length]; return [String(Number(base[0]) + Math.floor(index / datasetFieldRows.length) * 2100), `${base[1]}${index >= datasetFieldRows.length ? `_${index + 1}` : ""}`, ...base.slice(2)]; }), fieldPaging).rows.map((row) => <tr key={row[1]}>{row.map((value, index) => <td key={`${row[1]}-${index}`} className={index === 1 ? "field-code-cell" : ""}>{value}</td>)}</tr>)}
        </tbody></table>
      </div>
      <Pagination total={item.fields} state={fieldPaging} onChange={setFieldPaging} noun="个字段" />
    </>;

    if (activePanel === "sync" && drawerLink) return <>
      <div className="panel-heading"><div><h3>抽取与调度</h3><p>{activeLink.name} · {activeLink.institutions.length} 家机构</p></div><span className={`status status-${activeLink.state}`}>{activeLink.state}</span></div>
      <label className="link-switcher"><span>当前链路</span><select value={activeLink.id} onChange={(event) => setDrawerLink(linksFor(item).find((link) => link.id === event.target.value) ?? activeLink)}>{linksFor(item).map((link) => <option value={link.id} key={link.id}>{link.name}</option>)}</select></label>
      <div className="form-section"><label><span>同步模式</span><select defaultValue="UPSERT"><option>UPSERT</option><option>INSERT ONLY</option><option>全量覆盖</option></select><small>主键相同的数据将执行更新操作</small></label>
        <label><span>首次同步</span><select defaultValue="全量同步"><option>全量同步</option><option>从指定时间开始</option><option>不执行首次同步</option></select></label>
        <label><span>增量周期</span><select defaultValue={activeLink.schedule}><option>{activeLink.schedule}</option><option>每 2 小时</option><option>每天 02:00</option></select></label>
        <label><span>增量依据</span><select defaultValue="更新时间字段"><option>更新时间字段</option><option>自增主键</option><option>CDC 日志</option></select></label></div>
    </>;

    if (activePanel === "sync") return <>
      <div className="panel-heading"><div><h3>同步策略</h3></div><button className="secondary-button" onClick={() => setDatasetPolicyDialog("sync")}><EditOutlined /> 编辑策略</button></div>
      <div className="policy-description">
        <div><span>写入模式</span><strong>{syncPolicy.writeMode ?? "UPSERT"}</strong></div><div><span>同步方式</span><strong>{syncPolicy.syncMode ?? "首次全量后增量"}</strong></div>
        <div><span>增量字段</span><strong>{syncPolicy.incrementField ?? "修改时间（XIUGAISJ）"}</strong></div><div><span>增量上界</span><strong>{syncPolicy.upperBound ?? "当前时间"}</strong></div>
        <div><span>上界延迟</span><strong>{syncPolicy.upperDelay ?? "5"} 分钟</strong></div><div><span>回看窗口</span><strong>{syncPolicy.lookback ?? "0"} 秒</strong></div>
        <div><span>Reader 并发</span><strong>{syncPolicy.readerConcurrency ?? "4"}</strong></div><div><span>Fetch Size</span><strong>{syncPolicy.fetchSize ?? "继承全局"}</strong></div>
        <div><span>调度方式</span><strong>{syncPolicy.scheduleMode ?? "固定小时间隔"}</strong></div><div><span>执行间隔</span><strong>每 {syncPolicy.interval ?? "4"} 小时</strong></div>
        <div><span>时区</span><strong>Asia/Shanghai</strong></div><div><span>调度状态</span><strong className="green-text">已启用</strong></div>
      </div>
    </>;

    if (activePanel === "validation") return <>
      <div className="panel-heading"><div><h3>数据校验</h3></div><button className="secondary-button" onClick={() => setDatasetPolicyDialog("validation")}><EditOutlined /> 编辑策略</button></div>
      <div className="policy-description">
        <div><span>策略来源</span><strong>数据集配置</strong></div><div><span>启用状态</span><strong className="green-text">已启用</strong></div>
        <div><span>触发方式</span><strong>{validationPolicy.trigger ?? "同步后自动"}</strong></div><div><span>校验方法</span><strong>{validationPolicy.method ?? "CHECKSUM"}</strong></div>
        <div><span>行数容差</span><strong>{validationPolicy.tolerance ?? "0"}%</strong></div><div><span>失败阻断</span><strong>关闭</strong></div>
        <div><span>启用复检</span><strong>开启</strong></div><div><span>复检延迟</span><strong>{validationPolicy.retryDelay ?? "30"} 秒</strong></div>
        <div><span>校验回看</span><strong>{validationPolicy.lookbackHours ?? "2"} 小时</strong></div><div><span>最近结果</span><strong className={item.exceptions ? "red-text" : "green-text"}>{item.exceptions ? `${item.exceptions} 条异常` : "通过"}</strong></div>
      </div>
    </>;

    if (activePanel === "collection") return <>
      <div className="panel-heading"><div><h3>源端映射</h3><p>{activeLink.vendor} · {activeLink.institutions.length} 家机构</p></div><span className="quiet-tag">链路级</span></div>
      <label className="link-switcher"><span>当前链路</span><select value={activeLink.id} onChange={(event) => setDrawerLink(linksFor(item).find((link) => link.id === event.target.value) ?? activeLink)}>{linksFor(item).map((link) => <option value={link.id} key={link.id}>{link.name}</option>)}</select></label>
      <div className="institution-scope"><div><strong>机构范围</strong></div><div>{activeLink.institutions.slice(0, 3).map((name) => <b key={name}>{name}</b>)}{activeLink.institutions.length > 3 && <b>+{activeLink.institutions.length - 3} 家</b>}</div></div>
      <div className="mapping-preview"><div><small>源端</small><strong>{activeLink.source}</strong><span>{activeLink.sourceType}</span></div><b>→</b><div><small>标准数据集</small><strong>{item.name}</strong><span>{item.code}</span></div></div>
      <div className="form-section"><label><span>源数据源</span><select value={linkConfigDraft.source} onChange={(event) => setLinkConfigDraft((current) => ({ ...current, source: event.target.value }))}>{sourceDataSources.map((source) => <option key={source.name}>{source.name}</option>)}</select></label><label><span>Schema / 数据库</span><input value={linkConfigDraft.schema} onChange={(event) => setLinkConfigDraft((current) => ({ ...current, schema: event.target.value }))} /></label><label><span>源视图 / 表</span><input value={linkConfigDraft.object} onChange={(event) => setLinkConfigDraft((current) => ({ ...current, object: event.target.value }))} /></label><label><span>标准数据集</span><input value={item.code} readOnly /></label></div>
      <div className="mapping-count-line"><span>映射字段</span><strong>{linkConfigDraft.mappedFields} / {item.fields}</strong><button className="text-button" onClick={() => { setLinkConfigDraft((current) => ({ ...current, mappedFields: item.fields })); notifySettings("字段已按编码重新映射"); }}>重新映射字段</button></div>
    </>;

    const enabled = messageEnabled[item.code];
    return <>
      <div className="panel-heading"><div><h3>消息通知</h3></div><button className="secondary-button" onClick={() => { setMessageDraftEnabled(messageEnabled[item.code]); setDatasetPolicyDialog("message"); }}><EditOutlined /> 编辑配置</button></div>
      <div className="policy-description">
        <div><span>启用状态</span><strong className={enabled ? "green-text" : ""}>{enabled ? "已启用" : "未启用"}</strong></div><div><span>来源系统</span><strong>HIS</strong></div>
        <div><span>租户 ID</span><strong>0</strong></div><div><span>Routing Key</span><strong>YL_KESHIXT</strong></div>
        <div><span>Topic</span><strong>dfetl.dataset.change</strong></div><div><span>messageKey 模板</span><strong>{"{yiliaojgdm}:{keshidm}"}</strong></div>
        <div><span>首次全量发布</span><strong>ALL（发布全部）</strong></div><div><span>传输方式</span><strong>RabbitMQ</strong></div>
        <div><span>限速</span><strong>1000 条/秒</strong></div><div><span>分页大小</span><strong>1000</strong></div>
      </div>
    </>;
  }

  function settingsPage() {
    const updateFlag = (key: string) => setSettingEnabled((current) => ({ ...current, [key]: !current[key] }));
    const help = (title: string) => <QuestionCircleOutlined className="field-help" title={title} />;
    const footer = (children: ReactNode, key?: string) => <div className="settings-actions">{key && savedSettings[key] && <small className="saved-at">最近保存 {savedSettings[key]}</small>}{children}</div>;

    if (appPage === "globalSettings") return <>
      <div className="settings-shell">
        <section className="setting-card">
          <h2>平台配置</h2>
          <div className="setting-body setting-grid two-columns">
            <label className="setting-field"><span className="required">平台显示名称 {help("左上角侧栏和登录页展示的平台名称，保存后刷新页面生效")}</span><input value={globalSettings.platformName} onChange={(event) => setGlobalSettings((current) => ({ ...current, platformName: event.target.value }))} /></label>
          </div>
        </section>
        <section className="setting-card">
          <h2>执行参数</h2>
          <div className="setting-body setting-grid three-columns">
            <label className="setting-field"><span>Fetch Size {help("SeaTunnel JDBC source fetch_size；任务未设置覆盖值时继承该值")}</span><input type="number" value={globalSettings.fetchSize} onChange={(event) => setGlobalSettings((current) => ({ ...current, fetchSize: event.target.value }))} /></label>
            <label className="setting-field"><span>并发数 {help("同时执行的 Chunk 数量")}</span><input type="number" value={globalSettings.concurrency} onChange={(event) => setGlobalSettings((current) => ({ ...current, concurrency: event.target.value }))} /></label>
            <span className="setting-grid-spacer" />
            <label className="setting-field"><span>重试次数</span><input type="number" value={globalSettings.retries} onChange={(event) => setGlobalSettings((current) => ({ ...current, retries: event.target.value }))} /></label>
            <label className="setting-field"><span>重试间隔（秒）</span><input type="number" value={globalSettings.retryDelay} onChange={(event) => setGlobalSettings((current) => ({ ...current, retryDelay: event.target.value }))} /></label>
            <label className="setting-field"><span>连接超时（秒）</span><input type="number" value={globalSettings.connectTimeout} onChange={(event) => setGlobalSettings((current) => ({ ...current, connectTimeout: event.target.value }))} /></label>
            <label className="setting-field"><span>读取超时（秒）</span><input type="number" value={globalSettings.readTimeout} onChange={(event) => setGlobalSettings((current) => ({ ...current, readTimeout: event.target.value }))} /></label>
            <label className="setting-field"><span>任务超时（秒） {help("SeaTunnel 任务的 checkpoint 超时时间，超过此时间任务将失败。大表全量建议设为 3600 秒（1小时）")}</span><input type="number" value={globalSettings.taskTimeout} onChange={(event) => setGlobalSettings((current) => ({ ...current, taskTimeout: event.target.value }))} /></label>
          </div>
        </section>
        <section className="setting-card">
          <h2>默认同步策略</h2>
          <div className="setting-body setting-grid three-columns"><label className="setting-field"><span>默认同步模式</span><select value={globalSettings.syncMode} onChange={(event) => setGlobalSettings((current) => ({ ...current, syncMode: event.target.value }))}><option value="truncate">清空写入 (truncate)</option><option value="append">追加写入 (append)</option><option value="upsert">更新写入 (upsert)</option></select></label></div>
        </section>
        {footer(<button className="primary-button" onClick={() => markSaved("global", "全局参数已保存")}>保存</button>, "global")}
      </div>
    </>;

    if (appPage === "registrySettings") {
      const enabled = settingEnabled.medicalRegistry;
      return <>
        <div className="settings-shell">
          <section className="setting-card">
            <h2>功能开关</h2>
            <div className="setting-body switch-setting"><div className="switch-line"><Toggle checked={enabled} label="医共体数据模型" onChange={() => updateFlag("medicalRegistry")} /><strong>{enabled ? "启用" : "关闭"}</strong></div><p>启用后，视图分类评估将优先使用规范定义判定档位，自动提取业务唯一键和时间字段。</p></div>
          </section>
          <section className="setting-card">
            <h2>Doris 连接信息</h2>
            <div className="setting-body setting-grid connection-grid">
              <label className="setting-field wide-field"><span className="required">主机地址</span><input value={registrySettings.host} onChange={(event) => setRegistrySettings((current) => ({ ...current, host: event.target.value }))} disabled={!enabled} /></label>
              <label className="setting-field"><span className="required">端口</span><input value={registrySettings.port} onChange={(event) => setRegistrySettings((current) => ({ ...current, port: event.target.value }))} disabled={!enabled} /></label>
              <label className="setting-field"><span className="required">数据库</span><input value={registrySettings.database} onChange={(event) => setRegistrySettings((current) => ({ ...current, database: event.target.value }))} disabled={!enabled} /></label>
              <label className="setting-field wide-field"><span className="required">用户名</span><input value={registrySettings.username} onChange={(event) => setRegistrySettings((current) => ({ ...current, username: event.target.value }))} disabled={!enabled} /></label>
              <label className="setting-field password-field"><span className="required">密码</span><input type="password" value={registrySettings.password} onChange={(event) => setRegistrySettings((current) => ({ ...current, password: event.target.value }))} disabled={!enabled} /><small>密码以 AES 加密存储，显示为 **** 表示已有密码未修改</small></label>
            </div>
          </section>
          <section className="setting-card">
            <h2>模型表配置</h2>
            <div className="setting-body setting-grid two-columns">
              <label className="setting-field"><span className="required">数据集表名</span><input value={registrySettings.datasetTable} onChange={(event) => setRegistrySettings((current) => ({ ...current, datasetTable: event.target.value }))} disabled={!enabled} /><small>存储数据集定义的表（如 dm_shujuji）</small></label>
              <label className="setting-field"><span className="required">字段定义表名</span><input value={registrySettings.fieldTable} onChange={(event) => setRegistrySettings((current) => ({ ...current, fieldTable: event.target.value }))} disabled={!enabled} /><small>存储字段定义的表（如 dm_shujuyzd）</small></label>
              <label className="setting-field"><span className="required">数据项定义表名</span><input value={registrySettings.itemTable} onChange={(event) => setRegistrySettings((current) => ({ ...current, itemTable: event.target.value }))} disabled={!enabled} /><small>存储字段编码、名称、类型、格式和值域的数据项主表（如 dm_shujuxiang）</small></label>
              <label className="setting-field"><span className="required">数据集编码前缀</span><input defaultValue="ODS_YL_" disabled /><small>当前任务模型固定只同步有效的 ODS_YL_ 标准数据集</small></label>
            </div>
          </section>
          {footer(<><button className="secondary-button" disabled={!enabled} onClick={() => notifySettings(`连接成功，共 ${datasets.length} 个有效数据集`)}>测试连接</button><button className="primary-button" onClick={() => markSaved("registry", "医共体数据模型配置已保存")}>保存</button></>, "registry")}
        </div>
      </>;
    }

    if (appPage === "validationSettings") return <>
      <div className="settings-shell">
        <section className="setting-card"><h2>强制校验配置</h2><div className="setting-body inline-control"><Toggle checked={settingEnabled.enforceValidation} label="强制所有任务必须配置校验" onChange={() => updateFlag("enforceValidation")} /><strong>{settingEnabled.enforceValidation ? "强制开启" : "未强制"}</strong><span>所有任务必须配置校验 {help("开启后，新建任务自动使用默认校验策略，任务列表会标记未配置任务。")}</span></div></section>
        <section className="setting-card"><h2>基本设置</h2><div className="setting-body setting-grid three-columns">
          <label className="setting-field"><span>自动校验 {help("开启后，数据同步每次成功完成将自动触发一次数据校验")}</span><div className="field-switch"><Toggle checked={settingEnabled.autoValidate} label="自动校验" onChange={() => updateFlag("autoValidate")} /><b>{settingEnabled.autoValidate ? "开启" : "关闭"}</b></div></label>
          <label className="setting-field"><span>校验触发时机 {help("after_sync：任务执行成功后自动触发；manual_only：仅通过数据校验页手动触发")}</span><select value={validationTrigger} onChange={(event) => setValidationTrigger(event.target.value)}><option value="after_sync">同步完成后自动触发</option><option value="manual_only">仅手动触发</option></select></label>
          <label className="setting-field"><span>默认校验方式 {help("行数校验：只比较源端与目标端 COUNT(*)，速度最快；Checksum 校验：逐行 hash 对比，能发现行内容修改，耗时较长")}</span><select value={validationMethod} onChange={(event) => setValidationMethod(event.target.value)}><option value="row_count">行数校验</option><option value="checksum">Checksum 校验</option></select></label>
        </div></section>
        {validationMethod === "checksum" ? <section className="setting-card"><h2>Checksum 配置 <b className="blue-tag">适用于行内容修改检测</b></h2><div className="setting-body empty-setting" /></section> : <section className="setting-card"><h2>容差配置</h2><div className="setting-body setting-grid three-columns"><label className="setting-field"><span>行数容差 (%) {help("源端与目标端行数差异的允许比例。0 = 必须完全一致；1 = 差异 ≤1% 视为通过")}</span><div className="suffix-input"><input type="number" value={validationSettingsForm.tolerance} onChange={(event) => setValidationSettingsForm((current) => ({ ...current, tolerance: event.target.value }))} /><b>%</b></div></label></div></section>}
        <section className="setting-card"><h2>校验失败处理</h2><div className="setting-body setting-grid three-columns">
          <label className="setting-field"><span>校验失败阻断水位推进 {help("开启后，校验发现差异时增量水位不会前移；阻断判断仅比较源端与目标端行数，避免长时间等待。")}</span><div className="field-switch"><Toggle checked={settingEnabled.failBlock} label="校验失败阻断水位推进" onChange={() => updateFlag("failBlock")} /><b>{settingEnabled.failBlock ? "阻断" : "不阻断"}</b></div></label>
          <label className="setting-field"><span>校验失败自动重试 {help("开启后，校验结果为 DIFF 时延迟指定秒数再触发一次校验（用于排除短暂数据延迟导致的误报）")}</span><div className="field-switch"><Toggle checked={settingEnabled.revalidate} label="校验失败自动重试" onChange={() => updateFlag("revalidate")} /><b>{settingEnabled.revalidate ? "重试" : "不重试"}</b></div></label>
          <label className="setting-field"><span>重试延迟（秒） {help("校验失败后，等待多少秒再进行一次重试校验")}</span><div className="suffix-input"><input type="number" value={validationSettingsForm.retryDelay} onChange={(event) => setValidationSettingsForm((current) => ({ ...current, retryDelay: event.target.value }))} /><b>秒</b></div></label>
        </div></section>
        <section className="setting-card"><h2>自动修复</h2><div className="setting-body inline-control"><span>允许视图源自动修复 {help("开启后，视图源任务可从源端回查并修复目标库；关闭后需要在校验工作台人工处理。")}</span><Toggle checked={settingEnabled.viewRepair} label="允许视图源自动修复" onChange={() => updateFlag("viewRepair")} /><strong>{settingEnabled.viewRepair ? "允许" : "禁止"}</strong></div></section>
        <section className="setting-card"><h2>校验回看窗口</h2><div className="setting-body setting-grid three-columns"><label className="setting-field"><span>回看时长（小时） {help("向前覆盖历史延迟修改；0 表示只校验本次增量窗口，任务级可单独覆盖该值。")}</span><div className="suffix-input"><input type="number" value={validationSettingsForm.lookbackHours} onChange={(event) => setValidationSettingsForm((current) => ({ ...current, lookbackHours: event.target.value }))} /><b>小时</b></div></label></div></section>
        {footer(<button className="primary-button" onClick={() => markSaved("validation", "校验策略已保存")}>保存</button>, "validation")}
      </div>
    </>;

    if (appPage === "dorisSettings") {
      const partitionEnabled = settingEnabled.partition;
      return <>
        <div className="settings-shell">
          <section className="setting-card"><h2>建表策略</h2><div className="setting-body">
            <div className="setting-grid two-columns"><label className="setting-field"><span>建表模式</span><select value={dorisSettingsForm.mode} onChange={(event) => setDorisSettingsForm((current) => ({ ...current, mode: event.target.value }))}><option value="RELAXED">宽松模式（基于 JDBC 元数据，全部 NULL）</option><option value="STRICT">严格模式（基于规范定义，精确类型+NOT NULL）</option></select><small>宽松模式：适合快速跑通，类型取最大容量，所有列 NULL。严格模式：适合生产环境，按医共体规范精确建表。</small></label></div>
            <div className="setting-divider" /><h3>已存在表校验策略</h3><div className="check-line"><label><input type="checkbox" checked={settingEnabled.typeCheck} onChange={() => updateFlag("typeCheck")} /> 检查类型兼容性</label><label><input type="checkbox" checked={settingEnabled.nullableCheck} onChange={() => updateFlag("nullableCheck")} /> 检查 NOT NULL 一致性</label></div><p className="setting-note">默认不检查类型和 NOT NULL，只检查字段是否存在（缺失自动添加）。开启后，已存在的 Doris 表如果类型或 NULL 约束不匹配会报错。</p>
          </div></section>
          <section className="setting-card"><h2>自动分区策略</h2><div className="setting-body setting-grid three-columns">
            <label className="setting-field"><span>启用自动分区</span><div className="field-switch"><Toggle checked={partitionEnabled} label="启用自动分区" onChange={() => updateFlag("partition")} /><b>{partitionEnabled ? "启用" : "关闭"}</b></div></label>
            <label className="setting-field"><span>分区字段</span><input value={dorisSettingsForm.partitionField} onChange={(event) => setDorisSettingsForm((current) => ({ ...current, partitionField: event.target.value }))} disabled={!partitionEnabled} /></label>
            <label className="setting-field"><span>分区粒度</span><select value={partitionGranularity} disabled={!partitionEnabled} onChange={(event) => setPartitionGranularity(event.target.value)}><option value="MONTH">按月分区</option><option value="DAY">按日分区</option></select></label>
            <label className="setting-field"><span>{partitionGranularity === "DAY" ? "历史覆盖天数" : "历史覆盖月数"}</span><input type="number" value={dorisSettingsForm.historyRange} onChange={(event) => setDorisSettingsForm((current) => ({ ...current, historyRange: event.target.value }))} disabled={!partitionEnabled} /></label>
            <label className="setting-field"><span>{partitionGranularity === "DAY" ? "未来预建天数" : "未来预建月数"}</span><input type="number" value={dorisSettingsForm.futureRange} onChange={(event) => setDorisSettingsForm((current) => ({ ...current, futureRange: event.target.value }))} disabled={!partitionEnabled} /></label>
          </div></section>
          <section className="setting-card"><h2>自动分桶策略</h2><div className="setting-body">
            <div className="setting-grid three-columns"><label className="setting-field"><span>bucket 策略</span><select value={bucketStrategy} onChange={(event) => setBucketStrategy(event.target.value)}><option value="FIXED">固定 bucket 数</option><option value="DATA_SCALE">按预计数据量自动选择</option></select></label><label className="setting-field"><span>固定 bucket 数</span><input type="number" value={dorisSettingsForm.fixedBuckets} onChange={(event) => setDorisSettingsForm((current) => ({ ...current, fixedBuckets: event.target.value }))} disabled={bucketStrategy === "DATA_SCALE"} /></label></div>
            <div className="bucket-table"><div className="bucket-head"><b>预计数据量</b><b>bucket 数</b></div>{bucketRows.map(([scale, count], index) => <div key={scale}><span>{scale}</span><input type="number" value={count} onChange={(event) => setBucketRows((current) => current.map((row, rowIndex) => rowIndex === index ? [row[0], event.target.value] : row))} disabled={bucketStrategy !== "DATA_SCALE"} /></div>)}</div>
          </div></section>
          <section className="setting-card note-card"><div className="setting-body"><p>自动建表分桶字段：有业务 key 时使用第一个业务 key；无业务 key 时优先使用 xiugaisj；否则使用第一列。</p><p>partition 默认关闭；启用后仅在源视图包含分区字段时生成分区 DDL。</p><p>已存在 Doris 表不自动变更 partition / bucket，需要由 Doris 运维侧按需调整。</p></div></section>
          {footer(<button className="primary-button" onClick={() => markSaved("doris", "Doris 自动建表策略已保存")}>保存</button>, "doris")}
        </div>
      </>;
    }

    if (appPage === "externalApi") {
      return <>
        <div className="settings-shell">
          <div className="settings-tabs"><button className={externalSettingsTab === "clients" ? "active" : ""} onClick={() => setExternalSettingsTab("clients")}>调用方管理</button><button className={externalSettingsTab === "guide" ? "active" : ""} onClick={() => setExternalSettingsTab("guide")}>接口说明</button></div>
          {externalSettingsTab === "clients" ? <section className="setting-card external-client-card"><h2>外部授权调用方 <button className="primary-button" onClick={() => { setExternalEditingId(null); setSettingDialog("external"); }}><PlusOutlined /> 新增调用方</button></h2><div className="table-scroll"><table className="resource-table settings-table"><thead><tr><th>Client ID</th><th>名称</th><th>状态</th><th>医疗机构</th><th>说明</th><th className="action-col">操作</th></tr></thead><tbody>{externalClients.map((row) => <tr key={row[0]}><td><code>{row[0]}</code></td><td>{row[1]}</td><td><b className="precheck-pass">{row[2]}</b></td><td>{row[3] === "不限" ? <span className="neutral-tag">不限</span> : <code>{row[3]}</code>}</td><td>{row[4]}</td><td className="action-col"><button className="text-button" onClick={() => { setExternalEditingId(row[0]); setSettingDialog("external"); }}>编辑</button><button className="text-button" onClick={() => setExternalResetTarget(row[0])}>重置密钥</button><button className="text-button danger-text" onClick={() => setExternalDeleteTarget(row[0])}>删除</button></td></tr>)}</tbody></table></div></section> : <div className="guide-stack">
            <section className="setting-card"><h2>文档入口</h2><div className="setting-body button-row"><button className="secondary-button" onClick={() => navigate("docs")}>打开 Swagger 调试文档</button><button className="secondary-button" onClick={downloadOpenApi}>下载 OpenAPI JSON</button></div></section>
            <section className="setting-card"><h2>调用流程</h2><div className="setting-body ordered-guide"><ol><li>维护人员在“调用方管理”中创建外部授权调用方，获取 Client ID 和 Shared Secret。</li><li>第三方系统按请求方法、URI、时间戳、nonce、body 摘要拼接签名串。</li><li>使用 Shared Secret 计算 HMAC-SHA256，小写十六进制结果放入签名请求头。</li><li>调用 /api/v1/** 接口创建、查询、运行或删除数据同步任务。</li></ol></div></section>
            <section className="setting-card"><h2>HMAC 请求头</h2><div className="compact-info-table"><div><b>Header</b><b>含义</b></div>{[["X-DFETL-Client-Id", "外部授权调用方 Client ID"], ["X-DFETL-Timestamp", "epoch milliseconds，允许 5 分钟时钟偏差"], ["X-DFETL-Nonce", "单次请求随机串，同一 Client ID 下不可重复"], ["X-DFETL-Signature", "HMAC-SHA256 十六进制小写签名"]].map(([header, meaning]) => <div key={header}><code>{header}</code><span>{meaning}</span></div>)}</div></section>
            <section className="setting-card"><h2>签名串</h2><div className="setting-body"><p className="setting-note"><code>secret</code> 不在请求中传输，只用于本地计算签名。</p><pre className="code-block">{`METHOD + "\\n" +\nREQUEST_URI + "\\n" +\nTIMESTAMP + "\\n" +\nNONCE + "\\n" +\nSHA256_HEX(REQUEST_BODY)`}</pre></div></section>
            <section className="setting-card"><h2>核心接口</h2><div className="compact-info-table api-table"><div><b>方法</b><b>路径</b><b>用途</b></div>{[["POST", "/api/v1/sync-task-plans", "按机构和数据集清单批量预检，不落库"], ["POST", "/api/v1/sync-tasks", "按机构和数据集清单批量创建普通同步任务"], ["GET", "/api/v1/sync-tasks", "按机构编码和数据集编码查询任务"], ["DELETE", "/api/v1/sync-tasks", "按机构编码和数据集编码删除任务"], ["POST", "/api/v1/sync-runs", "按机构编码和数据集编码运行任务"], ["GET", "/api/v1/message-publish-runs/{executionId}", "查询指定执行的消息发布状态"], ["POST", "/api/v1/message-publish-runs/{executionId}/retries", "重试指定执行的消息发布"]].map(([method, path, usage]) => <div key={`${method}${path}`}><b className={`method-tag method-${method}`}>{method}</b><code>{path}</code><span>{usage}</span></div>)}</div></section>
            <section className="setting-card"><h2>任务创建规则</h2><div className="setting-body"><p>调用方只需提交机构编码和标准数据集编码；已有任务请先删除再创建。</p></div></section>
            <section className="setting-card"><h2>计划或创建请求示例</h2><div className="setting-body"><pre className="code-block">{`{\n  "requestId": "YGT-20260805-001",\n  "yiLiaoJgDm": "YGT330106H001",\n  "datasetCodes": [\n    "ODS_YL_HUANZHEJBXX",\n    "ODS_YL_KESHIXX",\n    "ODS_YL_ZHIGONGXX"\n  ],\n  "runAfterCreate": true,\n  "failurePolicy": "BEST_EFFORT"\n}`}</pre></div></section>
          </div>}
        </div>
      </>;
    }

    const filteredMappingRows = mappingRules.filter((row) => !mappingFilter || row[0] === mappingFilter);
    const pagedMappingRows = paginate(filteredMappingRows, mappingPaging);
    return <>
      <section className="data-card management-card mapping-card"><div className="toolbar management-toolbar"><select value={mappingFilter} onChange={(event) => { setMappingFilter(event.target.value); setMappingPaging((state) => ({ ...state, page: 1 })); }}><option value="">全部</option><option value="POSTGRESQL">PostgreSQL</option><option value="MYSQL">MySQL</option><option value="ORACLE">Oracle</option><option value="SQLSERVER">SQL Server</option></select><div className="toolbar-spacer" /><button className="secondary-button" onClick={() => notifySettings(`初始化完成：新增 0 条，跳过 ${mappingRules.length} 条`)}>初始化默认规则</button></div><div className="table-scroll"><table className="resource-table settings-table mapping-table"><thead><tr><th>方言</th><th>源类型匹配</th><th>推荐 Doris 类型</th><th>等级</th><th>原因</th><th>优先级</th><th>启用</th><th className="action-col">操作</th></tr></thead><tbody>{pagedMappingRows.rows.map((row) => <tr key={`${row[0]}${row[1]}`}>{row.map((cell, index) => <td key={index}>{index === 3 ? <b className={`level-tag level-${cell}`}>{cell}</b> : index === 6 ? <b className="precheck-pass">{cell}</b> : cell}</td>)}<td className="action-col"><button className="text-button" onClick={() => { setMappingEditingKey(`${row[0]}:${row[1]}`); setSettingDialog("mapping"); }}><EditOutlined /> 编辑</button></td></tr>)}</tbody></table></div><Pagination total={filteredMappingRows.length} state={mappingPaging} onChange={setMappingPaging} noun="条规则" /></section>
    </>;
  }

  function institutionPage() {
    const normalizedQuery = institutionQuery.trim().toLowerCase();
    const filteredRows = institutionRows.filter((item) => {
      const status = institutionEnabled[item.code] ? "启用" : "禁用";
      return (institutionType === "全部机构类型" || item.type === institutionType)
        && (institutionStatus === "全部状态" || status === institutionStatus)
        && (!normalizedQuery || `${item.name}${item.code}`.toLowerCase().includes(normalizedQuery));
    });
    const paged = paginate(filteredRows, institutionPaging);
    return <>
      <section className="data-card management-card">
        <div className="toolbar management-toolbar"><select aria-label="机构类型" value={institutionType} onChange={(event) => { setInstitutionType(event.target.value); setInstitutionPaging((state) => ({ ...state, page: 1 })); }}><option>全部机构类型</option><option>医院</option><option>妇幼保健院</option><option>乡镇卫生院</option></select><select aria-label="机构状态" value={institutionStatus} onChange={(event) => { setInstitutionStatus(event.target.value); setInstitutionPaging((state) => ({ ...state, page: 1 })); }}><option>全部状态</option><option>启用</option><option>禁用</option></select><label className="search-box wide-search"><SearchOutlined /><input value={institutionQuery} onChange={(event) => { setInstitutionQuery(event.target.value); setInstitutionPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索机构名称或编码" /><kbd>⌘ K</kbd></label><button className="ghost-button" onClick={() => { setInstitutionType("全部机构类型"); setInstitutionStatus("全部状态"); setInstitutionQuery(""); setInstitutionPaging((state) => ({ ...state, page: 1 })); }}><ReloadOutlined /> 重置</button><div className="toolbar-spacer" /><button className="primary-button" onClick={() => setInstitutionDialog({ mode: "create" })}><PlusOutlined /> 新增机构</button></div>
        <div className="table-scroll"><table className="resource-table institution-table"><thead><tr><th>机构名称</th><th>类型 / 等级</th><th>上级机构</th><th>行政区划</th><th>业务系统</th><th>接入状态</th><th className="action-col">操作</th></tr></thead><tbody>
          {paged.rows.map((item) => <tr key={item.code}><td><div className="resource-name"><strong>{item.name}</strong><span>{item.code}</span></div></td><td><div className="tag-stack"><b className="neutral-tag">{item.type}</b><span>{item.level}</span></div></td><td>{item.parent}</td><td>{item.division}</td><td><div className="system-cell"><strong>{item.system}</strong><span>{item.source === "mysql-jcyl" ? "9 家机构共用" : "独立业务系统"}</span></div></td><td><div className="status-toggle-cell"><Toggle checked={institutionEnabled[item.code]} label={`${item.name}状态`} onChange={() => setInstitutionEnabled((current) => ({ ...current, [item.code]: !current[item.code] }))} /><span>{institutionEnabled[item.code] ? "启用" : "禁用"}</span></div></td><td className="action-col"><button className="text-button" onClick={() => setInstitutionDialog({ mode: "edit", code: item.code })}><EditOutlined /> 编辑</button><button className="text-button danger-text" onClick={() => setInstitutionDelete(item.code)}><DeleteOutlined /> 删除</button></td></tr>)}
          {!paged.rows.length && <tr><td colSpan={7} className="empty-state">没有符合条件的机构。</td></tr>}
        </tbody></table></div>
        <Pagination total={filteredRows.length} state={institutionPaging} onChange={setInstitutionPaging} noun="家机构" />
      </section>
    </>;
  }

  function dataSourcesPage() {
    const normalizedQuery = dataSourceQuery.trim().toLowerCase();
    const sourceRows = sourceDataSources.filter((item) => (dataSourceType === "全部数据库类型" || item.type === dataSourceType) && (dataSourceStatus === "全部状态" || (sourceEnabled[item.name] ? "启用" : "禁用") === dataSourceStatus) && (!normalizedQuery || `${item.name}${item.host}${item.database}${item.schema}`.toLowerCase().includes(normalizedQuery)));
    const targetRows = targetDataSources.filter((item) => (dataSourceStatus === "全部状态" || (targetEnabled[item.name] ? "启用" : "禁用") === dataSourceStatus) && (!normalizedQuery || `${item.name}${item.host}${item.database}${item.writeDb}`.toLowerCase().includes(normalizedQuery)));
    const filteredRows: Array<SourceDataSource | TargetDataSource> = dataSourceTab === "source" ? sourceRows : targetRows;
    const paged = paginate<SourceDataSource | TargetDataSource>(filteredRows, dataSourcePaging);
    return <>
      <section className="data-card management-card">
        <div className="resource-tabs"><button className={dataSourceTab === "source" ? "active" : ""} onClick={() => { setDataSourceTab("source"); setDataSourcePaging((state) => ({ ...state, page: 1 })); }}>源数据源 <span>{sourceDataSources.length}</span></button><button className={dataSourceTab === "target" ? "active" : ""} onClick={() => { setDataSourceTab("target"); setDataSourcePaging((state) => ({ ...state, page: 1 })); }}>目标数据源 <span>{targetDataSources.length}</span></button></div>
        <div className="toolbar management-toolbar">
          {dataSourceTab === "source" && <select value={dataSourceType} onChange={(event) => { setDataSourceType(event.target.value); setDataSourcePaging((state) => ({ ...state, page: 1 })); }}><option>全部数据库类型</option><option>PostgreSQL</option><option>Oracle</option><option>SQL Server</option><option>MySQL</option></select>}
          <select value={dataSourceStatus} onChange={(event) => { setDataSourceStatus(event.target.value); setDataSourcePaging((state) => ({ ...state, page: 1 })); }}><option>全部状态</option><option>启用</option><option>禁用</option></select>
          <label className="search-box wide-search"><SearchOutlined /><input value={dataSourceQuery} onChange={(event) => { setDataSourceQuery(event.target.value); setDataSourcePaging((state) => ({ ...state, page: 1 })); }} placeholder={dataSourceTab === "source" ? "搜索名称或主机" : "搜索名称或 FE 地址"} /></label>
          <button className="ghost-button" onClick={() => { setDataSourceType("全部数据库类型"); setDataSourceStatus("全部状态"); setDataSourceQuery(""); setDataSourcePaging((state) => ({ ...state, page: 1 })); }}><ReloadOutlined /> 重置</button>
          <div className="toolbar-spacer" />
          <button className="primary-button toolbar-primary" onClick={() => { if (dataSourceTab === "source") setSourceDbType("PostgreSQL"); setDataSourceDialog({ mode: "create", tab: dataSourceTab }); }}><PlusOutlined /> 新建{dataSourceTab === "source" ? "源数据源" : "目标数据源"}</button>
        </div>
        {dataSourceTab === "source" ? <div className="table-scroll"><table className="resource-table source-table"><thead><tr><th>名称</th><th>类型</th><th>主机</th><th>数据库 / Schema</th><th>用户名 / 密码</th><th>关联机构</th><th>状态</th><th className="action-col">操作</th></tr></thead><tbody>{(paged.rows as SourceDataSource[]).map((item) => <tr key={item.name}><td><div className="resource-name"><strong>{item.name}</strong><span>源端连接</span></div></td><td><b className={`db-tag db-${item.type.replace(" ", "-")}`}>{item.type}</b></td><td className="mono-cell">{item.host}</td><td><div className="system-cell"><strong>{item.database}</strong><span>{item.type === "MySQL" ? "数据库" : item.schema}</span></div></td><td><div className="credential-cell"><strong>{item.username}</strong><span><EyeInvisibleOutlined /> {item.password}</span></div></td><td><div className="org-chips source-orgs">{item.institutions.slice(0, 2).map((name) => <b key={name}>{name}</b>)}{item.institutions.length > 2 && <b>+{item.institutions.length - 2} 家</b>}</div></td><td><div className="status-toggle-cell"><Toggle checked={sourceEnabled[item.name]} label={`${item.name}状态`} onChange={() => setSourceEnabled((current) => ({ ...current, [item.name]: !current[item.name] }))} /><span>{sourceEnabled[item.name] ? "启用" : "禁用"}</span></div></td><td className="action-col"><button className="text-button" disabled={testingDataSource === item.name} onClick={() => simulateConnectionTest(item.name, item.name.includes("oracle"))}>{testingDataSource === item.name ? "测试中…" : "连接测试"}</button><button className="text-button" onClick={() => { setSourceDbType(item.type); setDataSourceDialog({ mode: "edit", tab: "source", name: item.name }); }}><EditOutlined /> 编辑</button><span className="relative-action"><button className="more-button" aria-label={`${item.name}更多操作`} onClick={() => setDataSourceMore((value) => value === item.name ? null : item.name)}><MoreOutlined /></button>{dataSourceMore === item.name && <span className="row-more-menu"><button onClick={() => { navigator.clipboard?.writeText(item.name); setDataSourceMore(null); }}>复制名称</button><button onClick={() => { setSourceEnabled((current) => ({ ...current, [item.name]: false })); setDataSourceMore(null); }}>禁用</button><button className="danger-text" onClick={() => { setDataSourceDeleteTarget({ tab: "source", name: item.name }); setDataSourceMore(null); }}>删除</button></span>}</span></td></tr>)}</tbody></table></div> : <div className="table-scroll"><table className="resource-table target-table"><thead><tr><th>名称</th><th>FE 地址</th><th>数据库 / 写入库</th><th>写入参数</th><th>用户名 / 密码</th><th>状态</th><th className="action-col">操作</th></tr></thead><tbody>{(paged.rows as TargetDataSource[]).map((item) => <tr key={item.name}><td><div className="resource-name"><strong>{item.name}</strong><span>Doris 目标端</span></div></td><td><div className="system-cell mono-cell"><strong>{item.host}:{item.fePort}</strong><span>HTTP {item.httpPort} · Stream Load {item.streamLoadPort}</span></div></td><td><div className="system-cell"><strong>{item.database}</strong><span>{item.writeDb}</span></div></td><td><div className="system-cell"><strong>批量 {item.batchSize}</strong><span>并发 {item.writeConcurrency} · 连接池 {item.poolSize}</span></div></td><td><div className="credential-cell"><strong>{item.username}</strong><span><EyeInvisibleOutlined /> {item.password}</span></div></td><td><div className="status-toggle-cell"><Toggle checked={targetEnabled[item.name]} label={`${item.name}状态`} onChange={() => setTargetEnabled((current) => ({ ...current, [item.name]: !current[item.name] }))} /><span>{targetEnabled[item.name] ? "启用" : "禁用"}</span></div></td><td className="action-col"><button className="text-button" disabled={testingDataSource === item.name} onClick={() => simulateConnectionTest(item.name, !targetEnabled[item.name], "目标数据源已禁用")}>{testingDataSource === item.name ? "测试中…" : "连接测试"}</button><button className="text-button" onClick={() => setDataSourceDialog({ mode: "edit", tab: "target", name: item.name })}><EditOutlined /> 编辑</button><span className="relative-action"><button className="more-button" aria-label={`${item.name}更多操作`} onClick={() => setDataSourceMore((value) => value === item.name ? null : item.name)}><MoreOutlined /></button>{dataSourceMore === item.name && <span className="row-more-menu"><button onClick={() => { navigator.clipboard?.writeText(item.name); setDataSourceMore(null); }}>复制名称</button><button onClick={() => { setTargetEnabled((current) => ({ ...current, [item.name]: false })); setDataSourceMore(null); }}>禁用</button><button className="danger-text" onClick={() => { setDataSourceDeleteTarget({ tab: "target", name: item.name }); setDataSourceMore(null); }}>删除</button></span>}</span></td></tr>)}</tbody></table></div>}
        {!paged.rows.length && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的数据源。</span></div>}
        <Pagination total={filteredRows.length} state={dataSourcePaging} onChange={setDataSourcePaging} />
      </section>
    </>;
  }

  function tasksPage() {
    const normalizedQuery = taskQuery.trim().toLowerCase();
    const filteredTasks = tasks.filter((task) => {
      const scope = task.link.institutions.length > 1 ? "基层医疗共享" : task.link.institutions[0];
      return (taskInstitution === "全部机构 / 机构组" || scope === taskInstitution)
        && (taskStatus === "全部状态" || task.state === taskStatus)
        && (!normalizedQuery || `${task.name}${task.dataset.name}${task.dataset.code}${task.view}`.toLowerCase().includes(normalizedQuery));
    });
    const paged = paginate(filteredTasks, taskPaging);
    return <>
      <section className="data-card management-card"><div className="toolbar management-toolbar"><select value={taskInstitution} onChange={(event) => { setTaskInstitution(event.target.value); setTaskPaging((state) => ({ ...state, page: 1 })); }}><option>全部机构 / 机构组</option><option>县人民医院</option><option>县中医院</option><option>基层医疗共享</option></select><select value={taskStatus} onChange={(event) => { setTaskStatus(event.target.value); setTaskPaging((state) => ({ ...state, page: 1 })); }}><option>全部状态</option><option>运行中</option><option>失败</option><option>已停止</option></select><label className="search-box wide-search"><span>⌕</span><input value={taskQuery} onChange={(event) => { setTaskQuery(event.target.value); setTaskPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索任务名、数据集或源表" /></label><button className="ghost-button" onClick={() => { setTaskQuery(""); setTaskInstitution("全部机构 / 机构组"); setTaskStatus("全部状态"); setTaskPaging((state) => ({ ...state, page: 1 })); }}><ReloadOutlined /> 重置</button><div className="toolbar-spacer" /></div>
        <div className="table-scroll"><table className="resource-table task-table"><thead><tr><th>任务名称</th><th>类型</th><th>源 → 目标</th><th>机构范围</th><th>视图 / 表</th><th>调度</th><th>状态</th><th>最近运行</th><th className="action-col">操作</th></tr></thead><tbody>{paged.rows.map((task) => <tr key={task.id}><td><div className="resource-name"><strong>{task.name}</strong><span>{task.id} · {task.dataset.name}</span></div></td><td><b className="sync-tag">增量同步</b></td><td><div className="task-route"><span>{task.link.source}</span><b>→</b><span className="target">ygt-doris</span></div></td><td><div className="system-cell"><strong>{task.link.institutions.length > 1 ? "基层医疗共享" : task.link.institutions[0]}</strong><span>{task.link.institutions.length} 家机构</span></div></td><td className="mono-cell">{task.view}</td><td>{task.link.schedule}</td><td><span className={`runtime-status runtime-${task.state}`}>{task.state}</span></td><td><div className="system-cell"><strong>{task.recent}</strong><span>成功率 {task.successRate}</span></div></td><td className="action-col"><button className="text-button" onClick={() => { setSelectedTask(task); setTaskDetailTab("basic"); navigate("taskDetail", task.id); }}><FileTextOutlined /> 任务详情</button><button className="start-button"><PlayCircleOutlined /> 运行</button><button className="more-button" aria-label={`${task.name}更多操作`}><MoreOutlined /></button></td></tr>)}</tbody></table>{!paged.rows.length && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的数据同步任务。</span></div>}</div>
        <Pagination total={filteredTasks.length} state={taskPaging} onChange={setTaskPaging} noun="个任务" />
      </section>
    </>;
  }

  function monitorPage() {
    const normalizedMonitorQuery = monitorQuery.trim().toLowerCase();
    const rows = monitorRows.filter((row) => (monitorFilter === "全部状态" || row.status === monitorFilter) && (!normalizedMonitorQuery || `${row.name}${row.batch}${row.id}`.toLowerCase().includes(normalizedMonitorQuery)));
    const paged = paginate(rows, monitorPaging);
    const openMonitorTask = (row: MonitorRow) => {
      const task = tasks.find((candidate) => candidate.id === row.taskId) ?? tasks.find((candidate) => candidate.name === row.name);
      if (!task) return notifySettings("未找到对应的数据同步任务");
      setSelectedTask(task);
      setTaskDetailTab("runs");
      navigate("taskDetail", task.id);
    };
    const metrics = [
      ["运行中", monitorRows.filter((row) => row.status === "运行中").length, "8.2 MB/s", "run"],
      ["已完成", monitorRows.filter((row) => row.status === "已完成").length, "今日 1,831 次", "done"],
      ["失败", monitorRows.filter((row) => row.status === "失败").length, "3 个待重试", "failed"],
      ["需人工核对", monitorRows.filter((row) => row.status === "需核对").length, "影响 66 行", "review"],
    ];
    const trend = [44, 61, 53, 78, 68, 82, 57, 74, 88, 69, 92, 81];
    const completedCount = monitorRows.filter((row) => row.status === "已完成").length;
    const runningCount = monitorRows.filter((row) => row.status === "运行中").length;
    const failedCount = monitorRows.filter((row) => row.status === "失败").length;
    const successRate = monitorRows.length ? Math.round(completedCount / monitorRows.length * 100) : 100;
    const percentage = (count: number) => `${monitorRows.length ? count / monitorRows.length * 100 : 0}%`;
    return <>
      <section className="workspace-heading"><div><span className="workspace-kicker">任务中心</span><h1>任务监控</h1><p>跟踪运行状态、吞吐与异常任务</p></div><div className="monitor-refresh"><span><SyncOutlined /> 30 秒自动刷新</span><small>更新于 13:43:48</small><button className="secondary-button" onClick={() => notifySettings("任务状态已刷新")}><ReloadOutlined /> 刷新</button></div></section>
      <section className="monitor-summary">
        <article className="monitor-health"><div><span>任务运行健康度</span><b>{monitorRows.some((row) => row.status === "失败") ? "需关注" : "稳定"}</b></div><strong>{monitorRows.length ? Math.round(monitorRows.filter((row) => row.status !== "失败").length / monitorRows.length * 100) : 100}%</strong><p><i /> {monitorRows.filter((row) => row.status !== "失败").length} 条记录正常，{monitorRows.filter((row) => row.status === "失败").length} 条失败</p><button className="text-button" onClick={() => setMonitorFilter("失败")}>查看异常任务 <RightOutlined /></button></article>
        <div className="ops-metrics">{metrics.map(([label, value, meta, tone]) => <article key={label}><i className={`ops-icon ${tone}`}>{tone === "run" ? <PlayCircleOutlined /> : tone === "done" ? <CheckCircleOutlined /> : tone === "failed" ? <CloseCircleOutlined /> : <ExclamationCircleOutlined />}</i><div><span>{label}</span><strong>{value}</strong><small>{meta}</small></div></article>)}</div>
      </section>
      <section className="monitor-pulse-grid">
        <article className="data-card monitor-trend"><div className="panel-title"><div><h2>最近 60 分钟吞吐</h2><span>12:45–13:45</span></div><b>平均 6.8 MB/s</b></div><div className="pulse-bars">{trend.map((value, index) => <i key={index} style={{ height: `${value}%` }} title={`${index * 5} 分钟：${value}`} />)}</div><div className="pulse-axis"><span>12:45</span><span>13:15</span><span>13:45</span></div></article>
        <article className="data-card execution-summary"><div className="panel-title"><div><h2>本小时执行</h2><span>共 {monitorRows.length} 个批次</span></div></div><div className="execution-number"><strong>{successRate}%</strong><span>完成率</span></div><div className="execution-split"><span className="success" style={{ width: percentage(completedCount) }} /><span className="running" style={{ width: percentage(runningCount) }} /><span className="failed" style={{ width: percentage(failedCount) }} /></div><div className="execution-legend"><span><i className="success" />完成 {completedCount}</span><span><i className="running" />运行 {runningCount}</span><span><i className="failed" />失败 {failedCount}</span></div></article>
      </section>
      <section className="data-card management-card ops-card">
        <div className="monitor-table-heading"><div><h2>运行记录</h2><span>优先展示失败与需核对任务</span></div><span className="table-count">共 {rows.length} 条</span></div>
        <div className="toolbar management-toolbar monitor-toolbar"><div className="status-filter-group">{["全部状态", "运行中", "已完成", "失败", "需核对"].map((status) => <button key={status} className={monitorFilter === status ? "active" : ""} onClick={() => setMonitorFilter(status)}>{status}{status !== "全部状态" && <b>{monitorRows.filter((row) => row.status === status).length}</b>}</button>)}</div><label className="search-box wide-search"><SearchOutlined /><input value={monitorQuery} onChange={(event) => setMonitorQuery(event.target.value)} placeholder="搜索任务名、批次号" /></label><div className="toolbar-spacer" /></div>
        <div className="table-scroll"><table className="resource-table monitor-table"><thead><tr><th>任务名称</th><th>类型</th><th>批次号</th><th>开始时间</th><th>耗时</th><th>读取行数</th><th>写入行数</th><th>速率 (MB/s)</th><th>状态</th><th className="action-col">操作</th></tr></thead><tbody>{paged.rows.map((row) => <tr key={row.id} className={row.status === "失败" ? "failure-row" : ""}><td><div className="resource-name"><strong>{row.name}</strong><span>{row.id}</span></div></td><td><b className="increment-tag">增量</b></td><td><button className="inline-link mono-cell" onClick={() => openMonitorTask(row)}>{row.batch}</button></td><td>{row.start}</td><td>{row.duration}</td><td>{row.read}</td><td>{row.written}</td><td>{row.speed}</td><td><span className={`runtime-status monitor-${row.status.replace(" / ", "-")}`}>{row.status}</span></td><td className="action-col">{row.status === "失败" && <button className="text-button" onClick={() => { setMonitorRows((current) => current.map((item) => item.id === row.id ? { ...item, status: "运行中" } : item)); notifySettings(`${row.id} 已提交重试`); }}>重试</button>}<button className="text-button" onClick={() => openMonitorTask(row)}>详情</button></td></tr>)}</tbody></table>{rows.length === 0 && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的运行记录</span></div>}</div>
        <Pagination total={rows.length} state={monitorPaging} onChange={setMonitorPaging} noun="条运行记录" />
      </section>
    </>;
  }

  function validationOverviewPage() {
    const differenceRows = validationRows.filter((row) => row.result === "发现差异");
    const filteredDifferenceRows = differenceRows.filter((row) => !validationOverviewQuery.trim() || `${row.task}${row.name}`.toLowerCase().includes(validationOverviewQuery.trim().toLowerCase()));
    const pagedDifferenceRows = paginate(filteredDifferenceRows, validationOverviewPaging);
    const validationTrend = [18, 13, 21, 16, 26, 19, 12];
    return <>
      <section className="workspace-heading"><div><span className="workspace-kicker">数据质量</span><h1>校验总览</h1><p>同步任务校验覆盖、差异趋势与待处理任务</p></div><div className="heading-actions"><button className="secondary-button" onClick={() => notifySettings("校验总览已刷新")}><ReloadOutlined /> 刷新</button><button className="primary-button" onClick={() => navigate("validationWorkbench")}><SafetyCertificateOutlined /> 校验工作台</button></div></section>
      <section className="validation-hero">
        <article className="coverage-card"><div className="coverage-score"><strong>100%</strong><span>校验覆盖率</span></div><div className="coverage-copy"><div><span>数据同步任务</span><strong>{tasks.length}</strong></div><p><CheckCircleOutlined /> 当前 Mock 任务均已配置校验策略</p></div></article>
        <div className="validation-kpis"><article><i className="green"><CheckCircleOutlined /></i><div><span>当前已通过</span><strong>{validationRows.filter((row) => row.result === "数据一致").length}</strong><small>按当前 Mock 数据统计</small></div></article><article><i className="red"><CloseCircleOutlined /></i><div><span>存在差异任务</span><strong>{differenceRows.length}</strong><small>均需进入工作台处理</small></div></article><article><i className="amber"><WarningOutlined /></i><div><span>差异数据行</span><strong>{differenceRows.reduce((sum, row) => sum + row.differences, 0).toLocaleString()}</strong><small>来自差异任务汇总</small></div></article></div>
      </section>
      <section className="validation-insight-grid">
        <article className="data-card policy-coverage"><div className="panel-title"><div><h2>策略覆盖</h2><span>按 {tasks.length} 个数据同步任务统计</span></div><button className="text-button" onClick={() => navigate("validationWorkbench")}>管理策略 <RightOutlined /></button></div><div className="policy-bars"><div><span>基础行数校验</span><b>{tasks.length} / {tasks.length}</b><i><em style={{ width: "100%" }} /></i></div><div><span>全量内容校验</span><b>{Math.max(tasks.length - 2, 0)} / {tasks.length}</b><i><em style={{ width: `${tasks.length ? Math.max(tasks.length - 2, 0) / tasks.length * 100 : 0}%` }} /></i></div><div><span>数据修改校验</span><b>{Math.max(tasks.length - 1, 0)} / {tasks.length}</b><i><em style={{ width: `${tasks.length ? Math.max(tasks.length - 1, 0) / tasks.length * 100 : 0}%` }} /></i></div><div><span>数据删除校验</span><b>1 / {tasks.length}</b><i><em style={{ width: `${tasks.length ? 100 / tasks.length : 0}%` }} /></i></div></div></article>
        <article className="data-card validation-trend-card"><div className="panel-title"><div><h2>近 7 天差异趋势</h2><span>单位：任务</span></div><b>本周 50</b></div><div className="validation-trend">{validationTrend.map((value, index) => <div key={index}><span>{value}</span><i style={{ height: `${value * 3}%` }} /><small>{["06", "07", "08", "09", "10", "11", "12"][index]}日</small></div>)}</div></article>
      </section>
      <section className="data-card overview-list"><div className="overview-list-heading"><div><h2>近 7 天差异任务</h2><span>优先处理高影响差异</span></div><label className="search-box"><SearchOutlined /><input value={validationOverviewQuery} onChange={(event) => { setValidationOverviewQuery(event.target.value); setValidationOverviewPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索任务名称" /></label><b>{filteredDifferenceRows.length} 条</b></div><div className="table-scroll"><table className="resource-table overview-table"><thead><tr><th>ID</th><th>任务名称</th><th>视图 / 表</th><th>差异行</th><th>风险</th><th className="action-col">操作</th></tr></thead><tbody>{pagedDifferenceRows.rows.map((row) => { const differences = row.differences; return <tr key={row.id}><td>{row.id}</td><td><div className="resource-name"><strong>{row.task}</strong><span>{row.name}</span></div></td><td className="mono-cell">{tasks.find((task) => task.id === row.taskId)?.view ?? "—"}</td><td><div className="difference-cell"><strong>{differences}</strong><span><i style={{ width: `${Math.min(100, 18 + differences / 2)}%` }} /></span></div></td><td><b className={`risk-tag ${differences > 100 ? "high" : "medium"}`}>{differences > 100 ? "高" : "中"}</b></td><td><button className="text-button" onClick={() => { navigate("validationWorkbench"); setValidationDrawer(row); }}>进入工作台 <RightOutlined /></button></td></tr>; })}</tbody></table>{filteredDifferenceRows.length === 0 && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的差异任务</span></div>}</div><Pagination total={filteredDifferenceRows.length} state={validationOverviewPaging} onChange={setValidationOverviewPaging} noun="个差异任务" /></section>
    </>;
  }

  function validationWorkbenchPage() {
    const normalizedQuery = validationQuery.trim().toLowerCase();
    const filteredRows = validationRows.filter((row) => (validationResultFilter === "全部状态" || row.result === validationResultFilter) && (validationMethodFilter === "全部校验" || row.method === validationMethodFilter) && (!normalizedQuery || `${row.name}${row.task}${row.id}`.toLowerCase().includes(normalizedQuery)));
    const paged = paginate(filteredRows, validationPaging);
    return <>
      <section className="data-card management-card ops-card"><div className="toolbar management-toolbar"><select value={validationResultFilter} onChange={(event) => { setValidationResultFilter(event.target.value); setValidationPaging((state) => ({ ...state, page: 1 })); }}><option>全部状态</option><option>数据一致</option><option>发现差异</option></select><select value={validationMethodFilter} onChange={(event) => { setValidationMethodFilter(event.target.value); setValidationPaging((state) => ({ ...state, page: 1 })); }}><option>全部校验</option><option>行数比对</option><option>CHECKSUM</option></select><label className="search-box wide-search"><span>⌕</span><input value={validationQuery} onChange={(event) => { setValidationQuery(event.target.value); setValidationPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索校验名或数据同步名称" /></label><div className="toolbar-spacer" /><button className="secondary-button" onClick={() => notifySettings("校验任务已与数据同步任务对齐")}><SyncOutlined /> 同步校验任务</button></div>
        <div className="table-scroll"><table className="resource-table validation-table"><thead><tr><th>校验名称</th><th>关联任务</th><th>校验方式</th><th>结果</th><th>差异行</th><th>耗时</th><th className="action-col">操作</th></tr></thead><tbody>{paged.rows.map((row) => <tr key={row.id}><td><div className="resource-name"><strong>{row.name}</strong><span>{row.id}</span></div></td><td>{row.task}</td><td><b className="neutral-tag">{row.method}</b></td><td><b className={`validation-result ${row.result === "数据一致" ? "pass" : "diff"}`}>{row.result === "数据一致" ? "✓" : "!"} {row.result}</b></td><td>{row.differences}</td><td>{row.duration}</td><td className="action-col"><button className="secondary-button row-action-button" onClick={() => { setValidationDrawer(row); setValidationMode("full"); setValidationView("result"); }}><FileTextOutlined /> 校验详情</button><button className="start-button"><PlayCircleOutlined /> 运行</button></td></tr>)}</tbody></table>{!paged.rows.length && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的校验任务。</span></div>}</div>
        <Pagination total={filteredRows.length} state={validationPaging} onChange={setValidationPaging} />
      </section>
    </>;
  }

  function precheckPage() {
    const statusOf = (dataset: Dataset, link: DataLink, index: number) => link.state === "异常" || dataset.exceptions > 0 ? "未通过" : index === 6 ? "待预检" : index === 9 ? "执行中" : "已通过";
    const allRows = datasets.flatMap((dataset) => linksFor(dataset).map((link) => ({ dataset, link }))).map((row, index) => ({ ...row, status: statusOf(row.dataset, row.link, index) }));
    const normalizedQuery = precheckQuery.trim().toLowerCase();
    const rows = allRows.filter((row) => (precheckFilter === "全部" || row.status === precheckFilter)
      && (precheckInstitution === "全部机构" || row.link.institutions.includes(precheckInstitution) || (precheckInstitution === "基层医疗共享" && row.link.institutions.length > 1))
      && (precheckSource === "全部数据源" || row.link.source === precheckSource)
      && (precheckDataset === "全部数据集" || row.dataset.name === precheckDataset)
      && (!normalizedQuery || `${row.link.name}${row.link.sourceType}${row.dataset.name}${row.dataset.code}`.toLowerCase().includes(normalizedQuery)));
    const paged = paginate(rows, precheckPaging);
    const rowKeys = paged.rows.map(({ dataset, link }) => `${dataset.code}-${link.id}`);
    const allRowsSelected = rowKeys.length > 0 && rowKeys.every((key) => precheckSelected.includes(key));
    return <section className="data-card management-card precheck-card">
      <div className="precheck-status-tabs">{["全部", "待预检", "执行中", "未通过", "已通过"].map((label) => <button className={precheckFilter === label ? "active" : ""} onClick={() => { setPrecheckFilter(label); setPrecheckSelected([]); setPrecheckPaging((state) => ({ ...state, page: 1 })); }} key={label}>{label} <b>{label === "全部" ? allRows.length : allRows.filter((row) => row.status === label).length}</b></button>)}<button className="secondary-button refresh-precheck" onClick={() => notifySettings("预检状态已刷新")}><ReloadOutlined /> 刷新</button></div>
      <div className="toolbar management-toolbar"><label className="search-box wide-search"><SearchOutlined /><input value={precheckQuery} onChange={(event) => { setPrecheckQuery(event.target.value); setPrecheckPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索链路名称、源对象或数据集" /></label><select aria-label="机构" value={precheckInstitution} onChange={(event) => { setPrecheckInstitution(event.target.value); setPrecheckPaging((state) => ({ ...state, page: 1 })); }}><option>全部机构</option><option>县人民医院</option><option>县中医院</option><option>基层医疗共享</option></select><select aria-label="数据源" value={precheckSource} onChange={(event) => { setPrecheckSource(event.target.value); setPrecheckPaging((state) => ({ ...state, page: 1 })); }}><option>全部数据源</option><option>postgresql-rmyy</option><option>oracle-zyy</option><option>mysql-jcyl</option></select><select aria-label="数据集" value={precheckDataset} onChange={(event) => { setPrecheckDataset(event.target.value); setPrecheckPaging((state) => ({ ...state, page: 1 })); }}><option>全部数据集</option>{datasets.map((item) => <option key={item.code}>{item.name}</option>)}</select><div className="toolbar-spacer" /><button className="primary-button" disabled={!precheckSelected.length} onClick={() => { notifySettings(`已提交 ${precheckSelected.length} 条采集链路预检`); setPrecheckSelected([]); }}><PlayCircleOutlined /> 批量预检{precheckSelected.length ? ` (${precheckSelected.length})` : ""}</button></div>
      <div className="table-scroll precheck-table-scroll"><table className="resource-table precheck-table"><thead><tr><th className="check-col"><input type="checkbox" aria-label="选择当前页全部链路" checked={allRowsSelected} onChange={() => setPrecheckSelected((current) => allRowsSelected ? current.filter((key) => !rowKeys.includes(key)) : Array.from(new Set([...current, ...rowKeys])))} /></th><th>采集链路</th><th>机构</th><th>源数据源 / 对象</th><th>标准数据集</th><th>最近执行</th><th>结果</th><th className="action-col">操作</th></tr></thead><tbody>{paged.rows.map(({ dataset, link, status }) => { const issue = status === "未通过"; const rowKey = `${dataset.code}-${link.id}`; return <tr key={rowKey} className={precheckSelected.includes(rowKey) ? "selected-row" : ""}><td className="check-col"><input type="checkbox" aria-label={`选择${link.name}`} checked={precheckSelected.includes(rowKey)} onChange={() => setPrecheckSelected((current) => current.includes(rowKey) ? current.filter((key) => key !== rowKey) : [...current, rowKey])} /></td><td><div className="resource-name"><strong>{link.name}</strong><span>{link.vendor}</span></div></td><td><div className="system-cell"><strong>{link.institutions.length > 1 ? "基层医疗共享" : link.institutions[0]}</strong><span>{link.institutions.length} 家机构</span></div></td><td><div className="system-cell"><strong>{link.source}</strong><span>{link.sourceType}</span></div></td><td><div className="resource-name"><strong>{dataset.name}</strong><span>{dataset.code}</span></div></td><td><div className="system-cell"><strong>{status === "待预检" ? "—" : status === "执行中" ? "正在执行" : link.lastRun}</strong><span>{status === "待预检" ? "尚未执行" : status === "执行中" ? "已扫描 82,560 行" : "扫描 135,800 行"}</span></div></td><td>{status === "执行中" ? <b className="precheck-running"><SyncOutlined spin /> 执行中</b> : issue ? <><b className="precheck-issue">未通过</b><span className="issue-count">6,392 行</span></> : status === "待预检" ? <b className="precheck-pending">待预检</b> : <b className="precheck-pass">已通过</b>}</td><td className="action-col"><button className="secondary-button row-action-button" onClick={() => openPrecheckTask(dataset, link, status)}><FileTextOutlined /> 任务详情</button><button className="start-button" disabled={status === "执行中"} onClick={() => openPrecheckTask(dataset, link, status, "overview", true)}><PlayCircleOutlined /> {status === "执行中" ? "运行中" : "运行"}</button></td></tr>; })}</tbody></table>{!paged.rows.length && <div className="table-empty"><FileSearchOutlined /><span>没有符合条件的数据预检任务。</span></div>}</div>
      <Pagination total={rows.length} state={precheckPaging} onChange={setPrecheckPaging} noun="条采集链路" />
    </section>;
  }

  function validationDrawerContent() {
    const row = validationDrawer!;
    const consistent = row.result === "数据一致";
    const history = Array.from({ length: validationMode === "modify" ? 7 : 3 }, (_, index) => ({ time: validationMode === "modify" ? `2026-08-10 ${String(20 - index * 4).padStart(2, "0")}:21:04` : `2026-08-08 02:29:${26 - index * 3}`, trigger: index % 2 ? "门控校验" : "同步后自动" }));
    return <><button className="drawer-backdrop" aria-label="关闭校验工作台" onClick={() => setValidationDrawer(null)} /><aside className="workspace-drawer" aria-label="校验工作台详情"><header><button onClick={() => setValidationDrawer(null)} aria-label="关闭">×</button><h2>校验工作台 — {row.task}</h2><button className="text-button" onClick={() => { const task = tasks.find((candidate) => candidate.id === row.taskId); if (task) setSelectedTask(task); setValidationDrawer(null); setTaskDetailTab("validation"); navigate("taskDetail", task?.id ?? row.taskId); }}>查看配置 →</button></header><div className="workspace-body">
      <div className="workbench-summary-line"><strong>{row.task}</strong><b className={consistent ? "pass" : "diff"}>{consistent ? "未发现差异" : `${row.differences} 行差异`}</b><span>最近：1 天前</span></div>
      <div className="mode-tabs"><button className={validationMode === "full" ? "active" : ""} onClick={() => setValidationMode("full")}>数据全量校验 · {consistent ? "一致" : "有差异"}</button><button className={validationMode === "modify" ? "active" : ""} onClick={() => setValidationMode("modify")}>数据修改校验 · 一致</button><button className={validationMode === "delete" ? "active" : ""} onClick={() => setValidationMode("delete")}>数据删除校验 · 未配置</button></div>
      <div className="workbench-tabs"><button className={validationView === "result" ? "active" : ""} onClick={() => setValidationView("result")}>结果与诊断</button><button className={validationView === "history" ? "active" : ""} onClick={() => setValidationView("history")}>执行历史</button></div>
      {validationView === "history" ? <div className="history-view"><button className="secondary-button">↻ 刷新</button><div className="history-table"><div className="history-head"><b>执行时间</b><b>校验方式</b><b>触发类型</b><b>结果</b><b>差异行</b><b>总分片</b><b>一致分片</b><b>操作</b></div>{history.map((item, index) => <div key={item.time}><span>{item.time}</span><span>{validationMode === "modify" ? "窗口校验" : "全量校验"}</span><b className={item.trigger === "门控校验" ? "gate" : "auto"}>{item.trigger}</b><b className="validation-result pass">一致</b><span>0</span><span>0</span><span>0</span><span><button className="text-button" onClick={() => setValidationView("result")}>查看详情</button>{index < 2 && <button className="text-button">与上次对比</button>}</span></div>)}</div></div> : validationMode === "delete" ? <div className="unconfigured-state"><i>◇</i><h3>删除校验未配置</h3><p>为当前任务启用删除数据检测。</p><button className="primary-button" onClick={() => navigate("taskDetail", row.taskId)}>前往配置</button></div> : <div className="result-view">
        <section className="result-panel"><h3>结果摘要</h3><div className="result-summary-grid"><div><span>当前状态</span><b className={consistent ? "green-text" : "red-text"}>{consistent ? "正常" : "有差异"}</b></div><div><span>最近执行</span><strong>{validationMode === "modify" ? "2026-08-10 20:21:14" : "2026-08-08 02:29:31"}</strong></div><div><span>差异</span><strong>{row.differences} 行</strong></div><aside><i>✓</i><div><b>建议</b><span>{consistent ? "无需处理，按计划继续校验。" : "检查源端更新与目标写入日志。"}</span></div></aside></div></section>
        <section className="result-panel"><div className="result-panel-title"><h3>校验详情</h3><button className="secondary-button">加载分片详情</button></div><div className="validation-detail-grid"><span>执行时间</span><strong>{validationMode === "modify" ? "2026-08-10 20:21:04" : "2026-08-08 02:29:26"}</strong><span>校验方式</span><strong>行数对比</strong><span>校验范围</span><strong>{validationMode === "modify" ? "增量窗口（含回看）" : "全表"}</strong></div>{validationMode === "modify" && <p className="window-line">校验窗口　<code>2026-08-10 16:19:24 ~ 2026-08-10 20:20:17</code></p>}<div className="partition-result"><b>分片对比结果</b><span>总分片：0 · 匹配：0 · 差异行：{row.differences}</span></div></section>
        <section className="result-panel action-panel"><div className="result-panel-title"><h3>{validationMode === "modify" ? "执行修改校验" : "重新全量校验"}</h3>{validationMode === "modify" && <button className="text-button">高级选项</button>}</div>{validationMode === "modify" ? <div className="date-action"><span>时间窗口</span><input type="date" /><b>→</b><input type="date" /><button className="primary-button">执行窗口校验</button></div> : <button className="secondary-button">↻ 重新全量校验</button>}</section>
        <section className="result-panel"><h3>差异明细 <b className="green-text">{row.differences ? `${row.differences} 行` : "无差异"}</b></h3><div className="difference-head"><b>#</b><b>PK</b><b>差异类型</b><b>来源</b><b>修复状态</b><b>操作</b></div></section>
      </div>}
    </div></aside></>;
  }

  function precheckDetailPage() {
    const { dataset: item, link, status } = precheckDrawer!;
    const issue = link.state === "异常" || item.exceptions > 0 || item.code === "ODS_YL_BINGANSYSS";
    const pending = status === "待预检" && !precheckRunning;
    const running = precheckRunning;
    const hasRun = !pending;
    const result = running ? "执行中" : pending ? "待预检" : issue ? "未通过" : "已通过";
    const samples = [
      ["JC202608120001", "BR00031892", "CT 胸部平扫", "2026-08-12 08:16:32", "已申请"],
      ["JC202608120002", "BR00052671", "腹部彩超", "2026-08-12 08:21:04", "已登记"],
      ["JC202608120003", "BR00077410", "颅脑 MRI", "2026-08-12 08:29:18", "已检查"],
      ["JC202608120004", "BR00081426", "心电图", "2026-08-12 08:32:55", "已报告"],
      ["JC202608120005", "BR00092107", "DR 胸片", "2026-08-12 08:40:11", "已申请"],
    ];
    const issueSummary = [
      ["CHUSHENGRQ", "REQUIRED_FIELD_NULL", "阻断", "2,815", "影响 2,815 行"],
      ["YILIAOJGDM", "REQUIRED_FIELD_NULL", "阻断", "2,143", "影响 2,143 行"],
      ["—", "PRIMARY_KEY_DUPLICATE", "阻断", "1,434", "影响 1,434 行"],
    ];
    const issueRows = Array.from({ length: 14 }, (_, index) => {
      const duplicate = index % 4 === 3;
      const field = ["CHUSHENGRQ", "YILIAOJGDM", "ZHENGJIANHM", "—"][index % 4];
      return [
        `H43112600216|${String(113755 + index).padStart(6, "0")}`,
        field,
        duplicate ? "PRIMARY_KEY_DUPLICATE" : "REQUIRED_FIELD_NULL",
        "BLOCKER",
        duplicate ? `H43112600216|${113755 + index}` : "—",
        duplicate ? "PRIMARY KEY" : field === "CHUSHENGRQ" ? "DT15" : "AN..30",
        duplicate ? "主键组合重复，同一组合存在多条源记录" : "标准非空字段不能为空或空白",
      ];
    });
    const tabs: Array<[PrecheckDetailTab, string]> = [["overview", "基本信息"], ["sample", "数据预览"], ["runs", "运行记录"], ["issues", `问题明细${issue ? " · 6,392" : ""}`]];
    const metrics = [
      ["最近执行", pending ? "尚未执行" : "2026-08-12 12:18:00", "blue"],
      ["最近结果", result, issue ? "red" : running ? "amber" : "green"],
      ["扫描行数", hasRun ? "135,800" : "—", "amber"],
      ["问题行数", hasRun ? issue ? "6,392" : "0" : "—", "purple"],
      ["运行耗时", hasRun ? "1m 44s" : "—", "rose"],
    ];
    return <section className="task-detail-card precheck-detail-card">
      <button className="back-link" onClick={() => { navigate("precheck"); setPrecheckDrawer(null); setPrecheckRunning(false); }}>← 返回数据预检</button>
      <div className="detail-title-row"><div><span className="task-id-label">PRECHECK-{link.id.replace(/\D/g, "").slice(-4) || "341"}</span><h1>{link.name}</h1><div className="detail-badges"><b>{item.code}</b><span className={`precheck-detail-status status-${result}`}>{result}</span></div></div><div className="detail-actions"><button className="primary-button" disabled={running} onClick={() => { setPrecheckRunning(true); notifySettings("预检任务已开始运行"); }}><PlayCircleOutlined /> {running ? "运行中" : pending ? "开始运行" : "重新运行"}</button>{running && <button className="secondary-button" onClick={() => setPrecheckRunning(false)}><CloseCircleOutlined /> 停止</button>}<button className="secondary-button" onClick={() => notifySettings("预检任务状态已刷新")}><ReloadOutlined /> 刷新</button></div></div>
      <div className="detail-metrics">{metrics.map(([label, value, tone]) => <article className={`metric-${tone}`} key={label}><span>{label}</span><strong>{value}</strong></article>)}</div>
      <div className="detail-tabs precheck-detail-tabs">{tabs.map(([key, label]) => <button className={precheckDetailTab === key ? "active" : ""} onClick={() => setPrecheckDetailTab(key)} key={key}>{label}</button>)}</div>

      {precheckDetailTab === "overview" && <>
        <div className="ownership-grid precheck-ownership"><article><span>标准数据集</span><strong>{item.name}</strong><small>{item.code} · {item.fields} 个字段</small><button onClick={() => openPanel(item, "fields")}>管理数据集</button></article><article><span>采集链路</span><strong>{link.name}</strong><small>{link.source} · {link.institutions.length} 家机构</small><button onClick={() => openLink(item, link, "collection")}>配置链路</button></article><article><span>源对象</span><strong>{link.sourceType}</strong><small>仅读取，不写入目标端</small><button onClick={() => setPrecheckDetailTab("sample")}>数据预览</button></article></div>
        <div className="config-grid compact-config precheck-config"><div><span>机构范围</span><strong>{link.institutions.length > 1 ? `基层医疗共享（${link.institutions.length} 家）` : link.institutions[0]}</strong></div><div><span>源数据源</span><strong>{link.source}</strong></div><div><span>源对象</span><strong>{link.sourceType}</strong></div><div><span>标准数据集</span><strong>{item.name}</strong></div><div><span>校验字段</span><strong>{item.fields}</strong></div><div><span>预检范围</span><strong>源端全量数据</strong></div></div>
        <section className="detail-panel precheck-progress-panel"><div className="detail-panel-head"><div><h2>本次运行</h2><div className="inline-meta"><b>{result}</b><span>{running ? "正在扫描源端数据" : pending ? "等待首次运行" : "最近运行已完成"}</span></div></div></div><div className="precheck-progress-body"><div className="progress-line"><span style={{ width: running ? "64%" : pending ? "0%" : "100%" }} /><b>{running ? "64%" : pending ? "0%" : "100%"}</b></div><div className="precheck-run-summary"><div><span>运行 ID</span><strong>{hasRun ? "#341" : "—"}</strong></div><div><span>阶段</span><strong>{running ? "RUNNING" : pending ? "NOT_STARTED" : "COMPLETED"}</strong></div><div><span>开始</span><strong>{hasRun ? "2026-08-08 12:18:00" : "—"}</strong></div><div><span>结束</span><strong>{running || pending ? "—" : "2026-08-08 12:19:44"}</strong></div><div><span>源端行数</span><strong>{hasRun ? "135800" : "—"}</strong></div><div><span>校验字段</span><strong>{item.fields}</strong></div><div><span>校验行数</span><strong>{hasRun ? "135800" : "—"}</strong></div><div><span>问题数</span><strong>{hasRun ? issue ? "6392" : "0" : "—"}</strong></div></div></div></section>
      </>}

      {precheckDetailTab === "sample" && <div className="sample-view precheck-detail-sample"><div className="sample-toolbar"><span>源端样例数据</span><small>前 20 条 · 仅读取不写入</small><button className="secondary-button" onClick={() => notifySettings("样例数据已重新提取")}><ReloadOutlined /> 重新提取</button></div><div className="table-scroll"><table className="resource-table sample-table"><thead><tr><th>JIANCHASQID</th><th>BINGRENID</th><th>JIANCHAXM</th><th>SHENQINGSJ</th><th>ZHUANGTAI</th></tr></thead><tbody>{samples.map((row) => <tr key={row[0]}>{row.map((cell) => <td key={cell}>{cell}</td>)}</tr>)}</tbody></table></div><div className="sample-footer"><span>已读取 20 行</span><span>字段 {item.fields} 个</span><span>耗时 286 ms</span></div></div>}

      {precheckDetailTab === "runs" && <div className="detail-panel precheck-runs-panel"><div className="detail-panel-head"><div><h2>运行记录</h2><div className="inline-meta"><span>保留最近 90 天</span><b>{hasRun ? "4 次运行" : "暂无记录"}</b></div></div><button className="secondary-button" onClick={() => notifySettings("运行记录已刷新")}><ReloadOutlined /> 刷新</button></div>{hasRun ? <div className="simple-table precheck-detail-run-table"><div className="simple-head"><b>运行</b><b>结果</b><b>阶段</b><b>进度</b><b>扫描行</b><b>问题行</b><b>开始时间</b><b>操作</b></div>{[341, 242, 143, 44].map((run, index) => <div key={run}><strong>#{run}</strong><b className={issue && index < 3 ? "issue" : "passed"}>{issue && index < 3 ? "未通过" : "已通过"}</b><span>COMPLETED</span><span>100%</span><span>135800</span><span>{issue && index < 3 ? "6392" : "0"}</span><span>2026-08-0{8 - Math.min(index, 1)} 12:18:00</span><button className="text-button" onClick={() => setPrecheckDetailTab(issue && index < 3 ? "issues" : "overview")}>查看详情</button></div>)}</div> : <div className="precheck-empty-run"><FileSearchOutlined /><span>暂无运行记录</span></div>}</div>}

      {precheckDetailTab === "issues" && (issue ? <div className="precheck-issues-tab"><section className="precheck-section"><div className="detail-panel-head"><div><h2>问题汇总</h2><div className="inline-meta"><b>6,392 条问题</b><span>3 类规则</span></div></div></div><div className="issue-summary-table">{issueSummary.map((row) => <div className="issue-summary-row" key={`${row[0]}-${row[1]}`}>{row.map((cell, index) => index === 2 ? <b key={cell}>{cell}</b> : index >= 3 ? <strong key={cell}>{cell}</strong> : <span key={cell}>{cell}</span>)}</div>)}</div></section><section className="precheck-section issue-detail-section"><div className="result-panel-title"><h3>问题明细</h3><button className="secondary-button"><ReloadOutlined /> 刷新</button></div><div className="issue-filters"><input placeholder="业务主键" /><input placeholder="字段编码" /><select><option>全部问题类型</option><option>必填字段为空</option><option>主键重复</option></select><select><option>全部级别</option><option>阻断</option></select><button className="primary-button"><SearchOutlined /> 查询</button><button className="secondary-button">重置</button></div><div className="issue-table-scroll precheck-detail-issue-scroll"><div className="precheck-issue-table issue-table-head"><b>业务主键</b><b>字段</b><b>问题类型</b><b>级别</b><b>原始值</b><b>标准规则</b><b>错误说明</b></div>{issueRows.map((row, index) => <div className="precheck-issue-table" key={`${row[0]}-${index}`}>{row.map((cell, cellIndex) => cellIndex === 3 ? <b className="blocker-tag" key={`${cell}-${cellIndex}`}>{cell}</b> : <span key={`${cell}-${cellIndex}`}>{cell}</span>)}</div>)}</div><div className="issue-table-footer"><span>共 6,392 条问题</span><div className="drawer-pagination"><button disabled>‹</button><button className="active">1</button><button>2</button><button>3</button><button>4</button><button>5</button><i>…</i><button>128</button><button>›</button></div><button className="secondary-button">XLSX</button><button className="secondary-button">CSV</button><button className="secondary-button">导出当前筛选</button></div></section></div> : <div className="detail-empty"><div><CheckCircleOutlined /></div><h3>未发现数据问题</h3><p>最近一次预检已通过。</p></div>)}
    </section>;
  }

  function taskDetailPage() {
    const task = selectedTask;
    const metrics = [
      ["最近运行", "2026-08-12 08:01:51", "blue"],
      ["最近状态", task.state === "失败" ? "失败" : "成功", task.state === "失败" ? "red" : "green"],
      ["累计运行", "25 次", "amber"],
      ["成功率", task.successRate, "purple"],
      ["人工核对", "未处理 0 / 已处理 0", "rose"],
    ];
    const detailTabs: Array<[TaskDetailTab, string]> = [["basic", "基本配置"], ["extract", "抽取配置"], ["mapping", "映射配置"], ["runs", "运行批次"], ["validation", "校验策略"]];
    if (messageEnabled[task.dataset.code]) detailTabs.push(["messageConfig", "消息发布配置"], ["messageLogs", "发布记录"]);
    const mappingRows = [
      ["YILIAOJGDM", "varchar", "yiliaojgdm", "varchar(90)"],
      ["YILIAOJGMC", "varchar", "yiliaojgmc", "varchar(300)"],
      ["JIANCHASQID", "varchar", "jianchasqid", "varchar(90)"],
      ["BINGRENID", "varchar", "bingrenid", "varchar(192)"],
      ["XINGMING", "varchar", "xingming", "varchar(150)"],
      ["XINGBIEDM", "varchar", "xingbiedm", "varchar(60)"],
      ["SHENQINGSJ", "timestamp", "shenqingsj", "datetime"],
    ];
    const batchRows = [
      ["20260812_080147_1418", "2026-08-12 08:01:47", "0m 3s", "0", "0", "0"],
      ["20260812_040138_1418", "2026-08-12 04:01:38", "0m 3s", "0", "0", "0"],
      ["20260812_000137_1418", "2026-08-12 00:01:37", "0m 3s", "0", "0", "0"],
      ["20260811_200139_1418", "2026-08-11 20:01:39", "0m 3s", "0", "0", "0"],
      ["20260811_160139_1418", "2026-08-11 16:01:39", "0m 3s", "0", "0", "0"],
      ["20260811_120138_1418", "2026-08-11 12:01:38", "0m 3s", "0", "0", "0"],
    ];
    return <section className="task-detail-card">
      <button className="back-link" onClick={() => navigate("tasks")}>← 返回数据同步</button>
      <div className="detail-title-row"><div><span className="task-id-label">{task.id}</span><h1>{task.name}</h1><div className="detail-badges"><b>V1 · 已发布</b><span>首次全量已完成</span></div></div><div className="detail-actions"><button className="secondary-button" onClick={() => openLink(task.dataset, task.link, "collection")}><EditOutlined /> 配置链路</button><button className="primary-button"><PlayCircleOutlined /> 运行</button><button className="secondary-button"><ClockCircleOutlined /> 停用调度</button><button className="secondary-button"><ReloadOutlined /> 重置水位</button><button className="danger-button"><DeleteOutlined /> 删除</button></div></div>
      <div className="detail-metrics">{metrics.map(([label, value, tone]) => <article className={`metric-${tone}`} key={label}><span>{label}</span><strong>{value}</strong></article>)}</div>
      <div className="detail-tabs">{detailTabs.map(([key, label]) => <button className={taskDetailTab === key ? "active" : ""} onClick={() => setTaskDetailTab(key)} key={key}>{label}</button>)}</div>
      {taskDetailTab === "basic" && <>
        <div className="ownership-grid"><article><span>标准数据集</span><strong>{task.dataset.name}</strong><small>{task.dataset.fields} 字段 · {task.dataset.rules} 条校验规则</small><button onClick={() => openPanel(task.dataset, "fields")}>管理数据集</button></article><article><span>采集链路</span><strong>{task.link.name}</strong><small>{task.link.source} · {task.link.institutions.length} 家机构</small><button onClick={() => openLink(task.dataset, task.link, "collection")}>配置链路</button></article><article><span>任务实例</span><strong>{task.id}</strong><small>SeaTunnel Cluster · V1</small><button onClick={() => setTaskDetailTab("runs")}>运行批次</button></article></div>
        <div className="config-grid compact-config"><div><span>所属机构</span><strong>{task.link.institutions.length > 1 ? `基层医疗共享（${task.link.institutions.length} 家）` : task.link.institutions[0]}</strong></div><div><span>源数据源</span><strong>{task.link.source}</strong></div><div><span>目标数据源</span><strong>ygt-doris</strong></div><div><span>同步方式</span><strong>Upsert · 增量</strong></div><div><span>源视图</span><strong>{task.view}</strong></div><div><span>调度</span><strong>{task.link.schedule}</strong></div><div><span>增量字段</span><strong>XIUGAISJ</strong></div><div><span>当前水位</span><strong>2026-08-10 20:17:41</strong></div><div><span>并发数</span><strong>1</strong></div></div>
      </>}
      {taskDetailTab === "extract" && <div className="detail-panel"><div className="detail-panel-head"><div><h2>抽取配置</h2><span className="scope-tag link-scope-tag">链路级</span></div><button className="secondary-button" onClick={() => openLink(task.dataset, task.link, "sync")}>编辑配置</button></div><div className="extract-grid"><div><span>抽取模式</span><strong>表模式</strong></div><div><span>源端 Schema</span><strong>{task.link.sourceType.split(".")[0]}</strong></div><div><span>视图列表</span><strong className="blue-text">{task.view}</strong></div><div><span>分片策略</span><strong>主键范围分片</strong></div><div><span>分片数量</span><strong>5</strong></div><div><span>增量字段</span><strong>XIUGAISJ</strong></div><div><span>Fetch Size</span><strong>继承全局</strong></div><div><span>并发数</span><strong>1</strong></div></div></div>}
      {taskDetailTab === "mapping" && <div className="detail-panel"><div className="detail-panel-head"><div><h2>字段映射</h2><div className="inline-meta"><span>{task.view}</span><b>{task.dataset.fields} / {task.dataset.fields} 已映射</b><i>目标字段来自标准数据集</i></div></div><button className="secondary-button" onClick={() => openLink(task.dataset, task.link, "collection")}>编辑映射</button></div><div className="mapping-table"><div className="mapping-head"><b>源字段</b><b>源类型</b><span /><b>目标字段（Doris）</b><b>目标类型</b><b>状态</b></div>{mappingRows.map((row) => <div key={row[0]}><strong>{row[0]}</strong><span>{row[1]}</span><i>→</i><strong>{row[2]}</strong><span>{row[3]}</span><b>匹配</b></div>)}</div></div>}
      {taskDetailTab === "runs" && <div className="detail-panel runs-panel"><div className="detail-panel-head"><div><h2>运行批次</h2><div className="inline-meta"><span>共 25 次</span><b>{task.successRate} 成功率</b></div></div><button className="secondary-button">导出记录</button></div><div className="batch-table"><div className="batch-head"><b>批次号</b><b>状态</b><b>类型</b><b>开始时间</b><b>耗时</b><b>读取</b><b>写入</b><b>失败</b><b>操作</b></div>{batchRows.map((row, index) => { const failed = task.state === "失败" && index < 4; return <div key={row[0]}><strong>{row[0]}</strong><b className={failed ? "batch-failed" : "batch-success"}>{failed ? "失败" : "成功"}</b><span className="increment-tag">增量</span><span>{row[1]}</span><span>{row[2]}</span><span>{failed ? row[3] : "12,486"}</span><span>{failed ? row[4] : "12,486"}</span><span>{row[5]}</span><span><button>查看配置</button><button>查看日志</button></span></div>; })}</div></div>}
      {taskDetailTab === "validation" && <div className="detail-panel"><div className="detail-panel-head"><div><h2>校验策略</h2><div className="inline-meta"><span>规则来源</span><b>{task.dataset.name}</b><i>{task.dataset.rules} 条规则</i></div></div><button className="secondary-button" onClick={() => openPanel(task.dataset, "validation")}>管理数据集规则</button></div><div className="override-line"><div><strong>任务级覆盖</strong><span>{taskValidationOverride ? "已覆盖数据集默认策略" : "继承数据集默认策略"}</span></div><Toggle checked={taskValidationOverride} label="任务级校验覆盖" onChange={() => setTaskValidationOverride((value) => !value)} /></div><div className={`effective-policy ${taskValidationOverride ? "is-editable" : ""}`}><div><span>校验方式</span><strong>行数校验（ROW_COUNT）</strong></div><div><span>自动触发</span><strong>开启</strong></div><div><span>失败阻断</span><strong>开启</strong></div><div><span>校验窗口</span><strong>最近 24 小时</strong></div><div><span>生效范围</span><strong>{taskValidationOverride ? "当前任务" : "全部采集链路"}</strong></div><div><span>最近结果</span><strong className={task.state === "失败" ? "red-text" : "green-text"}>{task.state === "失败" ? "未执行" : "通过"}</strong></div></div></div>}
      {taskDetailTab === "messageConfig" && <div className="detail-panel message-config-panel"><div className="detail-panel-head"><div><h2>消息发布配置</h2><div className="inline-meta"><b>已启用</b><i>任务级配置</i></div></div><button className="secondary-button" onClick={() => openPanel(task.dataset, "message")}>管理数据集消息</button></div><div className="message-config-grid"><label><span>来源系统</span><input defaultValue="DFETL" /></label><label><span>租户 ID</span><input defaultValue="YGT330106H001" /></label><label><span>Topic</span><input defaultValue="medical-dataset-change" /></label><label><span>Routing Key</span><input defaultValue={task.dataset.code.toLowerCase()} /></label><label><span>全量发布</span><select defaultValue="仅发布完成事件"><option>仅发布完成事件</option><option>逐条发布</option><option>不发布</option></select></label><label><span>失败重试</span><select defaultValue="3 次"><option>3 次</option><option>5 次</option><option>不重试</option></select></label></div><div className="message-preview"><MessageOutlined /><div><strong>消息预览</strong><code>{`{ "dataset": "${task.dataset.code}", "institution": "${task.link.institutions[0]}", "event": "SYNC_COMPLETED" }`}</code></div><button className="secondary-button">发送测试消息</button></div></div>}
      {taskDetailTab === "messageLogs" && <div className="detail-panel"><div className="detail-panel-head"><div><h2>发布记录</h2><div className="inline-meta"><span>最近 7 天</span><b>成功率 99.8%</b></div></div><button className="secondary-button"><ReloadOutlined /> 刷新</button></div><div className="message-log-table"><div className="message-log-head"><b>发布时间</b><b>批次号</b><b>事件类型</b><b>消息数</b><b>状态</b><b>耗时</b><b>操作</b></div>{[["2026-08-12 12:18:44", "20260812_121802_1419", "SYNC_COMPLETED", "1", "成功", "128ms"], ["2026-08-12 08:01:55", "20260812_080147_1418", "SYNC_FAILED", "1", "成功", "94ms"], ["2026-08-12 04:02:28", "20260812_040138_1418", "DATA_CHANGED", "12,486", "成功", "8.4s"], ["2026-08-11 20:02:11", "20260811_200139_1418", "DATA_CHANGED", "9,832", "失败", "30s"]].map((row) => <div key={row[1]}>{row.map((cell, index) => <span key={index} className={index === 4 ? (cell === "成功" ? "log-success" : "log-failed") : ""}>{cell}</span>)}<button className="text-button">查看详情</button></div>)}</div></div>}
    </section>;
  }

  const passwordReady = Boolean(passwordForm.current) && passwordForm.next.length >= 8 && passwordForm.next === passwordForm.confirm;

  if (!sessionActive) {
    return <main className="signed-out-page">
      <section className="signed-out-card" aria-labelledby="signed-out-title">
        <div className="signed-out-brand"><div className="brand-mark"><i /><i /><i /><i /><b /></div><div><strong>东防数据采集系统</strong><span>DATA INTEGRATION</span></div></div>
        <div className="signed-out-icon"><LogoutOutlined /></div>
        <h1 id="signed-out-title">已安全退出</h1>
        <p>当前登录会话已结束。</p>
        <button className="primary-button" onClick={() => setSessionActive(true)}>重新登录</button>
      </section>
    </main>;
  }

  return (
    <div className="app-shell" onKeyDown={(event) => { if (event.key === "Escape") { setUserMenuOpen(false); setProfileOpen(false); setLogoutConfirm(false); } }}>
      <aside className="sidebar">
        <div className="brand"><div className="brand-mark"><i /><i /><i /><i /><b /></div><div><strong>东防数据采集系统</strong><span>DATA INTEGRATION</span></div></div>
        <nav aria-label="主导航"><button className={`home-link ${appPage === "dashboard" ? "active" : ""}`} type="button" onClick={() => navigate("dashboard")}><span className="nav-icon"><HomeOutlined /></span>首页</button>{navGroups.map((group) => <div className="nav-group" key={group.title}><div className="nav-group-title"><span className="nav-icon">{group.icon}</span>{group.title}<small><UpOutlined /></small></div>{group.items.map((item) => { const target = navTarget(item); return <button className={isNavActive(item) ? "active" : ""} type="button" onClick={() => target && navigate(target)} key={item}><span className="menu-item-icon">{menuIcons[item]}</span>{item}</button>; })}</div>)}<button className={`docs-link ${appPage === "docs" ? "active" : ""}`} type="button" onClick={() => navigate("docs")}><span className="nav-icon"><BookOutlined /></span>使用文档</button></nav>
      </aside>

      <main className="main-area">
        <header className="topbar"><div className="breadcrumbs"><span>首页</span>{pageSection && <><b>/</b><span>{pageSection}</span></>}<b>/</b><strong>{pageLabel}</strong>{appPage === "taskDetail" && <><b>/</b><span>{selectedTask.id}</span></>}{appPage === "precheckDetail" && precheckDrawer && <><b>/</b><span>PRECHECK-{precheckDrawer.link.id.replace(/\D/g, "").slice(-4) || "341"}</span></>}</div><div className="top-actions"><button className="notification" aria-label="通知" onClick={() => navigate("alerts")}><BellOutlined /><i /></button><div className="account-area"><button className={`user user-trigger ${userMenuOpen ? "is-open" : ""}`} aria-label="打开账号菜单" aria-haspopup="menu" aria-expanded={userMenuOpen} onClick={() => setUserMenuOpen((open) => !open)}><span><UserOutlined /></span><div><strong>admin</strong><small>系统管理员</small></div><b><DownOutlined /></b></button>{userMenuOpen && <><button className="user-menu-scrim" aria-label="关闭账号菜单" onClick={() => setUserMenuOpen(false)} /><div className="user-menu" role="menu"><button role="menuitem" onClick={() => openProfile("account")}><UserOutlined /><span>个人中心</span></button><div /><button className="logout-menu-item" role="menuitem" onClick={() => { setUserMenuOpen(false); setLogoutConfirm(true); }}><LogoutOutlined /><span>退出登录</span></button></div></>}</div></div></header>
        <div className="content">
          {appPage === "dashboard" && <DashboardPage institutionCount={institutionRows.length} datasetCount={datasets.length} linkCount={aggregateLinkCount} failedLinks={aggregateFailedLinks} validationIssues={validationRows.filter((row) => row.result === "发现差异").length} taskSummary={{ total: monitorRows.length, completed: monitorRows.filter((row) => row.status === "已完成").length, running: monitorRows.filter((row) => row.status === "运行中").length, failed: monitorRows.filter((row) => row.status === "失败").length, review: monitorRows.filter((row) => row.status === "需核对").length }} onOpenMonitor={() => navigate("monitor")} onOpenPrecheck={() => navigate("precheck")} onOpenValidation={() => navigate("validationWorkbench")} />}
          {appPage === "institutions" && institutionPage()}
          {appPage === "datasources" && dataSourcesPage()}
          {appPage === "tasks" && tasksPage()}
          {appPage === "taskDetail" && taskDetailPage()}
          {appPage === "monitor" && monitorPage()}
          {appPage === "validationOverview" && validationOverviewPage()}
          {appPage === "validationWorkbench" && validationWorkbenchPage()}
          {appPage === "precheck" && precheckPage()}
          {appPage === "precheckDetail" && precheckDrawer && precheckDetailPage()}
          {(appPage === "alerts" || appPage === "logs" || appPage === "audit") && <OperationsPage page={appPage} query={operationsQuery} level={operationsLevel} module={operationsModule} paging={operationsPaging} onQuery={setOperationsQuery} onLevel={setOperationsLevel} onModule={setOperationsModule} onPaging={setOperationsPaging} onNotice={notifySettings} />}
          {(appPage === "globalSettings" || appPage === "registrySettings" || appPage === "validationSettings" || appPage === "dorisSettings" || appPage === "externalApi" || appPage === "mappingRules") && settingsPage()}
          {appPage === "docs" && <DocumentationPage onNavigate={(page) => navigate(page)} />}
          {appPage === "datasets" && <>
          <section className="stat-grid compact-stats" aria-label="数据集概览"><article><div className="stat-icon blue"><DatabaseOutlined /></div><div><span>标准数据集</span><strong>{datasets.length}</strong></div></article><article><div className="stat-icon green"><ApartmentOutlined /></div><div><span>覆盖机构</span><strong>{institutionRows.length}</strong></div></article><article><div className="stat-icon amber"><LinkOutlined /></div><div><span>采集链路</span><strong>{datasets.reduce((sum, item) => sum + linksFor(item).length, 0)}</strong></div></article><article><div className="stat-icon blue"><ClockCircleOutlined /></div><div><span>最近更新</span><strong>10:42</strong><small>2026-08-12</small></div></article></section>
          <section className="data-card">
            <div className="toolbar dataset-toolbar"><label className="search-box"><SearchOutlined /><input value={query} onChange={(e) => { setQuery(e.target.value); setDatasetPaging((state) => ({ ...state, page: 1 })); }} placeholder="搜索数据集名称或编码" /><kbd>⌘ K</kbd></label><select aria-label="业务分类" value={datasetCategory} onChange={(event) => { setDatasetCategory(event.target.value); setDatasetPaging((state) => ({ ...state, page: 1 })); }}><option>全部业务分类</option><option>检查检验</option><option>病案首页</option><option>费用结算</option></select><select aria-label="配置状态" value={filter} onChange={(e) => { setFilter(e.target.value); setDatasetPaging((state) => ({ ...state, page: 1 })); }}><option>全部状态</option><option>正常</option><option>待配置</option><option>异常</option></select><button className="ghost-button" onClick={() => { setQuery(""); setFilter("全部状态"); setDatasetCategory("全部业务分类"); setDatasetPaging((state) => ({ ...state, page: 1 })); }}><ReloadOutlined /> 重置</button></div>
            <div className="table-scroll"><table className="aggregate-table"><thead><tr><th>标准数据集</th><th>字段结构</th><th>同步策略</th><th>数据校验</th><th>消息通知</th><th>更新时间</th><th className="action-col">操作</th></tr></thead>
              <tbody>{pagedDatasets.rows.map((item) => { const links = linksFor(item); const isOpen = expanded.includes(item.code); return <Fragment key={item.code}><tr className={isOpen ? "parent-open" : ""}>
                <td><div className="dataset-with-expand"><button className={`expand-button ${isOpen ? "open" : ""}`} onClick={() => setExpanded((current) => current.includes(item.code) ? current.filter((code) => code !== item.code) : [...current, item.code])} aria-label={isOpen ? "收起采集链路" : "展开采集链路"}><RightOutlined /></button><div><span className="dataset-name-static">{item.name}</span><button className="dataset-code" onClick={() => navigator.clipboard?.writeText(item.code)} title="复制编码">{item.code}<span><CopyOutlined /></span></button></div></div></td>
                <td><div className="definition-cell"><strong>{item.fields} 个字段</strong><span>{item.primaryKeys} 个主键</span></div></td>
                <td><div className="definition-cell"><strong>UPSERT · 全量后增量</strong><span>固定间隔 · 每 4 小时</span></div></td>
                <td><div className="definition-cell"><strong>{item.rules} 条规则</strong><span className={item.exceptions ? "danger" : "positive"}>通过 {item.passed} · 异常 {item.exceptions}</span></div></td>
                <td><div className="definition-cell"><strong>{messageEnabled[item.code] ? "已开启" : "未开启"}</strong><span>{messageEnabled[item.code] ? "按数据集发送" : "不发送通知"}</span></div></td>
                <td><time className="dataset-updated-time" dateTime={item.updated.replace(" ", "T")}>{item.updated}</time></td>
                <td className="action-col"><button className="secondary-button row-action-button" onClick={() => openPanel(item, "basic")}><SettingOutlined /> 管理数据集</button></td></tr>
                {isOpen && <tr className="link-detail-row"><td colSpan={7}><div className="link-detail"><div className="link-detail-header"><div><strong>{item.name} · 机构采集链路</strong><span>按厂商和源端结构配置采集入口</span></div><button className="primary-button link-create-button" onClick={() => beginLinkCreation(item)}><PlusOutlined /> 新增采集链路</button></div><div className="link-grid link-grid-head"><span>链路 / 厂商</span><span>覆盖机构</span><span>数据源与源对象</span><span>操作</span></div>{links.map((link) => <div className="link-grid" key={link.id}><div className="link-name"><i><LinkOutlined /></i><p><strong>{link.name}</strong><span>{link.vendor}</span></p></div><div className="org-chips">{link.institutions.slice(0, 2).map((name) => <b key={name}>{name}</b>)}{link.institutions.length > 2 && <b>+{link.institutions.length - 2} 家</b>}</div><div className="source-object"><strong>{link.source}</strong><span>{link.sourceType}</span></div><div className="link-row-actions"><button className="text-button" onClick={() => navigate("tasks")}><SwapOutlined /> 数据同步</button><button className="text-button" onClick={() => navigate("precheck")}><FileSearchOutlined /> 数据预检</button><button className="text-button" onClick={() => openLink(item, link)}><SettingOutlined /> 配置链路</button><button className="text-button danger-text" onClick={() => setLinkDeleteTarget({ dataset: item, link })}><DeleteOutlined /> 删除链路</button></div></div>)}</div></td></tr>}
              </Fragment>})}{!visible.length && <tr><td colSpan={7} className="empty-state">没有找到匹配的标准数据集或采集链路，请调整筛选条件。</td></tr>}</tbody></table></div>
            <Pagination total={visible.length} state={datasetPaging} onChange={setDatasetPaging} noun="个数据集" />
          </section></>}
        </div>
      </main>

      {settingsNotice && <div className="settings-toast"><CheckCircleOutlined />{settingsNotice}</div>}
      {profileOpen && <><button className="drawer-backdrop profile-backdrop" aria-label="关闭个人中心" onClick={() => setProfileOpen(false)} /><aside className="profile-drawer" role="dialog" aria-modal="true" aria-labelledby="profile-title">
        <header><button aria-label="关闭个人中心" onClick={() => setProfileOpen(false)}><CloseOutlined /></button><h2 id="profile-title">个人中心</h2></header>
        <div className="profile-tabs" role="tablist" aria-label="个人中心功能">
          <button role="tab" aria-selected={profileTab === "account"} className={profileTab === "account" ? "active" : ""} onClick={() => setProfileTab("account")}><UserOutlined /> 账户信息</button>
          <button role="tab" aria-selected={profileTab === "password"} className={profileTab === "password" ? "active" : ""} onClick={() => setProfileTab("password")}><LockOutlined /> 修改密码</button>
          <button role="tab" aria-selected={profileTab === "alerts"} className={profileTab === "alerts" ? "active" : ""} onClick={() => setProfileTab("alerts")}><BellOutlined /> 告警设置</button>
        </div>
        <div className="profile-body">
          {profileTab === "account" && <section className="profile-account" role="tabpanel">
            <div className="profile-identity"><span><UserOutlined /></span><div><h3>admin</h3><b>系统管理员</b></div></div>
            <div className="profile-info-grid"><div><span>用户名</span><strong>admin</strong></div><div><span>角色</span><strong className="profile-tag blue">ADMIN</strong></div><div><span>账号状态</span><strong className="profile-tag green">正常</strong></div><div><span>最近登录</span><strong>2026-08-12 10:08:31</strong></div><div><span>登录地址</span><strong>192.168.1.18</strong></div><div><span>会话剩余有效期</span><strong>5 小时 2 分钟</strong></div></div>
          </section>}
          {profileTab === "password" && <section className="password-panel" role="tabpanel">
            <div className="profile-form-field"><label htmlFor="current-password"><i>*</i> 当前密码</label><div><LockOutlined /><input id="current-password" type={passwordVisible.current ? "text" : "password"} value={passwordForm.current} onChange={(event) => setPasswordForm((current) => ({ ...current, current: event.target.value }))} placeholder="请输入当前密码" autoComplete="current-password" /><button aria-label={passwordVisible.current ? "隐藏当前密码" : "显示当前密码"} onClick={() => setPasswordVisible((current) => ({ ...current, current: !current.current }))}>{passwordVisible.current ? <EyeOutlined /> : <EyeInvisibleOutlined />}</button></div></div>
            <div className="profile-form-field"><label htmlFor="new-password"><i>*</i> 新密码</label><div><LockOutlined /><input id="new-password" type={passwordVisible.next ? "text" : "password"} value={passwordForm.next} onChange={(event) => setPasswordForm((current) => ({ ...current, next: event.target.value }))} placeholder="至少 8 位" autoComplete="new-password" /><button aria-label={passwordVisible.next ? "隐藏新密码" : "显示新密码"} onClick={() => setPasswordVisible((current) => ({ ...current, next: !current.next }))}>{passwordVisible.next ? <EyeOutlined /> : <EyeInvisibleOutlined />}</button></div>{passwordForm.next && passwordForm.next.length < 8 && <small>新密码至少需要 8 位。</small>}</div>
            <div className="profile-form-field"><label htmlFor="confirm-password"><i>*</i> 确认新密码</label><div><LockOutlined /><input id="confirm-password" type={passwordVisible.confirm ? "text" : "password"} value={passwordForm.confirm} onChange={(event) => setPasswordForm((current) => ({ ...current, confirm: event.target.value }))} placeholder="再次输入新密码" autoComplete="new-password" /><button aria-label={passwordVisible.confirm ? "隐藏确认密码" : "显示确认密码"} onClick={() => setPasswordVisible((current) => ({ ...current, confirm: !current.confirm }))}>{passwordVisible.confirm ? <EyeOutlined /> : <EyeInvisibleOutlined />}</button></div>{passwordForm.confirm && passwordForm.confirm !== passwordForm.next && <small>两次输入的新密码不一致。</small>}</div>
            <button className="primary-button profile-submit" disabled={!passwordReady} onClick={submitPasswordChange}>修改密码</button>
          </section>}
          {profileTab === "alerts" && <section className="profile-alerts" role="tabpanel">
            <div className="profile-alert-summary"><span><BellOutlined /></span><div><h3>告警通知</h3><p>账号级告警统一在运维管理中查看和处理。</p></div></div>
            <div className="profile-info-grid compact"><div><span>站内通知</span><strong className="profile-tag green">已启用</strong></div><div><span>未读告警</span><strong>3 条</strong></div><div><span>通知范围</span><strong>系统异常与任务失败</strong></div></div>
            <button className="primary-button profile-submit" onClick={() => navigate("alerts")}><BellOutlined /> 前往告警通知 <RightOutlined /></button>
          </section>}
        </div>
      </aside></>}
      {logoutConfirm && <><button className="modal-backdrop logout-backdrop" aria-label="取消退出登录" onClick={() => setLogoutConfirm(false)} /><section className="settings-modal confirm-modal logout-confirm" role="alertdialog" aria-modal="true" aria-labelledby="logout-title"><header><h2 id="logout-title">退出登录</h2><button onClick={() => setLogoutConfirm(false)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认退出当前账号？</strong><span>退出后将结束当前会话，未保存的表单内容不会保留。</span></div></div><footer><button className="secondary-button" onClick={() => setLogoutConfirm(false)}>取消</button><button className="danger-button" onClick={logout}><LogoutOutlined /> 退出登录</button></footer></section></>}
      {settingDialog && <><button className="modal-backdrop" aria-label="关闭弹窗" onClick={() => setSettingDialog(null)} /><form className="settings-modal" role="dialog" aria-modal="true" aria-label={settingDialog === "external" ? "外部授权调用方" : "编辑类型映射规则"} onSubmit={(event) => { event.preventDefault(); const values = new FormData(event.currentTarget); if (settingDialog === "external") { const id = String(values.get("clientId") || "").trim(); const next = [id, String(values.get("clientName") || "").trim(), "启用", String(values.get("institutions") || "").trim() || "不限", String(values.get("description") || "").trim()]; setExternalClients((current) => externalEditingId ? current.map((item) => item[0] === externalEditingId ? next : item) : [...current, next]); notifySettings(externalEditingId ? "外部授权调用方已更新" : "外部授权调用方已创建并生成密钥"); } else if (editingMapping && mappingEditingKey) { const next = [...editingMapping]; next[2] = String(values.get("dorisType") || ""); next[3] = String(values.get("level") || "PASS"); next[4] = String(values.get("reason") || ""); next[5] = String(values.get("priority") || "100"); setMappingRules((current) => current.map((item) => `${item[0]}:${item[1]}` === mappingEditingKey ? next : item)); notifySettings("类型映射规则已保存"); } setSettingDialog(null); setExternalEditingId(null); setMappingEditingKey(null); }}>
        <header><h2>{settingDialog === "external" ? (externalEditingId ? "编辑外部授权调用方" : "新增外部授权调用方") : "编辑类型映射规则"}</h2><button type="button" onClick={() => setSettingDialog(null)} aria-label="关闭"><CloseOutlined /></button></header>
        {settingDialog === "external" ? <div className="settings-modal-body">
          <label className="setting-field"><span className="required">Client ID</span><input name="clientId" required readOnly={Boolean(externalEditingId)} defaultValue={editingExternal?.[0] ?? ""} placeholder="如：partner-his" /><small>第三方请求头 X-DFETL-Client-Id 使用该值；创建后不可修改。</small></label>
          <label className="setting-field"><span className="required">调用方名称</span><input name="clientName" required defaultValue={editingExternal?.[1] ?? ""} placeholder="如：医共体 HIS 外部调用方" /></label>
          <label className="setting-field"><span>启用状态</span><div className="field-switch"><Toggle checked label="启用状态" /><b>启用</b></div></label>
          <label className="setting-field"><span>授权医疗机构</span><input name="institutions" defaultValue={editingExternal?.[3] === "不限" ? "" : editingExternal?.[3] ?? ""} placeholder="YGT330106H001" /><small>留空或 * 表示不限；生产建议显式配置。</small></label>
          <label className="setting-field"><span>说明</span><textarea name="description" rows={3} defaultValue={editingExternal?.[4] ?? ""} placeholder="记录第三方系统、交付对象、用途等信息" /></label>
        </div> : <div className="settings-modal-body">
          <label className="setting-field"><span className="required">推荐 Doris 类型</span><input name="dorisType" required defaultValue={editingMapping?.[2] ?? "VARCHAR(65533)"} placeholder="DATETIME / VARCHAR(255) / DECIMAL(18,2)" /></label>
          <label className="setting-field"><span className="required">兼容等级</span><select name="level" defaultValue={editingMapping?.[3] ?? "PASS"}><option>PASS</option><option>WARN</option><option>FAIL</option></select></label>
          <label className="setting-field"><span>原因</span><textarea name="reason" rows={3} defaultValue={editingMapping?.[4] ?? "字符串类型兼容"} /></label>
          <label className="setting-field"><span className="required">优先级</span><input name="priority" type="number" defaultValue={editingMapping?.[5] ?? "100"} /></label>
          <label className="setting-field"><span>启用</span><div className="field-switch"><Toggle checked label="启用类型映射规则" /><b>启用</b></div></label>
        </div>}
        <footer><button type="button" className="secondary-button" onClick={() => setSettingDialog(null)}>取消</button><button type="submit" className="primary-button">{settingDialog === "external" ? (externalEditingId ? "保存" : "创建并生成密钥") : "确定"}</button></footer>
      </form></>}

      {dataSourceDialog && <><button className="modal-backdrop" aria-label="关闭数据源配置" onClick={() => setDataSourceDialog(null)} /><form className="settings-modal data-source-modal" role="dialog" aria-modal="true" aria-label="数据源配置" onSubmit={(event) => { event.preventDefault(); const values = new FormData(event.currentTarget); if (dataSourceDialog.tab === "source") { const name = String(values.get("name") || "").trim(); const next: SourceDataSource = { name, type: sourceDbType, host: String(values.get("host") || ""), database: String(values.get("database") || ""), schema: sourceDbType === "MySQL" ? String(values.get("database") || "") : String(values.get("schema") || ""), username: String(values.get("username") || ""), password: String(values.get("password") || editingSource?.password || "****"), institutions: [String(values.get("institution") || "县人民医院")], enabled: editingSource ? sourceEnabled[editingSource.name] : true }; setSourceDataSources((current) => dataSourceDialog.mode === "edit" ? current.map((item) => item.name === dataSourceDialog.name ? next : item) : [...current, next]); setSourceEnabled((current) => ({ ...current, [name]: next.enabled })); } else { const name = String(values.get("name") || "").trim(); const next: TargetDataSource = { name, host: String(values.get("host") || ""), fePort: String(values.get("fePort") || "9030"), httpPort: String(values.get("httpPort") || "8030"), streamLoadPort: String(values.get("streamLoadPort") || "8040"), username: String(values.get("username") || ""), password: String(values.get("password") || editingTarget?.password || "****"), database: String(values.get("database") || ""), writeDb: String(values.get("writeDb") || ""), batchSize: String(values.get("batchSize") || "50000"), writeConcurrency: String(values.get("writeConcurrency") || "8"), poolSize: String(values.get("poolSize") || "20"), ssl: editingTarget?.ssl ?? false, description: String(values.get("description") || ""), enabled: editingTarget ? targetEnabled[editingTarget.name] : true }; setTargetDataSources((current) => dataSourceDialog.mode === "edit" ? current.map((item) => item.name === dataSourceDialog.name ? next : item) : [...current, next]); setTargetEnabled((current) => ({ ...current, [name]: next.enabled })); } notifySettings(dataSourceDialog.mode === "create" ? "数据源已创建" : "数据源配置已保存"); setDataSourceDialog(null); }}>
        <header><h2>{dataSourceDialog.mode === "create" ? "新建" : "编辑"}{dataSourceDialog.tab === "source" ? "源数据源" : "目标数据源"}</h2><button onClick={() => setDataSourceDialog(null)} aria-label="关闭"><CloseOutlined /></button></header>
        {dataSourceDialog.tab === "source" ? <div className="settings-modal-body data-source-form">
          <label className="setting-field"><span className="required">名称</span><input name="name" required defaultValue={editingSource?.name ?? ""} placeholder="如：postgresql-rmyy" /></label>
          <label className="setting-field"><span className="required">数据库类型</span><select value={sourceDbType} onChange={(event) => setSourceDbType(event.target.value)}><option>PostgreSQL</option><option>Oracle</option><option>SQL Server</option><option>MySQL</option></select></label>
          <label className="setting-field form-span-2"><span className="required">主机地址</span><input name="host" required defaultValue={editingSource?.host ?? ""} placeholder={sourceDbType === "PostgreSQL" ? "IP 地址:5432" : sourceDbType === "Oracle" ? "IP 地址:1521" : sourceDbType === "SQL Server" ? "IP 地址:1433" : "IP 地址:3306"} /></label>
          <label className={`setting-field ${sourceDbType === "MySQL" ? "form-span-2" : ""}`}><span className="required">{sourceDbType === "Oracle" ? "服务名 / SID" : "数据库"}</span><input name="database" required defaultValue={editingSource?.database ?? ""} placeholder={sourceDbType === "Oracle" ? "如：FREEPDB1" : "请输入数据库名"} />{sourceDbType === "MySQL" && <small>MySQL 中数据库即 Schema，不重复配置。</small>}</label>
          {sourceDbType !== "MySQL" && <label className="setting-field"><span className="required">{sourceDbType === "Oracle" ? "Schema / 用户" : "Schema"}</span><input name="schema" required defaultValue={editingSource?.schema ?? (sourceDbType === "PostgreSQL" ? "public" : sourceDbType === "SQL Server" ? "dbo" : "")} /></label>}
          <label className="setting-field"><span className="required">用户名</span><input name="username" required defaultValue={editingSource?.username ?? ""} /></label>
          <label className="setting-field"><span className="required">密码</span><input name="password" type="password" defaultValue="" placeholder={dataSourceDialog.mode === "edit" ? "不修改请留空" : "请输入密码"} /><small>密码加密存储，列表中仅显示掩码。</small></label>
          <label className="setting-field form-span-2"><span className="required">关联机构</span><select name="institution" defaultValue={editingSource?.institutions[0] ?? "县人民医院"}>{institutionNames.map((name) => <option key={name}>{name}</option>)}</select></label>
        </div> : <div className="settings-modal-body data-source-form">
          <div className="form-section-title form-span-2">Doris 连接配置</div>
          <label className="setting-field form-span-2"><span className="required">数据源名称</span><input name="name" required defaultValue={editingTarget?.name ?? ""} placeholder="如：ygt-doris" /></label>
          <label className="setting-field"><span className="required">FE 地址</span><input name="host" required defaultValue={editingTarget?.host ?? ""} placeholder="如：192.168.1.10" /></label>
          <label className="setting-field"><span className="required">FE 端口</span><input name="fePort" required defaultValue={editingTarget?.fePort ?? "9030"} /></label>
          <label className="setting-field"><span>HTTP API 端口</span><input name="httpPort" defaultValue={editingTarget?.httpPort ?? "8030"} /></label>
          <label className="setting-field"><span>Stream Load 端口</span><input name="streamLoadPort" defaultValue={editingTarget?.streamLoadPort ?? "8040"} /></label>
          <label className="setting-field"><span className="required">用户名</span><input name="username" required defaultValue={editingTarget?.username ?? ""} /></label>
          <label className="setting-field"><span className="required">密码</span><input name="password" type="password" defaultValue="" placeholder={dataSourceDialog.mode === "edit" ? "不修改请留空" : "请输入密码"} /><small>密码加密存储，列表中仅显示掩码。</small></label>
          <label className="setting-field form-span-2"><span className="required">默认数据库</span><input name="database" required defaultValue={editingTarget?.database ?? ""} placeholder="如：df_ygt" /></label>
          <div className="form-section-title form-span-2">写入配置</div>
          <label className="setting-field form-span-2"><span className="required">默认写入数据库</span><input name="writeDb" required defaultValue={editingTarget?.writeDb ?? ""} placeholder="如：ODS_RAW" /></label>
          <label className="setting-field"><span>写入批量大小</span><input name="batchSize" type="number" defaultValue={editingTarget?.batchSize ?? "50000"} /></label>
          <label className="setting-field"><span>写入并发数</span><input name="writeConcurrency" type="number" defaultValue={editingTarget?.writeConcurrency ?? "8"} /></label>
          <div className="form-section-title form-span-2">高级配置</div>
          <label className="setting-field"><span>连接池大小</span><input name="poolSize" type="number" defaultValue={editingTarget?.poolSize ?? "20"} /></label>
          <label className="setting-field"><span>SSL</span><div className="field-switch"><Toggle checked={editingTarget?.ssl ?? false} label="启用 SSL" /><b>{editingTarget?.ssl ? "启用" : "关闭"}</b></div></label>
          <label className="setting-field form-span-2"><span>描述</span><textarea name="description" rows={3} defaultValue={editingTarget?.description ?? ""} placeholder="请输入描述" /></label>
        </div>}
        <footer><button type="button" className="secondary-button" disabled={connectionTest === "testing"} onClick={(event) => { const form = event.currentTarget.closest("form"); if (!form) return; const values = new FormData(form); const name = String(values.get("name") || "未命名数据源"); const host = String(values.get("host") || ""); simulateConnectionTest(name, !host || host === "0.0.0.0", "主机不可达"); }}>{connectionTest === "testing" ? "测试中…" : "测试连接"}</button><span className="modal-footer-spacer" /><button type="button" className="secondary-button" onClick={() => setDataSourceDialog(null)}>取消</button><button type="submit" className="primary-button">{dataSourceDialog.mode === "create" ? "创建" : "保存"}</button></footer>
      </form></>}

      {institutionDialog && <><button className="modal-backdrop" aria-label="关闭机构配置" onClick={() => setInstitutionDialog(null)} /><form className="settings-modal data-source-modal" role="dialog" aria-modal="true" aria-label="机构配置" onSubmit={(event) => { event.preventDefault(); const values = new FormData(event.currentTarget); const code = String(values.get("code") || "").trim(); const next: Institution = { name: String(values.get("name") || "").trim(), code, type: String(values.get("type") || "医院"), level: String(values.get("level") || "二级"), parent: String(values.get("parent") || "安溪县总医院"), division: String(values.get("division") || ""), system: String(values.get("system") || "HIS"), source: editingInstitution?.source ?? "未关联", enabled: editingInstitution ? institutionEnabled[editingInstitution.code] : true }; setInstitutionRows((current) => institutionDialog.mode === "edit" ? current.map((item) => item.code === institutionDialog.code ? next : item) : [...current, next]); setInstitutionEnabled((current) => ({ ...current, [code]: next.enabled })); notifySettings(institutionDialog.mode === "create" ? "机构已新增" : "机构信息已保存"); setInstitutionDialog(null); }}>
        <header><h2>{institutionDialog.mode === "create" ? "新增机构" : "编辑机构"}</h2><button onClick={() => setInstitutionDialog(null)} aria-label="关闭"><CloseOutlined /></button></header>
        <div className="settings-modal-body data-source-form">
          <label className="setting-field"><span className="required">机构名称</span><input name="name" required defaultValue={editingInstitution?.name ?? ""} placeholder="请输入机构名称" /></label>
          <label className="setting-field"><span className="required">机构编码</span><input name="code" required defaultValue={editingInstitution?.code ?? `YGT${104 + institutionRows.length}`} placeholder="如：YGT104" /></label>
          <label className="setting-field"><span className="required">机构类型</span><select name="type" defaultValue={editingInstitution?.type ?? "医院"}><option>医院</option><option>妇幼保健院</option><option>乡镇卫生院</option></select></label>
          <label className="setting-field"><span>机构等级</span><select name="level" defaultValue={editingInstitution?.level ?? "二级"}><option>三级</option><option>二级</option><option>一级</option></select></label>
          <label className="setting-field"><span>上级机构</span><input name="parent" defaultValue={editingInstitution?.parent ?? "安溪县总医院"} /></label>
          <label className="setting-field"><span>行政区划</span><input name="division" defaultValue={editingInstitution?.division ?? "凤城镇"} /></label>
          <label className="setting-field form-span-2"><span className="required">业务系统</span><input name="system" required defaultValue={editingInstitution?.system ?? "HIS"} placeholder="请输入业务系统名称" /></label>
        </div>
        <footer><button type="button" className="secondary-button" onClick={() => setInstitutionDialog(null)}>取消</button><button type="submit" className="primary-button">{institutionDialog.mode === "create" ? "新增" : "保存"}</button></footer>
      </form></>}

      {institutionDelete && <><button className="modal-backdrop" aria-label="取消删除机构" onClick={() => setInstitutionDelete(null)} /><section className="settings-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label="删除机构确认"><header><h2>删除机构</h2><button onClick={() => setInstitutionDelete(null)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认删除该机构？</strong><span>删除后不再出现在机构列表中，已有关联关系需重新配置。</span></div></div><footer><button className="secondary-button" onClick={() => setInstitutionDelete(null)}>取消</button><button className="danger-button" onClick={() => { setInstitutionRows((current) => current.filter((item) => item.code !== institutionDelete)); setInstitutionDelete(null); notifySettings("机构已删除"); }}>删除</button></footer></section></>}

      {dataSourceDeleteTarget && <><button className="modal-backdrop" aria-label="取消删除数据源" onClick={() => setDataSourceDeleteTarget(null)} /><section className="settings-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label="删除数据源确认"><header><h2>删除数据源</h2><button onClick={() => setDataSourceDeleteTarget(null)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认删除「{dataSourceDeleteTarget.name}」？</strong><span>删除后需重新配置依赖该数据源的采集链路。</span></div></div><footer><button className="secondary-button" onClick={() => setDataSourceDeleteTarget(null)}>取消</button><button className="danger-button" onClick={() => { if (dataSourceDeleteTarget.tab === "source") setSourceDataSources((current) => current.filter((item) => item.name !== dataSourceDeleteTarget.name)); else setTargetDataSources((current) => current.filter((item) => item.name !== dataSourceDeleteTarget.name)); setDataSourceDeleteTarget(null); notifySettings("数据源已删除"); }}>删除</button></footer></section></>}

      {externalDeleteTarget && <><button className="modal-backdrop" aria-label="取消删除调用方" onClick={() => setExternalDeleteTarget(null)} /><section className="settings-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label="删除外部调用方确认"><header><h2>删除外部调用方</h2><button onClick={() => setExternalDeleteTarget(null)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认删除「{externalDeleteTarget}」？</strong><span>删除后该 Client ID 将无法继续调用外部接口。</span></div></div><footer><button className="secondary-button" onClick={() => setExternalDeleteTarget(null)}>取消</button><button className="danger-button" onClick={() => { setExternalClients((current) => current.filter((item) => item[0] !== externalDeleteTarget)); setExternalDeleteTarget(null); notifySettings("外部授权调用方已删除"); }}>删除</button></footer></section></>}

      {externalResetTarget && <><button className="modal-backdrop" aria-label="取消重置密钥" onClick={() => setExternalResetTarget(null)} /><section className="settings-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label="重置外部调用方密钥确认"><header><h2>重置 Shared Secret</h2><button onClick={() => setExternalResetTarget(null)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认重置「{externalResetTarget}」的密钥？</strong><span>旧密钥将立即失效，调用方必须同步更新配置。</span></div></div><footer><button className="secondary-button" onClick={() => setExternalResetTarget(null)}>取消</button><button className="danger-button" onClick={() => { setExternalResetTarget(null); notifySettings("Shared Secret 已重置，请立即复制并安全交付"); }}>确认重置</button></footer></section></>}

      {linkDeleteTarget && <><button className="modal-backdrop" aria-label="取消删除采集链路" onClick={() => setLinkDeleteTarget(null)} /><section className="settings-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label="删除采集链路确认"><header><h2>删除采集链路</h2><button onClick={() => setLinkDeleteTarget(null)} aria-label="关闭"><CloseOutlined /></button></header><div className="confirm-body"><ExclamationCircleOutlined /><div><strong>确认删除「{linkDeleteTarget.link.name}」？</strong><span>删除后，该链路将从数据同步和数据预检的可用范围中移除。</span></div></div><footer><button className="secondary-button" onClick={() => setLinkDeleteTarget(null)}>取消</button><button className="danger-button" onClick={() => { setDeletedLinks((current) => [...new Set([...current, `${linkDeleteTarget.dataset.code}:${linkDeleteTarget.link.id}`])]); setLinkDeleteTarget(null); notifySettings("采集链路已删除"); }}>删除</button></footer></section></>}

      {linkCreateDataset && <><button className="modal-backdrop" aria-label="关闭新增采集链路" onClick={() => setLinkCreateDataset(null)} /><section className="settings-modal link-create-modal" role="dialog" aria-modal="true" aria-label="新增采集链路">
        <header><div><h2>新增采集链路</h2><span>{linkCreateDataset.name} · {linkCreateDataset.code}</span></div><button onClick={() => setLinkCreateDataset(null)} aria-label="关闭"><CloseOutlined /></button></header>
        <div className="link-create-steps">{[[1, "机构与数据源"], [2, "源对象与映射"], [3, "确认链路"]].map(([step, label]) => <div className={linkCreateStep === step ? "active" : linkCreateStep > Number(step) ? "done" : ""} key={step}><i>{linkCreateStep > Number(step) ? "✓" : step}</i><span>{label}</span></div>)}</div>
        <div className="settings-modal-body link-create-body">
          {linkCreateStep === 1 && <div className="data-source-form link-step-form"><label className="setting-field form-span-2"><span className="required">链路名称</span><input value={linkDraft.name} onChange={(event) => setLinkDraft((current) => ({ ...current, name: event.target.value }))} /></label><label className="setting-field"><span className="required">厂商 / 业务系统</span><select value={linkDraft.vendor} onChange={(event) => setLinkDraft((current) => ({ ...current, vendor: event.target.value }))}><option>东软 HIS</option><option>东华 HIS</option><option>创业 HIS</option><option>卫宁基层 HIS</option></select></label><label className="setting-field"><span className="required">覆盖机构</span><select value={linkDraft.institution} onChange={(event) => setLinkDraft((current) => ({ ...current, institution: event.target.value }))}><option>县人民医院</option><option>县中医院</option><option>县妇保院</option><option>基层医疗共享</option></select></label><label className="setting-field form-span-2"><span className="required">源数据源</span><select value={linkDraft.source} onChange={(event) => { const source = event.target.value; const schema = source === "oracle-zyy" ? "his_zyy" : source === "sqlserver-fby" ? "dbo" : source === "mysql-jcyl" ? "jc_his" : "his_rmyy"; setLinkDraft((current) => ({ ...current, source, schema })); }}><option>postgresql-rmyy</option><option>oracle-zyy</option><option>sqlserver-fby</option><option>mysql-jcyl</option></select></label><div className="link-form-note form-span-2"><DatabaseOutlined /><div><strong>数据源连接正常</strong><span>创建前仍会执行源对象读取测试。</span></div></div></div>}
          {linkCreateStep === 2 && <div className="data-source-form link-step-form"><label className="setting-field"><span className="required">源 Schema / 数据库</span><input value={linkDraft.schema} onChange={(event) => setLinkDraft((current) => ({ ...current, schema: event.target.value }))} /></label><label className="setting-field"><span className="required">源视图 / 表</span><input value={linkDraft.object} onChange={(event) => setLinkDraft((current) => ({ ...current, object: event.target.value }))} /></label><label className="setting-field"><span>标准数据集</span><input value={linkCreateDataset.name} readOnly /></label><label className="setting-field"><span>数据集编码</span><input value={linkCreateDataset.code} readOnly /></label><div className="mapping-check form-span-2"><div><TableOutlined /><span><strong>字段映射</strong><small>按编码自动匹配标准字段</small></span></div><b>{linkCreateDataset.fields} / {linkCreateDataset.fields} 已匹配</b><span className="precheck-pass">可创建</span></div><button className="secondary-button form-span-2 test-read-button" onClick={() => notifySettings("源对象读取成功，字段结构与标准数据集匹配")}><FileSearchOutlined /> 测试读取与字段匹配</button></div>}
          {linkCreateStep === 3 && <div className="link-confirm"><div className="link-confirm-grid"><div><span>链路名称</span><strong>{linkDraft.name}</strong></div><div><span>厂商 / 业务系统</span><strong>{linkDraft.vendor}</strong></div><div><span>覆盖机构</span><strong>{linkDraft.institution === "基层医疗共享" ? "9 家乡镇卫生院" : linkDraft.institution}</strong></div><div><span>源数据源</span><strong>{linkDraft.source}</strong></div><div><span>源对象</span><strong>{linkDraft.schema}.{linkDraft.object}</strong></div><div><span>标准数据集</span><strong>{linkCreateDataset.name} · {linkCreateDataset.code}</strong></div><div><span>字段映射</span><strong>{linkCreateDataset.fields} / {linkCreateDataset.fields} 已匹配</strong></div></div><div className="link-form-note"><LinkOutlined /><div><strong>采集链路仅保存来源与映射关系</strong><span>数据执行参数请在任务中心的“数据同步”中配置；创建后可直接进行数据预检。</span></div></div></div>}
        </div>
        <footer><button className="secondary-button" onClick={() => setLinkCreateDataset(null)}>取消</button><span className="modal-footer-spacer" />{linkCreateStep > 1 && <button className="secondary-button" onClick={() => setLinkCreateStep((step) => step - 1)}>上一步</button>}{linkCreateStep < 3 ? <button className="primary-button" disabled={!linkDraft.name || !linkDraft.source || (linkCreateStep === 2 && !linkDraft.object)} onClick={() => setLinkCreateStep((step) => step + 1)}>下一步 <RightOutlined /></button> : <button className="primary-button" onClick={() => { const created: DataLink = { id: `link-${Date.now()}`, name: linkDraft.name, vendor: linkDraft.vendor, source: linkDraft.source, sourceType: `${linkDraft.schema}.${linkDraft.object}`, institutions: linkDraft.institution === "基层医疗共享" ? institutionNames.slice(3) : [linkDraft.institution], schedule: "未配置", state: "未启用", lastRun: "—" }; setCreatedLinks((current) => ({ ...current, [linkCreateDataset.code]: [...(current[linkCreateDataset.code] ?? []), created] })); setExpanded((current) => current.includes(linkCreateDataset.code) ? current : [...current, linkCreateDataset.code]); setLinkCreateDataset(null); notifySettings("采集链路已创建，可前往数据同步或数据预检"); }}>创建链路</button>}</footer>
      </section></>}

      {datasetPolicyDialog && drawer && <><button className="modal-backdrop policy-modal-backdrop" aria-label="关闭策略配置" onClick={() => setDatasetPolicyDialog(null)} /><form className="settings-modal dataset-policy-modal" role="dialog" aria-modal="true" aria-label={datasetPolicyDialog === "sync" ? "同步策略" : datasetPolicyDialog === "validation" ? "校验策略" : "消息配置"} onSubmit={(event) => { event.preventDefault(); const values = Object.fromEntries(Array.from(new FormData(event.currentTarget).entries()).map(([key, value]) => [key, String(value)])); setDatasetPolicyValues((current) => ({ ...current, [drawer.code]: { ...(current[drawer.code] ?? {}), [datasetPolicyDialog]: values } })); if (datasetPolicyDialog === "message") setMessageEnabled((current) => ({ ...current, [drawer.code]: messageDraftEnabled })); markSaved(`${drawer.code}:${datasetPolicyDialog}`, datasetPolicyDialog === "sync" ? "同步策略已保存" : datasetPolicyDialog === "validation" ? "校验策略已保存" : "消息配置已保存"); setDatasetPolicyDialog(null); }}>
        <header><h2>{datasetPolicyDialog === "sync" ? `同步策略 — ${drawer.code}` : datasetPolicyDialog === "validation" ? `校验策略 — ${drawer.code}` : `消息配置 — ${drawer.code}`}</h2><button type="button" onClick={() => setDatasetPolicyDialog(null)} aria-label="关闭"><CloseOutlined /></button></header>
        {datasetPolicyDialog === "sync" && <div className="settings-modal-body policy-form-grid">
          <label className="setting-field"><span className="required">写入模式</span><select name="writeMode" defaultValue={datasetPolicyValues[drawer.code]?.sync?.writeMode ?? "UPSERT"}><option>UPSERT</option><option>INSERT</option><option>TRUNCATE</option></select></label>
          <label className="setting-field"><span className="required">同步方式</span><select name="syncMode" defaultValue={datasetPolicyValues[drawer.code]?.sync?.syncMode ?? "首次全量后增量"}><option>首次全量后增量</option><option>仅全量</option><option>仅增量</option></select></label>
          <label className="setting-field"><span className="required">增量字段</span><select name="incrementField" defaultValue={datasetPolicyValues[drawer.code]?.sync?.incrementField ?? "修改时间（XIUGAISJ）"}><option>修改时间（XIUGAISJ）</option><option>创建时间（CHUANGJIANSJ）</option></select></label>
          <label className="setting-field"><span>增量上界</span><select name="upperBound" defaultValue={datasetPolicyValues[drawer.code]?.sync?.upperBound ?? "当前时间"}><option>当前时间</option><option>任务开始时间</option></select></label>
          <label className="setting-field"><span>上界延迟（分钟）</span><input name="upperDelay" type="number" defaultValue={datasetPolicyValues[drawer.code]?.sync?.upperDelay ?? "5"} /></label><label className="setting-field"><span>回看窗口（秒）</span><input name="lookback" type="number" defaultValue={datasetPolicyValues[drawer.code]?.sync?.lookback ?? "0"} /></label>
          <label className="setting-field"><span>Reader 并发</span><input name="readerConcurrency" type="number" defaultValue={datasetPolicyValues[drawer.code]?.sync?.readerConcurrency ?? "4"} /></label><label className="setting-field"><span>Fetch Size</span><input name="fetchSize" defaultValue={datasetPolicyValues[drawer.code]?.sync?.fetchSize ?? "继承全局"} /></label>
          <label className="setting-field"><span>启用调度</span><div className="field-switch"><Toggle checked label="启用调度" /><b>启用</b></div></label>
          <label className="setting-field"><span>调度方式</span><select name="scheduleMode" defaultValue={datasetPolicyValues[drawer.code]?.sync?.scheduleMode ?? "固定小时间隔"}><option>固定小时间隔</option><option>Cron 表达式</option></select></label>
          <label className="setting-field"><span>间隔（小时）</span><input name="interval" type="number" defaultValue={datasetPolicyValues[drawer.code]?.sync?.interval ?? "4"} /></label><label className="setting-field"><span className="required">时区</span><input name="timezone" value="Asia/Shanghai" readOnly /></label>
        </div>}
        {datasetPolicyDialog === "validation" && <div className="settings-modal-body policy-form-grid">
          <label className="setting-field"><span>继承全局</span><div className="field-switch"><Toggle checked={false} label="继承全局" /><b>关闭</b></div></label>
          <label className="setting-field"><span>启用校验</span><div className="field-switch"><Toggle checked label="启用校验" /><b>启用</b></div></label>
          <label className="setting-field"><span>触发方式</span><select name="trigger" defaultValue={datasetPolicyValues[drawer.code]?.validation?.trigger ?? "同步后自动"}><option>同步后自动</option><option>手动触发</option></select></label>
          <label className="setting-field"><span>校验方法</span><select name="method" defaultValue={datasetPolicyValues[drawer.code]?.validation?.method ?? "CHECKSUM"}><option>CHECKSUM</option><option>ROW_COUNT</option></select></label>
          <label className="setting-field"><span>行数容差（%）</span><input name="tolerance" type="number" defaultValue={datasetPolicyValues[drawer.code]?.validation?.tolerance ?? "0"} /></label>
          <label className="setting-field"><span>失败阻断</span><div className="field-switch"><Toggle checked={false} label="失败阻断" /><b>关闭</b></div></label>
          <label className="setting-field"><span>启用复检</span><div className="field-switch"><Toggle checked label="启用复检" /><b>启用</b></div></label>
          <label className="setting-field"><span>复检延迟（秒）</span><input name="retryDelay" type="number" defaultValue={datasetPolicyValues[drawer.code]?.validation?.retryDelay ?? "30"} /></label>
          <label className="setting-field"><span>校验回看（小时）</span><input name="lookbackHours" type="number" defaultValue={datasetPolicyValues[drawer.code]?.validation?.lookbackHours ?? "2"} /></label>
        </div>}
        {datasetPolicyDialog === "message" && <div className="settings-modal-body policy-form-grid">
          <label className="setting-field"><span>启用消息发布</span><div className="field-switch"><Toggle checked={messageDraftEnabled} label="启用消息发布" onChange={() => setMessageDraftEnabled((current) => !current)} /><b>{messageDraftEnabled ? "启用" : "关闭"}</b></div></label>
          <label className="setting-field"><span className="required">来源系统</span><input name="sourceSystem" defaultValue={datasetPolicyValues[drawer.code]?.message?.sourceSystem ?? "HIS"} /></label><label className="setting-field"><span className="required">租户 ID</span><input name="tenantId" defaultValue={datasetPolicyValues[drawer.code]?.message?.tenantId ?? "0"} /></label><label className="setting-field"><span>Routing Key</span><input name="routingKey" defaultValue={datasetPolicyValues[drawer.code]?.message?.routingKey ?? "YL_KESHIXT"} /></label>
          <label className="setting-field"><span>Topic</span><input name="topic" defaultValue={datasetPolicyValues[drawer.code]?.message?.topic ?? "dfetl.dataset.change"} /></label><label className="setting-field form-span-2"><span>messageKey 模板</span><input name="messageKey" defaultValue={datasetPolicyValues[drawer.code]?.message?.messageKey ?? "{yiliaojgdm}:{keshidm}"} /></label>
          <label className="setting-field"><span>首次全量发布</span><select name="initialPublish" defaultValue={datasetPolicyValues[drawer.code]?.message?.initialPublish ?? "ALL（发布全部）"}><option>ALL（发布全部）</option><option>NONE（不发布）</option></select></label>
          <label className="setting-field"><span>限速（条/秒）</span><input name="rateLimit" type="number" defaultValue={datasetPolicyValues[drawer.code]?.message?.rateLimit ?? "1000"} /></label><label className="setting-field"><span>分页大小</span><input name="pageSize" type="number" defaultValue={datasetPolicyValues[drawer.code]?.message?.pageSize ?? "1000"} /></label>
        </div>}
        <footer><button type="button" className="secondary-button" onClick={() => setDatasetPolicyDialog(null)}>取消</button><button type="submit" className="primary-button">保存</button></footer>
      </form></>}

      {drawer && <><button className="drawer-backdrop" aria-label="关闭详情" onClick={() => setDrawer(null)} /><aside className={`drawer drawer-large ${drawerLink ? "link-config-drawer" : "dataset-config-drawer"}`} aria-label={drawerLink ? "配置采集链路" : "管理数据集"}><div className="drawer-header"><div className="drawer-title-wrap"><div className="drawer-kind-icon">{drawerLink ? <LinkOutlined /> : <DatabaseOutlined />}</div><div><span>{drawerLink ? "采集链路配置" : "数据集管理"}</span><h2>{drawerLink ? drawerLink.name : drawer.name}</h2><p>{drawerLink ? `${drawer.name} · ${drawerLink.vendor} · ${drawerLink.institutions.length} 家机构` : drawer.code}</p></div></div><button onClick={() => setDrawer(null)} aria-label="关闭"><CloseOutlined /></button></div>
        <div className="drawer-object-note"><i className={drawerLink ? "link-dot" : "global-dot"} /><div><strong>{drawerLink ? "链路级配置" : "数据集级配置"}</strong><span>{drawerLink ? "机构、数据源、源对象与字段映射" : "字段、同步、校验、消息"}</span></div></div>
        <div className="drawer-body"><nav className="drawer-nav" aria-label={drawerLink ? "采集链路配置导航" : "数据集配置导航"}>{drawerLink ? <button className="active" onClick={() => selectPanel(drawer, "collection")}><span><LinkOutlined /></span><b>源端映射</b></button> : <>{(["basic", "fields", "sync", "validation", "message"] as Panel[]).map((panel) => <button className={activePanel === panel ? "active" : ""} onClick={() => selectPanel(drawer, panel)} key={panel}><span>{panel === "basic" ? <DatabaseOutlined /> : panel === "fields" ? <TableOutlined /> : panel === "sync" ? <FieldTimeOutlined /> : panel === "validation" ? <SafetyCertificateOutlined /> : <MessageOutlined />}</span><b>{panelNames[panel]}</b>{panel === "message" && messageEnabled[drawer.code] && <i>已开启</i>}</button>)}</>}</nav><div className="drawer-content drawer-panel">{panelContent(drawer)}</div></div>
        <div className="drawer-footer">{drawerLink ? <><button className="secondary-button" onClick={() => setDrawer(null)}>取消</button><button className="primary-button" onClick={() => { setLinkOverrides((current) => ({ ...current, [`${drawer.code}:${drawerLink.id}`]: { source: linkConfigDraft.source, sourceType: `${linkConfigDraft.schema}.${linkConfigDraft.object}`, mappedFields: linkConfigDraft.mappedFields } })); notifySettings("采集链路配置已保存"); setDrawer(null); }}>保存链路配置</button></> : <button className="primary-button" onClick={() => setDrawer(null)}>关闭</button>}</div></aside></>}
      {validationDrawer && validationDrawerContent()}
    </div>
  );
}

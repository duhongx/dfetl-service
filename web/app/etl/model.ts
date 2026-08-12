export type Panel = "basic" | "fields" | "validation" | "message" | "collection" | "sync";
export type AppPage = "dashboard" | "institutions" | "datasources" | "datasets" | "precheck" | "precheckDetail" | "tasks" | "taskDetail" | "monitor" | "validationOverview" | "validationWorkbench" | "alerts" | "logs" | "audit" | "globalSettings" | "registrySettings" | "validationSettings" | "dorisSettings" | "externalApi" | "mappingRules" | "docs";
export type DataSourceTab = "source" | "target";
export type TaskDetailTab = "basic" | "extract" | "mapping" | "runs" | "validation" | "messageConfig" | "messageLogs";
export type ValidationMode = "full" | "modify" | "delete";
export type ValidationView = "result" | "history";
export type PrecheckDetailTab = "overview" | "sample" | "runs" | "issues";
export type ProfileTab = "account" | "password" | "alerts";

export type Dataset = {
  name: string;
  code: string;
  category: string;
  fields: number;
  primaryKeys: number;
  strategy: string;
  schedule: string;
  scope: string;
  messageEnabled: boolean;
  rules: number;
  passed: number;
  exceptions: number;
  syncState: "正常" | "待配置" | "异常";
  updated: string;
};

export type DataLink = {
  id: string;
  name: string;
  vendor: string;
  source: string;
  sourceType: string;
  institutions: string[];
  schedule: string;
  state: "正常" | "异常" | "未启用";
  lastRun: string;
  mappedFields?: number;
};

export type Institution = {
  name: string;
  code: string;
  type: string;
  level: string;
  parent: string;
  division: string;
  system: string;
  source: string;
  enabled: boolean;
};

export type SourceDataSource = {
  name: string;
  type: string;
  host: string;
  database: string;
  schema: string;
  username: string;
  password: string;
  institutions: string[];
  enabled: boolean;
};

export type TargetDataSource = {
  name: string;
  host: string;
  fePort: string;
  httpPort: string;
  streamLoadPort: string;
  database: string;
  writeDb: string;
  username: string;
  password: string;
  batchSize: string;
  writeConcurrency: string;
  poolSize: string;
  ssl: boolean;
  description: string;
  enabled: boolean;
};

export type TaskRow = {
  id: string;
  name: string;
  dataset: Dataset;
  link: DataLink;
  view: string;
  state: "运行中" | "失败" | "已停止";
  recent: string;
  successRate: string;
};

export type MonitorRow = {
  id: string;
  taskId: string;
  name: string;
  batch: string;
  start: string;
  duration: string;
  read: string;
  written: string;
  speed: string;
  status: "运行中" | "已完成" | "失败" | "需核对";
};

export type ValidationRow = {
  id: string;
  taskId: string;
  name: string;
  task: string;
  method: string;
  result: "数据一致" | "发现差异";
  differences: number;
  duration: string;
};

export type PageState = { page: number; pageSize: number };

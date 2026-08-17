export type AppPage =
  | "dashboard"
  | "institutions"
  | "systemInstances"
  | "datasources"
  | "datasets"
  | "routes"
  | "tasks"
  | "taskDetail"
  | "precheck"
  | "precheckRouteDetail"
  | "precheckRunDetail"
  | "monitor"
  | "executionDetail"
  | "validationOverview"
  | "validationWorkbench"
  | "alerts"
  | "logs"
  | "audit"
  | "globalSettings"
  | "registrySettings"
  | "validationPolicy"
  | "dorisTables"
  | "externalApi"
  | "mappingRules"
  | "security"
  | "docs";

export type EnabledStatus = "ENABLED" | "DISABLED";
export type TestStatus = "UNTESTED" | "SUCCESS" | "PARTIAL" | "FAILED";
export type SystemType = "HIS" | "LIS" | "PACS" | "EMR" | "OTHER";

export type Institution = {
  id: string;
  code: string;
  name: string;
  type: string;
  level: string;
  region: string;
  status: EnabledStatus;
  description: string;
};

export type BusinessSystemInstance = {
  id: string;
  code: string;
  name: string;
  systemType: SystemType;
  vendor: string;
  productVersion: string;
  institutionIds: string[];
  datasourceIds: string[];
  status: EnabledStatus;
  description: string;
  updatedAt: string;
};

export type SourceDataSource = {
  id: string;
  code: string;
  name: string;
  dbType: "POSTGRESQL" | "MYSQL" | "ORACLE" | "SQLSERVER";
  connectionMode: "HOST_PORT" | "JDBC_URL";
  host: string;
  port: string;
  database: string;
  defaultSchema: string;
  jdbcUrl: string;
  username: string;
  sslEnabled: boolean;
  readOnly: boolean;
  queryTimeoutSeconds: number;
  connectTimeoutSeconds: number;
  socketTimeoutSeconds: number;
  poolMaxSize: number;
  status: EnabledStatus;
  testStatus: Exclude<TestStatus, "PARTIAL">;
  lastTestedAt: string;
  lastTestError: string;
  description: string;
};

export type TargetEndpoint = {
  id: string;
  host: string;
  queryPort: string;
  httpPort: string;
  enabled: boolean;
  ordinal: number;
  testStatus: Exclude<TestStatus, "PARTIAL">;
  lastTestedAt: string;
  lastTestError: string;
};

export type TargetDataSource = {
  id: string;
  code: string;
  name: string;
  database: string;
  username: string;
  status: EnabledStatus;
  testStatus: TestStatus;
  lastTestedAt: string;
  lastTestError: string;
  endpoints: TargetEndpoint[];
  description: string;
};

export type Dataset = {
  id: string;
  externalId: string;
  code: string;
  name: string;
  category: string;
  status: "ACTIVE" | "VOID";
  version: number;
  fieldCount: number;
  businessKeyCount: number;
  incrementalField: string | null;
  validationOverride: "INHERIT" | "ROW_COUNT" | "ROW_COUNT_CHECKSUM";
  scheduleDefault: string;
  messageEnabled: boolean;
  lastSyncResult: "CREATED" | "UPDATED" | "UNCHANGED" | "REACTIVATED" | "VOIDED" | "FAILED";
  lastSyncedAt: string;
};

export type RouteRow = {
  id: string;
  datasetCode: string;
  datasetName: string;
  datasetVersion: number;
  systemInstanceId: string;
  systemInstanceName: string;
  sourceId: string;
  sourceCode: string;
  sourceName: string;
  schema: string;
  object: string;
  objectType: "TABLE" | "VIEW" | "MATERIALIZED_VIEW";
  targetId: string;
  targetCode: string;
  targetName: string;
  institutionIds: string[];
  version: number;
  contractHash: string;
  lastPrecheckRunId: string | null;
  lastPrecheckResult: "PASS" | "ISSUES" | null;
  deletedAt: string | null;
};

export type TaskVersion = {
  id: string;
  versionNo: number;
  routeId: string;
  routeVersion: number;
  datasetVersion: number;
  taskKind: "FULL_ONLY" | "FULL_THEN_INCREMENTAL";
  writeMode: "REPLACE_INSTITUTION_SCOPE" | "UPSERT";
  keyModel: "DUPLICATE_KEY" | "UNIQUE_KEY";
  incrementalField: string | null;
  fetchSize: number;
  upperBoundDelayMinutes: number;
  lookbackSeconds: number;
  scheduleMode: "MANUAL" | "EVERY_N_HOURS" | "CRON";
  scheduleIntervalHours: number | null;
  scheduleCron: string | null;
  scheduleTimezone: string;
  scheduleSource: "GLOBAL" | "DATASET" | "TASK";
  scheduleLabel: string;
  validationOverride: "INHERIT" | "ROW_COUNT" | "ROW_COUNT_CHECKSUM";
  createdAt: string;
  createdBy: string;
  changeSummary: string;
};

export type TaskRow = {
  id: string;
  name: string;
  institutionId: string;
  institutionCode: string;
  institutionName: string;
  datasetCode: string;
  datasetName: string;
  currentVersionId: string;
  versions: TaskVersion[];
  scheduleEnabled: boolean;
  watermark: string | null;
  deletedAt: string | null;
};

export type ExecutionRow = {
  id: string;
  taskId: string;
  taskVersionId: string;
  taskVersionNo: number;
  taskName: string;
  institutionName: string;
  datasetCode: string;
  operation: "NORMAL" | "RECOLLECT" | "BACKFILL";
  trigger: "SCHEDULED" | "MANUAL" | "EXTERNAL_API";
  scope: "FULL" | "INITIAL_FULL" | "INCREMENTAL" | "BACKFILL_TIME" | "BACKFILL_KEY";
  status: "PENDING" | "RUNNING" | "LOADING" | "VALIDATING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "STATE_UNKNOWN";
  range: string;
  sourceRows: number;
  loadedRows: number;
  rejectedRows: number;
  startedAt: string;
  finishedAt: string;
};

export type PrecheckRun = {
  id: string;
  routeId: string;
  routeVersion: number;
  datasetVersion: number;
  status: "PENDING" | "EXTRACTING" | "VALIDATING" | "COMPLETED" | "FAILED" | "CANCELLED";
  result: "PASS" | "ISSUES" | null;
  extractedRows: number;
  checkedRows: number;
  problemRecordCount: number;
  problemItemCount: number;
  affectedInstitutionCount: number;
  retentionStatus: "AVAILABLE" | "EXPIRING" | "CLEANING" | "EXPIRED" | "CLEAN_FAILED";
  detailExpiresAt: string;
  startedAt: string;
  finishedAt: string;
  startedBy: string;
  failureReason: string;
};

export type PrecheckIssueSummary = {
  id: string;
  runId: string;
  institutionId: string;
  institutionCode: string;
  institutionName: string;
  scope: "STRUCTURE" | "FIELD" | "COMPOSITE";
  fieldCode: string;
  fieldName: string;
  ruleCode: string;
  ruleVersion: string;
  checkedCount: number;
  affectedRecordCount: number;
  problemItemCount: number;
  deviation: string;
};

export type PrecheckIssueItem = {
  id: string;
  scope: "FIELD" | "COMPOSITE";
  fieldCodes: string[];
  fieldNames: string[];
  ruleCode: string;
  ruleVersion: string;
  maskedValue: string;
  rawValue: string;
  expected: string;
  reason: string;
  deviation: string;
  sensitive: boolean;
};

export type PrecheckIssueRecord = {
  id: string;
  runId: string;
  institutionId: string;
  institutionCode: string;
  institutionName: string;
  locatorType: "BUSINESS_KEY" | "RUN_SCOPED";
  locator: string;
  problemFieldCount: number;
  problemItemCount: number;
  sensitive: boolean;
  checkedAt: string;
  items: PrecheckIssueItem[];
};

export type ValidationRow = {
  id: string;
  taskId: string;
  executionId: string | null;
  institutionName: string;
  datasetCode: string;
  scope: "SYNC_WINDOW" | "FULL_DATASET" | "CHANGE_WINDOW" | "DELETE_RECONCILIATION";
  trigger: "SYNC_GATE" | "MANUAL" | "MANUAL_RECHECK" | "SCHEDULED";
  method: "ROW_COUNT" | "ROW_COUNT_CHECKSUM" | "DELETE_KEY_DIFF";
  source: "GLOBAL" | "DATASET" | "TASK" | "CONTRACT" | "FIXED";
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  result: "PASS" | "MISMATCH" | null;
  sourceRows: number | null;
  targetRows: number | null;
  differenceCount: number | null;
  startedAt: string;
};

export type AlertEvent = {
  id: string;
  severity: "INFO" | "WARNING" | "CRITICAL";
  title: string;
  source: string;
  status: "PENDING" | "SENDING" | "SUCCEEDED" | "FAILED";
  time: string;
};

export type AuditRow = {
  id: string;
  actor: string;
  source: "WEB" | "EXTERNAL_API" | "SCHEDULER" | "SYSTEM";
  permissionCode: string;
  operation: string;
  target: string;
  result: "SUCCESS" | "FAILED";
  detail: string;
  time: string;
};

export type ExternalClient = {
  id: string;
  clientId: string;
  clientName: string;
  authorizationMode: "ALL" | "SELECTED";
  institutions: string[];
  enabled: boolean;
};

export type AccountRow = {
  id: string;
  username: string;
  displayName: string;
  enabled: boolean;
  roleIds: string[];
  lastLoginAt: string;
  createdAt: string;
};

export type RoleRow = {
  id: string;
  code: string;
  name: string;
  permissions: string[];
  accountCount: number;
  builtIn: boolean;
};

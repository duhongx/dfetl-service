export type AppPage =
  | "dashboard"
  | "institutions"
  | "businessCatalogs"
  | "sourceDatasources"
  | "targetDatasources"
  | "datasets"
  | "routes"
  | "tasks"
  | "taskDetail"
  | "precheck"
  | "precheckDetail"
  | "monitor"
  | "executionDetail"
  | "validationOverview"
  | "validationWorkbench"
  | "alerts"
  | "logs"
  | "audit"
  | "globalSettings"
  | "registrySettings"
  | "externalApi"
  | "mappingRules"
  | "accounts"
  | "docs";

export type EnabledStatus = "ENABLED" | "DISABLED";
export type TestStatus = "UNTESTED" | "SUCCESS" | "PARTIAL" | "FAILED";

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

export type BusinessCatalog = {
  id: string;
  code: string;
  name: string;
  description: string;
  status: EnabledStatus;
};

export type SourceDataSource = {
  id: string;
  code: string;
  name: string;
  institutionCode: string;
  institutionName: string;
  businessCatalogCode: string;
  businessCatalogName: string;
  dbType: "POSTGRESQL" | "MYSQL" | "ORACLE" | "SQLSERVER";
  connectionMode: "HOST_PORT" | "JDBC_URL";
  host: string;
  port: string;
  database: string;
  defaultSchema: string;
  jdbcUrl: string;
  username: string;
  status: EnabledStatus;
  testStatus: Exclude<TestStatus, "PARTIAL">;
  lastTestedAt: string;
};

export type TargetEndpoint = {
  id: string;
  host: string;
  queryPort: string;
  httpPort: string;
  enabled: boolean;
  ordinal: number;
  testStatus: Exclude<TestStatus, "PARTIAL">;
};

export type TargetDataSource = {
  id: string;
  code: string;
  name: string;
  database: string;
  username: string;
  status: EnabledStatus;
  testStatus: TestStatus;
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
  institutionCode: string;
  institutionName: string;
  datasetCode: string;
  datasetName: string;
  datasetVersion: number;
  sourceCode: string;
  sourceName: string;
  businessCatalog: string;
  schema: string;
  object: string;
  objectType: "TABLE" | "VIEW" | "MATERIALIZED_VIEW";
  targetCode: string;
  targetName: string;
  version: number;
  status: EnabledStatus;
  structureStatus: "NOT_CHECKED" | "PASSED" | "FAILED" | "OUTDATED";
  structureCheckedAt: string;
};

export type TaskRow = {
  id: string;
  name: string;
  institutionCode: string;
  institutionName: string;
  datasetCode: string;
  datasetName: string;
  datasetVersion: number;
  routeId: string;
  routeVersion: number;
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
  scheduleEnabled: boolean;
  validationOverride: "INHERIT" | "ROW_COUNT" | "ROW_COUNT_CHECKSUM";
  watermark: string | null;
  state: "READY" | "RUNNING" | "FAILED" | "DISABLED";
};

export type ExecutionRow = {
  id: string;
  taskId: string;
  taskName: string;
  institutionName: string;
  datasetCode: string;
  operation: "NORMAL" | "RECOLLECT" | "BACKFILL";
  trigger: "SCHEDULED" | "MANUAL" | "EXTERNAL_API";
  scope: "FULL" | "INITIAL_FULL" | "INCREMENTAL" | "BACKFILL_TIME" | "BACKFILL_KEY";
  status: "PENDING" | "RUNNING" | "LOADING" | "VALIDATING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  range: string;
  sourceRows: number;
  loadedRows: number;
  rejectedRows: number;
  startedAt: string;
  finishedAt: string;
};

export type PrecheckRow = {
  id: string;
  routeId: string;
  institutionName: string;
  datasetCode: string;
  datasetName: string;
  status: "PENDING" | "EXTRACTING" | "VALIDATING" | "COMPLETED" | "FAILED" | "CANCELLED";
  result: "PASS" | "ISSUES" | null;
  issues: number;
  startedAt: string;
  finishedAt: string;
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
  operation: string;
  target: string;
  result: "SUCCESS" | "FAILED";
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
  lastLoginAt: string;
  createdAt: string;
};

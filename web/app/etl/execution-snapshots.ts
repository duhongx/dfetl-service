export type ExecutionSnapshotView = {
  taskRevision: number;
  institutionCode: string;
  datasetVersion: number;
  routeVersion: number;
  taskKind: string;
  writeMode: string;
  keyModel: string;
  incrementalField: string | null;
  fetchSize: number;
  upperBoundDelayMinutes: number;
  lookbackSeconds: number;
  validationMethod: "ROW_COUNT" | "ROW_COUNT_CHECKSUM";
  validationSource: "GLOBAL" | "DATASET" | "TASK" | "CONTRACT";
  checksumProtocol: string | null;
  sourceRuntime: {
    datasourceId: string;
    revision: number;
    dbType: string;
    connectionMode: "HOST_PORT" | "JDBC_URL";
    host: string;
    port: number | null;
    database: string;
    jdbcUrl: string | null;
    username: string;
    sslEnabled: boolean;
    readOnly: boolean;
    queryTimeoutSeconds: number;
    connectTimeoutSeconds: number;
    socketTimeoutSeconds: number;
  };
  targetRuntime: {
    datasourceId: string;
    revision: number;
    database: string;
    username: string;
    feEndpoints: Array<{ host: string; queryPort: number; httpPort: number; ordinalNo: number }>;
  };
  messagePolicy: {
    enabled: boolean;
    revision: number;
    sourceSystem: string;
    tenantId: string;
    routingKey: string;
    topic: string;
    keyTemplate: string;
    rateLimitPerSecond: number;
    pageSize: number;
  };
};

const sourceRuntime = (revision: number): ExecutionSnapshotView["sourceRuntime"] => ({
  datasourceId: "S01",
  revision,
  dbType: "POSTGRESQL",
  connectionMode: "HOST_PORT",
  host: "192.168.1.154",
  port: 5432,
  database: "df_his",
  jdbcUrl: null,
  username: "df_reader",
  sslEnabled: false,
  readOnly: true,
  queryTimeoutSeconds: 60,
  connectTimeoutSeconds: 10,
  socketTimeoutSeconds: 60,
});

const targetRuntime: ExecutionSnapshotView["targetRuntime"] = {
  datasourceId: "T01",
  revision: 4,
  database: "df_ygt",
  username: "df_load",
  feEndpoints: [
    { host: "192.168.1.41", queryPort: 9030, httpPort: 8030, ordinalNo: 1 },
    { host: "192.168.1.42", queryPort: 9030, httpPort: 8030, ordinalNo: 2 },
  ],
};

const disabledMessagePolicy: ExecutionSnapshotView["messagePolicy"] = {
  enabled: false,
  revision: 3,
  sourceSystem: "DFETL",
  tenantId: "YL",
  routingKey: "",
  topic: "",
  keyTemplate: "${institutionCode}:${businessKey}",
  rateLimitPerSecond: 1000,
  pageSize: 1000,
};

const patientMessagePolicy: ExecutionSnapshotView["messagePolicy"] = {
  enabled: true,
  revision: 6,
  sourceSystem: "DFETL",
  tenantId: "YL",
  routingKey: "YL_HUANZHEJBXX",
  topic: "ODS_YL_HUANZHEJBXX",
  keyTemplate: "${institutionCode}:${businessKey}",
  rateLimitPerSecond: 1000,
  pageSize: 1000,
};

export const executionSnapshotSeed: Record<string, ExecutionSnapshotView> = {
  "EXE-260817-001": {
    taskRevision: 12,
    institutionCode: "330106001",
    datasetVersion: 5,
    routeVersion: 5,
    taskKind: "FULL_THEN_INCREMENTAL",
    writeMode: "UPSERT",
    keyModel: "UNIQUE_KEY",
    incrementalField: "XIUGAISJ",
    fetchSize: 5000,
    upperBoundDelayMinutes: 5,
    lookbackSeconds: 0,
    validationMethod: "ROW_COUNT",
    validationSource: "TASK",
    checksumProtocol: null,
    sourceRuntime: sourceRuntime(8),
    targetRuntime,
    messagePolicy: disabledMessagePolicy,
  },
  "EXE-260817-002": {
    taskRevision: 19,
    institutionCode: "330106001",
    datasetVersion: 3,
    routeVersion: 7,
    taskKind: "FULL_THEN_INCREMENTAL",
    writeMode: "UPSERT",
    keyModel: "UNIQUE_KEY",
    incrementalField: "XIUGAISJ",
    fetchSize: 5000,
    upperBoundDelayMinutes: 5,
    lookbackSeconds: 0,
    validationMethod: "ROW_COUNT_CHECKSUM",
    validationSource: "DATASET",
    checksumProtocol: "DFETL-CHECKSUM-V1",
    sourceRuntime: sourceRuntime(7),
    targetRuntime,
    messagePolicy: patientMessagePolicy,
  },
  "EXE-260816-006": {
    taskRevision: 18,
    institutionCode: "330106001",
    datasetVersion: 3,
    routeVersion: 7,
    taskKind: "FULL_THEN_INCREMENTAL",
    writeMode: "UPSERT",
    keyModel: "UNIQUE_KEY",
    incrementalField: "XIUGAISJ",
    fetchSize: 5000,
    upperBoundDelayMinutes: 5,
    lookbackSeconds: 0,
    validationMethod: "ROW_COUNT_CHECKSUM",
    validationSource: "DATASET",
    checksumProtocol: "DFETL-CHECKSUM-V1",
    sourceRuntime: sourceRuntime(7),
    targetRuntime,
    messagePolicy: patientMessagePolicy,
  },
  "EXE-260816-007": {
    taskRevision: 11,
    institutionCode: "330106001",
    datasetVersion: 5,
    routeVersion: 5,
    taskKind: "FULL_THEN_INCREMENTAL",
    writeMode: "UPSERT",
    keyModel: "UNIQUE_KEY",
    incrementalField: "XIUGAISJ",
    fetchSize: 5000,
    upperBoundDelayMinutes: 5,
    lookbackSeconds: 0,
    validationMethod: "ROW_COUNT",
    validationSource: "TASK",
    checksumProtocol: null,
    sourceRuntime: sourceRuntime(7),
    targetRuntime,
    messagePolicy: disabledMessagePolicy,
  },
};

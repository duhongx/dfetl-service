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
    endpoint: string;
    database: string;
    username: string;
  };
  targetRuntime: {
    datasourceId: string;
    revision: number;
    database: string;
    username: string;
    feEndpoints: string[];
  };
  messagePolicy: {
    enabled: boolean;
    revision: number;
    routingKey: string;
    pageSize: number;
  };
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
    sourceRuntime: { datasourceId: "S01", revision: 8, dbType: "POSTGRESQL", endpoint: "192.168.1.154:5432", database: "df_his", username: "df_reader" },
    targetRuntime: { datasourceId: "T01", revision: 4, database: "df_ygt", username: "df_load", feEndpoints: ["192.168.1.41:9030", "192.168.1.42:9030"] },
    messagePolicy: { enabled: false, revision: 3, routingKey: "", pageSize: 1000 },
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
    sourceRuntime: { datasourceId: "S01", revision: 7, dbType: "POSTGRESQL", endpoint: "192.168.1.154:5432", database: "df_his", username: "df_reader" },
    targetRuntime: { datasourceId: "T01", revision: 4, database: "df_ygt", username: "df_load", feEndpoints: ["192.168.1.41:9030", "192.168.1.42:9030"] },
    messagePolicy: { enabled: true, revision: 6, routingKey: "YL_HUANZHEJBXX", pageSize: 1000 },
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
    sourceRuntime: { datasourceId: "S01", revision: 7, dbType: "POSTGRESQL", endpoint: "192.168.1.154:5432", database: "df_his", username: "df_reader" },
    targetRuntime: { datasourceId: "T01", revision: 4, database: "df_ygt", username: "df_load", feEndpoints: ["192.168.1.41:9030", "192.168.1.42:9030"] },
    messagePolicy: { enabled: true, revision: 6, routingKey: "YL_HUANZHEJBXX", pageSize: 1000 },
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
    sourceRuntime: { datasourceId: "S01", revision: 7, dbType: "POSTGRESQL", endpoint: "192.168.1.154:5432", database: "df_his", username: "df_reader" },
    targetRuntime: { datasourceId: "T01", revision: 4, database: "df_ygt", username: "df_load", feEndpoints: ["192.168.1.41:9030", "192.168.1.42:9030"] },
    messagePolicy: { enabled: false, revision: 3, routingKey: "", pageSize: 1000 },
  },
};

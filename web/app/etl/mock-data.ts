import type {
  AccountRow,
  AlertEvent,
  AuditRow,
  BusinessSystemInstance,
  Dataset,
  ExecutionRow,
  ExternalClient,
  Institution,
  PrecheckIssueRecord,
  PrecheckIssueSummary,
  PrecheckRun,
  RoleRow,
  RouteRow,
  SourceDataSource,
  TargetDataSource,
  TaskRow,
  ValidationRow,
} from "./model";

export const institutionsSeed: Institution[] = [
  { id: "I001", code: "330106001", name: "县人民医院", type: "综合医院", level: "三级", region: "城区", status: "ENABLED", description: "医共体牵头医院" },
  { id: "I002", code: "330106002", name: "县中医院", type: "中医医院", level: "二级", region: "城区", status: "ENABLED", description: "中医医疗机构" },
  { id: "I003", code: "330106003", name: "县妇幼保健院", type: "妇幼保健院", level: "二级", region: "城区", status: "ENABLED", description: "妇幼保健机构" },
  { id: "I004", code: "330106101", name: "城关镇卫生院", type: "乡镇卫生院", level: "一级", region: "城关镇", status: "ENABLED", description: "基层医疗机构" },
  { id: "I005", code: "330106102", name: "河东镇卫生院", type: "乡镇卫生院", level: "一级", region: "河东镇", status: "DISABLED", description: "已暂停新任务" },
];

export const sourceSeed: SourceDataSource[] = [
  { id: "S01", code: "SRC_REGION_HIS", name: "区域 HIS 生产库", dbType: "POSTGRESQL", connectionMode: "HOST_PORT", host: "192.168.1.154", port: "5432", database: "df_his", defaultSchema: "df_zhushuju", jdbcUrl: "", username: "df_reader", sslEnabled: false, readOnly: true, queryTimeoutSeconds: 60, connectTimeoutSeconds: 10, socketTimeoutSeconds: 60, poolMaxSize: 4, status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:12", lastTestError: "", description: "区域 HIS 生产只读连接" },
  { id: "S02", code: "SRC_ZYY_HIS", name: "中医院 HIS 主库", dbType: "ORACLE", connectionMode: "HOST_PORT", host: "192.168.1.20", port: "1521", database: "HIS", defaultSchema: "HIS_ZYY", jdbcUrl: "", username: "DFETL", sslEnabled: false, readOnly: true, queryTimeoutSeconds: 60, connectTimeoutSeconds: 10, socketTimeoutSeconds: 60, poolMaxSize: 4, status: "ENABLED", testStatus: "FAILED", lastTestedAt: "2026-08-17 09:04", lastTestError: "连接超时：192.168.1.20:1521", description: "中医院 HIS 生产只读连接" },
  { id: "S03", code: "SRC_RMYY_LIS", name: "人民医院 LIS", dbType: "MYSQL", connectionMode: "JDBC_URL", host: "", port: "", database: "", defaultSchema: "lis", jdbcUrl: "jdbc:mysql://192.168.1.160:3306/lis", username: "df_reader", sslEnabled: false, readOnly: true, queryTimeoutSeconds: 60, connectTimeoutSeconds: 10, socketTimeoutSeconds: 60, poolMaxSize: 4, status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 08:58", lastTestError: "", description: "人民医院 LIS 生产只读连接" },
  { id: "S04", code: "SRC_FY_HIS", name: "妇幼 HIS 主库", dbType: "SQLSERVER", connectionMode: "HOST_PORT", host: "192.168.1.30", port: "1433", database: "his_fy", defaultSchema: "dbo", jdbcUrl: "", username: "df_reader", sslEnabled: false, readOnly: true, queryTimeoutSeconds: 60, connectTimeoutSeconds: 10, socketTimeoutSeconds: 60, poolMaxSize: 4, status: "DISABLED", testStatus: "UNTESTED", lastTestedAt: "—", lastTestError: "", description: "妇幼 HIS 只读连接" },
  { id: "S05", code: "SRC_REGION_PACS", name: "区域 PACS 报告库", dbType: "POSTGRESQL", connectionMode: "HOST_PORT", host: "192.168.1.170", port: "5432", database: "pacs_report", defaultSchema: "public", jdbcUrl: "", username: "df_reader", sslEnabled: true, readOnly: true, queryTimeoutSeconds: 90, connectTimeoutSeconds: 10, socketTimeoutSeconds: 90, poolMaxSize: 3, status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:10", lastTestError: "", description: "区域影像报告只读连接" },
];

export const systemInstanceSeed: BusinessSystemInstance[] = [
  { id: "SI01", code: "SYS_REGION_HIS", name: "区域一体化 HIS", systemType: "HIS", vendor: "东软", productVersion: "V6.2", institutionIds: ["I001", "I004", "I005"], datasourceIds: ["S01"], status: "ENABLED", description: "覆盖人民医院及基层机构的共享 HIS", updatedAt: "2026-08-17 09:30" },
  { id: "SI02", code: "SYS_ZYY_HIS", name: "县中医院 HIS", systemType: "HIS", vendor: "卫宁健康", productVersion: "V5.8", institutionIds: ["I002", "I005"], datasourceIds: ["S02"], status: "ENABLED", description: "中医院独立 HIS，兼顾河东镇卫生院", updatedAt: "2026-08-17 09:26" },
  { id: "SI03", code: "SYS_RMYY_LIS", name: "县人民医院 LIS", systemType: "LIS", vendor: "金域", productVersion: "2025.3", institutionIds: ["I001"], datasourceIds: ["S03"], status: "ENABLED", description: "人民医院检验系统", updatedAt: "2026-08-17 09:18" },
  { id: "SI04", code: "SYS_FY_HIS", name: "县妇幼保健院 HIS", systemType: "HIS", vendor: "创业慧康", productVersion: "V4.9", institutionIds: ["I003"], datasourceIds: ["S04"], status: "DISABLED", description: "妇幼保健院独立 HIS", updatedAt: "2026-08-16 16:40" },
  { id: "SI05", code: "SYS_REGION_PACS", name: "区域 PACS", systemType: "PACS", vendor: "联影", productVersion: "V3.1", institutionIds: ["I001", "I002", "I003"], datasourceIds: ["S05"], status: "ENABLED", description: "医共体共享影像报告系统", updatedAt: "2026-08-17 08:50" },
];

export const targetSeed: TargetDataSource[] = [
  { id: "T01", code: "DORIS_PROD", name: "医共体 Doris 生产集群", database: "df_ygt", username: "df_load", status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:18", lastTestError: "", description: "生产 ODS/RAW 目标", endpoints: [
    { id: "FE01", host: "192.168.1.41", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 1, testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:18", lastTestError: "" },
    { id: "FE02", host: "192.168.1.42", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 2, testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:18", lastTestError: "" },
  ] },
  { id: "T02", code: "DORIS_DR", name: "Doris 灾备集群", database: "df_ygt", username: "df_load", status: "DISABLED", testStatus: "PARTIAL", lastTestedAt: "2026-08-16 14:22", lastTestError: "FE #2 连接失败：192.168.2.42:9030", description: "灾备演练使用", endpoints: [
    { id: "FE03", host: "192.168.2.41", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 1, testStatus: "SUCCESS", lastTestedAt: "2026-08-16 14:22", lastTestError: "" },
    { id: "FE04", host: "192.168.2.42", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 2, testStatus: "FAILED", lastTestedAt: "2026-08-16 14:22", lastTestError: "连接超时" },
  ] },
];

export const datasetSeed: Dataset[] = [
  { id: "D01", externalId: "10001", code: "ODS_YL_HUANZHEJBXX", name: "患者基本信息", category: "基础信息", status: "ACTIVE", version: 3, fieldCount: 42, businessKeyCount: 2, incrementalField: "XIUGAISJ", validationOverride: "ROW_COUNT_CHECKSUM", scheduleDefault: "每 4 小时", messageEnabled: true, lastSyncResult: "UNCHANGED", lastSyncedAt: "2026-08-17 08:40" },
  { id: "D02", externalId: "10002", code: "ODS_YL_KESHIXX", name: "科室信息", category: "基础字典", status: "ACTIVE", version: 2, fieldCount: 18, businessKeyCount: 2, incrementalField: "XIUGAISJ", validationOverride: "INHERIT", scheduleDefault: "每 4 小时", messageEnabled: true, lastSyncResult: "UPDATED", lastSyncedAt: "2026-08-17 08:40" },
  { id: "D03", externalId: "10003", code: "ODS_YL_ZHIGONGXX", name: "职工信息", category: "基础字典", status: "ACTIVE", version: 4, fieldCount: 27, businessKeyCount: 2, incrementalField: "XIUGAISJ", validationOverride: "INHERIT", scheduleDefault: "每 4 小时", messageEnabled: true, lastSyncResult: "UNCHANGED", lastSyncedAt: "2026-08-17 08:40" },
  { id: "D04", externalId: "12017", code: "ODS_YL_APP_JIANCHASQD", name: "检查申请单", category: "检查检验", status: "ACTIVE", version: 5, fieldCount: 38, businessKeyCount: 2, incrementalField: "XIUGAISJ", validationOverride: "ROW_COUNT", scheduleDefault: "CRON 0 15 */4 * * ?", messageEnabled: false, lastSyncResult: "UPDATED", lastSyncedAt: "2026-08-17 08:40" },
  { id: "D05", externalId: "13008", code: "ODS_YL_BCCHUYUANJL", name: "出院记录", category: "病案病历", status: "ACTIVE", version: 2, fieldCount: 49, businessKeyCount: 0, incrementalField: null, validationOverride: "INHERIT", scheduleDefault: "人工", messageEnabled: false, lastSyncResult: "UNCHANGED", lastSyncedAt: "2026-08-17 08:40" },
];

export const routeSeed: RouteRow[] = [
  { id: "R001", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, systemInstanceId: "SI01", systemInstanceName: "区域一体化 HIS", sourceId: "S01", sourceCode: "SRC_REGION_HIS", sourceName: "区域 HIS 生产库", schema: "df_zhushuju", object: "v_yl_huanzhejbxx", objectType: "VIEW", targetId: "T01", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", institutionIds: ["I001", "I004"], version: 7, contractHash: "sha256:a41d9f6c", lastPrecheckRunId: "PRE-260817-002", lastPrecheckResult: "PASS", deletedAt: null },
  { id: "R002", datasetCode: "ODS_YL_KESHIXX", datasetName: "科室信息", datasetVersion: 2, systemInstanceId: "SI01", systemInstanceName: "区域一体化 HIS", sourceId: "S01", sourceCode: "SRC_REGION_HIS", sourceName: "区域 HIS 生产库", schema: "df_zhushuju", object: "v_yl_keshixx", objectType: "VIEW", targetId: "T01", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", institutionIds: ["I001", "I004"], version: 4, contractHash: "sha256:15fe34bd", lastPrecheckRunId: "PRE-260816-004", lastPrecheckResult: "ISSUES", deletedAt: null },
  { id: "R003", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, systemInstanceId: "SI02", systemInstanceName: "县中医院 HIS", sourceId: "S02", sourceCode: "SRC_ZYY_HIS", sourceName: "中医院 HIS 主库", schema: "HIS_ZYY", object: "V_YL_HUANZHEJBXX", objectType: "VIEW", targetId: "T01", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", institutionIds: ["I002", "I005"], version: 3, contractHash: "sha256:be82cc7d", lastPrecheckRunId: "PRE-260817-003", lastPrecheckResult: null, deletedAt: null },
  { id: "R004", datasetCode: "ODS_YL_APP_JIANCHASQD", datasetName: "检查申请单", datasetVersion: 5, systemInstanceId: "SI01", systemInstanceName: "区域一体化 HIS", sourceId: "S01", sourceCode: "SRC_REGION_HIS", sourceName: "区域 HIS 生产库", schema: "df_zhushuju", object: "v_yl_jianchasqd", objectType: "VIEW", targetId: "T01", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", institutionIds: ["I001", "I004"], version: 5, contractHash: "sha256:30da4cb1", lastPrecheckRunId: "PRE-260817-005", lastPrecheckResult: "ISSUES", deletedAt: null },
  { id: "R005", datasetCode: "ODS_YL_BCCHUYUANJL", datasetName: "出院记录", datasetVersion: 2, systemInstanceId: "SI04", systemInstanceName: "县妇幼保健院 HIS", sourceId: "S04", sourceCode: "SRC_FY_HIS", sourceName: "妇幼 HIS 主库", schema: "dbo", object: "V_YL_BCCHUYUANJL", objectType: "VIEW", targetId: "T01", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", institutionIds: ["I003"], version: 2, contractHash: "sha256:2bb8376f", lastPrecheckRunId: null, lastPrecheckResult: null, deletedAt: null },
];

export const taskSeed: TaskRow[] = [
  {
    id: "TASK-1001", name: "县人民医院-患者基本信息", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", currentVersionId: "TV-1001-2", scheduleEnabled: true, watermark: "2026-08-17 08:17:00", deletedAt: null,
    versions: [
      { id: "TV-1001-1", versionNo: 1, routeId: "R001", routeVersion: 6, datasetVersion: 2, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 3000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "EVERY_N_HOURS", scheduleIntervalHours: 4, scheduleCron: "0 17 0/4 * * ?", scheduleTimezone: "Asia/Shanghai", scheduleSource: "DATASET", scheduleLabel: "每 4 小时 · 00:17 起", validationOverride: "INHERIT", createdAt: "2026-08-12 10:10", createdBy: "admin", changeSummary: "初始版本" },
      { id: "TV-1001-2", versionNo: 2, routeId: "R001", routeVersion: 7, datasetVersion: 3, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 5000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "EVERY_N_HOURS", scheduleIntervalHours: 4, scheduleCron: "0 17 0/4 * * ?", scheduleTimezone: "Asia/Shanghai", scheduleSource: "DATASET", scheduleLabel: "每 4 小时 · 00:17 起", validationOverride: "INHERIT", createdAt: "2026-08-16 15:20", createdBy: "admin", changeSummary: "应用 Dataset V3 与 Route V7，Fetch Size 调整为 5000" },
    ],
  },
  {
    id: "TASK-1002", name: "县人民医院-检查申请单", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", datasetName: "检查申请单", currentVersionId: "TV-1002-1", scheduleEnabled: true, watermark: "2026-08-17 08:15:00", deletedAt: null,
    versions: [
      { id: "TV-1002-1", versionNo: 1, routeId: "R004", routeVersion: 5, datasetVersion: 5, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 5000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "CRON", scheduleIntervalHours: null, scheduleCron: "0 15 */4 * * ?", scheduleTimezone: "Asia/Shanghai", scheduleSource: "DATASET", scheduleLabel: "0 15 */4 * * ?", validationOverride: "ROW_COUNT", createdAt: "2026-08-15 12:00", createdBy: "admin", changeSummary: "初始版本" },
    ],
  },
  {
    id: "TASK-1003", name: "县妇幼保健院-出院记录", institutionId: "I003", institutionCode: "330106003", institutionName: "县妇幼保健院", datasetCode: "ODS_YL_BCCHUYUANJL", datasetName: "出院记录", currentVersionId: "TV-1003-1", scheduleEnabled: false, watermark: null, deletedAt: null,
    versions: [
      { id: "TV-1003-1", versionNo: 1, routeId: "R005", routeVersion: 2, datasetVersion: 2, taskKind: "FULL_ONLY", writeMode: "REPLACE_INSTITUTION_SCOPE", keyModel: "DUPLICATE_KEY", incrementalField: null, fetchSize: 3000, upperBoundDelayMinutes: 0, lookbackSeconds: 0, scheduleMode: "MANUAL", scheduleIntervalHours: null, scheduleCron: null, scheduleTimezone: "Asia/Shanghai", scheduleSource: "TASK", scheduleLabel: "人工", validationOverride: "ROW_COUNT", createdAt: "2026-08-16 09:00", createdBy: "admin", changeSummary: "无主键任务：每次全量、替换当前机构范围" },
    ],
  },
];

export const executionSeed: ExecutionRow[] = [
  { id: "EXE-260817-001", taskId: "TASK-1002", taskVersionId: "TV-1002-1", taskVersionNo: 1, taskName: "县人民医院-检查申请单", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", operation: "NORMAL", trigger: "SCHEDULED", scope: "INCREMENTAL", status: "LOADING", range: "[08:15,12:15)", sourceRows: 12000, loadedRows: 6000, rejectedRows: 0, startedAt: "2026-08-17 12:15:02", finishedAt: "—" },
  { id: "EXE-260817-002", taskId: "TASK-1001", taskVersionId: "TV-1001-2", taskVersionNo: 2, taskName: "县人民医院-患者基本信息", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", operation: "NORMAL", trigger: "SCHEDULED", scope: "INCREMENTAL", status: "SUCCEEDED", range: "[04:17,08:17)", sourceRows: 3258, loadedRows: 3258, rejectedRows: 0, startedAt: "2026-08-17 08:17:02", finishedAt: "2026-08-17 08:18:16" },
  { id: "EXE-260816-006", taskId: "TASK-1001", taskVersionId: "TV-1001-1", taskVersionNo: 1, taskName: "县人民医院-患者基本信息", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", operation: "RECOLLECT", trigger: "MANUAL", scope: "FULL", status: "FAILED", range: "FULL", sourceRows: 91412, loadedRows: 91400, rejectedRows: 12, startedAt: "2026-08-16 17:24:10", finishedAt: "2026-08-16 17:29:42" },
  { id: "EXE-260816-007", taskId: "TASK-1002", taskVersionId: "TV-1002-1", taskVersionNo: 1, taskName: "县人民医院-检查申请单", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", operation: "BACKFILL", trigger: "MANUAL", scope: "BACKFILL_TIME", status: "SUCCEEDED", range: "[2026-08-01 00:00,12:00)", sourceRows: 820, loadedRows: 820, rejectedRows: 0, startedAt: "2026-08-16 15:00:00", finishedAt: "2026-08-16 15:03:32" },
];

export const precheckRunSeed: PrecheckRun[] = [
  { id: "PRE-260817-002", routeId: "R001", routeVersion: 7, datasetVersion: 3, status: "COMPLETED", result: "PASS", extractedRows: 425100, checkedRows: 425100, problemRecordCount: 0, problemItemCount: 0, affectedInstitutionCount: 0, retentionStatus: "AVAILABLE", detailExpiresAt: "2026-08-24 08:30", startedAt: "2026-08-17 08:20", finishedAt: "2026-08-17 08:30", startedBy: "admin", failureReason: "" },
  { id: "PRE-260816-001", routeId: "R001", routeVersion: 7, datasetVersion: 3, status: "COMPLETED", result: "ISSUES", extractedRows: 423900, checkedRows: 423900, problemRecordCount: 6, problemItemCount: 9, affectedInstitutionCount: 2, retentionStatus: "EXPIRING", detailExpiresAt: "2026-08-18 08:30", startedAt: "2026-08-16 08:15", finishedAt: "2026-08-16 08:28", startedBy: "operator", failureReason: "" },
  { id: "PRE-260816-004", routeId: "R002", routeVersion: 4, datasetVersion: 2, status: "COMPLETED", result: "ISSUES", extractedRows: 18420, checkedRows: 18420, problemRecordCount: 4, problemItemCount: 6, affectedInstitutionCount: 1, retentionStatus: "AVAILABLE", detailExpiresAt: "2026-08-23 17:45", startedAt: "2026-08-16 17:30", finishedAt: "2026-08-16 17:45", startedBy: "operator", failureReason: "" },
  { id: "PRE-260817-003", routeId: "R003", routeVersion: 3, datasetVersion: 3, status: "FAILED", result: null, extractedRows: 0, checkedRows: 0, problemRecordCount: 0, problemItemCount: 0, affectedInstitutionCount: 0, retentionStatus: "AVAILABLE", detailExpiresAt: "—", startedAt: "2026-08-17 09:00", finishedAt: "2026-08-17 09:07", startedBy: "admin", failureReason: "源数据库连接超时" },
  { id: "PRE-260817-005", routeId: "R004", routeVersion: 5, datasetVersion: 5, status: "COMPLETED", result: "ISSUES", extractedRows: 182030, checkedRows: 182030, problemRecordCount: 3, problemItemCount: 5, affectedInstitutionCount: 1, retentionStatus: "AVAILABLE", detailExpiresAt: "2026-08-24 09:22", startedAt: "2026-08-17 09:10", finishedAt: "2026-08-17 09:22", startedBy: "admin", failureReason: "" },
];

export const precheckSummarySeed: PrecheckIssueSummary[] = [
  { id: "PS-001", runId: "PRE-260816-001", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", scope: "FIELD", fieldCode: "SHENFENZH", fieldName: "身份证号", ruleCode: "MAX_LENGTH", ruleVersion: "v3", checkedCount: 320000, affectedRecordCount: 2, problemItemCount: 2, deviation: "最大长度 20，要求不超过 18" },
  { id: "PS-002", runId: "PRE-260816-001", institutionId: "I004", institutionCode: "330106101", institutionName: "城关镇卫生院", scope: "FIELD", fieldCode: "CHUSHENGRQ", fieldName: "出生日期", ruleCode: "DATE_FORMAT", ruleVersion: "v2", checkedCount: 103900, affectedRecordCount: 3, problemItemCount: 3, deviation: "存在无效月份或日期" },
  { id: "PS-003", runId: "PRE-260816-001", institutionId: "I004", institutionCode: "330106101", institutionName: "城关镇卫生院", scope: "COMPOSITE", fieldCode: "ORG_CODE+HUANZHEID", fieldName: "联合业务主键", ruleCode: "BUSINESS_KEY_UNIQUE", ruleVersion: "v1", checkedCount: 103900, affectedRecordCount: 1, problemItemCount: 4, deviation: "同一联合业务主键重复 4 次" },
  { id: "PS-004", runId: "PRE-260816-004", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", scope: "FIELD", fieldCode: "KESHIID", fieldName: "科室编码", ruleCode: "NOT_NULL", ruleVersion: "v1", checkedCount: 18420, affectedRecordCount: 4, problemItemCount: 4, deviation: "字段为空" },
  { id: "PS-005", runId: "PRE-260816-004", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", scope: "COMPOSITE", fieldCode: "ORG_CODE+KESHIID", fieldName: "联合业务主键", ruleCode: "BUSINESS_KEY_UNIQUE", ruleVersion: "v1", checkedCount: 18420, affectedRecordCount: 2, problemItemCount: 2, deviation: "联合业务主键重复" },
  { id: "PS-006", runId: "PRE-260817-005", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", scope: "FIELD", fieldCode: "JIANCHAXMID", fieldName: "检查项目编码", ruleCode: "NOT_NULL", ruleVersion: "v1", checkedCount: 182030, affectedRecordCount: 3, problemItemCount: 3, deviation: "字段为空" },
  { id: "PS-007", runId: "PRE-260817-005", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", scope: "COMPOSITE", fieldCode: "ORG_CODE+SHENQINGDID", fieldName: "联合业务主键", ruleCode: "BUSINESS_KEY_UNIQUE", ruleVersion: "v1", checkedCount: 182030, affectedRecordCount: 2, problemItemCount: 2, deviation: "联合业务主键重复" },
];

export const precheckIssueRecordSeed: PrecheckIssueRecord[] = [
  {
    id: "PIR-001", runId: "PRE-260816-001", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", locatorType: "BUSINESS_KEY", locator: "ORG_CODE=330106001 / HUANZHEID=10002341", problemFieldCount: 2, problemItemCount: 2, sensitive: true, checkedAt: "2026-08-16 08:20:11",
    items: [
      { id: "PI-001", scope: "FIELD", fieldCodes: ["SHENFENZH"], fieldNames: ["身份证号"], ruleCode: "MAX_LENGTH", ruleVersion: "v3", maskedValue: "320************123456", rawValue: "32010619890101123456", expected: "长度不超过 18", reason: "实际长度为 20", deviation: "+2", sensitive: true },
      { id: "PI-002", scope: "FIELD", fieldCodes: ["CHUSHENGRQ"], fieldNames: ["出生日期"], ruleCode: "DATE_FORMAT", ruleVersion: "v2", maskedValue: "2026-13-40", rawValue: "2026-13-40", expected: "合法 DATE", reason: "月份和日期超出有效范围", deviation: "invalid date", sensitive: false },
    ],
  },
  {
    id: "PIR-002", runId: "PRE-260816-001", institutionId: "I004", institutionCode: "330106101", institutionName: "城关镇卫生院", locatorType: "RUN_SCOPED", locator: "RUN_ROW=00018420 / FP=8b79…2ae1", problemFieldCount: 1, problemItemCount: 4, sensitive: false, checkedAt: "2026-08-16 08:23:05",
    items: [
      { id: "PI-003", scope: "COMPOSITE", fieldCodes: ["ORG_CODE", "HUANZHEID"], fieldNames: ["机构代码", "患者编号"], ruleCode: "BUSINESS_KEY_UNIQUE", ruleVersion: "v1", maskedValue: "330106101 / 9000188", rawValue: "330106101 / 9000188", expected: "联合业务主键唯一", reason: "同一键在本次运行中出现 4 次", deviation: "duplicate x4", sensitive: false },
    ],
  },
  {
    id: "PIR-003", runId: "PRE-260816-004", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", locatorType: "BUSINESS_KEY", locator: "ORG_CODE=330106001 / KESHIID=<NULL>", problemFieldCount: 1, problemItemCount: 1, sensitive: false, checkedAt: "2026-08-16 17:36:20",
    items: [
      { id: "PI-004", scope: "FIELD", fieldCodes: ["KESHIID"], fieldNames: ["科室编码"], ruleCode: "NOT_NULL", ruleVersion: "v1", maskedValue: "<NULL>", rawValue: "<NULL>", expected: "非空", reason: "标准字段不能为空", deviation: "NULL", sensitive: false },
    ],
  },
  {
    id: "PIR-004", runId: "PRE-260817-005", institutionId: "I001", institutionCode: "330106001", institutionName: "县人民医院", locatorType: "BUSINESS_KEY", locator: "ORG_CODE=330106001 / SHENQINGDID=SQD-77801", problemFieldCount: 1, problemItemCount: 2, sensitive: false, checkedAt: "2026-08-17 09:16:42",
    items: [
      { id: "PI-005", scope: "FIELD", fieldCodes: ["JIANCHAXMID"], fieldNames: ["检查项目编码"], ruleCode: "NOT_NULL", ruleVersion: "v1", maskedValue: "<NULL>", rawValue: "<NULL>", expected: "非空", reason: "标准字段不能为空", deviation: "NULL", sensitive: false },
      { id: "PI-006", scope: "COMPOSITE", fieldCodes: ["ORG_CODE", "SHENQINGDID"], fieldNames: ["机构代码", "申请单编号"], ruleCode: "BUSINESS_KEY_UNIQUE", ruleVersion: "v1", maskedValue: "330106001 / SQD-77801", rawValue: "330106001 / SQD-77801", expected: "联合业务主键唯一", reason: "同一键重复 2 次", deviation: "duplicate x2", sensitive: false },
    ],
  },
];

export const validationSeed: ValidationRow[] = [
  { id: "VAL-260817-001", taskId: "TASK-1001", executionId: "EXE-260817-002", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "SYNC_WINDOW", trigger: "SYNC_GATE", method: "ROW_COUNT_CHECKSUM", source: "DATASET", status: "COMPLETED", result: "PASS", sourceRows: 3258, targetRows: 3258, differenceCount: 0, startedAt: "2026-08-17 08:17:45" },
  { id: "VAL-260817-002", taskId: "TASK-1002", executionId: "EXE-260817-001", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", scope: "SYNC_WINDOW", trigger: "SYNC_GATE", method: "ROW_COUNT", source: "TASK", status: "PENDING", result: null, sourceRows: null, targetRows: null, differenceCount: null, startedAt: "—" },
  { id: "VAL-260816-009", taskId: "TASK-1001", executionId: null, institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "FULL_DATASET", trigger: "MANUAL", method: "ROW_COUNT_CHECKSUM", source: "DATASET", status: "COMPLETED", result: "MISMATCH", sourceRows: 425100, targetRows: 425073, differenceCount: 27, startedAt: "2026-08-16 17:20:00" },
  { id: "VAL-DEL-001", taskId: "TASK-1001", executionId: null, institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "DELETE_RECONCILIATION", trigger: "MANUAL", method: "DELETE_KEY_DIFF", source: "FIXED", status: "COMPLETED", result: "MISMATCH", sourceRows: 425073, targetRows: 425100, differenceCount: 27, startedAt: "2026-08-16 09:00:00" },
];

export const alertSeed: AlertEvent[] = [
  { id: "ALT-001", severity: "CRITICAL", title: "中医院 HIS 数据源连接失败", source: "SRC_ZYY_HIS", status: "FAILED", time: "2026-08-17 09:04" },
  { id: "ALT-002", severity: "WARNING", title: "患者基本信息全量校验发现 27 条差异", source: "VAL-260816-009", status: "SUCCEEDED", time: "2026-08-16 17:29" },
];

export const auditSeed: AuditRow[] = [
  { id: "AUD-001", actor: "admin", source: "WEB", permissionCode: "sync_task.run", operation: "SYNC_TASK_MANUAL_RUN", target: "TASK-1002 / TV-1002-1", result: "SUCCESS", detail: "创建 EXE-260817-001", time: "2026-08-17 12:15:02" },
  { id: "AUD-002", actor: "operator", source: "WEB", permissionCode: "precheck.run", operation: "PRECHECK_RUN_CREATE", target: "R002", result: "SUCCESS", detail: "创建 PRE-260816-004", time: "2026-08-16 17:30:00" },
  { id: "AUD-003", actor: "admin", source: "WEB", permissionCode: "system_instance.bind_institution", operation: "SYSTEM_INSTANCE_INSTITUTIONS_UPDATE", target: "SI01", result: "SUCCESS", detail: "覆盖机构 2 → 3", time: "2026-08-16 10:20:00" },
];

export const externalClientSeed: ExternalClient[] = [
  { id: "EC01", clientId: "regional-platform", clientName: "区域数据平台", authorizationMode: "ALL", institutions: [], enabled: true },
  { id: "EC02", clientId: "county-audit", clientName: "县级质控平台", authorizationMode: "SELECTED", institutions: ["330106001", "330106002"], enabled: true },
];

export const roleSeed: RoleRow[] = [
  { id: "ROLE_ADMIN", code: "ADMIN", name: "系统管理员", permissions: ["*"], accountCount: 1, builtIn: true },
  { id: "ROLE_OPERATOR", code: "OPERATOR", name: "数据运维", permissions: [
    "dashboard.view", "institution.view", "system_instance.view", "datasource.view", "datasource.test", "dataset.view", "route.view",
    "sync_task.view", "sync_task.run", "sync_task.recollect", "sync_task.backfill", "sync_task.schedule", "sync_execution.view", "sync_execution.cancel",
    "precheck.view", "precheck.run", "precheck.summary.view", "precheck.summary.export", "precheck.detail.view", "precheck.detail.export",
    "validation.view", "validation.run", "validation.recheck", "alert.view", "log.view", "audit.view",
  ], accountCount: 1, builtIn: true },
  { id: "ROLE_AUDITOR", code: "AUDITOR", name: "审计查看", permissions: ["dashboard.view", "sync_task.view", "sync_execution.view", "precheck.view", "precheck.summary.view", "validation.view", "alert.view", "log.view", "audit.view", "audit.export"], accountCount: 1, builtIn: true },
];

export const accountSeed: AccountRow[] = [
  { id: "U01", username: "admin", displayName: "系统管理员", enabled: true, roleIds: ["ROLE_ADMIN"], lastLoginAt: "2026-08-17 09:00", createdAt: "2026-08-01 09:00" },
  { id: "U02", username: "operator", displayName: "数据运维", enabled: true, roleIds: ["ROLE_OPERATOR"], lastLoginAt: "2026-08-17 08:45", createdAt: "2026-08-02 10:00" },
  { id: "U03", username: "auditor", displayName: "审计员", enabled: true, roleIds: ["ROLE_AUDITOR"], lastLoginAt: "2026-08-16 16:20", createdAt: "2026-08-03 11:00" },
];

export const precheckSeed = precheckRunSeed;

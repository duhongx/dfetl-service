import type {
  AccountRow,
  AlertEvent,
  AuditRow,
  BusinessCatalog,
  Dataset,
  ExecutionRow,
  ExternalClient,
  Institution,
  PrecheckRow,
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

export const businessCatalogSeed: BusinessCatalog[] = [
  { id: "BC01", code: "HIS", name: "医院信息系统", description: "门诊、住院、收费、医嘱等核心业务", status: "ENABLED" },
  { id: "BC02", code: "LIS", name: "检验信息系统", description: "检验申请、标本、结果等", status: "ENABLED" },
  { id: "BC03", code: "PACS", name: "医学影像系统", description: "影像申请、检查、报告等", status: "ENABLED" },
  { id: "BC04", code: "EMR", name: "电子病历系统", description: "病历、病程、出院记录等", status: "DISABLED" },
];

export const sourceSeed: SourceDataSource[] = [
  { id: "S01", code: "SRC_RMYY_HIS", name: "县人民医院 HIS 主库", institutionCode: "330106001", institutionName: "县人民医院", businessCatalogCode: "HIS", businessCatalogName: "医院信息系统", dbType: "POSTGRESQL", connectionMode: "HOST_PORT", host: "192.168.1.154", port: "5432", database: "df_his", defaultSchema: "df_zhushuju", jdbcUrl: "", username: "df_reader", status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 09:12" },
  { id: "S02", code: "SRC_ZYY_HIS", name: "县中医院 HIS 主库", institutionCode: "330106002", institutionName: "县中医院", businessCatalogCode: "HIS", businessCatalogName: "医院信息系统", dbType: "ORACLE", connectionMode: "HOST_PORT", host: "192.168.1.20", port: "1521", database: "HIS", defaultSchema: "HIS_ZYY", jdbcUrl: "", username: "DFETL", status: "ENABLED", testStatus: "FAILED", lastTestedAt: "2026-08-17 09:04" },
  { id: "S03", code: "SRC_RMYY_LIS", name: "县人民医院 LIS", institutionCode: "330106001", institutionName: "县人民医院", businessCatalogCode: "LIS", businessCatalogName: "检验信息系统", dbType: "MYSQL", connectionMode: "JDBC_URL", host: "", port: "", database: "", defaultSchema: "lis", jdbcUrl: "jdbc:mysql://192.168.1.160:3306/lis", username: "df_reader", status: "ENABLED", testStatus: "SUCCESS", lastTestedAt: "2026-08-17 08:58" },
  { id: "S04", code: "SRC_FY_HIS", name: "县妇幼保健院 HIS", institutionCode: "330106003", institutionName: "县妇幼保健院", businessCatalogCode: "HIS", businessCatalogName: "医院信息系统", dbType: "SQLSERVER", connectionMode: "HOST_PORT", host: "192.168.1.30", port: "1433", database: "his_fy", defaultSchema: "dbo", jdbcUrl: "", username: "df_reader", status: "DISABLED", testStatus: "UNTESTED", lastTestedAt: "—" },
];

export const targetSeed: TargetDataSource[] = [
  { id: "T01", code: "DORIS_PROD", name: "医共体 Doris 生产集群", database: "df_ygt", username: "df_load", status: "ENABLED", testStatus: "SUCCESS", description: "生产 ODS/RAW 目标", endpoints: [
    { id: "FE01", host: "192.168.1.41", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 1, testStatus: "SUCCESS" },
    { id: "FE02", host: "192.168.1.42", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 2, testStatus: "SUCCESS" },
  ] },
  { id: "T02", code: "DORIS_DR", name: "Doris 灾备集群", database: "df_ygt", username: "df_load", status: "DISABLED", testStatus: "PARTIAL", description: "灾备演练使用", endpoints: [
    { id: "FE03", host: "192.168.2.41", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 1, testStatus: "SUCCESS" },
    { id: "FE04", host: "192.168.2.42", queryPort: "9030", httpPort: "8030", enabled: true, ordinal: 2, testStatus: "FAILED" },
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
  { id: "R001", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, sourceCode: "SRC_RMYY_HIS", sourceName: "县人民医院 HIS 主库", businessCatalog: "HIS", schema: "df_zhushuju", object: "v_yl_huanzhejbxx", objectType: "VIEW", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", version: 7, status: "ENABLED", structureStatus: "PASSED", structureCheckedAt: "2026-08-17 09:20" },
  { id: "R002", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_KESHIXX", datasetName: "科室信息", datasetVersion: 2, sourceCode: "SRC_RMYY_HIS", sourceName: "县人民医院 HIS 主库", businessCatalog: "HIS", schema: "df_zhushuju", object: "v_yl_keshixx", objectType: "VIEW", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", version: 4, status: "DISABLED", structureStatus: "OUTDATED", structureCheckedAt: "2026-08-16 17:45" },
  { id: "R003", institutionCode: "330106002", institutionName: "县中医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, sourceCode: "SRC_ZYY_HIS", sourceName: "县中医院 HIS 主库", businessCatalog: "HIS", schema: "HIS_ZYY", object: "V_YL_HUANZHEJBXX", objectType: "VIEW", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", version: 3, status: "DISABLED", structureStatus: "FAILED", structureCheckedAt: "2026-08-17 09:07" },
  { id: "R004", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", datasetName: "检查申请单", datasetVersion: 5, sourceCode: "SRC_RMYY_HIS", sourceName: "县人民医院 HIS 主库", businessCatalog: "HIS", schema: "df_zhushuju", object: "v_yl_jianchasqd", objectType: "VIEW", targetCode: "DORIS_PROD", targetName: "医共体 Doris 生产集群", version: 5, status: "ENABLED", structureStatus: "PASSED", structureCheckedAt: "2026-08-17 09:22" },
];

export const taskSeed: TaskRow[] = [
  { id: "TASK-1001", name: "县人民医院-患者基本信息", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, routeId: "R001", routeVersion: 7, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 5000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "EVERY_N_HOURS", scheduleIntervalHours: 4, scheduleCron: "0 17 0/4 * * ?", scheduleTimezone: "Asia/Shanghai", scheduleSource: "DATASET", scheduleLabel: "每 4 小时 · 00:17 起", scheduleEnabled: true, validationOverride: "INHERIT", watermark: "2026-08-17 08:17:00", state: "READY" },
  { id: "TASK-1002", name: "县人民医院-检查申请单", institutionCode: "330106001", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", datasetName: "检查申请单", datasetVersion: 5, routeId: "R004", routeVersion: 5, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 5000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "CRON", scheduleIntervalHours: null, scheduleCron: "0 15 */4 * * ?", scheduleTimezone: "Asia/Shanghai", scheduleSource: "DATASET", scheduleLabel: "0 15 */4 * * ?", scheduleEnabled: true, validationOverride: "ROW_COUNT", watermark: "2026-08-17 08:15:00", state: "RUNNING" },
  { id: "TASK-1003", name: "县中医院-患者基本信息", institutionCode: "330106002", institutionName: "县中医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", datasetVersion: 3, routeId: "R003", routeVersion: 3, taskKind: "FULL_THEN_INCREMENTAL", writeMode: "UPSERT", keyModel: "UNIQUE_KEY", incrementalField: "XIUGAISJ", fetchSize: 5000, upperBoundDelayMinutes: 5, lookbackSeconds: 0, scheduleMode: "MANUAL", scheduleIntervalHours: null, scheduleCron: null, scheduleTimezone: "Asia/Shanghai", scheduleSource: "TASK", scheduleLabel: "人工", scheduleEnabled: false, validationOverride: "ROW_COUNT_CHECKSUM", watermark: null, state: "DISABLED" },
];

export const executionSeed: ExecutionRow[] = [
  { id: "EXE-260817-001", taskId: "TASK-1002", taskName: "县人民医院-检查申请单", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", operation: "NORMAL", trigger: "SCHEDULED", scope: "INCREMENTAL", status: "LOADING", range: "[08:15, 12:15)", sourceRows: 18420, loadedRows: 12000, rejectedRows: 0, startedAt: "2026-08-17 12:15:03", finishedAt: "—" },
  { id: "EXE-260817-002", taskId: "TASK-1001", taskName: "县人民医院-患者基本信息", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", operation: "NORMAL", trigger: "SCHEDULED", scope: "INCREMENTAL", status: "SUCCEEDED", range: "[04:17, 08:17)", sourceRows: 3258, loadedRows: 3258, rejectedRows: 0, startedAt: "2026-08-17 08:17:02", finishedAt: "2026-08-17 08:18:16" },
  { id: "EXE-260816-007", taskId: "TASK-1002", taskName: "县人民医院-检查申请单", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", operation: "BACKFILL", trigger: "MANUAL", scope: "BACKFILL_TIME", status: "SUCCEEDED", range: "2026-08-10 00:00 ~ 2026-08-11 00:00", sourceRows: 6422, loadedRows: 6422, rejectedRows: 0, startedAt: "2026-08-16 19:12:05", finishedAt: "2026-08-16 19:13:41" },
  { id: "EXE-260816-006", taskId: "TASK-1001", taskName: "县人民医院-患者基本信息", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", operation: "RECOLLECT", trigger: "MANUAL", scope: "FULL", status: "FAILED", range: "FULL", sourceRows: 128300, loadedRows: 91400, rejectedRows: 12, startedAt: "2026-08-16 17:24:11", finishedAt: "2026-08-16 17:29:34" },
];

export const precheckSeed: PrecheckRow[] = [
  { id: "PRE-260817-001", routeId: "R001", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", status: "COMPLETED", result: "PASS", issues: 0, startedAt: "2026-08-17 09:25", finishedAt: "2026-08-17 09:27" },
  { id: "PRE-260817-002", routeId: "R004", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", datasetName: "检查申请单", status: "COMPLETED", result: "ISSUES", issues: 4, startedAt: "2026-08-17 09:30", finishedAt: "2026-08-17 09:35" },
  { id: "PRE-260817-003", routeId: "R003", institutionName: "县中医院", datasetCode: "ODS_YL_HUANZHEJBXX", datasetName: "患者基本信息", status: "FAILED", result: null, issues: 0, startedAt: "2026-08-17 09:10", finishedAt: "2026-08-17 09:11" },
];

export const validationSeed: ValidationRow[] = [
  { id: "VAL-260817-001", taskId: "TASK-1001", executionId: "EXE-260817-002", institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "SYNC_WINDOW", trigger: "SYNC_GATE", method: "ROW_COUNT_CHECKSUM", source: "DATASET", status: "COMPLETED", result: "PASS", sourceRows: 3258, targetRows: 3258, differenceCount: 0, startedAt: "2026-08-17 08:18" },
  { id: "VAL-260816-009", taskId: "TASK-1002", executionId: "EXE-260816-006", institutionName: "县人民医院", datasetCode: "ODS_YL_APP_JIANCHASQD", scope: "SYNC_WINDOW", trigger: "SYNC_GATE", method: "ROW_COUNT", source: "TASK", status: "COMPLETED", result: "MISMATCH", sourceRows: 128300, targetRows: 91400, differenceCount: 36900, startedAt: "2026-08-16 17:29" },
  { id: "VAL-260816-008", taskId: "TASK-1001", executionId: null, institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "FULL_DATASET", trigger: "MANUAL", method: "ROW_COUNT_CHECKSUM", source: "DATASET", status: "COMPLETED", result: "PASS", sourceRows: 426821, targetRows: 426821, differenceCount: 0, startedAt: "2026-08-16 16:10" },
  { id: "VAL-DEL-001", taskId: "TASK-1001", executionId: null, institutionName: "县人民医院", datasetCode: "ODS_YL_HUANZHEJBXX", scope: "DELETE_RECONCILIATION", trigger: "SCHEDULED", method: "DELETE_KEY_DIFF", source: "FIXED", status: "COMPLETED", result: "MISMATCH", sourceRows: null, targetRows: null, differenceCount: 27, startedAt: "2026-08-16 02:00" },
];

export const alertSeed: AlertEvent[] = [
  { id: "ALERT-901", severity: "CRITICAL", title: "县中医院源端连接测试失败", source: "SRC_ZYY_HIS", status: "SUCCEEDED", time: "2026-08-17 09:04" },
  { id: "ALERT-902", severity: "WARNING", title: "检查申请单同步校验发现差异", source: "TASK-1002", status: "SUCCEEDED", time: "2026-08-16 17:29" },
  { id: "ALERT-903", severity: "INFO", title: "Doris 灾备 FE 部分不可达", source: "DORIS_DR", status: "FAILED", time: "2026-08-16 14:22" },
];

export const auditSeed: AuditRow[] = [
  { id: "AUD-001", actor: "admin", source: "WEB", operation: "ROUTE_STRUCTURE_CHECK", target: "R001", result: "SUCCESS", time: "2026-08-17 09:20:18" },
  { id: "AUD-002", actor: "admin", source: "WEB", operation: "TASK_UPDATE", target: "TASK-1002", result: "SUCCESS", time: "2026-08-17 09:15:42" },
  { id: "AUD-003", actor: "partner-his", source: "EXTERNAL_API", operation: "TASK_RUN", target: "TASK-1001", result: "FAILED", time: "2026-08-16 21:11:05" },
];

export const externalClientSeed: ExternalClient[] = [
  { id: "EC01", clientId: "partner-his", clientName: "HIS 自动化接入", authorizationMode: "SELECTED", institutions: ["县人民医院", "县中医院"], enabled: true },
  { id: "EC02", clientId: "ops-console", clientName: "运维自动化平台", authorizationMode: "ALL", institutions: [], enabled: true },
];

export const accountSeed: AccountRow[] = [
  { id: "U01", username: "admin", displayName: "系统管理员", enabled: true, lastLoginAt: "2026-08-17 12:33", createdAt: "2026-08-13 09:00" },
  { id: "U02", username: "ops", displayName: "运维管理员", enabled: true, lastLoginAt: "2026-08-16 18:20", createdAt: "2026-08-14 14:22" },
];

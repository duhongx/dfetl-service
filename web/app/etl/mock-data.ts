import type { DataLink, Dataset, Institution, MonitorRow, SourceDataSource, TargetDataSource, TaskRow, ValidationRow } from "./model";

export const datasets: Dataset[] = [
  { name: "检查申请单", code: "ODS_YL_APP_JIANCHASQD", category: "检查检验", fields: 38, primaryKeys: 2, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: false, rules: 1, passed: 1, exceptions: 0, syncState: "正常", updated: "2026-08-12 10:42:18" },
  { name: "预约检查记录", code: "ODS_YL_APP_JIANCHAYYJL", category: "检查检验", fields: 38, primaryKeys: 2, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: true, rules: 1, passed: 1, exceptions: 0, syncState: "正常", updated: "2026-08-12 10:42:21" },
  { name: "检验申请单", code: "ODS_YL_APP_JIANYANSQD", category: "检查检验", fields: 51, primaryKeys: 2, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: true, rules: 2, passed: 2, exceptions: 0, syncState: "正常", updated: "2026-08-12 10:42:24" },
  { name: "收费明细记录", code: "ODS_YL_APP_SHOUFEIMX", category: "费用结算", fields: 34, primaryKeys: 3, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: false, rules: 3, passed: 2, exceptions: 1, syncState: "异常", updated: "2026-08-12 10:42:29" },
  { name: "住院病程记录 出院记录", code: "ODS_YL_BCCHUYUANJL", category: "病案首页", fields: 49, primaryKeys: 2, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: false, rules: 1, passed: 1, exceptions: 0, syncState: "正常", updated: "2026-08-12 10:42:34" },
  { name: "病案首页手术", code: "ODS_YL_BINGANSYSS", category: "病案首页", fields: 35, primaryKeys: 2, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: false, rules: 1, passed: 1, exceptions: 0, syncState: "待配置", updated: "2026-08-12 10:42:38" },
  { name: "病案首页诊断", code: "ODS_YL_BINGANSYZD", category: "病案首页", fields: 24, primaryKeys: 3, strategy: "UPSERT · 全量后增量", schedule: "每 4 小时", scope: "继承全局", messageEnabled: true, rules: 1, passed: 1, exceptions: 0, syncState: "正常", updated: "2026-08-12 10:42:43" },
];

export const datasetFieldRows = [
  ["100", "YILIAOJGDM", "医疗机构代码", "AN..30", "VARCHAR(90)", "是", "是"], ["101", "YILIAOJGMC", "医疗机构名称", "AN..100", "VARCHAR(300)", "否", "是"],
  ["300", "RUCHUYJLLSH", "24小时内入出院记录流水号", "AN..100", "VARCHAR(300)", "是", "是"], ["500", "ZHUYUANJZLSH", "住院就诊流水号", "AN..50", "VARCHAR(150)", "否", "是"],
  ["600", "BINGANHAO", "病案号", "AN..40", "VARCHAR(120)", "否", "否"], ["700", "BINGRENID", "病人ID", "AN..64", "VARCHAR(192)", "否", "是"],
  ["800", "ZHENGJIANLX", "证件类型", "AN..20", "VARCHAR(60)", "否", "否"], ["900", "ZHENGJIANHM", "证件号码", "AN..20", "VARCHAR(60)", "否", "否"],
  ["1000", "XINGMING", "姓名", "A..50", "VARCHAR(150)", "否", "是"], ["1100", "XINGBIEDM", "性别代码", "AN..20", "VARCHAR(60)", "否", "否"],
  ["1200", "XINGBIEMC", "性别名称", "AN..30", "VARCHAR(90)", "否", "否"], ["1300", "CHUSHENGRQ", "出生日期", "DT15", "DATETIME(6)", "否", "否"],
  ["1400", "NIANLINGSUI", "年龄（岁）", "N3", "INT", "否", "否"], ["1500", "NIANLINGYUE", "年龄（月）", "N3", "INT", "否", "否"],
  ["1600", "NIANLINGRI", "年龄（日）", "N3", "INT", "否", "否"], ["1700", "NIANLINGXS", "年龄（小时）", "N3", "INT", "否", "否"],
  ["1800", "GUOJIDM", "国籍代码", "AN..20", "VARCHAR(60)", "否", "否"], ["1900", "GUOJIMC", "国籍名称", "AN..50", "VARCHAR(150)", "否", "否"],
  ["2000", "MINZUDM", "民族代码", "AN..20", "VARCHAR(60)", "否", "否"], ["2100", "MINZUMC", "民族名称", "AN..10", "VARCHAR(30)", "否", "否"],
];

export const institutionNames = ["县人民医院", "县中医院", "县妇幼保健院", "城关镇卫生院", "河东镇卫生院", "板桥镇卫生院", "双河镇卫生院", "新集镇卫生院", "龙泉镇卫生院", "永安镇卫生院", "大桥镇卫生院", "石门镇卫生院"];
export const institutions: Institution[] = [
  { name: "县人民医院", code: "YGT001", type: "医院", level: "三级", parent: "安溪县总医院", division: "凤城镇", system: "东软 HIS", source: "postgresql-rmyy", enabled: true },
  { name: "县中医院", code: "YGT002", type: "医院", level: "二级", parent: "安溪县总医院", division: "凤城镇", system: "东华 HIS", source: "oracle-zyy", enabled: true },
  { name: "县妇幼保健院", code: "YGT003", type: "妇幼保健院", level: "二级", parent: "安溪县总医院", division: "凤城镇", system: "创业 HIS", source: "sqlserver-fby", enabled: true },
  { name: "城关镇卫生院", code: "YGT101", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "城关镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "河东镇卫生院", code: "YGT102", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "河东镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "板桥镇卫生院", code: "YGT103", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "板桥镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: false },
  { name: "双河镇卫生院", code: "YGT104", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "双河镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "新集镇卫生院", code: "YGT105", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "新集镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "龙泉镇卫生院", code: "YGT106", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "龙泉镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "永安镇卫生院", code: "YGT107", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "永安镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "大桥镇卫生院", code: "YGT108", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "大桥镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
  { name: "石门镇卫生院", code: "YGT109", type: "乡镇卫生院", level: "一级", parent: "安溪县总医院", division: "石门镇", system: "卫宁基层 HIS", source: "mysql-jcyl", enabled: true },
];

export const sourceDataSourcesSeed: SourceDataSource[] = [
  { name: "postgresql-rmyy", type: "PostgreSQL", host: "192.168.1.154:5432", database: "his_rmyy", schema: "his_rmyy", username: "df_reader", password: "********", institutions: ["县人民医院"], enabled: true },
  { name: "oracle-zyy", type: "Oracle", host: "192.168.1.10:1521", database: "FREEPDB1", schema: "HIS_ZYY", username: "dfetl", password: "********", institutions: ["县中医院"], enabled: true },
  { name: "sqlserver-fby", type: "SQL Server", host: "192.168.1.10:1433", database: "his_fby", schema: "dbo", username: "df_reader", password: "********", institutions: ["县妇幼保健院"], enabled: true },
  { name: "mysql-jcyl", type: "MySQL", host: "192.168.1.10:3306", database: "jc_his", schema: "jc_his", username: "df_reader", password: "********", institutions: institutionNames.slice(3), enabled: true },
];

export const targetDataSourcesSeed: TargetDataSource[] = [
  { name: "ygt-doris", host: "192.168.1.10", fePort: "9030", httpPort: "8030", streamLoadPort: "8040", database: "df_ygt", writeDb: "df_ygt", username: "df_admin", password: "********", batchSize: "50000", writeConcurrency: "8", poolSize: "20", ssl: false, description: "医共体 Doris 目标集群", enabled: true },
  { name: "doris-ygt-test", host: "192.168.1.31", fePort: "9030", httpPort: "8030", streamLoadPort: "8040", database: "doris_ygt", writeDb: "doris_ygt", username: "df_admin", password: "********", batchSize: "20000", writeConcurrency: "4", poolSize: "10", ssl: false, description: "Doris 测试目标集群", enabled: false },
];

export const monitorSeed: MonitorRow[] = [
  { id: "MON-1418", taskId: "TASK-1418", name: "县人民医院-ODS_YL_APP_JIANCHASQD", batch: "20260812_120139_1418", start: "2026-08-12 12:01:39", duration: "3s", read: "0", written: "0", speed: "—", status: "失败" },
  { id: "MON-1419", taskId: "TASK-1419", name: "县中医院-ODS_YL_APP_JIANCHASQD", batch: "20260812_120139_1419", start: "2026-08-12 12:01:39", duration: "3s", read: "0", written: "0", speed: "—", status: "失败" },
  { id: "MON-1420", taskId: "TASK-1420", name: "县妇幼保健院-ODS_YL_APP_JIANCHASQD", batch: "20260812_120138_1420", start: "2026-08-12 12:01:38", duration: "3s", read: "0", written: "0", speed: "—", status: "失败" },
  { id: "MON-1421", taskId: "TASK-1421", name: "基层医疗共享-ODS_YL_APP_JIANCHASQD", batch: "20260812_115942_1421", start: "2026-08-12 11:59:42", duration: "1m 12s", read: "18,420", written: "18,420", speed: "2.1", status: "已完成" },
  { id: "MON-1422", taskId: "TASK-1422", name: "县人民医院-ODS_YL_APP_JIANCHAYYJL", batch: "20260812_115801_1422", start: "2026-08-12 11:58:01", duration: "2m 08s", read: "32,058", written: "31,992", speed: "1.8", status: "需核对" },
  { id: "MON-1423", taskId: "TASK-1423", name: "基层医疗共享-ODS_YL_APP_JIANCHAYYJL", batch: "20260812_115533_1423", start: "2026-08-12 11:55:33", duration: "—", read: "8,926", written: "8,214", speed: "2.4", status: "运行中" },
];

export const validationSeed: ValidationRow[] = [
  { id: "VAL-1417", taskId: "TASK-1418", name: "县人民医院-检查申请校验", task: "县人民医院-ODS_YL_APP_JIANCHASQD", method: "行数对比", result: "数据一致", differences: 0, duration: "10s" },
  { id: "VAL-1416", taskId: "TASK-1419", name: "县中医院-检查申请校验", task: "县中医院-ODS_YL_APP_JIANCHASQD", method: "行数对比", result: "数据一致", differences: 0, duration: "5s" },
  { id: "VAL-1409", taskId: "TASK-1420", name: "县妇幼保健院-检查申请校验", task: "县妇幼保健院-ODS_YL_APP_JIANCHASQD", method: "行数对比", result: "数据一致", differences: 0, duration: "4s" },
  { id: "VAL-1399", taskId: "TASK-1421", name: "基层医疗共享-收费校验", task: "基层医疗共享-ODS_YL_APP_SHOUFEIMX", method: "行数对比", result: "发现差异", differences: 136, duration: "8s" },
  { id: "VAL-1391", taskId: "TASK-1422", name: "县人民医院-预约检查校验", task: "县人民医院-ODS_YL_APP_JIANCHAYYJL", method: "行数对比", result: "数据一致", differences: 0, duration: "4s" },
  { id: "VAL-1386", taskId: "TASK-1423", name: "基层医疗共享-预约检查校验", task: "基层医疗共享-ODS_YL_APP_JIANCHAYYJL", method: "行数对比", result: "数据一致", differences: 0, duration: "4s" },
];

export function getLinks(item: Dataset): DataLink[] {
  const objectName = item.code.toLowerCase();
  return [
    { id: "link-rmyy", name: "县人民医院采集链路", vendor: "东软 HIS", source: "postgresql-rmyy", sourceType: `his_rmyy.${objectName}`, institutions: [institutionNames[0]], schedule: "每 4 小时", state: "正常", lastRun: "今天 12:18" },
    { id: "link-zyy", name: "县中医院采集链路", vendor: "东华 HIS", source: "oracle-zyy", sourceType: `his_zyy.${objectName}`, institutions: [institutionNames[1]], schedule: "每 4 小时", state: item.code === "ODS_YL_APP_JIANCHASQD" ? "异常" : "正常", lastRun: "今天 12:12" },
    { id: "link-fby", name: "县妇保院采集链路", vendor: "创业 HIS", source: "sqlserver-fby", sourceType: `dbo.${objectName}`, institutions: [institutionNames[2]], schedule: "每 4 小时", state: "正常", lastRun: "今天 12:07" },
    { id: "link-jc", name: "基层医疗共享采集链路", vendor: "卫宁基层 HIS", source: "mysql-jcyl", sourceType: `jc_his.${objectName}`, institutions: institutionNames.slice(3), schedule: "每 4 小时", state: "正常", lastRun: "今天 11:56" },
  ];
}

export function getTasks(): TaskRow[] {
  const pairs: Array<[number, number]> = [[0, 0], [0, 1], [0, 2], [0, 3], [1, 0], [1, 3], [2, 1]];
  return pairs.map(([datasetIndex, linkIndex], index) => {
    const dataset = datasets[datasetIndex]; const link = getLinks(dataset)[linkIndex];
    const state = index === 0 || link.state === "异常" ? "失败" : index === 5 ? "已停止" : "运行中";
    return { id: `TASK-${1418 + index}`, name: `${link.institutions.length > 1 ? "基层医疗共享" : link.institutions[0]}-${dataset.code}`, dataset, link, view: link.sourceType.split(".").pop() ?? dataset.code.toLowerCase(), state, recent: link.lastRun, successRate: state === "失败" ? "68%" : "99.6%" };
  });
}

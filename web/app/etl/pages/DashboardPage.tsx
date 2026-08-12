import { AlertOutlined, ApartmentOutlined, DatabaseOutlined, LinkOutlined, SafetyCertificateOutlined } from "@ant-design/icons";

type Props = {
  institutionCount: number;
  datasetCount: number;
  linkCount: number;
  failedLinks: number;
  validationIssues: number;
  taskSummary: { total: number; completed: number; running: number; failed: number; review: number };
  onOpenMonitor: () => void;
  onOpenPrecheck: () => void;
  onOpenValidation: () => void;
};

export function DashboardPage({ institutionCount, datasetCount, linkCount, failedLinks, validationIssues, taskSummary, onOpenMonitor, onOpenPrecheck, onOpenValidation }: Props) {
  const issueCount = failedLinks + validationIssues;
  const health = [
    ["县人民医院", "东软 HIS", "4 / 4", "正常"],
    ["县中医院", "东华 HIS", "3 / 4", failedLinks ? "异常" : "正常"],
    ["县妇幼保健院", "创业 HIS", "4 / 4", "正常"],
    ["基层医疗共享", "卫宁基层 HIS · 9 家机构", "4 / 4", "正常"],
  ];
  const trend = [42, 38, 51, 47, 64, 58, 72, 67, 82, 76, 91, 86];
  const availableLinks = Math.max(linkCount - failedLinks, 0);
  const successRate = taskSummary.total ? Math.round(taskSummary.completed / taskSummary.total * 100) : 100;
  return <>
    <section className="workspace-heading home-heading"><div><h1>运行概览</h1></div><div className="home-update"><span><i className="signal-dot" />系统运行正常</span><small>数据更新于 13:43:48</small></div></section>
    <section className="home-kpi-grid">
      <article><i className="home-kpi-icon blue"><ApartmentOutlined /></i><div><span>接入机构</span><strong>{institutionCount}</strong><p>全部机构正常接入</p></div></article>
      <article><i className="home-kpi-icon blue"><DatabaseOutlined /></i><div><span>标准数据集</span><strong>{datasetCount}</strong><p>来自医共体模型</p></div></article>
      <article><i className="home-kpi-icon green"><LinkOutlined /></i><div><span>采集链路</span><strong>{linkCount}</strong><p>{availableLinks} 可用 · {failedLinks} 异常</p></div></article>
      <article><i className="home-kpi-icon red"><AlertOutlined /></i><div><span>待处理异常</span><strong>{issueCount}</strong><p>{failedLinks} 个链路 · {validationIssues} 个校验问题</p></div></article>
    </section>
    <div className="home-main-grid">
      <section className="data-card home-trend-card"><div className="panel-title"><div><h2>今日数据同步</h2><span>每 2 小时汇总</span></div><div className="home-trend-total"><strong>5.28 亿行</strong><span>较昨日 +8.2%</span></div></div><div className="home-trend-chart" aria-label="今日数据同步趋势">{trend.map((value, index) => <div key={index}><i style={{ height: `${value}%` }} /><span>{index % 2 === 0 ? `${String(index * 2).padStart(2, "0")}:00` : ""}</span></div>)}</div></section>
      <section className="data-card home-run-card"><div className="panel-title"><div><h2>今日任务状态</h2><span>按运行记录汇总</span></div><b>成功率 {successRate}%</b></div><div className="home-run-ring"><div><strong>{taskSummary.total}</strong><span>任务总数</span></div></div><div className="home-run-list"><div><span><i className="legend done" />已完成</span><strong>{taskSummary.completed}</strong></div><div><span><i className="legend running" />运行中</span><strong>{taskSummary.running}</strong></div><div><span><i className="legend failed" />失败</span><strong>{taskSummary.failed}</strong></div><div><span><i className="legend review" />需核对</span><strong>{taskSummary.review}</strong></div></div></section>
    </div>
    <div className="home-lower-grid">
      <section className="data-card home-health-card"><div className="panel-title"><div><h2>机构接入状态</h2><span>按业务系统汇总</span></div><b>4 套业务系统</b></div><div className="home-health-list">{health.map(([name, vendor, coverage, state]) => <div key={name}><span className={`health-dot ${state === "异常" ? "bad" : ""}`} /><div><strong>{name}</strong><small>{vendor}</small></div><div className="health-progress"><span><i style={{ width: state === "异常" ? "75%" : "100%" }} /></span><b>{coverage}</b></div><em className={`status status-${state}`}>{state}</em></div>)}</div></section>
      <section className="data-card home-alert-card"><div className="panel-title"><div><h2>待处理异常</h2><span>按影响范围和发生时间排序</span></div><b>{issueCount} 项</b></div><div className="home-alert-list">{failedLinks > 0 && <button onClick={onOpenMonitor}><i className="risk-level high">紧急</i><div><strong>县中医院检查申请单链路失败</strong><small>连接源端超时 · 12:12</small></div></button>}<button onClick={onOpenPrecheck}><i className="risk-level medium">预检</i><div><strong>查看待运行与有问题的预检任务</strong><small>进入数据预检</small></div></button>{validationIssues > 0 && <button onClick={onOpenValidation}><i className="risk-level low"><SafetyCertificateOutlined /></i><div><strong>收费明细记录存在差异</strong><small>{validationIssues} 个校验任务待处理</small></div></button>}</div></section>
    </div>
  </>;
}

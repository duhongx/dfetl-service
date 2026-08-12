import { AlertOutlined, DatabaseOutlined, LinkOutlined, MonitorOutlined, RightOutlined, SafetyCertificateOutlined, SettingOutlined } from "@ant-design/icons";
import type { AppPage } from "../model";

export function DocumentationPage({ onNavigate }: { onNavigate: (page: AppPage) => void }) {
  const cards: Array<[React.ReactNode, string, string, AppPage]> = [[<DatabaseOutlined key="resource" />, "接入资源", "机构、数据源与标准数据集", "datasets"], [<LinkOutlined key="link" />, "采集链路", "源对象与字段映射", "datasets"], [<MonitorOutlined key="monitor" />, "任务运行", "数据同步、数据预检与运行监控", "monitor"], [<SafetyCertificateOutlined key="validation" />, "数据校验", "全量、修改与删除数据校验", "validationWorkbench"], [<AlertOutlined key="alert" />, "运维管理", "告警、日志和操作审计", "alerts"], [<SettingOutlined key="setting" />, "系统设置", "平台参数与类型映射", "globalSettings"]];
  return <><section className="page-heading"><div><h1>使用文档</h1><p>采集平台操作与配置手册</p></div></section><div className="docs-grid">{cards.map(([icon, name, desc, page]) => <button className="data-card doc-card" key={name} onClick={() => onNavigate(page)}>{icon}<div><strong>{name}</strong><span>{desc}</span></div><RightOutlined /></button>)}</div></>;
}

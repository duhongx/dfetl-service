"use client";

import { useState, type FormEvent } from "react";
import type { Dataset } from "./model";

export type DatasetPolicyValue = {
  validationOverride: "INHERIT" | "ROW_COUNT" | "ROW_COUNT_CHECKSUM";
  scheduleMode: "INHERIT" | "MANUAL" | "EVERY_N_HOURS" | "CRON";
  scheduleIntervalHours: string;
  scheduleCron: string;
  scheduleTimezone: string;
  messageEnabled: boolean;
  sourceSystem: string;
  tenantId: string;
  routingKey: string;
  topic: string;
  keyTemplate: string;
  rateLimitPerSecond: string;
  pageSize: string;
};

type Props = {
  dataset: Dataset | null;
  onClose: () => void;
  onSave: (datasetCode: string, value: DatasetPolicyValue) => void;
};

const messageDatasets = new Set(["ODS_YL_HUANZHEJBXX", "ODS_YL_KESHIXX", "ODS_YL_ZHIGONGXX"]);
const routingKeyByDataset: Record<string, string> = {
  ODS_YL_HUANZHEJBXX: "YL_HUANZHEJBXX",
  ODS_YL_KESHIXX: "YL_KESHIXX",
  ODS_YL_ZHIGONGXX: "YL_ZHIGONGXX",
};

function parseSchedule(dataset: Dataset) {
  const value = dataset.scheduleDefault;
  if (/手工|MANUAL/i.test(value)) return { mode: "MANUAL" as const, interval: "", cron: "", timezone: "" };
  const hour = value.match(/(\d+)\s*小时/);
  if (hour) return { mode: "EVERY_N_HOURS" as const, interval: hour[1], cron: "", timezone: "Asia/Shanghai" };
  if (/CRON/i.test(value)) return { mode: "CRON" as const, interval: "", cron: value.replace(/^CRON\s*[:：]?\s*/i, ""), timezone: "Asia/Shanghai" };
  return { mode: "INHERIT" as const, interval: "", cron: "", timezone: "" };
}

function positiveInteger(value: string) {
  if (!value.trim()) return false;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0;
}

export function DatasetPolicyEditor({ dataset, onClose, onSave }: Props) {
  const initialSchedule = dataset ? parseSchedule(dataset) : { mode: "INHERIT" as const, interval: "", cron: "", timezone: "" };
  const supportsMessage = dataset ? messageDatasets.has(dataset.code) : false;
  const [validationOverride, setValidationOverride] = useState<DatasetPolicyValue["validationOverride"]>(dataset?.validationOverride ?? "INHERIT");
  const [scheduleMode, setScheduleMode] = useState<DatasetPolicyValue["scheduleMode"]>(initialSchedule.mode);
  const [scheduleIntervalHours, setScheduleIntervalHours] = useState(initialSchedule.interval);
  const [scheduleCron, setScheduleCron] = useState(initialSchedule.cron);
  const [scheduleTimezone, setScheduleTimezone] = useState(initialSchedule.timezone);
  const [messageEnabled, setMessageEnabled] = useState(Boolean(dataset?.messageEnabled && supportsMessage));
  const [sourceSystem, setSourceSystem] = useState("DFETL");
  const [tenantId, setTenantId] = useState("YL");
  const [topic, setTopic] = useState(dataset?.code ?? "");
  const [keyTemplate, setKeyTemplate] = useState("${institutionCode}:${businessKey}");
  const [rateLimitPerSecond, setRateLimitPerSecond] = useState("1000");
  const [pageSize, setPageSize] = useState("1000");
  const [error, setError] = useState("");

  if (!dataset) return null;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setError("");
    if (validationOverride === "ROW_COUNT_CHECKSUM" && dataset.businessKeyCount === 0) return setError("无真实业务主键的数据集不能配置 ROW_COUNT_CHECKSUM。");
    if (scheduleMode === "EVERY_N_HOURS" && (!scheduleIntervalHours || !Number.isInteger(Number(scheduleIntervalHours)) || Number(scheduleIntervalHours) < 1 || Number(scheduleIntervalHours) > 8760)) return setError("EVERY_N_HOURS 的间隔必须是 1..8760 的整数小时。此处不保存最终错峰 Cron。");
    if (scheduleMode === "CRON" && !scheduleCron.trim()) return setError("CRON 模式必须填写 Cron 表达式。");
    if (["EVERY_N_HOURS", "CRON"].includes(scheduleMode) && !scheduleTimezone.trim()) return setError("EVERY_N_HOURS / CRON 必须填写 Timezone。");
    if (messageEnabled && !supportsMessage) return setError("P0 只有三个已确认 Dataset 允许开启消息发送。");
    if (messageEnabled && (!sourceSystem.trim() || !tenantId.trim() || !topic.trim() || !keyTemplate.trim() || !rateLimitPerSecond.trim() || !pageSize.trim())) return setError("消息启用时必须填写完整 Dataset 级消息参数。");
    if (messageEnabled && !positiveInteger(rateLimitPerSecond)) return setError("Rate Limit / s 必须是正整数。");
    if (messageEnabled && !positiveInteger(pageSize)) return setError("Page Size 必须是正整数。");
    onSave(dataset.code, {
      validationOverride,
      scheduleMode,
      scheduleIntervalHours: scheduleMode === "EVERY_N_HOURS" ? scheduleIntervalHours : "",
      scheduleCron: scheduleMode === "CRON" ? scheduleCron : "",
      scheduleTimezone: ["EVERY_N_HOURS", "CRON"].includes(scheduleMode) ? scheduleTimezone.trim() : "",
      messageEnabled,
      sourceSystem,
      tenantId,
      routingKey: routingKeyByDataset[dataset.code] ?? "",
      topic,
      keyTemplate,
      rateLimitPerSecond,
      pageSize,
    });
  };

  return <div className="modal-mask" role="presentation" onMouseDown={onClose}>
    <form className="modal editor-modal" onSubmit={submit} onMouseDown={(event) => event.stopPropagation()}>
      <header><div><h2>Dataset 当前管理配置</h2><p>{dataset.name} · {dataset.code} · 当前 Dataset Version V{dataset.version}</p></div><button type="button" onClick={onClose}>×</button></header>
      <div className="modal-body">
        <div className="editor-grid">
          <div className="editor-readonly"><span>Dataset Code</span><strong>{dataset.code}</strong><small>稳定业务身份，不能在此修改。</small></div>
          <label className="editor-field"><span>Validation Override</span><select value={validationOverride} onChange={(event) => setValidationOverride(event.target.value as DatasetPolicyValue["validationOverride"])}><option value="INHERIT">INHERIT</option><option value="ROW_COUNT">ROW_COUNT</option><option value="ROW_COUNT_CHECKSUM">ROW_COUNT_CHECKSUM</option></select><small>NULL/INHERIT → Global；无业务主键不能 Checksum。</small></label>
        </div>

        <h3 className="editor-subtitle">Dataset Sync Default（仅创建 Task 时读取）</h3>
        <div className="editor-grid">
          <label className="editor-field"><span>Schedule Mode</span><select value={scheduleMode} onChange={(event) => { setScheduleMode(event.target.value as DatasetPolicyValue["scheduleMode"]); setError(""); }}><option value="INHERIT">INHERIT</option><option value="MANUAL">MANUAL</option><option value="EVERY_N_HOURS">EVERY_N_HOURS</option><option value="CRON">CRON</option></select></label>
          {scheduleMode === "EVERY_N_HOURS" && <label className="editor-field"><span>Interval Hours *</span><input value={scheduleIntervalHours} onChange={(event) => setScheduleIntervalHours(event.target.value)} /><small>Dataset Default 只保存 interval + timezone，不保存最终错峰 Cron。</small></label>}
          {scheduleMode === "CRON" && <label className="editor-field"><span>Cron *</span><input value={scheduleCron} onChange={(event) => setScheduleCron(event.target.value)} /></label>}
          {["EVERY_N_HOURS", "CRON"].includes(scheduleMode) && <label className="editor-field"><span>Timezone *</span><input value={scheduleTimezone} onChange={(event) => setScheduleTimezone(event.target.value)} /></label>}
        </div>
        <div className="editor-note">Dataset Default 变化不自动修改已有 Task。EVERY_N_HOURS 的最终错峰 Cron 只在 Task 创建/编辑时生成并固化。</div>

        <h3 className="editor-subtitle">Dataset Message Policy</h3>
        {!supportsMessage ? <div className="notice">P0 当前只有 ODS_YL_HUANZHEJBXX / ODS_YL_KESHIXX / ODS_YL_ZHIGONGXX 三个 Dataset 允许开启 RabbitMQ 数据消息。本 Dataset 不提供消息开关。</div> : <>
          <label className="check-line"><input type="checkbox" checked={messageEnabled} onChange={(event) => setMessageEnabled(event.target.checked)} /><span>启用数据消息</span></label>
          {messageEnabled && <div className="editor-grid">
            <div className="editor-readonly"><span>Exchange</span><strong>YL</strong><small>固定，不允许 Task 覆盖。</small></div>
            <div className="editor-readonly"><span>Routing Key</span><strong>{routingKeyByDataset[dataset.code]}</strong><small>按已确认 Dataset 固定。</small></div>
            <label className="editor-field"><span>Source System *</span><input value={sourceSystem} onChange={(event) => setSourceSystem(event.target.value)} /></label>
            <label className="editor-field"><span>Tenant ID *</span><input value={tenantId} onChange={(event) => setTenantId(event.target.value)} /></label>
            <label className="editor-field"><span>Topic *</span><input value={topic} onChange={(event) => setTopic(event.target.value)} /></label>
            <label className="editor-field"><span>Key Template *</span><input value={keyTemplate} onChange={(event) => setKeyTemplate(event.target.value)} /></label>
            <label className="editor-field"><span>Rate Limit / s *</span><input value={rateLimitPerSecond} onChange={(event) => setRateLimitPerSecond(event.target.value)} /></label>
            <label className="editor-field"><span>Page Size *</span><input value={pageSize} onChange={(event) => setPageSize(event.target.value)} /></label>
          </div>}
          <div className="editor-note">消息只存在 Dataset 级；Task 不提供 Override。FULL 发送全量全部数据，INCREMENTAL/Backfill 发送本次范围全部数据，不支持 SKIP / NOTIFY_ONLY。</div>
        </>}
        {error && <div className="editor-error">{error}</div>}
      </div>
      <footer><button type="button" className="btn" onClick={onClose}>取消</button><button type="submit" className="btn btn-primary">保存 Dataset 配置</button></footer>
    </form>
  </div>;
}

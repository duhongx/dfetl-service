"use client";

import { useState, type ReactNode } from "react";
import { executionSeed, validationSeed } from "./mock-data";
import { executionSnapshotSeed } from "./execution-snapshots";
import { deleteApplySeed, deleteSnapshotSeed, loadBatchSeed, messageOutboxSeed, type DeleteApplyRow, type DeleteSnapshotRow, type MessageOutboxRow } from "./runtime-data";

type Ask = (title: string, message: ReactNode, onConfirm?: () => void, danger?: boolean, confirmLabel?: string) => void;

type TableProps = { headers: string[]; rows: ReactNode[][]; empty?: string };
function Table({ headers, rows, empty = "暂无数据" }: TableProps) {
  return <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row, index) => <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}><div className="empty">{empty}</div></td></tr>}</tbody></table></div>;
}
function Badge({ value }: { value: string | null | undefined }) {
  const text = value ?? "—";
  const good = ["SUCCESS", "SUCCEEDED", "PASS", "COMPLETED", "VISIBLE", "PUBLISHED", "BASELINE_CREATED"].includes(text);
  const bad = ["FAILED", "MISMATCH", "ABORTED", "DEAD_LETTER", "PARTIAL_FAILED"].includes(text);
  const warn = ["PENDING", "RUNNING", "LOADING", "VALIDATING", "PROBING", "DIFF_GENERATED"].includes(text);
  return <span className={`badge ${good ? "badge-good" : bad ? "badge-bad" : warn ? "badge-warn" : "badge-muted"}`}>{text}</span>;
}
function Card({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return <section className="card"><header><div><h2>{title}</h2>{note && <p>{note}</p>}</div></header><div className="card-body">{children}</div></section>;
}
function Button({ children, onClick, danger = false }: { children: ReactNode; onClick: () => void; danger?: boolean }) {
  return <button type="button" className={`btn ${danger ? "btn-danger" : ""}`} onClick={onClick}>{children}</button>;
}

export function TaskGovernancePanel({ taskId, ask, onToast }: { taskId: string; ask: Ask; onToast: (message: string) => void }) {
  const [outboxes, setOutboxes] = useState<MessageOutboxRow[]>(() => messageOutboxSeed.filter((row) => row.taskId === taskId));
  const [snapshots, setSnapshots] = useState<DeleteSnapshotRow[]>(() => deleteSnapshotSeed.filter((row) => row.taskId === taskId));
  const [applies, setApplies] = useState<DeleteApplyRow[]>(() => deleteApplySeed.filter((row) => row.taskId === taskId));
  const reconciliation = validationSeed.filter((row) => row.taskId === taskId && row.scope === "DELETE_RECONCILIATION").at(-1);

  const retry = (row: MessageOutboxRow) => ask(
    "人工重发消息",
    <><p>沿用 Event ID <code>{row.eventId}</code>，重置本轮 Attempt。</p><p>重发重新读取当前 Doris，不修改原 Execution、Watermark 或 Task 调度。</p></>,
    () => {
      setOutboxes((items) => items.map((item) => item.id === row.id ? { ...item, status: "PENDING", attemptCount: 0, publishedAt: "—", lastError: "" } : item));
      onToast("Outbox 已重置为 PENDING。")
    },
    true,
    "确认重发",
  );

  const createSnapshot = () => {
    if (snapshots.some((row) => ["PENDING", "EXTRACTING", "WRITING", "COMPARING"].includes(row.status))) {
      onToast("当前 Task 已有活动 Delete Snapshot，不能重复启动。");
      return;
    }
    const previous = snapshots.filter((row) => row.status === "COMPLETED").at(-1);
    setSnapshots((items) => [...items, {
      id: `DS-${taskId}-${items.length + 1}`,
      taskId,
      status: "PENDING",
      result: null,
      baselineId: previous?.id ?? null,
      keyRows: 0,
      differenceCount: 0,
      createdAt: "刚刚",
      cleanedAt: "—",
    }]);
    onToast("已创建新的 Delete Snapshot Run；不会自动删除 ODS。");
  };

  const dryRun = () => {
    if (!reconciliation || reconciliation.status !== "COMPLETED" || reconciliation.result !== "MISMATCH" || !reconciliation.differenceCount) {
      onToast("当前没有 COMPLETED + MISMATCH 的 DELETE_RECONCILIATION 可用于 Dry Run。");
      return;
    }
    ask(
      "Delete Apply Dry Run",
      <><p>Validation <code>{reconciliation.id}</code> 发现 <strong>{reconciliation.differenceCount}</strong> 条删除差异。</p><p>Dry Run 只计算/展示计划，不写 Doris，可重复执行。</p></>,
      () => {
        setApplies((items) => [...items, {
          id: `DA-${items.length + 1}`,
          validationId: reconciliation.id,
          taskId,
          dryRun: true,
          plannedCount: reconciliation.differenceCount ?? 0,
          appliedCount: 0,
          failedCount: 0,
          status: "SUCCEEDED",
          requestedAt: "刚刚",
        }]);
        onToast("Dry Run 已完成并记录。")
      },
      false,
      "执行 Dry Run",
    );
  };

  const realApply = () => {
    if (!reconciliation || reconciliation.status !== "COMPLETED" || reconciliation.result !== "MISMATCH" || !reconciliation.differenceCount) {
      onToast("当前没有可应用的删除差异。");
      return;
    }
    const hasSuccessfulDryRun = applies.some((row) => row.validationId === reconciliation.id && row.dryRun && row.status === "SUCCEEDED");
    if (!hasSuccessfulDryRun) {
      onToast("必须先针对当前 Delete Reconciliation 完成一次成功 Dry Run。");
      return;
    }
    const effective = applies.some((row) => row.validationId === reconciliation.id && !row.dryRun && ["PENDING", "RUNNING", "SUCCEEDED"].includes(row.status));
    if (effective) {
      onToast("该 Validation 已存在 PENDING/RUNNING/SUCCEEDED 的真实 Apply，数据库安全规则禁止再次发起。");
      return;
    }
    ask(
      "删除应用：第一次确认",
      <><p>计划应用 <strong>{reconciliation.differenceCount}</strong> 条删除差异。</p><p>P-004 风险阈值将在 Delete Apply 实现前确认；当前原型不虚构默认阈值。</p></>,
      () => ask(
        "删除应用：最终确认",
        <><p>这是实际删除 ODS 数据的危险操作。</p><p>必须确认 Dry Run 范围、差异数量和当前数据集后再继续，并写入 Audit。</p></>,
        () => {
          setApplies((items) => [...items, {
            id: `DA-${items.length + 1}`,
            validationId: reconciliation.id,
            taskId,
            dryRun: false,
            plannedCount: reconciliation.differenceCount ?? 0,
            appliedCount: reconciliation.differenceCount ?? 0,
            failedCount: 0,
            status: "SUCCEEDED",
            requestedAt: "刚刚",
          }]);
          onToast("真实 Delete Apply 已完成二次确认（前端原型）。")
        },
        true,
        "最终确认应用",
      ),
      true,
      "继续",
    );
  };

  return <>
    <Card title="Message Outbox" note="Dataset 级消息策略；不保存业务 Payload 或分页进度。">
      <Table headers={["Event", "Execution", "Scope", "Routing Key", "Attempts", "状态", "发布时间", "操作"]} rows={outboxes.map((row) => [row.eventId, row.executionId, row.publishScope, row.routingKey, `${row.attemptCount}/${row.maxAttempts}`, <Badge key="s" value={row.status} />, row.publishedAt, ["PUBLISHED", "DEAD_LETTER"].includes(row.status) ? <button type="button" key="a" className="btn btn-ghost" onClick={() => retry(row)}>人工重发</button> : "—"])} />
    </Card>
    <Card title="删除识别与人工应用" note="PostgreSQL 只保留 Run/Result；大规模 Key/Diff 位于 Doris。">
      <div className="actions section-actions"><Button onClick={createSnapshot}>生成 Delete Snapshot</Button><Button onClick={dryRun}>Dry Run</Button><Button danger onClick={realApply}>应用删除</Button></div>
      <div className="grid-2">
        <div><h3 className="section-title">Snapshot Runs</h3><Table headers={["Run", "Baseline", "状态", "结果", "Key Rows", "Diff", "创建"]} rows={snapshots.map((row) => [row.id, row.baselineId ?? "首次基线", <Badge key="s" value={row.status} />, <Badge key="r" value={row.result} />, row.keyRows, row.differenceCount, row.createdAt])} /></div>
        <div><h3 className="section-title">Apply Runs</h3><Table headers={["Apply", "Validation", "模式", "计划", "已应用", "失败", "状态"]} rows={applies.map((row) => [row.id, row.validationId, row.dryRun ? "DRY_RUN" : "REAL", row.plannedCount, row.appliedCount, row.failedCount, <Badge key="s" value={row.status} />])} /></div>
      </div>
      <div className="notice notice-warn">发现删除差异不会自动删除 ODS；真实 Apply 必须成功 Dry Run + 风险提示 + 二次确认 + Audit。</div>
    </Card>
  </>;
}

export function ExecutionDetailPanel({ executionId }: { executionId: string }) {
  const execution = executionSeed.find((row) => row.id === executionId);
  if (!execution) return <div className="notice notice-warn">Execution {executionId} 不存在或已不在当前原型数据中。</div>;
  const batches = loadBatchSeed.filter((row) => row.executionId === execution.id);
  const gate = validationSeed.find((row) => row.executionId === execution.id && row.trigger === "SYNC_GATE");
  const outbox = messageOutboxSeed.find((row) => row.executionId === execution.id);
  const snapshot = executionSnapshotSeed[execution.id];
  return <>
    <div className="grid-2">
      <Card title="运行事实"><div className="details"><div><span>Execution</span><strong>{execution.id}</strong></div><div><span>Task</span><strong>{execution.taskId}</strong></div><div><span>Operation / Trigger</span><strong>{execution.operation} / {execution.trigger}</strong></div><div><span>Scope / Range</span><strong>{execution.scope} · {execution.range}</strong></div><div><span>Rows</span><strong>{execution.sourceRows} / {execution.loadedRows} / {execution.rejectedRows}</strong></div><div><span>Status</span><Badge value={execution.status} /></div></div></Card>
      <Card title="启动快照身份"><div className="details"><div><span>Task Revision</span><strong>{snapshot?.taskRevision ?? "—"}</strong></div><div><span>Institution Code</span><strong>{snapshot?.institutionCode ?? "—"}</strong></div><div><span>Dataset Version</span><strong>{snapshot ? `V${snapshot.datasetVersion}` : "—"}</strong></div><div><span>Route Version</span><strong>{snapshot ? `V${snapshot.routeVersion}` : "—"}</strong></div><div><span>Validation</span><strong>{snapshot ? `${snapshot.validationMethod} / ${snapshot.validationSource}` : "—"}</strong></div><div><span>Checksum Protocol</span><strong>{snapshot?.checksumProtocol ?? "不使用"}</strong></div></div></Card>
    </div>
    {snapshot && <div className="grid-2">
      <Card title="Source Runtime Snapshot" note="只显示非 Secret 连接事实。"><div className="details"><div><span>Datasource / Revision</span><strong>{snapshot.sourceRuntime.datasourceId} / r{snapshot.sourceRuntime.revision}</strong></div><div><span>DB Type</span><strong>{snapshot.sourceRuntime.dbType}</strong></div><div><span>Endpoint</span><strong>{snapshot.sourceRuntime.endpoint}</strong></div><div><span>Database / Username</span><strong>{snapshot.sourceRuntime.database} / {snapshot.sourceRuntime.username}</strong></div></div></Card>
      <Card title="Target Runtime + Message Snapshot"><div className="details"><div><span>Target / Revision</span><strong>{snapshot.targetRuntime.datasourceId} / r{snapshot.targetRuntime.revision}</strong></div><div><span>FE</span><strong>{snapshot.targetRuntime.feEndpoints.join("、")}</strong></div><div><span>Message Policy</span><strong>{snapshot.messagePolicy.enabled ? `ENABLED r${snapshot.messagePolicy.revision}` : "DISABLED"}</strong></div><div><span>Routing Key</span><strong>{snapshot.messagePolicy.routingKey || "—"}</strong></div></div></Card>
    </div>}
    <Card title="Load Batches" note="COMMITTED 不是成功；只有 Doris VISIBLE + rejected=0 才 SUCCEEDED。"><Table headers={["Batch", "Source / Loaded / Rejected", "Doris Label", "Doris State", "Batch Status", "Started", "Visible"]} rows={batches.map((row) => [row.batchNo, `${row.sourceRows} / ${row.loadedRows} / ${row.rejectedRows}`, <code key="l">{row.dorisLabel}</code>, <Badge key="d" value={row.dorisState} />, <Badge key="s" value={row.status} />, row.startedAt, row.visibleAt])} /></Card>
    <div className="grid-2">
      <Card title="SYNC_GATE"><div className="details"><div><span>Validation</span><strong>{gate?.id ?? "尚未创建"}</strong></div><div><span>Method / Source</span><strong>{gate ? `${gate.method} / ${gate.source}` : "—"}</strong></div><div><span>Status / Result</span><strong>{gate ? `${gate.status} / ${gate.result}` : "—"}</strong></div><div><span>Difference</span><strong>{gate?.differenceCount ?? "—"}</strong></div></div></Card>
      <Card title="Message Outbox"><div className="details"><div><span>Event</span><strong>{outbox?.eventId ?? "未创建"}</strong></div><div><span>Status</span><Badge value={outbox?.status} /></div><div><span>Publish Scope</span><strong>{outbox?.publishScope ?? "—"}</strong></div><div><span>Attempts</span><strong>{outbox ? `${outbox.attemptCount}/${outbox.maxAttempts}` : "—"}</strong></div></div></Card>
    </div>
    <div className="notice">Execution 详情不显示数据库/RabbitMQ/API Secret，也不通过当前 Task 重算历史 Dataset/Route/Validation 配置。</div>
  </>;
}

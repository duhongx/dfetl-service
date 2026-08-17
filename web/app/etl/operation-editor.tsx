"use client";

import { useState, type FormEvent } from "react";
import type { Dataset, ExecutionRow, PrecheckRow, RouteRow, TaskRow } from "./model";

export type OperationKind = "PRECHECK" | "INDEPENDENT_VALIDATION" | "MANUAL_RECHECK" | "BACKFILL" | "RECOLLECT";
export type OperationRequest = { kind: OperationKind; taskId?: string; routeId?: string; executionId?: string };

export type OperationLaunch = {
  kind: OperationKind;
  taskId?: string;
  routeId?: string;
  executionId?: string;
  scope?: string;
  windowLower?: string;
  windowUpper?: string;
  keyLower?: string;
  keyUpper?: string;
  validationMethod?: string;
};

type Props = {
  request: OperationRequest | null;
  routes: RouteRow[];
  tasks: TaskRow[];
  datasets: Dataset[];
  executions: ExecutionRow[];
  prechecks: PrecheckRow[];
  onClose: () => void;
  onLaunch: (value: OperationLaunch) => void;
};

function resolvedValidation(task: TaskRow | undefined, datasets: Dataset[]) {
  if (!task) return "ROW_COUNT";
  if (task.validationOverride !== "INHERIT") return task.validationOverride;
  const dataset = datasets.find((item) => item.code === task.datasetCode);
  if (dataset?.validationOverride && dataset.validationOverride !== "INHERIT") return dataset.validationOverride;
  return "ROW_COUNT";
}

export function OperationEditor({ request, routes, tasks, datasets, executions, prechecks, onClose, onLaunch }: Props) {
  const [routeId, setRouteId] = useState(request?.routeId ?? routes[0]?.id ?? "");
  const [taskId, setTaskId] = useState(request?.taskId ?? tasks[0]?.id ?? "");
  const [executionId, setExecutionId] = useState(request?.executionId ?? executions.find((item) => item.taskId === request?.taskId)?.id ?? executions[0]?.id ?? "");
  const [scope, setScope] = useState("FULL_DATASET");
  const [windowLower, setWindowLower] = useState("");
  const [windowUpper, setWindowUpper] = useState("");
  const [keyLower, setKeyLower] = useState("");
  const [keyUpper, setKeyUpper] = useState("");
  const [error, setError] = useState("");

  if (!request) return null;
  const task = tasks.find((item) => item.id === taskId);
  const route = routes.find((item) => item.id === routeId);
  const dataset = datasets.find((item) => item.code === task?.datasetCode);
  const executionOptions = executions.filter((item) => !taskId || item.taskId === taskId);
  const sourceExecution = executions.find((item) => item.id === executionId);
  const activeExecution = task ? executions.find((item) => item.taskId === task.id && ["PENDING", "RUNNING", "LOADING", "VALIDATING"].includes(item.status)) : undefined;
  const activeIndependentValidation = task ? false : false;
  const activePrecheck = route ? prechecks.find((item) => item.routeId === route.id && ["PENDING", "EXTRACTING", "VALIDATING"].includes(item.status)) : undefined;
  const method = resolvedValidation(task, datasets);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (request.kind === "PRECHECK") {
      if (!route) return setError("请选择当前 Route。");
      if (activePrecheck) return setError(`Route ${route.id} 已有活动预检 ${activePrecheck.id}，不能重复启动。`);
      return onLaunch({ kind: request.kind, routeId: route.id });
    }
    if (request.kind === "INDEPENDENT_VALIDATION") {
      if (!task) return setError("请选择 Task。");
      if (activeExecution) return setError(`Task 当前存在活动 Execution ${activeExecution.id}，不能同时启动独立校验。`);
      if (activeIndependentValidation) return setError("Task 当前已有活动独立校验。");
      if (scope === "CHANGE_WINDOW" && (!windowLower || !windowUpper || windowLower >= windowUpper)) return setError("CHANGE_WINDOW 必须填写有效的 [lower, upper) 时间范围。");
      return onLaunch({ kind: request.kind, taskId: task.id, scope, windowLower: scope === "CHANGE_WINDOW" ? windowLower : undefined, windowUpper: scope === "CHANGE_WINDOW" ? windowUpper : undefined, validationMethod: method });
    }
    if (request.kind === "MANUAL_RECHECK") {
      if (!sourceExecution) return setError("请选择原 Execution。");
      if (["PENDING", "RUNNING", "LOADING", "VALIDATING"].includes(sourceExecution.status)) return setError("只能对已经结束的原 Execution 发起 MANUAL_RECHECK。");
      return onLaunch({ kind: request.kind, taskId: sourceExecution.taskId, executionId: sourceExecution.id, scope: "SYNC_WINDOW" });
    }
    if (request.kind === "BACKFILL") {
      if (!task) return setError("请选择 Task。");
      if (activeExecution) return setError(`Task 当前存在活动 Execution ${activeExecution.id}，不能启动 Backfill。`);
      if (scope === "BACKFILL_TIME" && (!windowLower || !windowUpper || windowLower >= windowUpper)) return setError("BACKFILL_TIME 必须填写有效的时间范围。");
      if (scope === "BACKFILL_KEY" && (!keyLower.trim() || !keyUpper.trim())) return setError("BACKFILL_KEY 必须填写联合业务主键范围。");
      if (scope === "BACKFILL_KEY" && task.keyModel !== "UNIQUE_KEY") return setError("当前 Task 没有真实业务主键，不能按业务键范围补采。");
      return onLaunch({ kind: request.kind, taskId: task.id, scope, windowLower: scope === "BACKFILL_TIME" ? windowLower : undefined, windowUpper: scope === "BACKFILL_TIME" ? windowUpper : undefined, keyLower: scope === "BACKFILL_KEY" ? keyLower : undefined, keyUpper: scope === "BACKFILL_KEY" ? keyUpper : undefined });
    }
    if (request.kind === "RECOLLECT") {
      if (!sourceExecution) return setError("请选择需要重新采集的历史 Execution。");
      if (["PENDING", "RUNNING", "LOADING", "VALIDATING"].includes(sourceExecution.status)) return setError("不能以活动 Execution 作为 Recollect 来源。");
      if (activeExecution) return setError(`Task 当前存在活动 Execution ${activeExecution.id}，不能启动 Recollect。`);
      return onLaunch({ kind: request.kind, taskId: sourceExecution.taskId, executionId: sourceExecution.id, scope: sourceExecution.scope });
    }
  };

  const title = request.kind === "PRECHECK" ? "人工启动数据预检" : request.kind === "INDEPENDENT_VALIDATION" ? "人工独立校验" : request.kind === "MANUAL_RECHECK" ? "人工重新校验" : request.kind === "BACKFILL" ? "数据补采" : "重新采集";
  return <div className="modal-mask" role="presentation" onMouseDown={onClose}>
    <form className="modal editor-modal" onSubmit={submit} onMouseDown={(event) => event.stopPropagation()}>
      <header><div><h2>{title}</h2><p>当前只创建新的运行事实；不会修改既有历史运行。</p></div><button type="button" onClick={onClose}>×</button></header>
      <div className="modal-body">
        {request.kind === "PRECHECK" && <div className="editor-grid">
          <label className="editor-field"><span>Route *</span><select value={routeId} onChange={(event) => setRouteId(event.target.value)}>{routes.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.institutionName} · {item.datasetCode} · {item.sourceName}</option>)}</select><small>预检只人工启动；同一 Route 只允许一个活动 Run。</small></label>
          <div className="editor-readonly"><span>当前结构状态</span><strong>{route?.structureStatus ?? "—"}</strong><small>预检独立于 Route Business Status 和正式同步。</small></div>
        </div>}

        {request.kind === "INDEPENDENT_VALIDATION" && <>
          <div className="editor-grid">
            <label className="editor-field"><span>Task *</span><select value={taskId} onChange={(event) => setTaskId(event.target.value)}>{tasks.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.name}</option>)}</select></label>
            <div className="editor-readonly"><span>Resolved Validation Method</span><strong>{method}</strong><small>Task → Dataset → Global → Contract，启动后冻结。</small></div>
            <label className="editor-field"><span>Scope</span><select value={scope} onChange={(event) => setScope(event.target.value)}><option value="FULL_DATASET">FULL_DATASET</option><option value="CHANGE_WINDOW">CHANGE_WINDOW</option></select></label>
          </div>
          {scope === "CHANGE_WINDOW" && <div className="editor-grid"><label className="editor-field"><span>Window Lower *</span><input type="datetime-local" value={windowLower} onChange={(event) => setWindowLower(event.target.value)} /></label><label className="editor-field"><span>Window Upper *</span><input type="datetime-local" value={windowUpper} onChange={(event) => setWindowUpper(event.target.value)} /></label></div>}
          <div className="editor-note">独立校验启动后 Task 可以继续编辑；本次校验使用自己的最小 Runtime Context/Range Snapshot。</div>
        </>}

        {request.kind === "MANUAL_RECHECK" && <><label className="editor-field"><span>原 Execution *</span><select value={executionId} onChange={(event) => setExecutionId(event.target.value)}>{executionOptions.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.taskName} · {item.scope} · {item.status}</option>)}</select></label><div className="editor-note">MANUAL_RECHECK 完全复用父 Execution 的固定范围、Route/Dataset Version、Runtime Snapshot 和 Validation Method，不读取当前 Task 新配置。</div></>}

        {request.kind === "BACKFILL" && <>
          <div className="editor-grid"><div className="editor-readonly"><span>Task</span><strong>{task?.name ?? "—"}</strong><small>{task?.id}</small></div><label className="editor-field"><span>Backfill Scope</span><select value={scope.startsWith("BACKFILL") ? scope : "BACKFILL_TIME"} onChange={(event) => setScope(event.target.value)}><option value="BACKFILL_TIME">BACKFILL_TIME</option>{task?.keyModel === "UNIQUE_KEY" && <option value="BACKFILL_KEY">BACKFILL_KEY</option>}</select><small>补采成功也不推进正式 Watermark。</small></label></div>
          {(scope === "BACKFILL_TIME" || !scope.startsWith("BACKFILL")) && <div className="editor-grid"><label className="editor-field"><span>Window Lower *</span><input type="datetime-local" value={windowLower} onChange={(event) => setWindowLower(event.target.value)} /></label><label className="editor-field"><span>Window Upper *</span><input type="datetime-local" value={windowUpper} onChange={(event) => setWindowUpper(event.target.value)} /></label></div>}
          {scope === "BACKFILL_KEY" && <div className="editor-grid"><label className="editor-field"><span>Key Lower *</span><input value={keyLower} onChange={(event) => setKeyLower(event.target.value)} placeholder='例如 {"BRID":"10001"}' /></label><label className="editor-field"><span>Key Upper *</span><input value={keyUpper} onChange={(event) => setKeyUpper(event.target.value)} placeholder='例如 {"BRID":"20000"}' /></label></div>}
        </>}

        {request.kind === "RECOLLECT" && <><div className="editor-grid"><div className="editor-readonly"><span>Task</span><strong>{task?.name ?? "—"}</strong><small>{task?.id}</small></div><label className="editor-field"><span>历史 Execution *</span><select value={executionId} onChange={(event) => setExecutionId(event.target.value)}>{executionOptions.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.scope} · {item.range} · {item.status}</option>)}</select></label></div><div className="editor-note">Recollect 创建全新 Execution，并从所选历史范围的起点与 Batch 1 重新读取；不复用旧 Batch/Checkpoint。</div></>}
        {error && <div className="editor-error">{error}</div>}
      </div>
      <footer><button type="button" className="btn" onClick={onClose}>取消</button><button type="submit" className="btn btn-primary">创建新运行</button></footer>
    </form>
  </div>;
}

"use client";

import { useState, type FormEvent } from "react";
import type { Dataset, ExecutionRow, PrecheckRun, RouteRow, TaskRow, TaskVersion } from "./model";

export type OperationKind = "PRECHECK" | "INDEPENDENT_VALIDATION" | "MANUAL_RECHECK" | "BACKFILL" | "RECOLLECT";
export type OperationRequest = { kind: OperationKind; taskId?: string; routeId?: string; executionId?: string };

export type OperationLaunch = {
  kind: OperationKind;
  taskId?: string;
  taskVersionId?: string;
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
  prechecks: PrecheckRun[];
  onClose: () => void;
  onLaunch: (value: OperationLaunch) => void;
};

type OpenProps = Omit<Props, "request"> & { request: OperationRequest };

const activeExecutionStatuses: ExecutionRow["status"][] = ["PENDING", "RUNNING", "LOADING", "VALIDATING", "STATE_UNKNOWN"];
const activeValidationStatuses = ["PENDING", "RUNNING"];

function currentVersion(task: TaskRow | undefined): TaskVersion | undefined {
  if (!task) return undefined;
  return task.versions.find((item) => item.id === task.currentVersionId);
}

function resolvedValidation(task: TaskRow | undefined, datasets: Dataset[]): string {
  const version = currentVersion(task);
  if (!task || !version) return "ROW_COUNT";
  if (version.validationOverride !== "INHERIT") return version.validationOverride;
  const dataset = datasets.find((item) => item.code === task.datasetCode);
  if (dataset?.validationOverride && dataset.validationOverride !== "INHERIT") return dataset.validationOverride;
  return "ROW_COUNT";
}

function defaultScope(kind: OperationKind): string {
  if (kind === "BACKFILL") return "BACKFILL_TIME";
  if (kind === "MANUAL_RECHECK") return "SYNC_WINDOW";
  return "FULL_DATASET";
}

function activeExecutionForTask(executions: ExecutionRow[], taskId: string | undefined): ExecutionRow | undefined {
  if (!taskId) return undefined;
  return executions.find((item) => item.taskId === taskId && activeExecutionStatuses.includes(item.status));
}

export function OperationEditor(props: Props) {
  if (!props.request) return null;
  return <OperationEditorForm {...props} request={props.request} />;
}

function OperationEditorForm({ request, routes, tasks, datasets, executions, prechecks, onClose, onLaunch }: OpenProps) {
  const executionOptions = executions.filter((item) => {
    if (request.taskId && item.taskId !== request.taskId) return false;
    if (["MANUAL_RECHECK", "RECOLLECT"].includes(request.kind)) return !activeExecutionStatuses.includes(item.status);
    return true;
  });

  const [routeId, setRouteId] = useState(request.routeId ?? routes.find((item) => item.deletedAt === null)?.id ?? "");
  const [taskId, setTaskId] = useState(request.taskId ?? tasks.find((item) => item.deletedAt === null)?.id ?? "");
  const [executionId, setExecutionId] = useState(request.executionId ?? executionOptions[0]?.id ?? "");
  const [scope, setScope] = useState(defaultScope(request.kind));
  const [windowLower, setWindowLower] = useState("");
  const [windowUpper, setWindowUpper] = useState("");
  const [keyLower, setKeyLower] = useState("");
  const [keyUpper, setKeyUpper] = useState("");
  const [error, setError] = useState("");

  const task = tasks.find((item) => item.id === taskId);
  const taskVersion = currentVersion(task);
  const route = routes.find((item) => item.id === routeId && item.deletedAt === null);
  const sourceExecution = executions.find((item) => item.id === executionId);
  const sourceTask = sourceExecution ? tasks.find((item) => item.id === sourceExecution.taskId) : undefined;
  const sourceTaskVersion = sourceExecution
    ? sourceTask?.versions.find((item) => item.id === sourceExecution.taskVersionId)
    : undefined;
  const selectedTaskActiveExecution = activeExecutionForTask(executions, task?.id);
  const sourceTaskActiveExecution = activeExecutionForTask(executions, sourceExecution?.taskId);
  const activePrecheck = route
    ? prechecks.find((item) => item.routeId === route.id && ["PENDING", "EXTRACTING", "VALIDATING"].includes(item.status))
    : undefined;
  const method = resolvedValidation(task, datasets);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setError("");

    if (request.kind === "PRECHECK") {
      if (!route) return setError("请选择有效采集链路。");
      if (activePrecheck) return setError(`采集链路 ${route.id} 已有活动预检 ${activePrecheck.id}，不能重复启动。`);
      return onLaunch({ kind: request.kind, routeId: route.id });
    }

    if (request.kind === "INDEPENDENT_VALIDATION") {
      if (!task || !taskVersion) return setError("请选择有效同步任务。");
      if (selectedTaskActiveExecution) return setError(`任务当前存在活动 Execution ${selectedTaskActiveExecution.id}，不能同时启动独立校验。`);
      if (scope === "CHANGE_WINDOW" && (!windowLower || !windowUpper || windowLower >= windowUpper)) {
        return setError("CHANGE_WINDOW 必须填写有效的 [lower, upper) 时间范围。");
      }
      return onLaunch({
        kind: request.kind,
        taskId: task.id,
        taskVersionId: taskVersion.id,
        scope,
        windowLower: scope === "CHANGE_WINDOW" ? windowLower : undefined,
        windowUpper: scope === "CHANGE_WINDOW" ? windowUpper : undefined,
        validationMethod: method,
      });
    }

    if (request.kind === "MANUAL_RECHECK") {
      if (!sourceExecution) return setError("请选择已经结束的原 Execution。");
      if (activeExecutionStatuses.includes(sourceExecution.status)) return setError("只能对已经结束的原 Execution 发起重新校验。");
      if (sourceTaskActiveExecution) return setError(`任务当前存在活动 Execution ${sourceTaskActiveExecution.id}，不能启动重新校验。`);
      return onLaunch({
        kind: request.kind,
        taskId: sourceExecution.taskId,
        taskVersionId: sourceExecution.taskVersionId,
        executionId: sourceExecution.id,
        scope: "SYNC_WINDOW",
      });
    }

    if (request.kind === "BACKFILL") {
      if (!task || !taskVersion) return setError("请选择有效同步任务。");
      if (selectedTaskActiveExecution) return setError(`任务当前存在活动 Execution ${selectedTaskActiveExecution.id}，不能启动数据补采。`);
      if (scope === "BACKFILL_TIME" && (!windowLower || !windowUpper || windowLower >= windowUpper)) {
        return setError("BACKFILL_TIME 必须填写有效的 [lower, upper) 时间范围。");
      }
      if (scope === "BACKFILL_KEY" && (!keyLower.trim() || !keyUpper.trim())) {
        return setError("BACKFILL_KEY 必须填写联合业务主键范围。");
      }
      if (scope === "BACKFILL_KEY" && taskVersion.keyModel !== "UNIQUE_KEY") {
        return setError("当前任务没有真实业务主键，不能按业务键范围补采。");
      }
      return onLaunch({
        kind: request.kind,
        taskId: task.id,
        taskVersionId: taskVersion.id,
        scope,
        windowLower: scope === "BACKFILL_TIME" ? windowLower : undefined,
        windowUpper: scope === "BACKFILL_TIME" ? windowUpper : undefined,
        keyLower: scope === "BACKFILL_KEY" ? keyLower : undefined,
        keyUpper: scope === "BACKFILL_KEY" ? keyUpper : undefined,
      });
    }

    if (request.kind === "RECOLLECT") {
      if (!sourceExecution || !sourceTaskVersion) return setError("请选择已经结束且版本信息完整的历史 Execution。");
      if (activeExecutionStatuses.includes(sourceExecution.status)) return setError("不能以活动 Execution 作为重新采集来源。");
      if (sourceTaskActiveExecution) return setError(`任务当前存在活动 Execution ${sourceTaskActiveExecution.id}，不能启动重新采集。`);
      return onLaunch({
        kind: request.kind,
        taskId: sourceExecution.taskId,
        taskVersionId: sourceExecution.taskVersionId,
        executionId: sourceExecution.id,
        scope: sourceExecution.scope,
      });
    }
  };

  const title =
    request.kind === "PRECHECK" ? "人工启动数据预检"
      : request.kind === "INDEPENDENT_VALIDATION" ? "人工独立校验"
        : request.kind === "MANUAL_RECHECK" ? "人工重新校验"
          : request.kind === "BACKFILL" ? "数据补采"
            : "重新采集";

  return <div className="modal-mask" role="presentation" onMouseDown={onClose}>
    <form className="modal editor-modal" onSubmit={submit} onMouseDown={(event) => event.stopPropagation()}>
      <header>
        <div><h2>{title}</h2><p>命令创建新的运行事实；既有 Task Version、Run 和 Execution 不被覆盖。</p></div>
        <button type="button" onClick={onClose}>×</button>
      </header>
      <div className="modal-body">
        {request.kind === "PRECHECK" && <div className="editor-grid">
          <label className="editor-field">
            <span>采集链路 *</span>
            <select value={routeId} onChange={(event) => setRouteId(event.target.value)}>
              {routes.filter((item) => item.deletedAt === null).map((item) => (
                <option key={item.id} value={item.id}>{item.id} · {item.systemInstanceName} · {item.datasetCode} · {item.sourceName}</option>
              ))}
            </select>
            <small>预检扫描整条链路及其全部覆盖机构；同一链路只允许一个活动 Run。</small>
          </label>
          <div className="editor-readonly">
            <span>本次固定版本</span>
            <strong>{route ? `Route V${route.version} / Dataset V${route.datasetVersion}` : "—"}</strong>
            <small>结构和字段解析属于预检第一阶段，不存在独立结构 Gate。</small>
          </div>
        </div>}

        {request.kind === "INDEPENDENT_VALIDATION" && <>
          <div className="editor-grid">
            <label className="editor-field">
              <span>Task *</span>
              <select value={taskId} onChange={(event) => { setTaskId(event.target.value); setError(""); }}>
                {tasks.filter((item) => item.deletedAt === null).map((item) => <option key={item.id} value={item.id}>{item.id} · {item.name}</option>)}
              </select>
            </label>
            <div className="editor-readonly"><span>Task Version</span><strong>{taskVersion ? `V${taskVersion.versionNo} · ${taskVersion.id}` : "—"}</strong><small>启动后固定。</small></div>
            <div className="editor-readonly"><span>Resolved Validation</span><strong>{method}</strong><small>Task Version → Dataset → Global。</small></div>
            <label className="editor-field">
              <span>Scope</span>
              <select value={scope} onChange={(event) => { setScope(event.target.value); setError(""); }}>
                <option value="FULL_DATASET">FULL_DATASET</option>
                <option value="CHANGE_WINDOW">CHANGE_WINDOW</option>
              </select>
            </label>
          </div>
          {scope === "CHANGE_WINDOW" && <div className="editor-grid">
            <label className="editor-field"><span>Window Lower *</span><input type="datetime-local" value={windowLower} onChange={(event) => setWindowLower(event.target.value)} /></label>
            <label className="editor-field"><span>Window Upper *</span><input type="datetime-local" value={windowUpper} onChange={(event) => setWindowUpper(event.target.value)} /></label>
          </div>}
        </>}

        {request.kind === "MANUAL_RECHECK" && <>
          <label className="editor-field">
            <span>原 Execution *</span>
            <select value={executionId} onChange={(event) => { setExecutionId(event.target.value); setError(""); }}>
              {executionOptions.map((item) => <option key={item.id} value={item.id}>{item.id} · Task V{item.taskVersionNo} · {item.scope} · {item.status}</option>)}
            </select>
          </label>
          <div className="editor-note">重新校验复用原 Execution 固定的 Task Version、Route Version、数据范围和校验方法，不读取当前任务的新版本。</div>
        </>}

        {request.kind === "BACKFILL" && <>
          <div className="editor-grid">
            <div className="editor-readonly"><span>Task</span><strong>{task?.name ?? "—"}</strong><small>{task?.id}</small></div>
            <div className="editor-readonly"><span>Task Version</span><strong>{taskVersion ? `V${taskVersion.versionNo}` : "—"}</strong></div>
            <label className="editor-field">
              <span>Backfill Scope</span>
              <select value={scope} onChange={(event) => { setScope(event.target.value); setError(""); }}>
                <option value="BACKFILL_TIME">BACKFILL_TIME</option>
                {taskVersion?.keyModel === "UNIQUE_KEY" && <option value="BACKFILL_KEY">BACKFILL_KEY</option>}
              </select>
              <small>补采成功也不推进正式 Watermark。</small>
            </label>
          </div>
          {scope === "BACKFILL_TIME" && <div className="editor-grid">
            <label className="editor-field"><span>Window Lower *</span><input type="datetime-local" value={windowLower} onChange={(event) => setWindowLower(event.target.value)} /></label>
            <label className="editor-field"><span>Window Upper *</span><input type="datetime-local" value={windowUpper} onChange={(event) => setWindowUpper(event.target.value)} /></label>
          </div>}
          {scope === "BACKFILL_KEY" && <div className="editor-grid">
            <label className="editor-field"><span>Key Lower *</span><input value={keyLower} onChange={(event) => setKeyLower(event.target.value)} placeholder='例如 {"BRID":"10001"}' /></label>
            <label className="editor-field"><span>Key Upper *</span><input value={keyUpper} onChange={(event) => setKeyUpper(event.target.value)} placeholder='例如 {"BRID":"20000"}' /></label>
          </div>}
        </>}

        {request.kind === "RECOLLECT" && <>
          <label className="editor-field">
            <span>历史 Execution *</span>
            <select value={executionId} onChange={(event) => { setExecutionId(event.target.value); setError(""); }}>
              {executionOptions.map((item) => <option key={item.id} value={item.id}>{item.id} · Task V{item.taskVersionNo} · {item.scope} · {item.range}</option>)}
            </select>
          </label>
          <div className="editor-note">重新采集创建新 Execution，从所选历史范围起点与 Batch 1 重新读取；不复用旧 Batch 或 Checkpoint。</div>
        </>}

        {error && <div className="editor-error">{error}</div>}
      </div>
      <footer>
        <button type="button" className="btn" onClick={onClose}>取消</button>
        <button type="submit" className="btn btn-primary">创建新运行</button>
      </footer>
    </form>
  </div>;
}

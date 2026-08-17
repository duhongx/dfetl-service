export type LoadBatchRow = {
  id: string;
  executionId: string;
  batchNo: number;
  sourceRows: number;
  loadedRows: number;
  rejectedRows: number;
  dorisLabel: string;
  dorisState: "UNKNOWN" | "PREPARE" | "COMMITTED" | "VISIBLE" | "ABORTED";
  status: "PENDING" | "LOADING" | "PROBING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  startedAt: string;
  visibleAt: string;
};

export type MessageOutboxRow = {
  id: string;
  eventId: string;
  executionId: string;
  taskId: string;
  publishScope: "FULL" | "INCREMENTAL";
  status: "PENDING" | "PUBLISHING" | "PUBLISHED" | "DEAD_LETTER";
  routingKey: string;
  attemptCount: number;
  maxAttempts: number;
  publishedAt: string;
  lastError: string;
};

export type DeleteSnapshotRow = {
  id: string;
  taskId: string;
  status: "PENDING" | "EXTRACTING" | "WRITING" | "COMPARING" | "COMPLETED" | "FAILED" | "CANCELLED";
  result: "BASELINE_CREATED" | "DIFF_GENERATED" | null;
  baselineId: string | null;
  keyRows: number;
  differenceCount: number;
  createdAt: string;
  cleanedAt: string;
};

export type DeleteApplyRow = {
  id: string;
  validationId: string;
  taskId: string;
  dryRun: boolean;
  plannedCount: number;
  appliedCount: number;
  failedCount: number;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "PARTIAL_FAILED" | "FAILED" | "CANCELLED";
  requestedAt: string;
};

export const loadBatchSeed: LoadBatchRow[] = [
  { id: "B-001-01", executionId: "EXE-260817-002", batchNo: 1, sourceRows: 2000, loadedRows: 2000, rejectedRows: 0, dorisLabel: "dfetl_EXE260817002_000001", dorisState: "VISIBLE", status: "SUCCEEDED", startedAt: "08:17:05", visibleAt: "08:17:21" },
  { id: "B-001-02", executionId: "EXE-260817-002", batchNo: 2, sourceRows: 1258, loadedRows: 1258, rejectedRows: 0, dorisLabel: "dfetl_EXE260817002_000002", dorisState: "VISIBLE", status: "SUCCEEDED", startedAt: "08:17:22", visibleAt: "08:17:39" },
  { id: "B-002-01", executionId: "EXE-260817-001", batchNo: 1, sourceRows: 6000, loadedRows: 6000, rejectedRows: 0, dorisLabel: "dfetl_EXE260817001_000001", dorisState: "VISIBLE", status: "SUCCEEDED", startedAt: "12:15:05", visibleAt: "12:15:27" },
  { id: "B-002-02", executionId: "EXE-260817-001", batchNo: 2, sourceRows: 6000, loadedRows: 6000, rejectedRows: 0, dorisLabel: "dfetl_EXE260817001_000002", dorisState: "COMMITTED", status: "PROBING", startedAt: "12:15:28", visibleAt: "—" },
  { id: "B-006-01", executionId: "EXE-260816-006", batchNo: 1, sourceRows: 50000, loadedRows: 50000, rejectedRows: 0, dorisLabel: "dfetl_EXE260816006_000001", dorisState: "VISIBLE", status: "SUCCEEDED", startedAt: "17:24:18", visibleAt: "17:25:41" },
  { id: "B-006-02", executionId: "EXE-260816-006", batchNo: 2, sourceRows: 41412, loadedRows: 41400, rejectedRows: 12, dorisLabel: "dfetl_EXE260816006_000002", dorisState: "ABORTED", status: "FAILED", startedAt: "17:25:42", visibleAt: "—" },
];

export const messageOutboxSeed: MessageOutboxRow[] = [
  { id: "OUT-001", eventId: "EVT-20260817-001", executionId: "EXE-260817-002", taskId: "TASK-1001", publishScope: "INCREMENTAL", status: "PUBLISHED", routingKey: "YL.HUANZHEJBXX", attemptCount: 1, maxAttempts: 5, publishedAt: "2026-08-17 08:18:22", lastError: "" },
];

export const deleteSnapshotSeed: DeleteSnapshotRow[] = [
  { id: "DS-1001-A", taskId: "TASK-1001", status: "COMPLETED", result: "BASELINE_CREATED", baselineId: null, keyRows: 425100, differenceCount: 0, createdAt: "2026-08-15 02:00", cleanedAt: "—" },
  { id: "DS-1001-B", taskId: "TASK-1001", status: "COMPLETED", result: "DIFF_GENERATED", baselineId: "DS-1001-A", keyRows: 426821, differenceCount: 27, createdAt: "2026-08-16 02:00", cleanedAt: "—" },
  { id: "DS-1002-A", taskId: "TASK-1002", status: "COMPLETED", result: "BASELINE_CREATED", baselineId: null, keyRows: 182030, differenceCount: 0, createdAt: "2026-08-16 02:15", cleanedAt: "—" },
];

export const deleteApplySeed: DeleteApplyRow[] = [
  { id: "DA-001", validationId: "VAL-DEL-001", taskId: "TASK-1001", dryRun: true, plannedCount: 27, appliedCount: 0, failedCount: 0, status: "SUCCEEDED", requestedAt: "2026-08-16 09:20" },
];

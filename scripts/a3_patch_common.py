from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


path = Path("web/app/etl/app-shell-final.tsx")
text = path.read_text(encoding="utf-8")

text = once(
    text,
    '  RouteRow, SourceDataSource, TargetDataSource, TaskRow, TaskVersion, TestStatus,\n',
    '  RouteRow, SourceDataSource, TargetDataSource, TaskRow, TaskVersion, TestStatus, ValidationRow,\n',
    "ValidationRow import",
)
text = once(
    text,
    'type ButtonProps = { children: ReactNode; icon?: ReactNode; onClick?: () => void; tone?: "default" | "primary" | "danger" | "ghost"; disabled?: boolean };',
    'type ButtonProps = { children: ReactNode; icon?: ReactNode; onClick?: () => void; tone?: "default" | "primary" | "danger" | "ghost"; disabled?: boolean };\n'
    'type CoreExportJob = { id: string; kind: string; target: string; rowCount: number; status: "SUCCEEDED" | "FAILED" | "EXPIRED"; createdAt: string };\n'
    'type DeleteApplyView = { id: string; validationId: string; dryRun: boolean; plannedCount: number; appliedCount: number; status: "SUCCEEDED" | "FAILED"; createdAt: string };',
    "A3 core types",
)
old_table = 'function Table({ headers, rows, empty = "暂无数据" }: { headers: string[]; rows: ReactNode[][]; empty?: string }) { return <div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row,index) => <tr key={index}>{row.map((cell,cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}><div className="empty">{empty}</div></td></tr>}</tbody></table></div>; }'
new_table = '''function Table({ headers, rows, empty = "暂无数据" }: { headers: string[]; rows: ReactNode[][]; empty?: string }) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const currentPage = Math.min(page, totalPages);
  const visibleRows = rows.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  return <><div className="table-wrap"><table><thead><tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{visibleRows.length ? visibleRows.map((row,index) => <tr key={`${currentPage}-${index}`}>{row.map((cell,cellIndex) => <td key={cellIndex}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}><div className="empty">{empty}</div></td></tr>}</tbody></table></div>{rows.length > pageSize && <div className="toolbar" style={{alignItems:"center",margin:"12px 0"}}><span style={{fontSize:11,color:"var(--muted)"}}>共 {rows.length} 条 · 第 {currentPage}/{totalPages} 页</span><div className="actions"><select value={pageSize} onChange={(event) => { setPageSize(Number(event.target.value)); setPage(1); }}><option value={10}>10 / 页</option><option value={20}>20 / 页</option><option value={50}>50 / 页</option></select><Button disabled={currentPage <= 1} onClick={() => setPage(currentPage - 1)}>上一页</Button><Button disabled={currentPage >= totalPages} onClick={() => setPage(currentPage + 1)}>下一页</Button></div></div>}</>;
}'''
text = once(text, old_table, new_table, "Global table pagination")
text = once(
    text,
    '  const [currentAccountId, setCurrentAccountId] = useState("U01");',
    '  const [currentAccountId, setCurrentAccountId] = useState("U01");\n'
    '  const [selectedInstitutionId, setSelectedInstitutionId] = useState<string | null>(null);\n'
    '  const [selectedDatasetCode, setSelectedDatasetCode] = useState<string | null>(datasetSeed[0]?.code ?? null);\n'
    '  const [datasetDetailTab, setDatasetDetailTab] = useState<"basic" | "fields" | "sync" | "validation" | "message">("basic");\n'
    '  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(routeSeed[0]?.id ?? null);\n'
    '  const [routeDetailTab, setRouteDetailTab] = useState<"basic" | "versions" | "fields">("basic");\n'
    '  const [selectedPrecheckRouteIds, setSelectedPrecheckRouteIds] = useState<string[]>([]);\n'
    '  const [coreExportJobs, setCoreExportJobs] = useState<CoreExportJob[]>([]);\n'
    '  const [validations, setValidations] = useState<ValidationRow[]>(validationSeed);\n'
    '  const [selectedValidationTaskId, setSelectedValidationTaskId] = useState(taskSeed[0]?.id ?? "");\n'
    '  const [selectedValidationId, setSelectedValidationId] = useState<string | null>(null);\n'
    '  const [deleteDryRunValidationIds, setDeleteDryRunValidationIds] = useState<string[]>([]);\n'
    '  const [deleteApplyRuns, setDeleteApplyRuns] = useState<DeleteApplyView[]>([]);',
    "A3 core state",
)
text = once(
    text,
    '  const PButton = ({ permission, ...props }: ButtonProps & { permission: string }) => can(permission) ? <Button {...props} /> : null;',
    '  const PButton = ({ permission, ...props }: ButtonProps & { permission: string }) => can(permission) ? <Button {...props} /> : null;\n'
    '  const createCoreExportJob = (permissionCode: string, event: string, kind: string, target: string, rowCount: number) => { if (!can(permissionCode)) return deny(permissionCode); ask("创建导出任务",`按当前筛选导出 ${rowCount} 条记录。`,() => { const id = nextId("EXP",coreExportJobs.length); setCoreExportJobs((items) => [{id,kind,target,rowCount,status:"SUCCEEDED",createdAt:"刚刚"},...items]); recordAudit(permissionCode,event,target,"SUCCESS",`${rowCount} rows`); setToast(`导出任务 ${id} 已完成`); },false,"确认导出"); };\n'
    '  const exportJobsFor = (kind: string) => { const jobs = coreExportJobs.filter((item) => item.kind === kind); return jobs.length ? <Card title="导出任务"><Table headers={["任务","范围","记录数","状态","创建"]} rows={jobs.map((item) => [item.id,item.target,item.rowCount,<Badge key="s" value={item.status}/>,item.createdAt])}/></Card> : null; };',
    "Core export helper",
)
text = once(text, 'validationSeed.filter((item) => item.result === "MISMATCH")', 'validations.filter((item) => item.result === "MISMATCH")', "Dashboard validations")
text = once(text, 'const validation = validationSeed.find((item) => item.executionId === execution.id && item.trigger === "SYNC_GATE")', 'const validation = validations.find((item) => item.executionId === execution.id && item.trigger === "SYNC_GATE")', "Execution validation")
text = once(
    text,
    '<footer><strong>A3 前端产品行为已稳定</strong><small>REST API Contract V1 已冻结；后端与数据库仍未实施</small></footer>',
    '<footer><strong>A3 前端矩阵逐页复核中</strong><small>REST API Contract V1 已冻结；后端与数据库仍未实施</small></footer>',
    "Sidebar audit state",
)
path.write_text(text, encoding="utf-8")

from pathlib import Path


def between(text: str, start: str, end: str, new: str, label: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise RuntimeError(f"{label}: markers are not unique")
    before, remainder = text.split(start, 1)
    _, after = remainder.split(end, 1)
    return before + new + end + after


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


path = Path("web/app/etl/app-shell-final.tsx")
text = path.read_text(encoding="utf-8")

validation_page = '''  const validationPage = (workbench: boolean) => {
    const rows = filterRows(validations,(item) => `${item.id} ${item.institutionName} ${item.datasetCode} ${item.method}`);
    const selected = validations.find((item) => item.id === selectedValidationId);
    const deleteRows = validations.filter((item) => item.scope === "DELETE_RECONCILIATION");
    const createDeleteReconciliation = () => {
      const task = tasks.find((item) => item.id === selectedValidationTaskId && item.deletedAt === null);
      if (!task) return setToast("请选择有效 Task");
      const id = `VAL-DEL-${Date.now()}`;
      ask("运行删除对账",`将对 ${task.name} 建立完整业务键快照并与上一有效基线比较；不会自动删除 ODS。`,() => {
        setValidations((items) => [{id,taskId:task.id,executionId:null,institutionName:task.institutionName,datasetCode:task.datasetCode,scope:"DELETE_RECONCILIATION",trigger:"MANUAL",method:"DELETE_KEY_DIFF",source:"FIXED",status:"COMPLETED",result:"MISMATCH",sourceRows:1000,targetRows:1003,differenceCount:3,startedAt:"刚刚"},...items]);
        recordAudit("validation.delete_reconciliation.run","DELETE_RECONCILIATION_RUN",id,"SUCCESS",task.id);
        setToast(`删除对账 ${id} 已完成，发现 3 条差异（Mock）`);
      },false,"确认运行");
    };
    const dryRun = (validation: ValidationRow) => ask("Delete Apply Dry Run",`针对 ${validation.id} 的 ${validation.differenceCount ?? 0} 条差异生成应用计划，不写 Doris。`,() => {
      setDeleteDryRunValidationIds((items) => items.includes(validation.id) ? items : [...items,validation.id]);
      setDeleteApplyRuns((items) => [{id:`DA-DRY-${Date.now()}`,validationId:validation.id,dryRun:true,plannedCount:validation.differenceCount ?? 0,appliedCount:0,status:"SUCCEEDED",createdAt:"刚刚"},...items]);
      recordAudit("validation.delete_apply.dry_run","DELETE_APPLY_DRY_RUN",validation.id,"SUCCESS",`${validation.differenceCount ?? 0} planned`);
    },false,"执行 Dry Run");
    const applyDelete = (validation: ValidationRow) => {
      if (!deleteDryRunValidationIds.includes(validation.id)) return setToast("必须先对当前 Validation 完成成功 Dry Run");
      if (deleteApplyRuns.some((item) => item.validationId === validation.id && !item.dryRun && item.status === "SUCCEEDED")) return setToast("当前 Validation 已有成功真实 Apply");
      ask("应用删除差异：最终确认",<><p>Validation：{validation.id}</p><p>计划删除：{validation.differenceCount ?? 0} 条。</p><p>这是实际修改 ODS 的危险操作，必须记录原因和审计。</p></>,() => {
        setDeleteApplyRuns((items) => [{id:`DA-${Date.now()}`,validationId:validation.id,dryRun:false,plannedCount:validation.differenceCount ?? 0,appliedCount:validation.differenceCount ?? 0,status:"SUCCEEDED",createdAt:"刚刚"},...items]);
        recordAudit("validation.delete_apply.execute","DELETE_APPLY_EXECUTE",validation.id,"SUCCESS",`${validation.differenceCount ?? 0} applied`);
        setToast("删除差异已应用（Mock）");
      },true,"最终确认应用");
    };
    return <><PageHeader page={workbench ? "validationWorkbench" : "validationOverview"} actions={<><PButton permission="validation.export" onClick={() => createCoreExportJob("validation.export","VALIDATION_DIFFERENCE_EXPORT","VALIDATION",query || "ALL",rows.length)}>导出差异汇总</PButton>{workbench && <><PButton permission="validation.run" tone="primary" onClick={() => setOperation({kind:"INDEPENDENT_VALIDATION"})}>人工校验</PButton><PButton permission="validation.recheck" onClick={() => setOperation({kind:"MANUAL_RECHECK"})}>重新校验</PButton></>}</>}/><SearchBar query={query} setQuery={setQuery}/><Card><Table headers={["Validation","机构 / Dataset","Scope / Trigger","Method / Source","Execution","Source / Target","Difference","状态 / 结果","操作"]} rows={rows.map((item) => [<button type="button" key="v" className="link-cell" onClick={() => setSelectedValidationId(item.id)}><strong>{item.id}</strong></button>,<span key="d"><strong>{item.institutionName}</strong><small>{item.datasetCode}</small></span>,`${item.scope} / ${item.trigger}`,`${item.method} / ${item.source}`,item.executionId ?? "独立校验",item.sourceRows === null ? "—" : `${item.sourceRows} / ${item.targetRows}`,item.differenceCount ?? "—",<span key="s"><Badge value={item.status}/><Badge value={item.result}/></span>,<Button key="d" tone="ghost" onClick={() => setSelectedValidationId(item.id)}>详情</Button>])}/></Card>{selected && <Card title={`Validation 详情 · ${selected.id}`} actions={<Button tone="ghost" onClick={() => setSelectedValidationId(null)}>关闭</Button>}><div className="details"><div><span>Task / Execution</span><strong>{selected.taskId} / {selected.executionId ?? "独立"}</strong></div><div><span>Scope / Trigger</span><strong>{selected.scope} / {selected.trigger}</strong></div><div><span>Method / Source</span><strong>{selected.method} / {selected.source}</strong></div><div><span>Status / Result</span><strong>{selected.status} / {selected.result ?? "—"}</strong></div><div><span>Source / Target</span><strong>{selected.sourceRows ?? "—"} / {selected.targetRows ?? "—"}</strong></div><div><span>Difference</span><strong>{selected.differenceCount ?? "—"}</strong></div></div></Card>}{workbench && <><Card title="删除对账" note="发现差异不会自动删除 ODS。"><div className="toolbar"><select value={selectedValidationTaskId} onChange={(event) => setSelectedValidationTaskId(event.target.value)}>{tasks.filter((task) => task.deletedAt === null).map((task) => <option key={task.id} value={task.id}>{task.id} · {task.name}</option>)}</select><PButton permission="validation.delete_reconciliation.run" tone="primary" onClick={createDeleteReconciliation}>运行删除对账</PButton></div><Table headers={["Validation","Task","Dataset","Difference","状态 / 结果","Dry Run","真实 Apply"]} rows={deleteRows.map((item) => [item.id,item.taskId,item.datasetCode,item.differenceCount ?? 0,<span key="s"><Badge value={item.status}/><Badge value={item.result}/></span>,<PButton key="dry" permission="validation.delete_apply.dry_run" tone="ghost" onClick={() => dryRun(item)}>{deleteDryRunValidationIds.includes(item.id) ? "重新 Dry Run" : "Dry Run"}</PButton>,<PButton key="apply" permission="validation.delete_apply.execute" tone="danger" onClick={() => applyDelete(item)}>应用删除</PButton>])}/></Card>{deleteApplyRuns.length > 0 && <Card title="Delete Apply 历史"><Table headers={["Apply","Validation","模式","计划","已应用","状态","创建"]} rows={deleteApplyRuns.map((item) => [item.id,item.validationId,item.dryRun ? "DRY_RUN" : "REAL",item.plannedCount,item.appliedCount,<Badge key="s" value={item.status}/>,item.createdAt])}/></Card>}<Notice>真实 Apply 必须成功 Dry Run、风险提示、二次确认和审计；同一 Validation 不允许重复有效 Apply。</Notice></>}{exportJobsFor("VALIDATION")}</>;
  };

'''
text = between(text, '  const validationPage = (workbench: boolean) =>', '  const managementProps = { can, deny, ask, setToast, recordAudit };', validation_page, "Validation page")
text = once(
    text,
    '<footer><strong>A3 前端矩阵逐页复核中</strong><small>REST API Contract V1 已冻结；后端与数据库仍未实施</small></footer>',
    '<footer><strong>A3 前端矩阵已逐页完成</strong><small>REST API Contract V1 已冻结；后端与数据库仍未实施</small></footer>',
    "Sidebar final state",
)
path.write_text(text, encoding="utf-8")

path = Path("spec/TASKS.md")
text = path.read_text(encoding="utf-8")
needle = '- [x] `API-001`：生成并冻结 `spec/FRONTEND_API_CONTRACT_V1.md`，覆盖分页、Revision、幂等、权限、审计、导出任务和长任务状态。'
text = once(
    text,
    needle,
    needle + '\n- [x] `B5-AUDIT`：二次反查 A3 操作矩阵，补齐数据源凭据/状态治理、核心表格分页、预检批量启动/取消、Execution/Validation 导出及删除对账 Dry Run/Apply。',
    "TASKS B5 audit",
)
path.write_text(text, encoding="utf-8")

path = Path("spec/FRONTEND_PRODUCT_CONTRACTS_A1_A3.md")
text = path.read_text(encoding="utf-8")
marker = '该合同覆盖统一响应、分页、Revision/ETag、幂等、权限、审计、错误码、Export Job、轮询/SSE 和全部页面接口。前端实现完成不代表 Java、PostgreSQL、Doris、RabbitMQ 或 Flyway 已完成。'
text = once(
    text,
    marker,
    marker + '\n\n二次操作级反查已补齐：Source/Target 复制、状态、凭据轮换、删除引用保护；数据集五分区详情；Route 版本与字段解析；全部核心表格分页；预检批量启动与取消；Execution/Validation 导出；删除对账、Dry Run 和真实 Apply。',
    "Frontend contract operation audit",
)
path.write_text(text, encoding="utf-8")

shell = Path("web/app/etl/app-shell-final.tsx").read_text(encoding="utf-8")
for required in (
    'datasource.credential.rotate',
    'precheck.run_batch',
    'precheck.cancel',
    'sync_execution.export',
    'validation.export',
    'validation.delete_reconciliation.run',
    'validation.delete_apply.dry_run',
    'validation.delete_apply.execute',
    'A3 前端矩阵已逐页完成',
):
    assert required in shell, required
assert '10 / 页' in shell

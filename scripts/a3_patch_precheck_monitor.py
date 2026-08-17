from pathlib import Path


def between(text: str, start: str, end: str, new: str, label: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise RuntimeError(f"{label}: markers are not unique")
    before, remainder = text.split(start, 1)
    _, after = remainder.split(end, 1)
    return before + new + end + after


path = Path("web/app/etl/app-shell-final.tsx")
text = path.read_text(encoding="utf-8")

precheck_page = '''  const precheckPage = () => {
    const rows = filterRows(routes.filter((item) => item.deletedAt === null),(item) => `${item.id} ${item.datasetCode} ${item.systemInstanceName} ${item.sourceName}`);
    const activeStatuses = ["PENDING","EXTRACTING","VALIDATING"];
    const batchRun = () => {
      const routeIds = selectedPrecheckRouteIds.filter((routeId) => !precheckRuns.some((run) => run.routeId === routeId && activeStatuses.includes(run.status)));
      if (!routeIds.length) return setToast("请选择没有活动 Run 的采集链路");
      ask("批量启动预检",`将为 ${routeIds.length} 条 Route 分别创建新的全链路预检 Run。`,() => {
        const created = routeIds.map((routeId,index) => {
          const route = routes.find((item) => item.id === routeId)!;
          return {id:`PRE-BATCH-${Date.now()}-${index + 1}`,routeId:route.id,routeVersion:route.version,datasetVersion:route.datasetVersion,status:"PENDING" as const,result:null,extractedRows:0,checkedRows:0,problemRecordCount:0,problemItemCount:0,affectedInstitutionCount:0,retentionStatus:"AVAILABLE" as const,detailExpiresAt:"待运行完成后计算",startedAt:"刚刚",finishedAt:"—",startedBy:currentAccount?.username ?? "unknown",failureReason:""};
        });
        setPrecheckRuns((items) => [...created,...items]);
        recordAudit("precheck.run_batch","PRECHECK_RUN_BATCH_CREATE",routeIds.join(","),"SUCCESS",`${created.length} runs`);
        setSelectedPrecheckRouteIds([]);
        setToast(`已创建 ${created.length} 个预检 Run`);
      },false,"确认启动");
    };
    return <><PageHeader page="precheck" actions={<><PButton permission="precheck.run_batch" onClick={batchRun}>批量运行（{selectedPrecheckRouteIds.length}）</PButton><PButton permission="precheck.run" tone="primary" icon={<PlayCircleOutlined/>} onClick={() => setOperation({kind:"PRECHECK"})}>运行预检</PButton></>}/><SearchBar query={query} setQuery={setQuery}/><Card><Table headers={["选择","采集链路","Instance / Source","覆盖机构","最新状态 / 结果","问题记录 / 问题项","最近运行","操作"]} rows={rows.map((route) => {
      const latest = precheckRuns.filter((run) => run.routeId === route.id).sort((a,b) => b.startedAt.localeCompare(a.startedAt))[0];
      const active = latest && activeStatuses.includes(latest.status);
      return [<input key="sel" type="checkbox" checked={selectedPrecheckRouteIds.includes(route.id)} disabled={Boolean(active)} onChange={(event) => setSelectedPrecheckRouteIds((items) => event.target.checked ? [...items,route.id] : items.filter((id) => id !== route.id))}/>,<button type="button" key="r" className="link-cell" onClick={() => navigate("precheckRouteDetail",route.id)}><strong>{route.id} · {route.datasetName}</strong><small>{route.datasetCode}</small></button>,<span key="s"><strong>{route.systemInstanceName}</strong><small>{route.sourceName} · {route.schema}.{route.object}</small></span>,<span key="i"><strong>{route.institutionIds.length} 家</strong><small>{route.institutionIds.map((id) => institutions.find((item) => item.id === id)?.name ?? id).join("、")}</small></span>,<span key="st"><Badge value={latest?.status}/><Badge value={latest?.result}/></span>,latest ? `${latest.problemRecordCount} / ${latest.problemItemCount}` : "—",latest?.startedAt ?? "未运行",<div key="a" className="row-actions"><Button tone="ghost" onClick={() => navigate("precheckRouteDetail",route.id)}>详情</Button>{active ? <PButton permission="precheck.cancel" tone="danger" onClick={() => ask("取消预检 Run",`确认取消 ${latest.id}？运行记录和已生成汇总继续保留。`,() => { setPrecheckRuns((items) => items.map((run) => run.id === latest.id ? {...run,status:"CANCELLED",result:null,finishedAt:"刚刚"} : run)); recordAudit("precheck.cancel","PRECHECK_RUN_CANCEL",latest.id,"SUCCESS",route.id); },true,"确认取消")}>取消</PButton> : <PButton permission="precheck.run" tone="ghost" onClick={() => startPrecheck(route.id)}>运行</PButton>}</div>];
    })}/></Card><Notice>列表顶层为 Route；每次重新预检创建新 Run。同 Route 活动 Run 互斥，不同 Route 可在全局并发上限内批量启动。</Notice></>;
  };

'''
text = between(text, '  const precheckPage = () =>', '  const precheckRouteDetailPage = () =>', precheck_page, "Precheck page")

monitor_page = '''  const monitorPage = () => {
    const rows = filterRows(executions,(item) => `${item.id} ${item.taskName} ${item.datasetCode} ${item.status}`);
    return <><PageHeader page="monitor" actions={<PButton permission="sync_execution.export" onClick={() => createCoreExportJob("sync_execution.export","SYNC_EXECUTION_EXPORT","SYNC_EXECUTION",query || "ALL",rows.length)}>导出执行记录</PButton>}/><SearchBar query={query} setQuery={setQuery}/><Card><Table headers={["Execution","Task / Version","Operation / Trigger","Scope / Range","Rows","状态","开始 / 完成","操作"]} rows={rows.map((item) => [<button type="button" key="e" className="link-cell" onClick={() => navigate("executionDetail",item.id)}><strong>{item.id}</strong></button>,<span key="t"><strong>{item.taskName}</strong><small>{item.taskId} · V{item.taskVersionNo}</small></span>,`${item.operation} / ${item.trigger}`,`${item.scope} / ${item.range}`,`${item.sourceRows} / ${item.loadedRows} / ${item.rejectedRows}`,<Badge key="s" value={item.status}/>,<span key="tm"><strong>{item.startedAt}</strong><small>{item.finishedAt}</small></span>,["PENDING","RUNNING","LOADING","VALIDATING"].includes(item.status) ? <PButton key="c" permission="sync_execution.cancel" tone="danger" onClick={() => ask("取消 Execution","取消不推进 Watermark，也不修改 Task 调度开关。",() => { setExecutions((items) => items.map((row) => row.id === item.id ? {...row,status:"CANCELLED",finishedAt:"刚刚"} : row)); recordAudit("sync_execution.cancel","SYNC_EXECUTION_CANCEL",item.id,"SUCCESS",item.taskVersionId); },true,"确认取消")}>取消</PButton> : <div key="a" className="row-actions"><Button tone="ghost" onClick={() => navigate("executionDetail",item.id)}>详情</Button><Button tone="ghost" onClick={() => navigate("logs")}>日志</Button></div>])}/></Card>{exportJobsFor("SYNC_EXECUTION")}<Notice>STATE_UNKNOWN 必须先核对 Doris Label 最终状态；不能直接重放。</Notice></>;
  };

'''
text = between(text, '  const monitorPage = () =>', '  const executionDetailPage = () =>', monitor_page, "Monitor page")
path.write_text(text, encoding="utf-8")

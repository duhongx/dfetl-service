package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelPrecheckConfBuilder;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelRestClient;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.DfetlContractSnapshotService;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** 提交、跟踪和取消 PostgreSQL 到 Doris STRING 暂存表的 SeaTunnel 子作业。 */
@Service
@RequiredArgsConstructor
public class PrecheckLoadService {

    private static final String RECONCILE_REQUIRED = "RECONCILE_REQUIRED";

    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final SourceDataSourceRepository sourceRepository;
    private final TargetDataSourceRepository targetRepository;
    private final DfetlContractSnapshotService contractSnapshotService;
    private final SourceDataSourceService sourceDataSourceService;
    private final DorisPrecheckStorageService storageService;
    private final SeaTunnelPrecheckConfBuilder confBuilder;
    private final SeaTunnelRestClient restClient;

    /**
     * 提交预检加载作业。
     *
     * <p>在 REST 提交前先落库 {@code LOADING + staging_table}，即使服务在提交窗口中断，
     * 后续也只允许人工对账，不能把该运行重新当作 PENDING 盲目提交。
     */
    public DfetlPrecheckRun submit(Long runId) {
        DfetlPrecheckRun run = requireRun(runId);
        requireStatus(run, "PENDING");
        InstitutionDatasetRoute route = requireRoute(run);

        try {
            SourceDataSource source = sourceRepository.findById(route.getSourceDatasourceId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "源数据源不存在: " + route.getSourceDatasourceId()));
            TargetDataSource target = targetRepository.findById(route.getTargetDatasourceId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "目标数据源不存在: " + route.getTargetDatasourceId()));
            MedicalDatasetContract contract = contractSnapshotService.load(
                    run.getDatasetId(), route.getTargetTable());
            List<SourceDataSourceService.ColumnInfo> sourceColumns = sourceDataSourceService.listColumns(
                    route.getSourceDatasourceId(), route.getSourceSchema(), route.getSourceObject());
            List<PrecheckSourceProjectionBuilder.ProjectionField> fields =
                    resolveProjectionFields(contract, sourceColumns);
            List<String> stagingColumns = fields.stream()
                    .map(PrecheckSourceProjectionBuilder.ProjectionField::stagingColumn)
                    .toList();
            DorisPrecheckTableSpec spec = storageService.ensureStorage(
                    route.getTargetDatasourceId(), contract.datasetCode(), stagingColumns);

            Instant startedAt = run.getStartedAt() == null ? Instant.now() : run.getStartedAt();
            String stagingTable = spec.database() + "." + spec.rawTable();
            int claimed = runRepository.claimPendingForLoading(
                    run.getId(), startedAt, stagingTable);
            if (claimed != 1) {
                throw new PrecheckClaimException(
                        "数据预检 PENDING 运行已被并发占用，禁止重复提交: runId=" + run.getId());
            }
            run.setStatus("LOADING");
            run.setStage("LOADING");
            run.setProgressPercent((short) 10);
            run.setStagingTable(stagingTable);
            run.setStartedAt(startedAt);
            run.setErrorMessage(null);

            Map<String, Object> jobMap = confBuilder.buildJobMap(
                    run.getId(), source, target, spec,
                    route.getSourceSchema(), route.getSourceObject(), fields);
            SeaTunnelRestClient.SubmitResult submitResult;
            try {
                submitResult = restClient.submitJob(jobMap);
            } catch (RuntimeException e) {
                return markReconcileLoading(run,
                        "SeaTunnel 预检作业提交异常，远端结果未知: " + e.getMessage());
            }
            if (!submitResult.success()) {
                return markReconcileLoading(run,
                        "SeaTunnel 预检作业提交未确认: " + submitResult.errorMsg());
            }
            run.setEngineJobId(submitResult.jobId());
            run.setProgressPercent((short) 20);
            run.setErrorMessage(null);
            return runRepository.save(run);
        } catch (PrecheckClaimException e) {
            throw e;
        } catch (RuntimeException e) {
            failRun(run, "初始化或提交 SeaTunnel 预检作业失败: " + e.getMessage());
            throw e;
        }
    }

    /** 拉取一次远端状态；只有明确 SUCCESS 才允许进入 VALIDATING。 */
    public DfetlPrecheckRun refresh(Long runId) {
        DfetlPrecheckRun run = requireRun(runId);
        requireStatus(run, "LOADING");
        String jobId = requiredJobId(run);
        Optional<SeaTunnelRestClient.JobInfo> jobInfo = restClient.getJobInfo(jobId);
        if (jobInfo.isEmpty()) {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": 无法确认 SeaTunnel 预检作业状态 jobId=" + jobId);
            return runRepository.save(run);
        }
        return applyRemoteState(run, jobInfo.orElseThrow(), false);
    }

    /** 请求停止并查询一次远端终态；未确认终态时保持 LOADING。 */
    public DfetlPrecheckRun cancel(Long runId) {
        DfetlPrecheckRun run = requireRun(runId);
        if ("PENDING".equals(run.getStatus())) {
            return markCancelled(run, "预检尚未提交 SeaTunnel，已取消");
        }
        requireStatus(run, "LOADING");
        String jobId = requiredJobId(run);
        SeaTunnelRestClient.StopResult stop = restClient.stopJob(jobId, true);
        if (!stop.success()) {
            run.setErrorMessage(safe(RECONCILE_REQUIRED
                    + ": SeaTunnel stop-job 失败，未确认停止 jobId=" + jobId
                    + "; " + stop.errorMsg()));
            return runRepository.save(run);
        }
        if (!jobId.equals(stop.jobId())) {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": SeaTunnel stop-job 返回不同 jobId，未确认停止 expected="
                    + jobId + " actual=" + stop.jobId());
            return runRepository.save(run);
        }
        Optional<SeaTunnelRestClient.JobInfo> info = restClient.getJobInfo(jobId);
        if (info.isEmpty()) {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": SeaTunnel stop-job 已受理但未确认停止 jobId=" + jobId);
            return runRepository.save(run);
        }
        return applyRemoteState(run, info.orElseThrow(), true);
    }

    private DfetlPrecheckRun applyRemoteState(
            DfetlPrecheckRun run,
            SeaTunnelRestClient.JobInfo info,
            boolean cancellationRequested) {
        updateMetrics(run, info);
        String mapped = normalize(info.mappedStatus());
        if ("SUCCESS".equals(mapped)) {
            run.setStatus("VALIDATING");
            run.setStage("VALIDATING");
            run.setProgressPercent((short) 65);
            run.setErrorMessage(null);
            return runRepository.save(run);
        }
        if ("FAILED".equals(mapped)) {
            if (cancellationRequested) {
                return markCancelled(run,
                        "SeaTunnel 预检作业停止后确认终态 FAILED jobId=" + run.getEngineJobId());
            }
            return failRun(run, "SeaTunnel 预检加载失败 jobId=" + run.getEngineJobId()
                    + ": " + info.errorMsg());
        }
        if ("CANCELLED".equals(mapped)) {
            return markCancelled(run,
                    "SeaTunnel 预检作业已取消 jobId=" + run.getEngineJobId());
        }
        if (cancellationRequested) {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": SeaTunnel stop-job 后未确认停止 jobId=" + run.getEngineJobId()
                    + " remoteStatus=" + valueOrUnknown(info.jobStatus()));
        } else if ("RUNNING".equals(mapped) || "SCHEDULED".equals(mapped)) {
            run.setProgressPercent((short) Math.max(run.getProgressPercent(), 40));
            run.setErrorMessage(null);
        } else {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": SeaTunnel 返回未知状态 jobId=" + run.getEngineJobId()
                    + " remoteStatus=" + valueOrUnknown(info.jobStatus()));
        }
        return runRepository.save(run);
    }

    private List<PrecheckSourceProjectionBuilder.ProjectionField> resolveProjectionFields(
            MedicalDatasetContract contract,
            List<SourceDataSourceService.ColumnInfo> sourceColumns) {
        if (contract == null || contract.fields() == null || contract.fields().isEmpty()) {
            throw new IllegalStateException("数据预检字段契约不能为空");
        }
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            throw new IllegalStateException("源视图字段不能为空");
        }
        List<PrecheckSourceProjectionBuilder.ProjectionField> fields = new ArrayList<>();
        for (MedicalFieldContract field : contract.fields()) {
            List<SourceDataSourceService.ColumnInfo> matched = sourceColumns.stream()
                    .filter(column -> column.columnName() != null
                            && column.columnName().equalsIgnoreCase(field.code()))
                    .toList();
            if (matched.size() != 1) {
                throw new IllegalStateException("标准字段必须在源视图唯一命中: "
                        + field.code() + "，命中数=" + matched.size());
            }
            SourceDataSourceService.ColumnInfo source = matched.getFirst();
            fields.add(new PrecheckSourceProjectionBuilder.ProjectionField(
                    source.columnName(), field.dorisColumn(), source.dataType()));
        }
        return List.copyOf(fields);
    }

    private InstitutionDatasetRoute requireRoute(DfetlPrecheckRun run) {
        InstitutionDatasetRoute route = routeRepository.findById(run.getRouteId())
                .orElseThrow(() -> new NoSuchElementException(
                        "机构数据集路由不存在: " + run.getRouteId()));
        if (!run.getDatasetId().equals(route.getDatasetId())
                || !run.getInstitutionId().equals(route.getInstitutionId())) {
            throw new IllegalStateException("预检运行与机构采集路由不一致: runId=" + run.getId());
        }
        return route;
    }

    private DfetlPrecheckRun requireRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须大于 0");
        }
        return runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
    }

    private void requireStatus(DfetlPrecheckRun run, String expected) {
        if (!expected.equals(run.getStatus())) {
            throw new IllegalStateException(
                    "数据预检运行状态必须为 " + expected + "，当前为: " + run.getStatus());
        }
    }

    private String requiredJobId(DfetlPrecheckRun run) {
        String jobId = run.getEngineJobId();
        if (jobId == null || jobId.isBlank()) {
            run.setErrorMessage(RECONCILE_REQUIRED
                    + ": LOADING 运行缺少 engineJobId，禁止重提或假定已停止");
            runRepository.save(run);
            throw new IllegalStateException(run.getErrorMessage());
        }
        return jobId;
    }

    private DfetlPrecheckRun failRun(DfetlPrecheckRun run, String error) {
        run.setStatus("FAILED");
        run.setStage("COMPLETED");
        run.setProgressPercent((short) 100);
        run.setErrorMessage(safe(error));
        run.setFinishedAt(Instant.now());
        return runRepository.save(run);
    }

    private DfetlPrecheckRun markCancelled(DfetlPrecheckRun run, String detail) {
        run.setStatus("CANCELLED");
        run.setStage("COMPLETED");
        run.setProgressPercent((short) 100);
        run.setErrorMessage(safe(detail));
        run.setFinishedAt(Instant.now());
        return runRepository.save(run);
    }

    private DfetlPrecheckRun markReconcileLoading(DfetlPrecheckRun run, String detail) {
        run.setStatus("LOADING");
        run.setStage("LOADING");
        run.setErrorMessage(safe(RECONCILE_REQUIRED + ": " + detail));
        run.setFinishedAt(null);
        return runRepository.save(run);
    }

    private void updateMetrics(DfetlPrecheckRun run, SeaTunnelRestClient.JobInfo info) {
        run.setSourceRows(Math.max(value(run.getSourceRows()), info.readRows()));
        run.setLoadedRows(Math.max(value(run.getLoadedRows()), info.writeRows()));
    }

    private long value(Long count) {
        return count == null ? 0 : count;
    }

    private String safe(String error) {
        return ExecutionErrorSanitizer.sanitize(error);
    }

    private String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static final class PrecheckClaimException extends IllegalStateException {
        private PrecheckClaimException(String message) {
            super(message);
        }
    }
}

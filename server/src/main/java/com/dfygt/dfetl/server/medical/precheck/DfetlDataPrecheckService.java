package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.dto.DfetlPrecheckRunDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthorizationService;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContractService;
import com.dfygt.dfetl.server.service.DfetlContractSnapshotService;
import com.dfygt.dfetl.server.service.InstitutionDatasetRouteValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Locale;

/**
 * 标准数据集路由全量预检的生命周期编排。
 * 正式任务不再重复扫描执行窗口，而是在启动前校验最近一次全量预检快照。
 */
@Service
@RequiredArgsConstructor
public class DfetlDataPrecheckService {

    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "LOADING", "VALIDATING");
    private static final List<String> TERMINAL_STATUSES =
            List.of("HAS_ERRORS", "PASSED", "FAILED", "CANCELLED");

    private final InstitutionDatasetRouteRepository routeRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionRepository institutionRepository;
    private final ExternalApiAuthorizationService authorizationService;
    private final DfetlPrecheckWorker worker;
    private final PrecheckLoadService loadService;
    private final InstitutionDatasetRouteValidationService routeValidationService;
    private final DfetlContractSnapshotService contractService;
    private final TargetWriteContractService targetContractService;

    public DfetlPrecheckRunDto runRoute(Long routeId) {
        RunContext context = currentContext(requiredId(routeId, "routeId"));
        assertActualStructure(context);
        DfetlPrecheckRun run = createPending(context, null);
        worker.enqueue(run.getId());
        return DfetlPrecheckRunDto.from(run);
    }

    public DfetlPrecheckRunDto retry(Long runId) {
        DfetlPrecheckRun previous = getAuthorizedRun(runId);
        if (!"ROUTE_FULL".equals(previous.getRunType())) {
            throw new IllegalArgumentException("只有路由全量预检可以人工重新预检");
        }
        if (!TERMINAL_STATUSES.contains(previous.getStatus())) {
            throw new IllegalStateException("只有终态预检运行可以重试，当前为: " + previous.getStatus());
        }
        RunContext context = currentContext(previous.getRouteId());
        assertActualStructure(context);
        DfetlPrecheckRun run = createPending(context, previous.getId());
        worker.enqueue(run.getId());
        return DfetlPrecheckRunDto.from(run);
    }

    public DfetlPrecheckRunDto cancel(Long runId) {
        DfetlPrecheckRun run = getAuthorizedRun(runId);
        if (run.isTerminal()) {
            return DfetlPrecheckRunDto.from(run);
        }
        if ("PENDING".equals(run.getStatus()) || "LOADING".equals(run.getStatus())) {
            return DfetlPrecheckRunDto.from(loadService.cancel(run.getId()));
        }
        if ("VALIDATING".equals(run.getStatus())) {
            run.transitionTo("CANCELLED", "COMPLETED", (short) 100);
            run.setFinishedAt(Instant.now());
            run.setErrorMessage("用户取消 Doris 预检校验；当前批次完成后停止");
            return DfetlPrecheckRunDto.from(runRepository.save(run));
        }
        throw new IllegalStateException("当前预检状态不可取消: " + run.getStatus());
    }

    public List<DfetlPrecheckRunDto> listByRoute(Long routeId) {
        RunContext context = currentContext(requiredId(routeId, "routeId"));
        authorizeInstitution(context.route().getInstitutionId());
        return runRepository.findByRouteIdOrderByCreatedAtDesc(context.route().getId())
                .stream().map(DfetlPrecheckRunDto::from).toList();
    }

    public DfetlPrecheckRunDto get(Long runId) {
        return DfetlPrecheckRunDto.from(getAuthorizedRun(runId));
    }

    private RunContext currentContext(Long routeId) {
        InstitutionDatasetRoute route = routeRepository.findById(requiredId(routeId, "routeId"))
                .orElseThrow(() -> new NoSuchElementException("机构数据集路由不存在: " + routeId));
        DfetlDataset dataset = datasetRepository.findById(route.getDatasetId())
                .orElseThrow(() -> new NoSuchElementException(
                        "标准数据集不存在: " + route.getDatasetId()));
        authorizeInstitution(route.getInstitutionId());
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw new IllegalStateException("标准数据集已作废，不能预检: " + dataset.getDatasetCode());
        }
        if (dataset.getDatasetCode() == null
                || !dataset.getDatasetCode().toUpperCase(Locale.ROOT).startsWith("ODS_YL_")) {
            throw new IllegalStateException("数据预检仅支持 ODS_YL_ 标准数据集: " + dataset.getDatasetCode());
        }
        return new RunContext(route, dataset);
    }

    /**
     * 每次启动都读取当前 PostgreSQL 视图和 Doris 正式表；历史路由状态与 contract_hash
     * 仅用于审计，不再作为数据预检门禁。
     */
    private void assertActualStructure(RunContext context) {
        InstitutionDatasetRoute route = context.route();
        InstitutionDatasetRouteValidationService.Result sourceResult =
                routeValidationService.validate(route);
        if (!sourceResult.passed()) {
            throw new IllegalStateException(
                    "PostgreSQL 视图实际结构不可用于数据预检: " + sourceResult.summary());
        }
        MedicalDatasetContract contract = contractService.load(
                context.dataset().getId(), route.getTargetTable());
        targetContractService.resolve(
                route.getTargetDatasourceId(), route.getTargetTable(), contract);
    }

    private DfetlPrecheckRun createPending(RunContext context, Long retryOfRunId) {
        InstitutionDatasetRoute route = context.route();
        DfetlDataset dataset = context.dataset();
        if (runRepository.existsByRouteIdAndContractHashAndRouteRevisionAndStatusIn(
                route.getId(), dataset.getContractHash(), route.getRouteRevision(), ACTIVE_STATUSES)) {
            throw new IllegalStateException(
                    "当前路由、合同和修订已有正在运行的数据预检: routeId=" + route.getId());
        }
        DfetlPrecheckRun run = new DfetlPrecheckRun();
        run.setRouteId(route.getId());
        run.setDatasetId(route.getDatasetId());
        run.setInstitutionId(route.getInstitutionId());
        run.setRetryOfRunId(retryOfRunId);
        run.setRunType("ROUTE_FULL");
        run.setScopeType("FULL");
        run.setContractHash(dataset.getContractHash());
        run.setRouteRevision(route.getRouteRevision());
        run.setStatus("PENDING");
        run.setStage("PREPARING");
        run.setProgressPercent((short) 0);
        try {
            return runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(
                    "当前路由、合同和修订已有正在运行的数据预检: routeId=" + route.getId(), e);
        }
    }

    private DfetlPrecheckRun getAuthorizedRun(Long runId) {
        DfetlPrecheckRun run = getRun(runId);
        authorizeInstitution(run.getInstitutionId());
        return run;
    }

    private void authorizeInstitution(Long institutionId) {
        Institution institution = institutionRepository.findById(requiredId(institutionId, "institutionId"))
                .orElseThrow(() -> new NoSuchElementException("机构不存在: " + institutionId));
        authorizationService.assertAllowed(institution.getCode());
    }

    private DfetlPrecheckRun getRun(Long runId) {
        return runRepository.findById(requiredId(runId, "runId"))
                .orElseThrow(() -> new NoSuchElementException("数据预检批次不存在: " + runId));
    }

    private static Long requiredId(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
        return value;
    }

    private record RunContext(InstitutionDatasetRoute route, DfetlDataset dataset) {
    }
}

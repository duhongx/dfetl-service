package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContractService;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.service.DfetlContractSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 有界异步推进一次路由全量预检，不创建第二套 sync_task。 */
@Service
@Slf4j
public class DfetlPrecheckWorker {

    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "LOADING", "VALIDATING");

    private final DfetlPrecheckRunRepository runRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final DfetlContractSnapshotService contractService;
    private final TargetWriteContractService targetContractService;
    private final DorisPrecheckStorageService storageService;
    private final PrecheckLoadService loadService;
    private final DorisPrecheckValidationService validationService;
    private final PrecheckRunLock runLock;
    private final Executor executor;
    private final Set<Long> localActiveRuns = ConcurrentHashMap.newKeySet();

    public DfetlPrecheckWorker(
            DfetlPrecheckRunRepository runRepository,
            InstitutionDatasetRouteRepository routeRepository,
            DfetlDatasetRepository datasetRepository,
            DfetlContractSnapshotService contractService,
            TargetWriteContractService targetContractService,
            DorisPrecheckStorageService storageService,
            PrecheckLoadService loadService,
            DorisPrecheckValidationService validationService,
            PrecheckRunLock runLock,
            @Qualifier("precheckExecutor") Executor executor) {
        this.runRepository = runRepository;
        this.routeRepository = routeRepository;
        this.datasetRepository = datasetRepository;
        this.contractService = contractService;
        this.targetContractService = targetContractService;
        this.storageService = storageService;
        this.loadService = loadService;
        this.validationService = validationService;
        this.runLock = runLock;
        this.executor = executor;
    }

    /** 入队失败时保留 PENDING/活动事实，定时恢复器下一轮继续处理。 */
    public boolean enqueue(Long runId) {
        if (runId == null || runId <= 0 || !localActiveRuns.add(runId)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    boolean acquired = runLock.runIfAcquired(runId, () -> processOne(runId));
                    if (!acquired) {
                        log.debug("DfetlPrecheckWorker: another instance owns runId={}", runId);
                    }
                } catch (Exception e) {
                    String safeError = ExecutionErrorSanitizer.sanitize(e.getMessage());
                    markLocalStageFailed(runId, safeError);
                    log.error("DfetlPrecheckWorker: advance failed runId={}: {}",
                            runId, safeError);
                } finally {
                    localActiveRuns.remove(runId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            localActiveRuns.remove(runId);
            log.warn("DfetlPrecheckWorker: bounded queue full, run remains recoverable runId={}", runId);
            return false;
        }
    }

    /** 每次只推进一个远端可确认阶段，避免工作线程阻塞等待长作业。 */
    public DfetlPrecheckRun processOne(Long runId) {
        DfetlPrecheckRun run = requireRun(runId);
        if ("PENDING".equals(run.getStatus())) {
            prepare(run);
            return loadService.submit(runId);
        }
        if ("LOADING".equals(run.getStatus())) {
            if (run.getEngineJobId() == null || run.getEngineJobId().isBlank()) {
                return run;
            }
            return loadService.refresh(runId);
        }
        if ("VALIDATING".equals(run.getStatus())) {
            ValidationContext context = validationContext(run);
            return validationService.validate(
                    runId,
                    context.route().getTargetDatasourceId(),
                    context.tableSpec(),
                    context.contract(),
                    context.targetContract());
        }
        return run;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveRuns() {
        activeRuns().forEach(run -> enqueue(run.getId()));
    }

    @Scheduled(
            fixedDelayString = "${dfetl.data-precheck.worker.poll-delay-ms:5000}",
            initialDelayString = "${dfetl.data-precheck.worker.initial-delay-ms:5000}")
    public void pollActiveRuns() {
        activeRuns().forEach(run -> enqueue(run.getId()));
    }

    private List<DfetlPrecheckRun> activeRuns() {
        return runRepository.findByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES);
    }

    /**
     * PREPARING/VALIDATING 都是本地可判定阶段，异常后应终止；LOADING 可能仍有远端
     * SeaTunnel 作业运行，必须保留给对账恢复，禁止盲目标记失败。
     */
    private void markLocalStageFailed(Long runId, String safeError) {
        runRepository.findById(runId).ifPresent(latest -> {
            if (!"PENDING".equals(latest.getStatus())
                    && !"VALIDATING".equals(latest.getStatus())) {
                return;
            }
            latest.transitionTo("FAILED", "COMPLETED", (short) 100);
            latest.setErrorMessage(safeError);
            latest.setFinishedAt(Instant.now());
            runRepository.save(latest);
        });
    }

    private void prepare(DfetlPrecheckRun run) {
        ValidationContext context = validationContext(run);
        run.setTargetSchemaHash(PrecheckTargetSchemaHasher.hash(context.targetContract()));
        runRepository.save(run);
    }

    private ValidationContext validationContext(DfetlPrecheckRun run) {
        InstitutionDatasetRoute route = routeRepository.findById(run.getRouteId())
                .orElseThrow(() -> new NoSuchElementException(
                        "机构数据集路由不存在: " + run.getRouteId()));
        if (!run.getDatasetId().equals(route.getDatasetId())
                || !run.getInstitutionId().equals(route.getInstitutionId())
                || !run.getRouteRevision().equals(route.getRouteRevision())) {
            throw new IllegalStateException("预检运行快照与当前路由不一致: runId=" + run.getId());
        }
        DfetlDataset dataset = datasetRepository.findById(run.getDatasetId())
                .orElseThrow(() -> new NoSuchElementException(
                        "标准数据集不存在: " + run.getDatasetId()));
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw new IllegalStateException("数据集已失效，不能继续数据预检: runId=" + run.getId());
        }
        MedicalDatasetContract contract = contractService.load(
                run.getDatasetId(), route.getTargetTable());
        TargetWriteContract targetContract = targetContractService.resolve(
                route.getTargetDatasourceId(), route.getTargetTable(), contract);
        List<String> targetColumns = contract.fields().stream()
                .map(field -> field.dorisColumn().toLowerCase(Locale.ROOT))
                .toList();
        DorisPrecheckTableSpec tableSpec = storageService.ensureStorage(
                route.getTargetDatasourceId(), dataset.getDatasetCode(), targetColumns);
        return new ValidationContext(
                route, contract, targetContract, tableSpec);
    }

    private DfetlPrecheckRun requireRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正整数");
        }
        return runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("数据预检运行不存在: " + runId));
    }

    private record ValidationContext(
            InstitutionDatasetRoute route,
            MedicalDatasetContract contract,
            TargetWriteContract targetContract,
            DorisPrecheckTableSpec tableSpec) {
    }
}

package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.config.DiffProperties;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.EtlVerifyDiffField;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffFieldRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Spec 056 — 字段级差异异步预计算服务。
 *
 * <p>由 {@code ChecksumService.verify()} 在 diffCount > 0 时异步触发；
 * 单 ValidationRun 内幂等（先 deleteByValidationRunId 再 saveAll），并按 {@link DiffProperties#precomputeParallelism()}
 * 限制全局并发，避免 JDBC 连接被压爆。
 *
 * <p>所有落库内容均经过 spec 055 的脱敏 / 截断流水线，且仅存 display + hash，**不存原始字段值**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiffFieldPrecomputeService {

    private static final int BATCH_SIZE = 500;

    private final EtlVerifyDiffRepository diffRepo;
    private final EtlVerifyDiffFieldRepository diffFieldRepo;
    private final DiffFieldDetailService detailService;
    private final DiffProperties props;
    private final ValidationRunService validationRunService;

    private Semaphore parallelismGate;

    @PostConstruct
    void init() {
        int permits = props.precomputeParallelism();
        this.parallelismGate = new Semaphore(permits, true);
        log.info("DiffFieldPrecomputeService initialized: enabled={} topN={} parallelism={} includeEqual={}",
                props.precomputeEnabled(), props.precomputeTopN(), permits, props.precomputeIncludeEqual());
    }

    /**
     * 异步触发：立即返回，工作放虚拟线程；按 Semaphore 排队。
     * 关闭开关或参数非法时直接 no-op。
     */
    public void precomputeAsync(Long taskId, Long execId) {
        if (!Boolean.TRUE.equals(props.precomputeEnabled())) {
            log.debug("Precompute disabled, skip taskId={} execId={}", taskId, execId);
            return;
        }
        if (taskId == null || execId == null) return;
        Thread.ofVirtual().name("diff-precompute-" + execId).start(() -> {
            try {
                if (!parallelismGate.tryAcquire(0, TimeUnit.MILLISECONDS)) {
                    log.info("Precompute taskId={} execId={} 等待并发槽位", taskId, execId);
                    parallelismGate.acquire();
                }
                try {
                    precompute(taskId, execId);
                } finally {
                    parallelismGate.release();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Precompute interrupted: taskId={} execId={}", taskId, execId);
            } catch (Exception e) {
                log.error("Precompute failed: taskId={} execId={}", taskId, execId, e);
            }
        });
    }

    /**
     * 同步执行：删除该 execId 已有的字段级差异 → 取前 N 条 diff → 调 detailInternal → batch save。
     */
    public void precompute(Long taskId, Long execId) {
        long startMs = System.currentTimeMillis();
        int topN = props.precomputeTopN();
        boolean includeEqual = Boolean.TRUE.equals(props.precomputeIncludeEqual());
        Long validationRunId = validationRunService.findByTaskIdAndLegacyExecId(taskId, execId)
                .map(ValidationRun::getId)
                .orElse(null);
        if (validationRunId == null) {
            log.warn("Precompute taskId={} execId={} 未找到 ValidationRun，跳过字段级差异预计算", taskId, execId);
            return;
        }

        // 幂等：先清理
        int deleted = diffFieldRepo.deleteByValidationRunId(validationRunId);
        if (deleted > 0) {
            log.info("Precompute taskId={} execId={} runId={} 清理旧字段级差异 {} 行",
                    taskId, execId, validationRunId, deleted);
        }

        var page = diffRepo.findByTaskIdAndValidationRunId(taskId, validationRunId,
                PageRequest.of(0, topN, Sort.by(Sort.Direction.ASC, "id")));
        List<EtlVerifyDiff> diffs = page.getContent();
        if (diffs.isEmpty()) {
            log.info("Precompute taskId={} execId={} 无 diff 行，跳过", taskId, execId);
            return;
        }

        List<EtlVerifyDiffField> buffer = new ArrayList<>(BATCH_SIZE);
        int totalRows = 0;
        int failures = 0;
        for (EtlVerifyDiff d : diffs) {
            try {
                DiffFieldDetailService.InternalReport ir = detailService.detailInternal(d.getId(), includeEqual);
                List<DiffFieldDetailService.FieldDiff> fields = ir.report().fields();
                List<DiffFieldDetailService.FieldHash> hashes = ir.hashes();
                // hashes 与计算时的全列同序对齐；report.fields 可能因 showEqual=false 被过滤
                // 这里 includeEqual 已经控制了 report.fields，hashes 也按 fields 同步生成
                int n = Math.min(fields.size(), hashes.size());
                for (int i = 0; i < n; i++) {
                    DiffFieldDetailService.FieldDiff f = fields.get(i);
                    DiffFieldDetailService.FieldHash h = hashes.get(i);
                    EtlVerifyDiffField row = new EtlVerifyDiffField();
                    row.setDiffId(d.getId());
                    row.setTaskId(taskId);
                    row.setExecId(execId);
                    row.setValidationRunId(validationRunId);
                    row.setColumnName(f.column());
                    row.setTargetColumn(f.targetColumn());
                    row.setDiffKind(f.diffKind());
                    row.setSrcValueDisplay(f.srcValueDisplay());
                    row.setTgtValueDisplay(f.tgtValueDisplay());
                    row.setSrcValueHash(h.srcValueHash());
                    row.setTgtValueHash(h.tgtValueHash());
                    row.setMasked(f.masked());
                    row.setTruncated(f.truncated());
                    row.setNormalizedDiffer(f.normalizedDiffer());
                    buffer.add(row);
                    totalRows++;
                    if (buffer.size() >= BATCH_SIZE) {
                        diffFieldRepo.saveAll(buffer);
                        buffer.clear();
                    }
                }
            } catch (Exception e) {
                failures++;
                log.warn("Precompute diffId={} 失败: {}", d.getId(), e.getMessage());
            }
        }
        if (!buffer.isEmpty()) diffFieldRepo.saveAll(buffer);

        log.info("Precompute done: taskId={} execId={} diffs={} rows={} failures={} {}ms",
                taskId, execId, diffs.size(), totalRows, failures, System.currentTimeMillis() - startMs);
    }
}

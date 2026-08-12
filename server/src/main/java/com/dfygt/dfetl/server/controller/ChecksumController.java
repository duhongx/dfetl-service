package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.entity.EtlVerifyChunk;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.EtlVerifyChunkRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.service.ChecksumService;
import com.dfygt.dfetl.server.service.DiffFieldCsvExportService;
import com.dfygt.dfetl.server.service.DiffFieldDetailService;
import com.dfygt.dfetl.server.service.ValidationDispatchService;
import com.dfygt.dfetl.server.service.ValidationRunService;
import com.dfygt.dfetl.server.service.WatermarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Spec 023：Checksum 引擎 REST 入口。
 */
@RestController
@RequestMapping("/api/sync-task/{taskId}/checksum")
@RequiredArgsConstructor
public class ChecksumController {

    private final ChecksumService checksumService;
    private final EtlVerifyChunkRepository chunkRepo;
    private final EtlVerifyDiffRepository diffRepo;
    private final DiffFieldDetailService diffFieldDetailService;
    private final DiffFieldCsvExportService diffFieldCsvExportService;
    private final ValidationRunService validationRunService;
    private final ValidationDispatchService validationDispatchService;
    private final TaskValidationConfigRepository taskValidationConfigRepository;
    private final ValidationRunRepository validationRunRepository;

    /** 触发一次 Checksum，返回汇总报告。{@code resumeFromExecId} 非空时启用 spec 032 断点续跑；{@code windowStart}/{@code windowEnd} 非空时启用 spec 048 窗口模式（ISO-8601）。 */
    @PostMapping("/run")
    public ApiResponse<ChecksumService.VerifyReport> run(@PathVariable Long taskId,
                                                         @RequestParam(required = false) Long execId,
                                                         @RequestParam(required = false) Long resumeFromExecId,
                                                         @RequestParam(required = false) Long resumeFromRunId,
                                                         @RequestParam(required = false) String windowStart,
                                                         @RequestParam(required = false) String windowEnd) {
        Instant wsInst = windowStart != null ? Instant.parse(windowStart) : null;
        Instant weInst = windowEnd   != null ? Instant.parse(windowEnd)   : null;
        validateTimeWindow(wsInst, weInst);
        Long resolvedResumeExecId = resumeFromExecId;
        ValidationRun resumeRun = null;
        if (resumeFromRunId != null) {
            resumeRun = validationRunService.findByIdAndTaskId(resumeFromRunId, taskId)
                    .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + resumeFromRunId));
            resolvedResumeExecId = resumeRun.getLegacyExecId();
            if (windowStart == null && windowEnd == null && hasWindow(resumeRun)) {
                wsInst = resumeRun.getWindowStart();
                weInst = resumeRun.getWindowEnd();
            } else {
                assertResumeWindowCompatible(resumeRun, wsInst, weInst);
            }
        }
        String checksumAlgo = taskValidationConfigRepository.findByTaskId(taskId)
                .map(c -> c.getChecksumAlgo())
                .orElse(null);

        Long runExecId = execId != null ? execId : ValidationRunService.nextSyntheticLegacyExecId();
        WatermarkService.WindowContext checksumWindow = buildChecksumWindow(resumeRun, wsInst, weInst);
        String scope = checksumWindow != null && checksumWindow.hasScopedWindow() ? "WINDOW" : "FULL";
        ValidationRun startedRun = validationDispatchService.startManualChecksumRun(
                taskId, runExecId, scope, wsInst, weInst);
        if (startedRun.getLegacyExecId() != null) {
            runExecId = startedRun.getLegacyExecId();
        }
        if (checksumWindow != null && (checksumWindow.windowStartId() != null || checksumWindow.windowEndId() != null)) {
            startedRun.setWindowType("ID_RANGE");
            startedRun.setWindowStartId(checksumWindow.windowStartId());
            startedRun.setWindowEndId(checksumWindow.windowEndId());
            validationRunRepository.save(startedRun);
        }

        ChecksumService.VerifyReport report;
        try {
            report = checksumService.verify(taskId, runExecId, resolvedResumeExecId, checksumWindow, checksumAlgo);
        } catch (Exception ex) {
            startedRun.setStatus("ERROR");
            startedRun.setErrorMsg(ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 2000))
                    : "Unknown error");
            startedRun.setUpdatedAt(java.time.LocalDateTime.now());
            validationRunRepository.save(startedRun);
            throw ex;
        }

        // verify 按同一个 legacyExecId 复用 startedRun；仍优先用 report.runId 精确收尾。
        if (report.runId() != null) {
            var completedRunOpt = validationRunRepository.findById(report.runId());
            if (completedRunOpt.isPresent()) {
                var completedRun = completedRunOpt.get();
                completedRun.setStatus(report.diffCount() == 0 ? "CONSISTENT" : "DIFF");
                completedRun.setDiffRows(report.diffCount());
                completedRun.setSourceRows(report.sourceCount());
                completedRun.setTargetRows(report.targetCount());
                completedRun.setLastRunAt(Instant.now());
                completedRun.setUpdatedAt(java.time.LocalDateTime.now());
                validationRunRepository.save(completedRun);
            }
        } else {
            startedRun.setStatus(report.diffCount() == 0 ? "CONSISTENT" : "DIFF");
            startedRun.setDiffRows(report.diffCount());
            startedRun.setSourceRows(report.sourceCount());
            startedRun.setTargetRows(report.targetCount());
            startedRun.setLastRunAt(Instant.now());
            startedRun.setUpdatedAt(java.time.LocalDateTime.now());
            validationRunRepository.save(startedRun);
        }

        return ApiResponse.ok(report);
    }

    private boolean hasWindow(ValidationRun run) {
        return run != null && (run.getWindowStart() != null
                || run.getWindowEnd() != null
                || run.getWindowStartId() != null
                || run.getWindowEndId() != null);
    }

    private void validateTimeWindow(Instant windowStart, Instant windowEnd) {
        if (windowStart != null && windowEnd != null && !windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("windowStart 必须早于 windowEnd");
        }
    }

    private void assertResumeWindowCompatible(ValidationRun resumeRun, Instant windowStart, Instant windowEnd) {
        if (resumeRun == null || !hasWindow(resumeRun)) {
            return;
        }
        if ((resumeRun.getWindowStartId() != null || resumeRun.getWindowEndId() != null)
                && (windowStart != null || windowEnd != null)) {
            throw new IllegalArgumentException("resumeFromRunId 对应 ID_RANGE 窗口，不能用时间窗口覆盖续跑");
        }
        if (resumeRun.getWindowStart() != null && !Objects.equals(resumeRun.getWindowStart(), windowStart)) {
            throw new IllegalArgumentException("resumeFromRunId 的 windowStart 与请求参数不一致");
        }
        if (resumeRun.getWindowEnd() != null && !Objects.equals(resumeRun.getWindowEnd(), windowEnd)) {
            throw new IllegalArgumentException("resumeFromRunId 的 windowEnd 与请求参数不一致");
        }
    }

    private WatermarkService.WindowContext buildChecksumWindow(ValidationRun resumeRun,
                                                               Instant windowStart,
                                                               Instant windowEnd) {
        Long windowStartId = resumeRun == null ? null : resumeRun.getWindowStartId();
        Long windowEndId = resumeRun == null ? null : resumeRun.getWindowEndId();
        if (windowStart == null && windowEnd == null && windowStartId == null && windowEndId == null) {
            return null;
        }
        return new WatermarkService.WindowContext(
                "INCREMENT",
                windowStart,
                windowEnd,
                windowStartId,
                windowEndId);
    }

    /** 历史：列出该任务最近的 verify-chunk，分页查询避免一次加载全部分片。 */
    @GetMapping("/history")
    public ApiResponse<List<EtlVerifyChunk>> history(@PathVariable Long taskId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "200") int size) {
        PageRequest pr = pageRequest(page, size, Sort.by(
                Sort.Order.desc("execId"),
                Sort.Order.asc("chunkNo")));
        return ApiResponse.ok(chunkRepo.findByTaskId(taskId, pr).getContent());
    }

    /** 某次 verify 的分片明细，分页查询避免一次加载全部分片。 */
    @GetMapping("/{verifyExecId}/chunks")
    public ApiResponse<List<EtlVerifyChunk>> chunks(@PathVariable Long taskId,
                                                    @PathVariable Long verifyExecId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "200") int size) {
        PageRequest pr = pageRequest(page, size, Sort.by("chunkNo").ascending());
        return ApiResponse.ok(chunkRepo.findByTaskIdAndExecId(taskId, verifyExecId, pr).getContent());
    }

    private PageRequest pageRequest(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 200));
        return PageRequest.of(safePage, safeSize, sort);
    }

    /** 某次 verify 的行级 diff（分页）。 */
    @GetMapping("/{verifyExecId}/diffs")
    public ApiResponse<Page<EtlVerifyDiff>> diffs(@PathVariable Long taskId,
                                                  @PathVariable Long verifyExecId,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<EtlVerifyDiff> result = (type == null || type.isBlank())
                ? diffRepo.findByTaskIdAndExecId(taskId, verifyExecId, pr)
                : diffRepo.findByTaskIdAndExecIdAndDiffType(taskId, verifyExecId, type, pr);
        return ApiResponse.ok(result);
    }

    /**
     * Spec 055 / validation-workbench-redesign · Task P0-2.3：
     * 实时展开单条 diff 的字段级差异。
     *
     * <p>{@code showEqual} 默认 {@code true}：保证 INSERT_MISSING / DELETE_MISSING 行
     * 能回读全部字段（diffKind 全是 MISSING_IN_TARGET 或 EXTRA_IN_TARGET）。
     * 若客户端只想看差异列（仅 UPDATE_DIFF / ROW_AUDIT_MISMATCH 场景有意义），
     * 显式传 {@code showEqual=false}。
     */
    @GetMapping("/diff/{diffId}/fields")
    public ApiResponse<DiffFieldDetailService.FieldDiffReport> diffFields(
            @PathVariable Long taskId,
            @PathVariable Long diffId,
            @RequestParam(defaultValue = "true") boolean showEqual) {
        // P1-4：校验 diffId 是否属于当前 taskId
        EtlVerifyDiff diff = diffRepo.findById(diffId)
                .orElseThrow(() -> new NoSuchElementException("Diff not found: " + diffId));
        if (!taskId.equals(diff.getTaskId())) {
            throw new IllegalArgumentException("差异记录不属于当前任务");
        }
        return ApiResponse.ok(diffFieldDetailService.detail(diffId, showEqual));
    }

    /**
     * Spec 056：导出某次 verify 的全部已预计算字段级差异 CSV。
     * <p>仅包含存在于 {@code etl_verify_diff_field} 中的行（未预计算的 diff 不会出现）。
     * <p>表头：diffId,pkValue,diffType,column,targetColumn,diffKind,srcValueDisplay,tgtValueDisplay,masked,truncated,normalizedDiffer
     */
    @GetMapping("/{verifyExecId}/diff/fields/export.csv")
    public ResponseEntity<StreamingResponseBody> exportDiffFieldsCsv(
            @PathVariable Long taskId,
            @PathVariable Long verifyExecId) {
        return diffFieldCsvExportService.exportByExecId(taskId, verifyExecId);
    }
}

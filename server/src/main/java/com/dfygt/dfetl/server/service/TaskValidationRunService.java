package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationRunDto;
import com.dfygt.dfetl.server.entity.EtlVerifyChunk;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.EtlVerifyChunkRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class TaskValidationRunService {

    private final ValidationRunRepository validationRunRepository;
    private final EtlVerifyChunkRepository chunkRepository;
    private final EtlVerifyDiffRepository diffRepository;
    private final TaskExecutionRepository executionRepository;
    private final RepairService repairService;

    @Transactional(readOnly = true)
    public List<ValidationRunDto> listRuns(Long taskId) {
        return validationRunRepository.findByTaskIdOrderByIdDesc(taskId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<EtlVerifyDiff> listRunDiffs(Long taskId, Long runId, String diffType, Pageable pageable) {
        getRunOrThrow(taskId, runId);
        if (StringUtils.hasText(diffType)) {
            return diffRepository.findByTaskIdAndValidationRunIdAndDiffType(taskId, runId, diffType, pageable);
        }
        return diffRepository.findByTaskIdAndValidationRunId(taskId, runId, pageable);
    }

    @Transactional
    public RepairService.RepairReport repairRun(Long taskId,
                                                Long runId,
                                                boolean forceDelete,
                                                boolean dryRun,
                                                Integer maxRows) {
        ValidationRun run = getRunOrThrow(taskId, runId);
        // 工作台是 runId 入口；repair 必须按 validation_run.id 收敛到用户当前选中的执行。
        // spec validation-workbench-redesign · Task P1-6.2：用户从工作台「确认修复」入口触发 → MANUAL
        return repairService.repairRun(taskId, run.getId(), forceDelete, dryRun, maxRows, "MANUAL");
    }

    /**
     * 获取校验运行详情（含 SQL 字段）。
     * 验证 runId 属于当前 taskId，不匹配时抛出 NoSuchElementException。
     */
    @Transactional(readOnly = true)
    public ValidationRunDto getRunDetail(Long taskId, Long runId) {
        ValidationRun run = getRunOrThrow(taskId, runId);
        return toDetailDto(run);
    }

    private ValidationRunDto toDetailDto(ValidationRun run) {
        ValidationRunDto dto = toDto(run);
        // 详情接口额外返回 SQL 字段
        dto.setSourceSql(run.getSourceSql());
        dto.setTargetSql(run.getTargetSql());
        dto.setSourceWhere(run.getSourceWhere());
        dto.setTargetWhere(run.getTargetWhere());
        return dto;
    }

    private ValidationRunDto toDto(ValidationRun run) {
        long totalChunks = chunkRepository.countByValidationRunId(run.getId());
        long matchedChunks = chunkRepository.countByValidationRunIdAndMatchedTrue(run.getId());
        ValidationRunDto dto = new ValidationRunDto();
        dto.setId(run.getId());
        dto.setTaskId(run.getTaskId());
        dto.setLegacyExecId(run.getLegacyExecId());
        dto.setMode(run.getMode());
        dto.setScope(run.getScope());
        dto.setWindowStart(run.getWindowStart());
        dto.setWindowEnd(run.getWindowEnd());
        dto.setTotalChunks(toDtoCount(totalChunks));
        dto.setMatchedChunks(toDtoCount(matchedChunks));
        // 差异行数口径：优先用 etl_verify_diff 明细计数（CHECKSUM/ROW_AUDIT 有行级 diff）；
        // 但 ROW_COUNT 校验只把差异写入 validation_run.diffRows，不产生 etl_verify_diff 明细，
        // 明细计数为 0 会让执行历史误显示「一致/0 差异」。
        // 修复（2026-06-04 批次2）：明细为 0 且 run.diffRows>0 时回退用 run.getDiffRows()，
        // 与 ValidationGoalSummaryService.validationRunSummary 的摘要口径保持一致。
        long diffDetailCount = diffRepository.countByTaskIdAndValidationRunId(run.getTaskId(), run.getId());
        if (diffDetailCount == 0 && run.getDiffRows() != null && run.getDiffRows() > 0) {
            dto.setDiffRows(run.getDiffRows());
        } else {
            dto.setDiffRows(diffDetailCount);
        }
        dto.setCreatedAt(run.getCreatedAt());
        dto.setUpdatedAt(run.getUpdatedAt());
        // spec validation-workbench-redesign · Task P1-5.1：透出 trigger_type 给前端着色
        dto.setTriggerType(run.getTriggerType());
        // P1-1：透出 status/errorMsg/sourceRows/targetRows，前端据此区分 PENDING/RUNNING/ERROR
        dto.setStatus(run.getStatus());
        dto.setErrorMsg(run.getErrorMsg());
        // spec 069：透出非阻塞口径警告（目标端存在 _etl_job_id NULL 历史行）
        dto.setScopeWarning(run.getScopeWarning());
        dto.setSourceRows(run.getSourceRows());
        dto.setTargetRows(run.getTargetRows());
        applyMedicalDiversionSummary(run, dto);
        // 续跑能力：仅 CHECKSUM 类有分片记录且存在未 matched 分片时可续跑；
        // ROW_COUNT（无分片）恒为 false。前端据此显示「继续未完成校验」，不再用 status 近似。
        boolean hasUnmatchedChunk = chunkRepository.existsByValidationRunIdAndMatchedFalse(run.getId());
        dto.setResumable(totalChunks > 0 && hasUnmatchedChunk);
        return dto;
    }

    private void applyMedicalDiversionSummary(ValidationRun run, ValidationRunDto dto) {
        if (run == null || run.getExecutionId() == null || dto == null) {
            return;
        }
        TaskExecution execution = executionRepository.findById(run.getExecutionId()).orElse(null);
        if (execution == null) {
            return;
        }
        dto.setSourceRowsTotal(execution.getSourceRowsTotal());
        dto.setValidSourceRows(execution.getValidSourceRows());
        dto.setExcludedRows(execution.getExcludedRows());
        dto.setWarningRows(execution.getWarningRows());
    }

    private int toDtoCount(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private ValidationRun getRunOrThrow(Long taskId, Long runId) {
        return validationRunRepository.findByIdAndTaskId(runId, taskId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + runId));
    }

    /** 获取某次校验的分片详情 */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> listRunChunks(Long taskId, Long runId, Pageable pageable) {
        getRunOrThrow(taskId, runId);
        return chunkRepository.findByTaskIdAndValidationRunId(taskId, runId, pageable)
                .getContent()
                .stream()
                .map(c -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("chunkNo", c.getChunkNo());
            m.put("chunkStart", c.getChunkStart());
            m.put("chunkEnd", c.getChunkEnd());
            m.put("sourceCount", c.getSourceCount());
            m.put("targetCount", c.getTargetCount());
            m.put("sourceChecksum", c.getSourceChecksum());
            m.put("targetChecksum", c.getTargetChecksum());
            m.put("matched", c.getMatched());
            m.put("finishedAt", c.getFinishedAt());
            return m;
        }).toList();
    }
}

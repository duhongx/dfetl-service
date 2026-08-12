package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class ValidationRunService {

    private final ValidationRunRepository repository;

    private static final AtomicLong SYNTHETIC_LEGACY_EXEC_ID =
            new AtomicLong(-System.currentTimeMillis());

    /**
     * spec validation-workbench-redesign · Task P1-5.2 / Requirement 4 (AC 2) + Property 5
     *
     * <p>合法 trigger_type 枚举：AUTO / AUTO_COUNT / MANUAL / DRIFT / GATE / MANUAL_REPAIR_RECHECK；
     * NULL 仅允许在历史记录上存在，新写入必须显式传入合法枚举之一。
     */
    private static final Set<String> ALLOWED_TRIGGER_TYPES = Set.of(
            "AUTO", "AUTO_COUNT", "MANUAL", "MANUAL_FULL", "DRIFT", "GATE", "MANUAL_REPAIR_RECHECK"
    );

    /**
     * spec validation-workbench-redesign · Task P1-5.2：带 triggerType 的入口。
     *
     * <p>新调用方应使用本入口，以保证 Property 5（trigger_type 单调可观测）。
     * 6-arg 旧重载保留向后兼容（默认 trigger_type=null + 调用方需自行 setTriggerType）。
     *
     * @throws IllegalArgumentException 如果 triggerType 不在合法枚举内（防御性校验，避免脏数据）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized ValidationRun getOrCreate(Long taskId, Long legacyExecId, String mode, String scope,
                                                  Instant windowStart, Instant windowEnd, String triggerType) {
        if (triggerType != null && !ALLOWED_TRIGGER_TYPES.contains(triggerType)) {
            throw new IllegalArgumentException(
                    "Invalid trigger_type: " + triggerType + "; allowed: " + ALLOWED_TRIGGER_TYPES);
        }
        return repository.findByTaskIdAndLegacyExecId(taskId, legacyExecId)
                .orElseGet(() -> {
                    ValidationRun run = new ValidationRun();
                    run.setTaskId(taskId);
                    run.setLegacyExecId(legacyExecId);
                    run.setMode(normalizeMode(mode));
                    run.setScope(normalizeScope(scope));
                    run.setWindowStart(windowStart);
                    run.setWindowEnd(windowEnd);
                    run.setTriggerType(triggerType);
                    run.setUpdatedAt(LocalDateTime.now());
                    try {
                        return repository.save(run);
                    } catch (DataIntegrityViolationException duplicate) {
                        return repository.findByTaskIdAndLegacyExecId(taskId, legacyExecId)
                                .orElseThrow(() -> duplicate);
                    }
                });
    }

    /**
     * 旧版 6-arg 入口（向后兼容）。
     *
     * @deprecated spec validation-workbench-redesign · Task P1-5.2：新代码请使用 7-arg 入口显式
     * 传入 triggerType；本入口的 triggerType 默认为 null（历史数据语义）。
     */
    @Deprecated
    public synchronized ValidationRun getOrCreate(Long taskId, Long legacyExecId, String mode, String scope,
                                                  Instant windowStart, Instant windowEnd) {
        return getOrCreate(taskId, legacyExecId, mode, scope, windowStart, windowEnd, null);
    }

    public Optional<ValidationRun> findByTaskIdAndLegacyExecId(Long taskId, Long legacyExecId) {
        return repository.findByTaskIdAndLegacyExecId(taskId, legacyExecId);
    }

    @Transactional
    public ValidationRun save(ValidationRun run) {
        run.setUpdatedAt(LocalDateTime.now());
        return repository.save(run);
    }

    public Optional<ValidationRun> findByIdAndTaskId(Long runId, Long taskId) {
        return repository.findByIdAndTaskId(runId, taskId);
    }

    public ValidationRun requireByIdAndTaskId(Long runId, Long taskId) {
        return findByIdAndTaskId(runId, taskId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + runId));
    }

    public static long nextSyntheticLegacyExecId() {
        return SYNTHETIC_LEGACY_EXEC_ID.updateAndGet(current -> current >= -1L
                ? -System.currentTimeMillis()
                : current - 1L);
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "CHECKSUM";
        return mode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "FULL";
        return scope.trim().toUpperCase(Locale.ROOT);
    }
}

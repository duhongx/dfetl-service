package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TaskValidationProfileService {

    private final TaskValidationConfigRepository repo;
    private final SyncTaskRepository syncTaskRepo;
    private final EffectiveValidationMethodResolver methodResolver;

    public TaskValidationConfigDto getByTaskId(Long taskId) {
        Optional<TaskValidationConfig> opt = repo.findByTaskId(taskId);
        SyncTask task = syncTaskRepo.findById(taskId).orElse(null);
        String taskName = task == null ? "" : task.getName();
        return opt.map(c -> toDto(c, task))
                .orElse(defaultDto(taskId, task));
    }

    @Transactional
    public TaskValidationConfigDto save(Long taskId, TaskValidationConfigDto dto) {
        TaskValidationConfig config = repo.findByTaskId(taskId)
                .orElseGet(() -> {
                    TaskValidationConfig c = new TaskValidationConfig();
                    c.setTaskId(taskId);
                    return c;
                });

        config.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        config.setMethod(normalizeTaskMethod(dto.getMethod()));
        config.setChecksumAlgo(dto.getChecksumAlgo() != null ? dto.getChecksumAlgo() : "XXHASH64");
        config.setSampleRate(dto.getSampleRate());
        config.setToleranceRows(dto.getToleranceRows() != null ? dto.getToleranceRows() : 0L);
        config.setTolerancePct(dto.getTolerancePct());
        config.setAutoTrigger(dto.getAutoTrigger());
        config.setBlockOnFail(dto.getBlockOnFail());
        config.setDriftCron(dto.getDriftCron() != null && !dto.getDriftCron().isBlank() ? dto.getDriftCron().trim() : null);
        config.setAutoRepair(dto.getAutoRepair() != null ? dto.getAutoRepair() : false);
        if (dto.getAutoRepairMaxRows() != null && dto.getAutoRepairMaxRows() > 0) {
            config.setAutoRepairMaxRows(dto.getAutoRepairMaxRows());
        }
        if (dto.getTargetTables() != null) {
            List<String> targetTables = dto.getTargetTables().stream()
                    .filter(table -> table != null && !table.isBlank())
                    .map(String::trim)
                    .toList();
            config.setTargetTables(targetTables.isEmpty() ? null : String.join(",", targetTables));
        }
        if (dto.getChecksumScope() != null && !dto.getChecksumScope().isBlank()) {
            config.setChecksumScope(dto.getChecksumScope().toUpperCase());
        }
        config.setValidationLookbackHours(dto.getValidationLookbackHours());
        config.setUpdatedAt(Instant.now());
        TaskValidationConfig saved = repo.save(config);

        SyncTask task = syncTaskRepo.findById(taskId).orElse(null);
        return toDto(saved, task);
    }

    @Transactional
    public void delete(Long taskId) {
        if (!repo.existsByTaskId(taskId)) {
            throw new NoSuchElementException("No validation config for task: " + taskId);
        }
        repo.deleteByTaskId(taskId);
    }

    public Optional<TaskValidationConfig> findEntityByTaskId(Long taskId) {
        return repo.findByTaskId(taskId);
    }

    private TaskValidationConfigDto toDto(TaskValidationConfig c, SyncTask task) {
        TaskValidationConfigDto dto = new TaskValidationConfigDto();
        dto.setId(c.getId());
        dto.setTaskId(c.getTaskId());
        dto.setTaskName(task == null ? "" : task.getName());
        dto.setEnabled(c.getEnabled());
        dto.setMethod(c.getMethod());
        dto.setChecksumAlgo(c.getChecksumAlgo());
        dto.setSampleRate(c.getSampleRate());
        dto.setToleranceRows(c.getToleranceRows());
        dto.setTolerancePct(c.getTolerancePct());
        dto.setAutoTrigger(c.getAutoTrigger());
        dto.setBlockOnFail(c.getBlockOnFail());
        List<String> tables = (c.getTargetTables() != null && !c.getTargetTables().isBlank())
                ? Arrays.stream(c.getTargetTables().split(","))
                .filter(table -> table != null && !table.isBlank())
                .map(String::trim)
                .toList()
                : List.of();
        dto.setTargetTables(tables);
        dto.setDriftCron(c.getDriftCron());
        dto.setAutoRepair(c.getAutoRepair());
        dto.setAutoRepairMaxRows(c.getAutoRepairMaxRows());
        dto.setChecksumScope(c.getChecksumScope() != null ? c.getChecksumScope() : "FULL");
        dto.setValidationLookbackHours(c.getValidationLookbackHours());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setUpdatedAt(c.getUpdatedAt());
        enrichEffectiveState(dto, c, task);
        return dto;
    }

    private TaskValidationConfigDto defaultDto(Long taskId, SyncTask task) {
        TaskValidationConfigDto dto = new TaskValidationConfigDto();
        dto.setTaskId(taskId);
        dto.setTaskName(task == null ? "" : task.getName());
        dto.setEnabled(false);
        dto.setMethod(null);
        dto.setChecksumAlgo("XXHASH64");
        dto.setAutoTrigger(null);
        dto.setBlockOnFail(null);
        dto.setToleranceRows(0L);
        dto.setTargetTables(List.of());
        dto.setChecksumScope("FULL");
        enrichEffectiveState(dto, null, task);
        return dto;
    }

    private void enrichEffectiveState(TaskValidationConfigDto dto, TaskValidationConfig config, SyncTask task) {
        dto.setTaskConfigEnabled(methodResolver.isTaskConfigActive(config));
        dto.setEffectiveEnabled(methodResolver.resolveEffectiveEnabled(config));
        dto.setEffectiveMethod(methodResolver.resolveTriggeredMethod(task, config));
        dto.setEffectiveAutoTrigger(methodResolver.resolveEffectiveAutoTrigger(config));
        dto.setEffectiveBlockOnFail(methodResolver.resolveEffectiveBlockOnFail(config));
        dto.setMethodSource(methodResolver.resolveMethodSource(config));
        dto.setAutoTriggerSource(methodResolver.resolveAutoTriggerSource(config));
        dto.setBlockOnFailSource(methodResolver.resolveBlockOnFailSource(config));
    }

    private String normalizeTaskMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }
}

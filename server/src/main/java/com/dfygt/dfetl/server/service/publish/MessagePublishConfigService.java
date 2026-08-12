package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.dto.MessagePublishLogDto;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.MessagePublishConfigRepository;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 消息发布配置服务 — 提供 message_publish_config 的 CRUD 与日志查询。
 * <p>
 * 与 {@link MessagePublishTrigger} 解耦：本服务只负责持久化与 DTO 转换，
 * 触发与重发逻辑保留在 Trigger 中。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePublishConfigService {

    private final MessagePublishConfigRepository configRepository;
    private final MessagePublishLogRepository logRepository;
    private final com.dfygt.dfetl.server.repository.SyncTaskRepository syncTaskRepository;
    private final com.dfygt.dfetl.server.service.SyncTaskService syncTaskService;
    // ── spec message-publish-tenant-scope · Bug 3：headers 派生依赖 ──────────────
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final InstitutionRepository institutionRepository;

    // ── 配置 CRUD ────────────────────────────────────────────────────────────

    /**
     * 按 taskId 查询配置，未配置时返回空。
     */
    @Transactional(readOnly = true)
    public Optional<MessagePublishConfigDto> getConfig(Long taskId) {
        return configRepository.findByTaskId(taskId).map(this::toConfigDto);
    }

    /**
     * 新建配置。若同一 taskId 已存在配置应由 DB 唯一约束阻止。
     * 同时校验同 channel 必须使用同 messageType（防止消费方解析冲突）。
     */
    @Transactional
    public MessagePublishConfigDto createConfig(MessagePublishConfigDto dto) {
        // 任务存在性校验：避免对不存在的 taskId 写入孤儿配置
        if (dto.getTaskId() == null || !syncTaskRepository.existsById(dto.getTaskId())) {
            throw new NoSuchElementException("SyncTask not found: " + dto.getTaskId());
        }
        // RUNNING 守卫：跑动期间改 routeKey/topic 等关键配置会让发布链路读到不一致状态
        syncTaskService.assertNoRunningExecution(dto.getTaskId(), "createMessagePublishConfig");
        validateChannelConsistency(dto, null);
        MessagePublishConfig entity = new MessagePublishConfig();
        applyDtoToEntity(dto, entity, resolveFullSyncModeForCreate(dto.getFullSyncMode()));
        entity.setId(null); // 强制新建
        entity = configRepository.save(entity);
        log.info("MessagePublishConfig created: taskId={}, channel={}", entity.getTaskId(), entity.getChannel());
        return toConfigDto(entity);
    }

    /**
     * 更新指定 taskId 的配置。配置不存在时抛 {@link NoSuchElementException}。
     */
    @Transactional
    public MessagePublishConfigDto updateConfig(Long taskId, MessagePublishConfigDto dto) {
        // 任务存在性校验：避免对不存在的 taskId 调 PUT 仍写入
        if (!syncTaskRepository.existsById(taskId)) {
            throw new NoSuchElementException("SyncTask not found: " + taskId);
        }
        // RUNNING 守卫：跑动期间改 routeKey/topic 等关键配置会让发布链路读到不一致状态
        syncTaskService.assertNoRunningExecution(taskId, "updateMessagePublishConfig");
        MessagePublishConfig existing = configRepository.findByTaskId(taskId)
                .orElseThrow(() -> new NoSuchElementException(
                        "MessagePublishConfig not found for taskId=" + taskId));
        String fullSyncMode = resolveFullSyncModeForUpdate(
                dto.getFullSyncMode(), existing.getFullSyncMode());
        validateChannelConsistency(dto, existing.getId());
        applyDtoToEntity(dto, existing, fullSyncMode);
        // 保护 taskId 不可变
        existing.setTaskId(taskId);
        existing = configRepository.save(existing);
        log.info("MessagePublishConfig updated: taskId={}", taskId);
        return toConfigDto(existing);
    }

    /**
     * 校验同一个 topic 不能绑定多个不同的 messageType（routeKey）。
     * 同 topic 出现多种 routeKey 会导致消费端解析冲突。
     *
     * @param dto       即将保存的配置
     * @param excludeId 排除自身（更新时）
     */
    private void validateChannelConsistency(MessagePublishConfigDto dto, Long excludeId) {
        if (dto.getTopic() == null || dto.getMessageType() == null) return;
        // channel 现在直接等于 topic
        String generatedChannel = dto.getTopic();
        List<MessagePublishConfig> sameChannel = configRepository.findByChannel(generatedChannel);
        for (MessagePublishConfig other : sameChannel) {
            if (excludeId != null && excludeId.equals(other.getId())) continue;
            if (!dto.getMessageType().equals(other.getMessageType())) {
                throw new IllegalArgumentException(
                        "Topic '" + dto.getTopic() + "' 已被任务 " + other.getTaskId()
                                + " 使用，且 routeKey 为 '" + other.getMessageType()
                                + "'，与当前 '" + dto.getMessageType()
                                + "' 冲突。同 topic 必须使用同 routeKey。");
            }
        }
    }

    /**
     * 按 taskId 删除配置。
     */
    @Transactional
    public void deleteConfig(Long taskId) {
        // 任务存在性校验
        if (!syncTaskRepository.existsById(taskId)) {
            throw new NoSuchElementException("SyncTask not found: " + taskId);
        }
        // RUNNING 守卫：跑动期间删配置会让发布链路读到 null 配置而失败
        syncTaskService.assertNoRunningExecution(taskId, "deleteMessagePublishConfig");
        configRepository.deleteByTaskId(taskId);
        log.info("MessagePublishConfig deleted: taskId={}", taskId);
    }

    // ── 日志查询 ─────────────────────────────────────────────────────────────

    /**
     * 分页查询某任务的发布日志。
     */
    @Transactional(readOnly = true)
    public Page<MessagePublishLogDto> getLogs(Long taskId, Pageable pageable) {
        return logRepository.findByTaskId(taskId, pageable).map(this::toLogDto);
    }

    /**
     * 按日志 ID 查询单条发布日志（用于消息样本预览）。
     */
    @Transactional(readOnly = true)
    public Optional<MessagePublishLogDto> getLogById(Long logId) {
        return logRepository.findById(logId).map(this::toLogDto);
    }

    // ── DTO ↔ Entity 转换 ────────────────────────────────────────────────────

    private MessagePublishConfigDto toConfigDto(MessagePublishConfig entity) {
        MessagePublishConfigDto dto = new MessagePublishConfigDto();
        dto.setId(entity.getId());
        dto.setTaskId(entity.getTaskId());
        dto.setEnabled(entity.isEnabled());
        dto.setChannel(entity.getChannel());
        dto.setMessageType(entity.getMessageType());
        dto.setTopic(entity.getTopic());
        dto.setMessageKeyTemplate(entity.getMessageKeyTemplate());
        dto.setFullSyncMode(entity.getFullSyncMode());
        dto.setRateLimit(entity.getRateLimit());
        dto.setPageSize(entity.getPageSize());
        dto.setSourceSystem(entity.getSourceSystem());
        dto.setTenantId(entity.getTenantId());
        dto.setFieldMappingJson(entity.getFieldMappingJson());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private void applyDtoToEntity(MessagePublishConfigDto dto,
                                  MessagePublishConfig entity,
                                  String fullSyncMode) {
        entity.setTaskId(dto.getTaskId());
        entity.setEnabled(dto.isEnabled());
        // channel 自动生成：直接使用 topic 值（topic 即为完整的 Stream key / 队列名）
        String channel = dto.getChannel();
        if (channel == null || channel.isBlank()) {
            channel = dto.getTopic();
        }
        entity.setChannel(channel);
        entity.setMessageType(dto.getMessageType());
        entity.setTopic(dto.getTopic());
        entity.setMessageKeyTemplate(dto.getMessageKeyTemplate());
        entity.setFullSyncMode(fullSyncMode);
        entity.setRateLimit(dto.getRateLimit());
        entity.setPageSize(dto.getPageSize() != null ? dto.getPageSize() : 1000);
        // ── spec message-publish-tenant-scope · Bug 3：headers 派生 ──
        SyncTask task = dto.getTaskId() != null
                ? syncTaskRepository.findById(dto.getTaskId()).orElse(null)
                : null;
        entity.setSourceSystem(resolveSourceSystem(dto.getSourceSystem(), task));
        entity.setTenantId(resolveTenantId(dto.getTenantId(), task));
        entity.setFieldMappingJson(dto.getFieldMappingJson());
    }

    private String resolveFullSyncModeForCreate(String requestedMode) {
        return FullSyncMode.parse(requestedMode).name();
    }

    private String resolveFullSyncModeForUpdate(String requestedMode, String existingMode) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return FullSyncMode.parse(existingMode).name();
        }
        return FullSyncMode.parse(requestedMode).name();
    }

    /**
     * spec message-publish-tenant-scope · Bug 3：source_system 派生
     * 优先级：dto 显式值 → SourceDataSource.sourceCode → "HIS"
     *
     * <p>Visible for testing.
     */
    String resolveSourceSystem(String dtoValue, SyncTask task) {
        if (dtoValue != null && !dtoValue.isBlank()) {
            return dtoValue;
        }
        if (task != null && task.getSourceDataSourceId() != null) {
            String code = sourceDataSourceRepository.findById(task.getSourceDataSourceId())
                    .map(SourceDataSource::getSourceCode)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
            if (code != null) return code;
        }
        return "HIS";
    }

    /**
     * spec message-publish-tenant-scope · Bug 3：tenant_id 派生
     * 优先级：dto 显式值 → Institution.code → String.valueOf(institutionId) → "0"
     *
     * <p>Visible for testing.
     */
    String resolveTenantId(String dtoValue, SyncTask task) {
        if (dtoValue != null && !dtoValue.isBlank()) {
            return dtoValue;
        }
        if (task != null && task.getInstitutionId() != null) {
            String code = institutionRepository.findById(task.getInstitutionId())
                    .map(Institution::getCode)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);
            if (code != null) return code;
            return String.valueOf(task.getInstitutionId());
        }
        return "0";
    }

    private MessagePublishLogDto toLogDto(MessagePublishLog entity) {
        MessagePublishLogDto dto = new MessagePublishLogDto();
        dto.setId(entity.getId());
        dto.setTaskId(entity.getTaskId());
        dto.setBatchId(entity.getBatchId());
        dto.setChannel(entity.getChannel());
        dto.setTopic(entity.getTopic());
        dto.setMessageCount(entity.getMessageCount());
        dto.setStatus(entity.getStatus());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setPublishTime(entity.getPublishTime());
        dto.setDataScope(entity.getDataScope());
        dto.setWindowStart(entity.getWindowStart());
        dto.setWindowEnd(entity.getWindowEnd());
        dto.setSampleMessages(entity.getSampleMessages());
        return dto;
    }
}

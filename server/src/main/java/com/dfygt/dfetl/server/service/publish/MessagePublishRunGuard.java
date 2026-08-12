package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.dto.MessagePublishConfigDto;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 外部同步启动前的消息闭环守卫。
 */
@Service
@RequiredArgsConstructor
public class MessagePublishRunGuard {

    private final TaskExecutionRepository executionRepository;
    private final MessagePublishLogRepository logRepository;
    private final MessagePublishConfigService configService;
    private final MessagePublishProperties properties;

    public void assertReadyForNextRun(Long taskId) {
        MessagePublishConfigDto config = configService.getConfig(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "MESSAGE_CONFIG_MISSING: 外部任务没有消息发布配置"));
        if (!config.isEnabled()) {
            throw new IllegalStateException("MESSAGE_CONFIG_DISABLED: 消息发布配置已禁用");
        }
        if (properties.getTransport() != Transport.RABBITMQ) {
            throw new IllegalStateException("MESSAGE_TRANSPORT_MISMATCH: 当前 server transport="
                    + properties.getTransport() + "，外部工作流要求 RABBITMQ");
        }
        if (!"ALL".equalsIgnoreCase(config.getFullSyncMode())) {
            throw new IllegalStateException("MESSAGE_FULL_SYNC_MODE_MISMATCH: 当前 fullSyncMode="
                    + config.getFullSyncMode() + "，外部首次全量要求 ALL");
        }

        Optional<TaskExecution> latest = executionRepository.findTopByTaskIdOrderByIdDesc(taskId);
        if (latest.isEmpty() || !"SUCCESS".equalsIgnoreCase(latest.get().getStatus())) {
            return;
        }
        // ALL 配置在上一次同步完成后才创建/校准时，历史 execution 不承担新消息义务。
        if (config.getUpdatedAt() != null && latest.get().getFinishedAt() != null
                && config.getUpdatedAt().isAfter(latest.get().getFinishedAt())) {
            return;
        }
        Optional<MessagePublishLog> publish = logRepository
                .findTopByTaskIdAndBatchIdInOrderByPublishTimeDesc(
                        taskId, java.util.List.of(latest.get().getId(), -latest.get().getId()));
        if (publish.isEmpty()) {
            throw new IllegalStateException("MESSAGE_PUBLISH_PENDING: 上一次同步 executionId="
                    + latest.get().getId() + " 尚未建立或完成消息发布");
        }
        String status = publish.get().getStatus();
        if (!"SUCCESS".equalsIgnoreCase(status)) {
            throw new IllegalStateException("MESSAGE_PUBLISH_NOT_CLOSED: 上一次同步 executionId="
                    + latest.get().getId() + " 的消息发布状态为 " + status
                    + "，需先处理或重发成功");
        }
    }
}

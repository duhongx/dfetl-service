package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.repository.MessagePublishConfigRepository;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/**
 * 一次同步 execution 对应的消息发布运行生命周期。
 *
 * <p>原始发布以 taskId + executionId（存入 batch_id）幂等识别。重发会创建新的
 * publish log，并继续保留原 executionId 作为读取范围。</p>
 */
@Service
@RequiredArgsConstructor
public class MessagePublishRunService {

    private final MessagePublishLogRepository logRepository;
    private final MessagePublishConfigRepository configRepository;
    private final MessagePublishProperties properties;

    @Transactional
    public synchronized Long prepareOriginal(Long taskId, Long executionId, String dataScope,
                                             Instant windowStart, Instant windowEnd) {
        if (taskId == null || executionId == null) {
            throw new IllegalArgumentException("taskId 和 executionId 不能为空");
        }
        Optional<MessagePublishConfig> config = configRepository.findByTaskId(taskId)
                .filter(MessagePublishConfig::isEnabled);
        if (config.isEmpty()) {
            return null;
        }
        Optional<MessagePublishLog> existing =
                logRepository.findFirstByTaskIdAndBatchIdOrderByPublishTimeAsc(taskId, executionId);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        MessagePublishLog run = new MessagePublishLog();
        run.setTaskId(taskId);
        run.setBatchId(executionId);
        run.setChannel(config.get().getChannel());
        run.setTopic(config.get().getTopic());
        run.setMessageCount(0);
        run.setStatus("PENDING");
        run.setPublishTime(Instant.now());
        run.setDataScope(dataScope);
        run.setRetryAttempts(0);
        run.setWindowStart(windowStart);
        run.setWindowEnd(windowEnd);
        return logRepository.save(run).getId();
    }

    @Transactional
    public void markRunning(Long publishLogId) {
        updateStatus(publishLogId, "RUNNING", 0, null, null);
    }

    @Transactional
    public void complete(Long publishLogId, int messageCount, String status,
                         String errorMessage, String sampleMessages) {
        updateStatus(publishLogId, status, messageCount, errorMessage, sampleMessages);
    }

    /** Doris 批次尚不可见时登记一次运行级重试；逐条消息聚合 WAIT_RETRY 不调用此方法。 */
    @Transactional
    public void scheduleRetry(Long publishLogId, String errorMessage) {
        MessagePublishLog run = logRepository.findById(publishLogId)
                .orElseThrow(() -> new IllegalStateException(
                        "MessagePublishLog not found: " + publishLogId));
        Instant now = Instant.now();
        int attempts = (run.getRetryAttempts() == null ? 0 : run.getRetryAttempts()) + 1;
        run.setRetryAttempts(attempts);
        run.setMessageCount(0);
        run.setErrorMessage(errorMessage);
        run.setSampleMessages(null);
        run.setPublishTime(now);
        if (attempts >= Math.max(1, properties.getRecoveryMaxAttempts())) {
            run.setStatus("FAILED_FINAL");
            run.setNextRetryTime(null);
        } else {
            run.setStatus("WAIT_RETRY");
            run.setNextRetryTime(now.plusMillis(retryDelayMs(attempts)));
        }
        logRepository.save(run);
    }

    @Transactional
    public Long prepareRetry(MessagePublishLog previous, Long executionId) {
        if (previous == null || previous.getTaskId() == null || executionId == null) {
            throw new IllegalArgumentException("previous publish run 和 executionId 不能为空");
        }
        MessagePublishConfig config = configRepository.findByTaskId(previous.getTaskId())
                .filter(MessagePublishConfig::isEnabled)
                .orElseThrow(() -> new IllegalStateException(
                        "MESSAGE_CONFIG_MISSING_OR_DISABLED: 无法重发"));
        MessagePublishLog retry = new MessagePublishLog();
        retry.setTaskId(previous.getTaskId());
        retry.setBatchId(-Math.abs(executionId));
        retry.setChannel(config.getChannel());
        retry.setTopic(config.getTopic());
        retry.setMessageCount(0);
        retry.setStatus("PENDING");
        retry.setPublishTime(Instant.now());
        retry.setDataScope(previous.getDataScope());
        retry.setRetryAttempts(0);
        retry.setWindowStart(previous.getWindowStart());
        retry.setWindowEnd(previous.getWindowEnd());
        return logRepository.save(retry).getId();
    }

    private void updateStatus(Long publishLogId, String status, int messageCount,
                              String errorMessage, String sampleMessages) {
        if (publishLogId == null) {
            return;
        }
        MessagePublishLog run = logRepository.findById(publishLogId)
                .orElseThrow(() -> new IllegalStateException(
                        "MessagePublishLog not found: " + publishLogId));
        Instant now = Instant.now();
        run.setStatus(status);
        run.setNextRetryTime(null);
        run.setMessageCount(messageCount);
        run.setErrorMessage(errorMessage);
        run.setSampleMessages(sampleMessages);
        run.setPublishTime(now);
        logRepository.save(run);
    }

    /** 多实例安全领取运行级恢复；只领取还没有稳定逐条消息的原始 execution。 */
    @Transactional
    public List<MessagePublishLog> claimRecoverableRuns(
            Instant now, Instant staleBefore, int limit) {
        List<MessagePublishLog> candidates = logRepository.lockRecoverableRunsForUpdate(
                now, staleBefore, Math.max(1, limit));
        List<MessagePublishLog> claimed = new java.util.ArrayList<>(candidates.size());
        for (MessagePublishLog run : candidates) {
            int attempts = run.getRetryAttempts() == null ? 0 : run.getRetryAttempts();
            if (attempts >= Math.max(1, properties.getRecoveryMaxAttempts())) {
                run.setStatus("FAILED_FINAL");
                run.setNextRetryTime(null);
                continue;
            }
            run.setStatus("RUNNING");
            run.setPublishTime(now);
            run.setNextRetryTime(null);
            claimed.add(run);
        }
        logRepository.saveAll(candidates);
        return List.copyOf(claimed);
    }

    private long retryDelayMs(int attempts) {
        long multiplier = 1L << Math.min(20L, Math.max(0L, attempts - 1L));
        long delay;
        try {
            delay = Math.multiplyExact(
                    Math.max(1L, properties.getRecoveryBaseBackoffMs()), multiplier);
        } catch (ArithmeticException ignored) {
            delay = Long.MAX_VALUE;
        }
        return Math.min(delay, 3_600_000L);
    }
}

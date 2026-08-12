package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.entity.MessagePublishLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 恢复 Doris 批次暂不可见、异步线程中断等尚未生成逐条消息的发布运行。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePublishRunRecoveryService {

    private final MessagePublishRunService runService;
    private final MessagePublishTrigger trigger;
    private final MessagePublishProperties properties;

    @Scheduled(
            fixedDelayString = "${dfetl.message-publish.recovery-scan-interval-ms:5000}",
            initialDelayString = "${dfetl.message-publish.recovery-scan-interval-ms:5000}")
    public void scheduledRecover() {
        try {
            recoverOnce();
        } catch (Exception e) {
            log.error("MessagePublishRunRecovery: scan failed", e);
        }
    }

    public int recoverOnce() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(
                Math.max(1L, properties.getRecoveryStaleSeconds()), ChronoUnit.SECONDS);
        List<MessagePublishLog> runs = runService.claimRecoverableRuns(
                now, staleBefore, Math.max(1, properties.getRecoveryBatchSize()));
        for (MessagePublishLog run : runs) {
            trigger.retryExecution(run.getId(), run.getBatchId());
        }
        return runs.size();
    }
}

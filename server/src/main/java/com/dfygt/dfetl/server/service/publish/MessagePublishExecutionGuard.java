package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.entity.MessagePublishLog;
import com.dfygt.dfetl.server.repository.MessagePublishLogRepository;
import com.dfygt.dfetl.server.repository.MessageSendRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在任务再次 UPSERT 前保护尚未物化为稳定消息的上一同步批次。
 *
 * <p>一旦上一批已经写入 {@code message_send_record.message_json/message_id}，后续重试不再依赖
 * Doris 的 {@code _etl_batch_id}，因此可以放行下一次同步。</p>
 */
@Service
@RequiredArgsConstructor
public class MessagePublishExecutionGuard {

    private static final List<String> UNRESOLVED_STATUSES =
            List.of("PENDING", "RUNNING", "WAIT_RETRY");

    private final MessagePublishLogRepository logRepository;
    private final MessageSendRecordRepository sendRecordRepository;

    public void assertCanStart(Long taskId) {
        for (MessagePublishLog run :
                logRepository.findUnresolvedOriginalRuns(taskId, UNRESOLVED_STATUSES)) {
            if (run.getId() == null || sendRecordRepository.countByPublishLogId(run.getId()) == 0) {
                throw new IllegalStateException(
                        "上一批消息尚未形成稳定消息记录，拒绝覆盖 Doris 批次范围: taskId="
                                + taskId + ", executionId=" + run.getBatchId()
                                + ", publishLogId=" + run.getId()
                                + ", status=" + run.getStatus());
            }
        }
    }
}

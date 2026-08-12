package com.dfygt.dfetl.server.service.publish;

import java.time.Instant;

/** 本地审计记录的医共体 msg_send 外部写入终态。 */
public record ExternalRecordUpdate(
        String messageId,
        String status,
        Instant updateTime,
        String error) {
}

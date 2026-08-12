package com.dfygt.dfetl.server.service.publish;

/**
 * 消息头部元数据。
 */
public record MessageHeaders(
    String operation,        // UPSERT / SNAPSHOT / TRUNCATE_BEGIN / TRUNCATE_END / FULL_SYNC_COMPLETE
    String businessKey,      // same as messageKey
    String tenantId,
    String yiLiaoJgDm,       // 医疗机构代码，来源于任务/源端数据源归属机构
    String sourceSystem,
    String traceId           // 追踪 ID
) {}

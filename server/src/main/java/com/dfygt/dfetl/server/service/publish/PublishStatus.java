package com.dfygt.dfetl.server.service.publish;

/**
 * 消息发布操作的结果状态。
 */
public enum PublishStatus {
    /** 全部成功 */
    SUCCESS,
    /** 全部失败 */
    FAILED,
    /** 部分成功 */
    PARTIAL
}

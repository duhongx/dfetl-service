package com.dfygt.dfetl.server.service.publish;

/** Doris 中的同步批次在限定时间内仍不可见，可由发布恢复任务稍后重试。 */
public class MessageBatchNotVisibleException extends RuntimeException {

    public MessageBatchNotVisibleException(String message) {
        super(message);
    }

    public MessageBatchNotVisibleException(String message, Throwable cause) {
        super(message, cause);
    }
}

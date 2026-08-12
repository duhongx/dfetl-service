package com.dfygt.dfetl.server.config.retry;

/**
 * 标记可重试的瞬态异常。
 * 包装原始异常，用于 RetryTemplate 的异常分类器判断是否重试。
 */
public class RetryableException extends RuntimeException {
    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryableException(String message) {
        super(message);
    }
}

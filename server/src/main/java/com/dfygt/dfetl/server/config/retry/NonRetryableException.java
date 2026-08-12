package com.dfygt.dfetl.server.config.retry;

/**
 * 标记不可重试的异常（如 HTTP 4xx、配置错误、SQL 语法错误）。
 * RetryTemplate 遇到此异常时立即终止重试。
 */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonRetryableException(String message) {
        super(message);
    }
}

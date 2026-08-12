package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 连接测试结果
 */
@Getter
@AllArgsConstructor
public class ConnectionTestResult {

    private final boolean success;
    private final String message;

    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message);
    }

    public static ConnectionTestResult fail(String message) {
        return new ConnectionTestResult(false, message);
    }
}

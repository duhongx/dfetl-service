package com.dfygt.dfetl.server.engine.doris;

/**
 * Doris Stream Load 调用结果。
 *
 * @param httpStatus           HTTP 状态码（200=请求成功，但仍要看 status 字段）
 * @param status               Doris 返回 JSON 中的 Status 字段（Success / Fail / Publish Timeout / Label Already Exists）
 * @param numberLoadedRows     成功载入行数
 * @param numberFilteredRows   被过滤行数
 * @param message              Doris 返回的 Message（失败原因）
 * @param label                本次提交使用的 label
 */
public record StreamLoadResult(
        int httpStatus,
        String status,
        long numberLoadedRows,
        long numberFilteredRows,
        String message,
        String label
) {
    public boolean success() {
        return httpStatus / 100 == 2 && ("Success".equalsIgnoreCase(status) || "Publish Timeout".equalsIgnoreCase(status));
    }
}

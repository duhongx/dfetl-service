package com.dfygt.dfetl.server.medical;

/**
 * DDL 生成结果。
 *
 * @param tableName    小写表名
 * @param ddl          完整 CREATE TABLE DDL
 * @param errorMessage 非 null 表示无法生成 DDL（含错误原因）
 */
public record DdlResult(
        String tableName,
        String ddl,
        String errorMessage
) {}

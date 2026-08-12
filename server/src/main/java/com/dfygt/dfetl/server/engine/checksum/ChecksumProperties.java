package com.dfygt.dfetl.server.engine.checksum;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec 023：Checksum 引擎配置。
 *
 * <p>对应 application.yml 中的 {@code dfetl.checksum.*}。
 */
@ConfigurationProperties(prefix = "dfetl.checksum")
public record ChecksumProperties(
        String defaultAlgo,
        Integer chunkSizeRows,
        Integer chunkPageSize,
        Integer parallelism,
        Integer drillDownMaxRows
) {
    public ChecksumProperties {
        if (defaultAlgo == null || defaultAlgo.isBlank()) defaultAlgo = "MD5";
        if (chunkSizeRows == null || chunkSizeRows <= 0) chunkSizeRows = 100_000;
        if (chunkPageSize == null || chunkPageSize <= 0) chunkPageSize = 5_000;
        if (parallelism == null || parallelism <= 0) parallelism = 4;
        if (drillDownMaxRows == null || drillDownMaxRows <= 0) drillDownMaxRows = 50_000;
    }
}

package com.dfygt.dfetl.server.engine.seatunnel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SeaTunnel 执行器配置（spec 015b）。
 *
 * <p>对应 application.yml 中的 {@code dfetl.executor.seatunnel.*}。
 * 默认 {@code enabled=false}，灰度开启。
 */
@ConfigurationProperties(prefix = "dfetl.executor.seatunnel")
public record SeaTunnelProperties(
        boolean enabled,
        Cluster cluster,
        Probe probe
) {

    public SeaTunnelProperties {
        if (cluster == null) cluster = new Cluster(null);
        if (probe == null)   probe   = new Probe(3000, 720);
    }

    public record Cluster(
            String restBaseUrl
    ) {
        public Cluster {
            if (restBaseUrl == null || restBaseUrl.isBlank()) restBaseUrl = "http://127.0.0.1:8080/seatunnel";
        }
    }

    public record Probe(int intervalMs, int maxPollMinutes) {
        public Probe {
            if (intervalMs <= 0)     intervalMs = 3000;
            if (maxPollMinutes <= 0) maxPollMinutes = 720;
        }
    }
}

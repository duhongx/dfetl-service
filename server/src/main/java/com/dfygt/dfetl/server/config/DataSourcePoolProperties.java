package com.dfygt.dfetl.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部数据源（源库 / 目标 Doris）JDBC 连接池配置。
 * <p>
 * 配置前缀 {@code dfetl.datasource-pool}。注意：这与 Spring 自身的 {@code spring.datasource.hikari}
 * （元数据库 PostgreSQL 连接池）完全独立——本池面向"运行时按需连接的多个外部源/目标库"。
 *
 * <p>历史问题：源库 count/审计/字段回读、Doris checksum/diff/补列等热路径各自
 * {@code DriverManager.getConnection} 新建连接、用完即弃（见 ETL_RISK_REGISTER「数据源连接池统一」），
 * 多表大任务一次校验会建立 2N 次连接。本池按"目标库身份 + 凭据"复用连接，封顶并发与池数量。
 */
@Data
@ConfigurationProperties(prefix = "dfetl.datasource-pool")
public class DataSourcePoolProperties {

    /** 是否启用连接池。关闭时回退为每次直连（DriverManager），用于排查回归。 */
    private boolean enabled = true;

    /** 单个外部库连接池的最大连接数。dfetl 自身负载不高，默认 4 足够热路径复用。 */
    private int maxPoolSize = 4;

    /** 单个池的最小空闲连接数。默认 0：空闲时不保活，按需创建，降低对外部库的常驻压力。 */
    private int minIdle = 0;

    /** 获取连接超时（ms）。 */
    private long connectionTimeoutMs = 30_000;

    /** 空闲连接回收时间（ms）。超过此时长的空闲连接被关闭（minIdle=0 时可回收到 0）。 */
    private long idleTimeoutMs = 60_000;

    /** 连接最大生存期（ms）。避免被外部库 idle 杀死后仍被复用。 */
    private long maxLifetimeMs = 600_000;

    /** 连接校验超时（ms）。 */
    private long validationTimeoutMs = 5_000;

    /**
     * 最多同时保留的池数量（按"库身份 + 凭据"去重）。超出后按 LRU 关闭最久未用的池。
     * 防止数据源/凭据频繁变化导致池无限增长。
     */
    private int maxPools = 32;
}

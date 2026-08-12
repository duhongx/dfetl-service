package com.dfygt.dfetl.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.quartz.QuartzDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Quartz 调度器独立 JDBC 数据源（{@link QuartzDataSource}）。
 *
 * <p><b>解决的问题</b>：原方案让 Quartz 与业务 JPA 共用 {@code spring.datasource}（HikariCP）。
 * Quartz JobStore 在 trigger acquire / fire / misfire 三个阶段会持有 {@code SELECT ... FOR UPDATE}
 * 行锁与连接，2000-3000 任务量级下与业务 JPA 抢同一池连接，整点高峰时业务接口（任务列表 / 校验
 * 工作台等）容易被 Quartz 锁链路饥饿。
 *
 * <p>本配置为 Quartz 提供独立 HikariCP 连接池（库连接串与业务库一致，**只是连接池独立**），
 * Spring Boot 通过 {@link QuartzDataSource} 注解优先使用本 Bean 而非 {@code @Primary} 的业务
 * dataSource（参见 {@code JdbcStoreTypeConfiguration}）。
 *
 * <p><b>配置前缀</b> {@code dfetl.quartz.datasource}（仅控制连接池参数；URL/账号沿用
 * {@code spring.datasource}，避免双源漂移）：
 * <ul>
 *   <li>{@code maximum-pool-size}（默认 10）：连接池上限</li>
 *   <li>{@code minimum-idle}（默认 2）：保活最小空闲</li>
 *   <li>{@code connection-timeout-ms}（默认 30000）</li>
 *   <li>{@code max-lifetime-ms}（默认 1800000，30 min）</li>
 *   <li>{@code idle-timeout-ms}（默认 600000，10 min）</li>
 * </ul>
 *
 * <p><b>容量算法</b>（基于 2000-3000 任务规模）：
 * Quartz threadCount × 每线程 1.5 conn（acquire/fire 高峰）+ 集群 checkin 1 conn
 * ≈ 30 × 1.5 + 1 = 46，但实际上 acquire 阶段是批处理（batchTriggerAcquisitionMaxCount=20）
 * 而非 per-thread，常稳态 5-8 conn 即够，留 10 应对峰值。
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@Slf4j
public class QuartzDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    @Value("${dfetl.quartz.datasource.maximum-pool-size:10}")
    private int maximumPoolSize;

    @Value("${dfetl.quartz.datasource.minimum-idle:2}")
    private int minimumIdle;

    @Value("${dfetl.quartz.datasource.connection-timeout-ms:30000}")
    private long connectionTimeoutMs;

    @Value("${dfetl.quartz.datasource.max-lifetime-ms:1800000}")
    private long maxLifetimeMs;

    @Value("${dfetl.quartz.datasource.idle-timeout-ms:600000}")
    private long idleTimeoutMs;

    @Value("${dfetl.quartz.datasource.validation-timeout-ms:5000}")
    private long validationTimeoutMs;

    /**
     * 业务 JPA / Repository 使用的主 DataSource。
     *
     * <p>本配置类同时声明了 Quartz 专用 DataSource。显式声明业务 {@code @Primary} DataSource，
     * 避免 Spring Boot 因已有 DataSource bean 而跳过默认业务池自动配置，导致 JPA 误用 Quartz 池。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * Quartz 专用 DataSource。
     *
     * <p>{@link QuartzDataSource} 注解触发 Spring Boot {@code QuartzAutoConfiguration} 把
     * 本 Bean 挑出来给 {@code SchedulerFactoryBean.setDataSource()}，而业务 JPA / Hibernate
     * 仍用 {@code @Primary} 的默认 dataSource。
     */
    @Bean
    @QuartzDataSource
    public DataSource quartzDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);

        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setMaxLifetime(maxLifetimeMs);
        config.setIdleTimeout(idleTimeoutMs);
        config.setValidationTimeout(validationTimeoutMs);

        // 连接池命名，便于在监控/日志中与业务池区分
        config.setPoolName("quartz-pool");
        // Spring LocalDataSourceJobStore 设置 dontSetAutoCommitFalse=true，不会主动关闭自动提交。
        // 动态注册/更新 trigger 时需要连接池保持自动提交，否则写入可能停留在未提交事务中。
        config.setAutoCommit(true);
        // 启动失败不阻塞应用（与业务池一致：外部库可能临时不可达，主流程也不能挂）
        config.setInitializationFailTimeout(-1);

        log.info("QuartzDataSourceConfig: created independent Quartz HikariCP pool (maxPool={}, minIdle={}, jdbcUrl={})",
                maximumPoolSize, minimumIdle, sanitizeUrl(jdbcUrl));

        return new HikariDataSource(config);
    }

    /** 日志脱敏：去除 URL 中可能的 user/password 查询参数（业务库 URL 通常不含，但稳妥处理）。 */
    private static String sanitizeUrl(String url) {
        if (url == null) return "(null)";
        return url.replaceAll("(?i)(password|user)=[^&]*", "$1=***");
    }
}

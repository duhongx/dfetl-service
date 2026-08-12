package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.dto.ConnectionTestResult;
import com.dfygt.dfetl.server.dto.TargetDataSourceDto;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TargetDataSourceService {

    private final TargetDataSourceRepository repository;
    private final AesUtil aesUtil;

    public List<TargetDataSourceDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public TargetDataSourceDto findById(Long id) {
        return toDto(getOrThrow(id));
    }

    @Transactional
    public TargetDataSourceDto create(TargetDataSourceDto dto) {
        TargetDataSource entity = new TargetDataSource();
        copyToEntity(dto, entity);
        return toDto(repository.save(entity));
    }

    @Transactional
    public TargetDataSourceDto update(Long id, TargetDataSourceDto dto) {
        TargetDataSource entity = getOrThrow(id);
        copyToEntity(dto, entity);
        return toDto(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /** 测试 Doris FE JDBC 连接（MySQL 协议，端口 fePort） */
    public ConnectionTestResult testConnection(Long id) {
        TargetDataSource ds = getOrThrow(id);
        String password = aesUtil.decrypt(ds.getPasswordEnc());
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false",
                ds.getFeHost(), ds.getFePort(), ds.getDbName());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, ds.getUsername(), password)) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            return ConnectionTestResult.ok("Doris 连接成功，版本: " + version);
        } catch (Exception e) {
            log.warn("Doris connection test failed for target {}: {}", id, e.getMessage());
            return ConnectionTestResult.fail("连接失败: " + e.getMessage());
        }
    }

    /**
     * 用 DTO 临时测试目标端连接，不落库（用于新建弹窗「测试连接」按钮）。
     */
    public ConnectionTestResult testConnectionByDto(TargetDataSourceDto dto) {
        try {
            if (dto == null || dto.getFeHost() == null || dto.getFePort() == null
                    || dto.getUsername() == null || dto.getDatabase() == null) {
                return ConnectionTestResult.fail("缺少必要的连接参数");
            }
            String password = dto.getPassword();
            if ((password == null || password.isBlank() || "****".equals(password)) && dto.getId() != null) {
                TargetDataSource existing = repository.findById(dto.getId()).orElse(null);
                if (existing != null) {
                    password = aesUtil.decrypt(existing.getPasswordEnc());
                }
            }
            if (password == null) password = "";
            String jdbcUrl = String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false",
                    dto.getFeHost(), dto.getFePort(), dto.getDatabase());
            try (Connection conn = DriverManager.getConnection(jdbcUrl, dto.getUsername(), password)) {
                String version = conn.getMetaData().getDatabaseProductVersion();
                return ConnectionTestResult.ok("Doris 连接成功，版本: " + version);
            }
        } catch (Exception e) {
            log.warn("Test-config target connection failed: {}", e.getMessage());
            return ConnectionTestResult.fail("连接失败: " + e.getMessage());
        }
    }

    /** 仅更新 status 字段（启用/禁用切换）。 */
    @Transactional
    public TargetDataSourceDto updateStatus(Long id, String status) {
        TargetDataSource entity = getOrThrow(id);
        entity.setStatus(status);
        return toDto(repository.save(entity));
    }

    // ── private helpers ────────────────────────────────────────────────

    private TargetDataSource getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found: " + id));
    }

    private void copyToEntity(TargetDataSourceDto dto, TargetDataSource entity) {
        entity.setName(dto.getName());
        entity.setEnvironment(dto.getEnvironment());
        entity.setFeHost(dto.getFeHost());
        entity.setFePort(dto.getFePort());
        if (dto.getHttpPort() != null)        entity.setHttpPort(dto.getHttpPort());
        if (dto.getStreamLoadPort() != null)  entity.setStreamLoadPort(dto.getStreamLoadPort());
        entity.setUsername(dto.getUsername());
        if (dto.getPassword() != null && !dto.getPassword().isBlank() && !dto.getPassword().equals("****")) {
            entity.setPasswordEnc(aesUtil.encrypt(dto.getPassword()));
        }
        entity.setDbName(dto.getDatabase());
        entity.setDefaultWriteDatabase(dto.getDefaultWriteDatabase());
        if (dto.getWriteBatchSize() != null)  entity.setWriteBatchSize(dto.getWriteBatchSize());
        if (dto.getWriteConcurrency() != null) entity.setWriteConcurrency(dto.getWriteConcurrency());
        if (dto.getPoolSize() != null)        entity.setPoolSize(dto.getPoolSize());
        if (dto.getSsl() != null)             entity.setSsl(dto.getSsl());
        entity.setDescription(dto.getDescription());
        if (dto.getStatus() != null)          entity.setStatus(dto.getStatus());
    }

    private TargetDataSourceDto toDto(TargetDataSource e) {
        TargetDataSourceDto dto = new TargetDataSourceDto();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setEnvironment(e.getEnvironment());
        dto.setFeHost(e.getFeHost());
        dto.setFePort(e.getFePort());
        dto.setHttpPort(e.getHttpPort());
        dto.setStreamLoadPort(e.getStreamLoadPort());
        dto.setUsername(e.getUsername());
        dto.setPassword(AesUtil.mask(e.getPasswordEnc()));
        dto.setDatabase(e.getDbName());
        dto.setDefaultWriteDatabase(e.getDefaultWriteDatabase());
        dto.setWriteBatchSize(e.getWriteBatchSize());
        dto.setWriteConcurrency(e.getWriteConcurrency());
        dto.setPoolSize(e.getPoolSize());
        dto.setSsl(e.getSsl());
        dto.setDescription(e.getDescription());
        dto.setStatus(e.getStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}

package com.dfygt.dfetl.server.external.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientCreateRequest;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientDto;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientSecretResponse;
import com.dfygt.dfetl.server.external.dto.ExternalApiClientUpdateRequest;
import com.dfygt.dfetl.server.external.entity.ExternalApiClient;
import com.dfygt.dfetl.server.external.repository.ExternalApiClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 管理端维护外部 API HMAC client。
 */
@Service
@RequiredArgsConstructor
public class ExternalApiClientAdminService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ExternalApiClientRepository repository;
    private final AesUtil aesUtil;

    @Transactional(readOnly = true)
    public List<ExternalApiClientDto> list() {
        return repository.findAll().stream()
                .map(ExternalApiClientDto::from)
                .toList();
    }

    @Transactional
    public ExternalApiClientSecretResponse create(ExternalApiClientCreateRequest request) {
        String clientId = required(request.clientId(), "clientId");
        repository.findByClientId(clientId).ifPresent(existing -> {
            throw new IllegalArgumentException("clientId 已存在: " + clientId);
        });

        String secret = generateSecret();
        ExternalApiClient client = new ExternalApiClient();
        client.setClientId(clientId);
        client.setClientName(required(request.clientName(), "clientName"));
        client.setSecretEnc(aesUtil.encrypt(secret));
        client.setEnabled(request.enabled() == null || request.enabled());
        client.setAllowedYiLiaoJgDm(blankToNull(request.allowedYiLiaoJgDm()));
        client.setDescription(blankToNull(request.description()));

        ExternalApiClient saved = repository.save(client);
        return new ExternalApiClientSecretResponse(ExternalApiClientDto.from(saved), secret);
    }

    @Transactional
    public ExternalApiClientDto update(Long id, ExternalApiClientUpdateRequest request) {
        ExternalApiClient client = get(id);
        client.setClientName(required(request.clientName(), "clientName"));
        client.setEnabled(request.enabled() == null || request.enabled());
        client.setAllowedYiLiaoJgDm(blankToNull(request.allowedYiLiaoJgDm()));
        client.setDescription(blankToNull(request.description()));
        return ExternalApiClientDto.from(repository.save(client));
    }

    @Transactional
    public ExternalApiClientSecretResponse resetSecret(Long id) {
        ExternalApiClient client = get(id);
        String secret = generateSecret();
        client.setSecretEnc(aesUtil.encrypt(secret));
        ExternalApiClient saved = repository.save(client);
        return new ExternalApiClientSecretResponse(ExternalApiClientDto.from(saved), secret);
    }

    @Transactional
    public void delete(Long id) {
        ExternalApiClient client = get(id);
        repository.delete(client);
    }

    private ExternalApiClient get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("外部 API client 不存在: " + id));
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

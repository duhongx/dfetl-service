package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.repository.InstitutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 统一校验源数据源的机构归属，供创建与更新路径共用。 */
@Service
@RequiredArgsConstructor
public class SourceDataSourceOwnershipValidator {

    private final InstitutionRepository institutionRepository;

    public void validate(Long institutionId) {
        if (institutionId == null) {
            throw new IllegalArgumentException("机构为必填");
        }
        if (!institutionRepository.existsById(institutionId)) {
            throw new IllegalArgumentException("机构不存在: id=" + institutionId);
        }
    }
}

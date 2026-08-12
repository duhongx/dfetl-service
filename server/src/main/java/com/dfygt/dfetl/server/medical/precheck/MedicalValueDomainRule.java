package com.dfygt.dfetl.server.medical.precheck;

import java.util.Set;

/**
 * 单字段值域预检规则。
 */
public record MedicalValueDomainRule(
        String fieldCode,
        String domainId,
        MedicalValueDomainCheckMode mode,
        int allowedCodeCount,
        Set<String> allowedCodes,
        String reason
) {

    public boolean strictBlock() {
        return mode == MedicalValueDomainCheckMode.STRICT_BLOCK
                && allowedCodes != null
                && !allowedCodes.isEmpty();
    }

    public boolean actualInvalidBlock() {
        return mode == MedicalValueDomainCheckMode.ACTUAL_INVALID_BLOCK
                && allowedCodes != null
                && !allowedCodes.isEmpty();
    }
}

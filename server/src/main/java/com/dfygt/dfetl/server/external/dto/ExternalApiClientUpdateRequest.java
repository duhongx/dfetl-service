package com.dfygt.dfetl.server.external.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExternalApiClientUpdateRequest(
        @NotBlank
        @Size(max = 100)
        String clientName,

        Boolean enabled,

        @Size(max = 50)
        String allowedYiLiaoJgDm,

        String description
) {
}

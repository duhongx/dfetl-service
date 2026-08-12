package com.dfygt.dfetl.server.external.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExternalApiClientCreateRequest(
        @NotBlank
        @Size(max = 100)
        String clientId,

        @NotBlank
        @Size(max = 100)
        String clientName,

        Boolean enabled,

        @Size(max = 50)
        String allowedYiLiaoJgDm,

        String description
) {
}

package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DorisTypeMappingRuleDto {
    private Long id;
    private String profileName;
    private Integer profileVersion;
    private String sourceDialect;
    private String sourceTypePattern;
    private String recommendedDorisType;
    private String compatibilityLevel;
    private String reason;
    private Boolean enabled;
    private Integer priority;
}

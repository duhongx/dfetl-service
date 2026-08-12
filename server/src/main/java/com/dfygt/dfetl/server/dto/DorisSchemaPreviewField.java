package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DorisSchemaPreviewField {
    private String sourceField;
    private String sourceType;
    private String dorisField;
    private String recommendedDorisType;
    private String compatibilityLevel;
    private String reason;
    private Boolean nullable;
    private Integer precision;
    private Integer scale;
    private Integer length;
}

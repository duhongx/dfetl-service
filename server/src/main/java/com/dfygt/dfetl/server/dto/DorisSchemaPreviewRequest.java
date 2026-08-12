package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DorisSchemaPreviewRequest {
    private Long sourceDataSourceId;
    private String sourceMode;
    private String sourceSchema;
    private String viewName;
    private List<String> viewNames;
    private String customSql;
    private String customSqlName;
    private String incrementalField;
    private List<String> upsertKeys;
    private String softDeleteField;
    private String sequenceCol;
    private String dorisTableModel;
    private Boolean enableDorisMerge;
    private String validationMethod;
}

package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DorisSchemaPreviewResponse {
    private String sourceDialect;
    private String sourceObject;
    private List<DorisSchemaPreviewField> fields = new ArrayList<>();
    private List<DorisSchemaPreviewIssue> issues = new ArrayList<>();
}

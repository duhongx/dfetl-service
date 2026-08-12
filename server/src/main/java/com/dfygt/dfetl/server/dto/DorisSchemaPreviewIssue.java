package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DorisSchemaPreviewIssue {
    private String code;
    private String level;
    private String message;
    private String field;

    public static DorisSchemaPreviewIssue of(String code, String level, String message, String field) {
        DorisSchemaPreviewIssue issue = new DorisSchemaPreviewIssue();
        issue.setCode(code);
        issue.setLevel(level);
        issue.setMessage(message);
        issue.setField(field);
        return issue;
    }
}

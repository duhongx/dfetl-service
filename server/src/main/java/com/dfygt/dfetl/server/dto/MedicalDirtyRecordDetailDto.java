package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class MedicalDirtyRecordDetailDto {

    private Long id;
    private Long taskId;
    private Long executionId;
    private String datasetCode;
    private String datasetName;
    private String sourceSchema;
    private String sourceView;
    private String targetTable;
    private String businessPkJson;
    private String sourceRowHash;
    private String windowJson;
    private String ownerName;
    private String ownerSource;
    private String rowAction;
    private String severity;
    private String status;
    private String rawRowJson;
    private Integer errorCount;
    private Instant foundAt;
    private Instant sentAt;
    private Instant handledAt;
    private String handledBy;
    private String handleNote;
    private List<FieldDto> fields = new ArrayList<>();

    @Getter
    @Setter
    public static class FieldDto {
        private Long id;
        private String fieldCode;
        private String fieldName;
        private String sourceColumn;
        private String targetColumn;
        private String errorType;
        private String standardRule;
        private String valueDomainCode;
        private String valueDomainMode;
        private Integer valueDomainAllowedCount;
        private String rawValue;
        private String normalizedValue;
        private String message;
        private String severity;
    }
}

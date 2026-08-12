package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DfetlDatasetImportResultDto {

    private int totalCount;
    private int createdCount;
    private int updatedCount;
    private int unchangedCount;
    private int voidedCount;
    private List<DfetlDatasetDto> datasets = new ArrayList<>();
}

package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueQuery;

import java.util.List;
import java.util.Map;

/** Doris 问题导出的有界读取与远程 OUTFILE 边界。 */
public interface DorisPrecheckExportGateway {

    ExportSchema schema(Long runId, DfetlPrecheckIssueQuery query);

    List<List<String>> readPage(
            Long runId,
            DfetlPrecheckIssueQuery query,
            ExportSchema schema,
            long offset,
            int limit);

    List<DorisPrecheckExportService.ExportFile> exportOutfile(
            Long runId,
            DfetlPrecheckIssueQuery query,
            ExportSchema schema,
            OutfileRequest request);

    record ExportSchema(long rowCount, List<String> headers, List<String> rawColumns) {
        public ExportSchema {
            headers = headers == null ? List.of() : List.copyOf(headers);
            rawColumns = rawColumns == null ? List.of() : List.copyOf(rawColumns);
            if (rowCount < 0 || headers.isEmpty()) {
                throw new IllegalArgumentException("导出行数和表头必须有效");
            }
        }
    }

    record OutfileRequest(String uriPrefix, Map<String, String> properties) {
        public OutfileRequest {
            if (uriPrefix == null || uriPrefix.isBlank()) {
                throw new IllegalArgumentException("OUTFILE URI 不能为空");
            }
            uriPrefix = uriPrefix.trim();
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }
}

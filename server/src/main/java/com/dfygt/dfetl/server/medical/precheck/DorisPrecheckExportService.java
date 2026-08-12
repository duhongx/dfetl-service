package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.dto.DfetlPrecheckIssueQuery;
import com.dfygt.dfetl.server.entity.DfetlPrecheckExport;
import com.dfygt.dfetl.server.repository.DfetlPrecheckExportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 从 Doris 分页生成受控本地导出，或把大 CSV 下推为 Doris OUTFILE。 */
@Service
public class DorisPrecheckExportService {

    private final DfetlPrecheckExportRepository exportRepository;
    private final DorisPrecheckExportGateway gateway;
    private final ObjectMapper objectMapper;
    private final Path exportRoot;
    private final long outfileThresholdRows;
    private final int pageSize;
    private final int rowsPerFile;
    private final String outfileUri;
    private final boolean outfileLifecycleManaged;
    private final Map<String, String> outfileProperties;

    public DorisPrecheckExportService(
            DfetlPrecheckExportRepository exportRepository,
            DorisPrecheckExportGateway gateway,
            ObjectMapper objectMapper,
            @Value("${dfetl.data-precheck.export.local-root:/var/lib/dfetl/precheck-exports}")
            String exportRoot,
            @Value("${dfetl.data-precheck.export.outfile-threshold-rows:1000000}")
            long outfileThresholdRows,
            @Value("${dfetl.data-precheck.export.page-size:5000}") int pageSize,
            @Value("${dfetl.data-precheck.export.rows-per-file:500000}") int rowsPerFile,
            @Value("${dfetl.data-precheck.export.outfile-uri:}") String outfileUri,
            @Value("${dfetl.data-precheck.export.outfile-lifecycle-managed:false}")
            boolean outfileLifecycleManaged,
            @Value("${dfetl.data-precheck.export.outfile-properties-json:{}}")
            String outfilePropertiesJson) {
        if (outfileThresholdRows <= 0 || pageSize <= 0 || rowsPerFile <= 0) {
            throw new IllegalArgumentException("导出阈值、分页大小和分片行数必须大于 0");
        }
        this.exportRepository = exportRepository;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.exportRoot = Path.of(exportRoot).toAbsolutePath().normalize();
        this.outfileThresholdRows = outfileThresholdRows;
        this.pageSize = pageSize;
        this.rowsPerFile = rowsPerFile;
        this.outfileUri = outfileUri == null ? "" : outfileUri.trim();
        this.outfileLifecycleManaged = outfileLifecycleManaged;
        try {
            this.outfileProperties = Map.copyOf(objectMapper.readValue(
                    outfilePropertiesJson == null || outfilePropertiesJson.isBlank()
                            ? "{}" : outfilePropertiesJson,
                    new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            throw new IllegalArgumentException("Doris OUTFILE properties JSON 非法", e);
        }
    }

    public DfetlPrecheckExport generate(Long exportId) {
        if (exportId == null || exportId <= 0) {
            throw new IllegalArgumentException("exportId 必须为正整数");
        }
        int claimed = exportRepository.claimPending(exportId, Instant.now());
        DfetlPrecheckExport export = exportRepository.findById(exportId)
                .orElseThrow(() -> new NoSuchElementException("数据预检导出不存在: " + exportId));
        if (claimed == 0) {
            return export;
        }
        export.setStatus("RUNNING");
        export.setStartedAt(export.getStartedAt() == null ? Instant.now() : export.getStartedAt());
        try {
            DfetlPrecheckIssueQuery query = issueQuery(export.getFilterSnapshot());
            DorisPrecheckExportGateway.ExportSchema schema = gateway.schema(export.getRunId(), query);
            List<ExportFile> files;
            if (useOutfile(export, schema.rowCount())) {
                files = gateway.exportOutfile(export.getRunId(), query, schema,
                        new DorisPrecheckExportGateway.OutfileRequest(
                                outfilePrefix(export.getId()), outfileProperties));
            } else {
                files = "XLSX".equals(export.getExportFormat())
                        ? writeLocalXlsxParts(export, query, schema)
                        : writeLocalCsvParts(export, query, schema);
            }
            long byteCount = files.stream().mapToLong(file -> value(file.bytes())).sum();
            export.setFileManifest(objectMapper.writeValueAsString(files));
            export.setRowCount(schema.rowCount());
            export.setByteCount(byteCount);
            export.setStatus("COMPLETED");
            export.setErrorMessage(null);
            export.setFinishedAt(Instant.now());
        } catch (Exception e) {
            deleteLocalExportDirectory(export.getId());
            export.setStatus("FAILED");
            export.setFinishedAt(Instant.now());
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            export.setErrorMessage(ExecutionErrorSanitizer.sanitize(detail));
        }
        return exportRepository.save(export);
    }

    private List<ExportFile> writeLocalCsvParts(
            DfetlPrecheckExport export,
            DfetlPrecheckIssueQuery query,
            DorisPrecheckExportGateway.ExportSchema schema) throws IOException {
        Path exportDirectory = safeExportDirectory(export.getId());
        Files.createDirectories(exportDirectory);
        List<ExportFile> files = new ArrayList<>();
        long offset = 0;
        int part = 1;
        while (offset < schema.rowCount() || (schema.rowCount() == 0 && files.isEmpty())) {
            String fileName = "part-%05d.csv".formatted(part++);
            Path file = exportDirectory.resolve(fileName).normalize();
            assertWithin(file, exportDirectory);
            long written = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write('\ufeff');
                appendCsv(writer, schema.headers());
                while (offset < schema.rowCount() && written < rowsPerFile) {
                    int limit = (int) Math.min(pageSize, rowsPerFile - written);
                    List<List<String>> rows = gateway.readPage(
                            export.getRunId(), query, schema, offset, limit);
                    if (rows.isEmpty()) {
                        throw new IllegalStateException(
                                "Doris 导出分页提前结束: offset=" + offset
                                        + ", expected=" + schema.rowCount());
                    }
                    for (List<String> row : rows) {
                        appendCsv(writer, row);
                    }
                    offset += rows.size();
                    written += rows.size();
                }
            }
            files.add(new ExportFile(
                    "LOCAL", export.getId() + "/" + fileName, fileName,
                    "text/csv", written, Files.size(file)));
            if (schema.rowCount() == 0) {
                break;
            }
        }
        return List.copyOf(files);
    }

    private List<ExportFile> writeLocalXlsxParts(
            DfetlPrecheckExport export,
            DfetlPrecheckIssueQuery query,
            DorisPrecheckExportGateway.ExportSchema schema) throws IOException {
        Path exportDirectory = safeExportDirectory(export.getId());
        Files.createDirectories(exportDirectory);
        List<ExportFile> files = new ArrayList<>();
        long offset = 0;
        int part = 1;
        while (offset < schema.rowCount() || (schema.rowCount() == 0 && files.isEmpty())) {
            String fileName = "part-%05d.xlsx".formatted(part++);
            Path file = exportDirectory.resolve(fileName).normalize();
            assertWithin(file, exportDirectory);
            long written = 0;
            try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                writeWorkbookMetadata(zip);
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetData>").getBytes(StandardCharsets.UTF_8));
                writeXlsxRow(zip, 1L, schema.headers());
                while (offset < schema.rowCount() && written < rowsPerFile) {
                    int limit = (int) Math.min(pageSize, rowsPerFile - written);
                    List<List<String>> rows = gateway.readPage(
                            export.getRunId(), query, schema, offset, limit);
                    if (rows.isEmpty()) {
                        throw new IllegalStateException(
                                "Doris 导出分页提前结束: offset=" + offset
                                        + ", expected=" + schema.rowCount());
                    }
                    for (List<String> row : rows) {
                        writeXlsxRow(zip, written + 2L, row);
                        written++;
                    }
                    offset += rows.size();
                }
                zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            files.add(new ExportFile(
                    "LOCAL", export.getId() + "/" + fileName, fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    written, Files.size(file)));
            if (schema.rowCount() == 0) {
                break;
            }
        }
        return List.copyOf(files);
    }

    private void writeWorkbookMetadata(ZipOutputStream zip) throws IOException {
        writeZipEntry(zip, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "</Types>");
        writeZipEntry(zip, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "</Relationships>");
        writeZipEntry(zip, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"issues\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        writeZipEntry(zip, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "</Relationships>");
    }

    private void writeZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeXlsxRow(ZipOutputStream zip, long rowNumber, List<String> values)
            throws IOException {
        zip.write(("<row r=\"" + rowNumber + "\">").getBytes(StandardCharsets.UTF_8));
        for (String value : values) {
            String safeValue = xlsxValue(value == null ? "" : value);
            zip.write(("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">" + safeValue
                    + "</t></is></c>").getBytes(StandardCharsets.UTF_8));
        }
        zip.write("</row>".getBytes(StandardCharsets.UTF_8));
    }

    private String xlsxValue(String value) {
        if (value.length() > 32767) {
            throw new IllegalStateException("XLSX 单元格超过 32767 字符，请改用 CSV 导出");
        }
        StringBuilder validXml = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> validXml.appendCodePoint(
                codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                        || codePoint >= 0x20 && codePoint <= 0xD7FF
                        || codePoint >= 0xE000 && codePoint <= 0xFFFD
                        || codePoint >= 0x10000 && codePoint <= 0x10FFFF
                        ? codePoint : 0xFFFD));
        return validXml.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void deleteLocalExportDirectory(Long exportId) {
        Path directory = safeExportDirectory(exportId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 失败状态已保存；后续 TTL 清理会再次处理该受控目录。
                }
            });
        } catch (IOException ignored) {
            // 同上，不能用清理失败覆盖真实导出错误。
        }
    }

    private boolean useOutfile(DfetlPrecheckExport export, long rowCount) {
        return "CSV".equals(export.getExportFormat())
                && rowCount >= outfileThresholdRows
                && !outfileUri.isBlank()
                && outfileLifecycleManaged;
    }

    private String outfilePrefix(Long exportId) {
        return outfileUri.replaceAll("/+$", "") + "/" + exportId + "/";
    }

    private DfetlPrecheckIssueQuery issueQuery(String snapshot) throws IOException {
        return objectMapper.readValue(snapshot, DfetlPrecheckIssueQuery.class);
    }

    private Path safeExportDirectory(Long exportId) {
        Path directory = exportRoot.resolve(String.valueOf(exportId)).normalize();
        assertWithin(directory, exportRoot);
        return directory;
    }

    private static void assertWithin(Path path, Path parent) {
        if (!path.startsWith(parent)) {
            throw new IllegalStateException("导出路径越界");
        }
    }

    private static void appendCsv(Appendable target, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                target.append(',');
            }
            String value = values.get(index) == null ? "" : values.get(index);
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                target.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                target.append(value);
            }
        }
        target.append('\n');
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    public record ExportFile(
            String storage,
            String path,
            String name,
            String contentType,
            Long rows,
            Long bytes) {
    }
}

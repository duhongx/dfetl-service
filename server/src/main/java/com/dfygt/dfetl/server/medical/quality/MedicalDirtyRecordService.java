package com.dfygt.dfetl.server.medical.quality;

import com.dfygt.dfetl.server.dto.MedicalDirtyRecordDetailDto;
import com.dfygt.dfetl.server.entity.MedicalDirtyField;
import com.dfygt.dfetl.server.entity.MedicalDirtyRow;
import com.dfygt.dfetl.server.repository.MedicalDirtyFieldRepository;
import com.dfygt.dfetl.server.repository.MedicalDirtyRowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class MedicalDirtyRecordService {

    private final MedicalDirtyRowRepository rowRepository;
    private final MedicalDirtyFieldRepository fieldRepository;

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_SENT_TO_OWNER = "SENT_TO_OWNER";
    private static final int BULK_STATUS_BATCH_SIZE = 10_000;
    private static final int EXPORT_LIMIT = 10_000;
    private static final List<String> EXPORT_HEADERS = List.of(
            "数据集代码",
            "数据集名称",
            "负责人",
            "源视图",
            "目标表",
            "业务主键",
            "问题级别",
            "行处理方式",
            "处理状态",
            "executionId",
            "发现时间",
            "字段编码",
            "字段名称",
            "源字段",
            "Doris字段",
            "错误类型",
            "原始值",
            "标准规则",
            "问题说明");

    @Transactional
    public MedicalDirtyRow upsertDirtyRow(MedicalDirtyRowIssue issue) {
        if (issue == null) {
            throw new IllegalArgumentException("医共体问题行不能为空");
        }
        return rowRepository.findByExecutionIdAndDatasetCodeAndSourceRowHash(
                        issue.executionId(), issue.datasetCode(), issue.sourceRowHash())
                .map(row -> appendMissingFields(row, issue))
                .orElseGet(() -> createDirtyRow(issue));
    }

    public Page<MedicalDirtyRow> list(
            Long taskId,
            Long executionId,
            String datasetCode,
            String ownerName,
            String status,
            String severity,
            Pageable pageable) {
        return rowRepository.findByFilter(
                taskId,
                executionId,
                blankToNull(datasetCode),
                blankToNull(ownerName),
                blankToNull(status),
                blankToNull(severity),
                pageable);
    }

    @Transactional(readOnly = true)
    public MedicalDirtyRecordDetailDto getDetail(Long id) {
        MedicalDirtyRow row = rowRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("MedicalDirtyRow not found: " + id));
        List<MedicalDirtyField> fields = id == null ? List.of() : fieldRepository.findByDirtyRowId(id);
        return toDetailDto(row, fields);
    }

    @Transactional
    public MedicalDirtyRow handle(Long id, String status, String handledBy, String note) {
        MedicalDirtyRow row = rowRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("MedicalDirtyRow not found: " + id));
        row.setStatus(blankToDefault(status, "CONFIRMED"));
        row.setHandledBy(blankToNull(handledBy));
        row.setHandleNote(blankToNull(note));
        row.setHandledAt(Instant.now());
        return rowRepository.save(row);
    }

    public String exportCsv(
            Long taskId,
            Long executionId,
            String datasetCode,
            String ownerName,
            String status,
            String severity) {
        Page<MedicalDirtyRow> rows = list(
                taskId,
                executionId,
                datasetCode,
                ownerName,
                status,
                severity,
                org.springframework.data.domain.PageRequest.of(0, EXPORT_LIMIT));
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", EXPORT_HEADERS)).append('\n');
        for (MedicalDirtyRow row : rows.getContent()) {
            List<MedicalDirtyField> fields = row.getId() == null
                    ? List.of()
                    : fieldRepository.findByDirtyRowId(row.getId());
            if (fields == null || fields.isEmpty()) {
                appendCsvRow(csv, row, null);
                continue;
            }
            for (MedicalDirtyField field : fields) {
                appendCsvRow(csv, row, field);
            }
        }
        return csv.toString();
    }

    public byte[] exportXlsx(
            Long taskId,
            Long executionId,
            String datasetCode,
            String ownerName,
            String status,
            String severity) {
        Page<MedicalDirtyRow> rows = list(
                taskId,
                executionId,
                datasetCode,
                ownerName,
                status,
                severity,
                org.springframework.data.domain.PageRequest.of(0, EXPORT_LIMIT));
        StringBuilder sheet = new StringBuilder();
        sheet.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                """);
        appendXlsxRow(sheet, EXPORT_HEADERS);
        for (MedicalDirtyRow row : rows.getContent()) {
            List<MedicalDirtyField> fields = row.getId() == null
                    ? List.of()
                    : fieldRepository.findByDirtyRowId(row.getId());
            if (fields == null || fields.isEmpty()) {
                appendXlsxRow(sheet, exportValues(row, null));
                continue;
            }
            for (MedicalDirtyField field : fields) {
                appendXlsxRow(sheet, exportValues(row, field));
            }
        }
        sheet.append("""
                  </sheetData>
                </worksheet>
                """);
        return buildXlsx(sheet.toString());
    }

    @Transactional
    public int markSent(
            Long taskId,
            Long executionId,
            String datasetCode,
            String ownerName,
            String severity) {
        int updatedCount = 0;
        while (true) {
            Page<MedicalDirtyRow> rows = list(
                    taskId,
                    executionId,
                    datasetCode,
                    ownerName,
                    STATUS_OPEN,
                    severity,
                    PageRequest.of(0, BULK_STATUS_BATCH_SIZE));
            List<MedicalDirtyRow> content = rows.getContent();
            if (content.isEmpty()) {
                return updatedCount;
            }
            Instant sentAt = Instant.now();
            for (MedicalDirtyRow row : content) {
                row.setStatus(STATUS_SENT_TO_OWNER);
                row.setSentAt(sentAt);
            }
            rowRepository.saveAll(content);
            updatedCount += content.size();
        }
    }

    private static void appendCsvRow(StringBuilder csv, MedicalDirtyRow row, MedicalDirtyField field) {
        List<String> values = exportValues(row, field);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(csv(values.get(i)));
        }
        csv.append('\n');
    }

    private static List<String> exportValues(MedicalDirtyRow row, MedicalDirtyField field) {
        return List.of(
                nullToEmpty(row.getDatasetCode()),
                nullToEmpty(row.getDatasetName()),
                nullToEmpty(row.getOwnerName()),
                nullToEmpty(row.getSourceView()),
                nullToEmpty(row.getTargetTable()),
                nullToEmpty(row.getBusinessPkJson()),
                nullToEmpty(row.getSeverity()),
                nullToEmpty(row.getRowAction()),
                nullToEmpty(row.getStatus()),
                row.getExecutionId() == null ? "" : row.getExecutionId().toString(),
                row.getFoundAt() == null ? "" : row.getFoundAt().toString(),
                field == null ? "" : nullToEmpty(field.getFieldCode()),
                field == null ? "" : nullToEmpty(field.getFieldName()),
                field == null ? "" : nullToEmpty(field.getSourceColumn()),
                field == null ? "" : nullToEmpty(field.getTargetColumn()),
                field == null ? "" : nullToEmpty(field.getErrorType()),
                field == null ? "" : nullToEmpty(field.getRawValue()),
                field == null ? "" : nullToEmpty(field.getStandardRule()),
                field == null ? "" : nullToEmpty(field.getMessage()));
    }

    private static void appendXlsxRow(StringBuilder sheet, List<String> values) {
        sheet.append("    <row>");
        for (String value : values) {
            sheet.append("<c t=\"inlineStr\"><is><t>")
                    .append(xml(value))
                    .append("</t></is></c>");
        }
        sheet.append("</row>\n");
    }

    private static byte[] buildXlsx(String sheetXml) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            putZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                    """);
            putZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            putZipEntry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="问题明细" sheetId="1" r:id="rId1"/>
                      </sheets>
                    </workbook>
                    """);
            putZipEntry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """);
            putZipEntry(zip, "xl/worksheets/sheet1.xml", sheetXml);
            zip.finish();
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("生成医共体问题清单 Excel 失败", e);
        }
    }

    private static void putZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static MedicalDirtyRecordDetailDto toDetailDto(MedicalDirtyRow row, List<MedicalDirtyField> fields) {
        MedicalDirtyRecordDetailDto dto = new MedicalDirtyRecordDetailDto();
        dto.setId(row.getId());
        dto.setTaskId(row.getTaskId());
        dto.setExecutionId(row.getExecutionId());
        dto.setDatasetCode(row.getDatasetCode());
        dto.setDatasetName(row.getDatasetName());
        dto.setSourceSchema(row.getSourceSchema());
        dto.setSourceView(row.getSourceView());
        dto.setTargetTable(row.getTargetTable());
        dto.setBusinessPkJson(row.getBusinessPkJson());
        dto.setSourceRowHash(row.getSourceRowHash());
        dto.setWindowJson(row.getWindowJson());
        dto.setOwnerName(row.getOwnerName());
        dto.setOwnerSource(row.getOwnerSource());
        dto.setRowAction(row.getRowAction());
        dto.setSeverity(row.getSeverity());
        dto.setStatus(row.getStatus());
        dto.setRawRowJson(row.getRawRowJson());
        dto.setErrorCount(row.getErrorCount());
        dto.setFoundAt(row.getFoundAt());
        dto.setSentAt(row.getSentAt());
        dto.setHandledAt(row.getHandledAt());
        dto.setHandledBy(row.getHandledBy());
        dto.setHandleNote(row.getHandleNote());
        dto.setFields(fields == null ? List.of() : fields.stream()
                .map(MedicalDirtyRecordService::toFieldDto)
                .toList());
        return dto;
    }

    private static MedicalDirtyRecordDetailDto.FieldDto toFieldDto(MedicalDirtyField field) {
        MedicalDirtyRecordDetailDto.FieldDto dto = new MedicalDirtyRecordDetailDto.FieldDto();
        dto.setId(field.getId());
        dto.setFieldCode(field.getFieldCode());
        dto.setFieldName(field.getFieldName());
        dto.setSourceColumn(field.getSourceColumn());
        dto.setTargetColumn(field.getTargetColumn());
        dto.setErrorType(field.getErrorType());
        dto.setStandardRule(field.getStandardRule());
        dto.setValueDomainCode(field.getValueDomainCode());
        dto.setValueDomainMode(field.getValueDomainMode());
        dto.setValueDomainAllowedCount(field.getValueDomainAllowedCount());
        dto.setRawValue(field.getRawValue());
        dto.setNormalizedValue(field.getNormalizedValue());
        dto.setMessage(field.getMessage());
        dto.setSeverity(field.getSeverity());
        return dto;
    }

    private MedicalDirtyRow createDirtyRow(MedicalDirtyRowIssue issue) {
        MedicalDirtyRow row = new MedicalDirtyRow();
        row.setTaskId(require(issue.taskId(), "taskId"));
        row.setExecutionId(require(issue.executionId(), "executionId"));
        row.setDatasetCode(requireText(issue.datasetCode(), "datasetCode"));
        row.setDatasetName(blankToNull(issue.datasetName()));
        row.setSourceSchema(blankToNull(issue.sourceSchema()));
        row.setSourceView(requireText(issue.sourceView(), "sourceView"));
        row.setTargetTable(blankToNull(issue.targetTable()));
        row.setBusinessPkJson(blankToNull(issue.businessPkJson()));
        row.setSourceRowHash(requireText(issue.sourceRowHash(), "sourceRowHash"));
        row.setWindowJson(blankToNull(issue.windowJson()));
        row.setOwnerName(blankToNull(issue.ownerName()));
        row.setOwnerSource(blankToNull(issue.ownerSource()));
        row.setRowAction(blankToDefault(issue.rowAction(), "EXCLUDED"));
        row.setSeverity(blankToDefault(issue.severity(), "BLOCKER"));
        row.setStatus("OPEN");
        row.setRawRowJson(blankToNull(issue.rawRowJson()));
        List<MedicalDirtyFieldIssue> fields = issue.fields() == null ? List.of() : issue.fields();
        row.setErrorCount(fields.size());
        MedicalDirtyRow saved = rowRepository.save(row);
        if (!fields.isEmpty()) {
            fieldRepository.saveAll(fields.stream()
                    .map(field -> toEntity(saved, field))
                    .toList());
        }
        return saved;
    }

    private MedicalDirtyRow appendMissingFields(MedicalDirtyRow row, MedicalDirtyRowIssue issue) {
        List<MedicalDirtyFieldIssue> incoming = issue.fields() == null ? List.of() : issue.fields();
        if (incoming.isEmpty()) {
            return row;
        }
        List<MedicalDirtyField> existing = row.getId() == null
                ? List.of()
                : fieldRepository.findByDirtyRowId(row.getId());
        List<MedicalDirtyField> missing = incoming.stream()
                .filter(field -> existing.stream().noneMatch(stored -> sameFieldIssue(stored, field)))
                .map(field -> toEntity(row, field))
                .toList();
        if (missing.isEmpty()) {
            return row;
        }
        fieldRepository.saveAll(missing);
        row.setErrorCount(existing.size() + missing.size());
        upgradeRowSeverityAndAction(row, issue);
        return rowRepository.save(row);
    }

    private static boolean sameFieldIssue(MedicalDirtyField stored, MedicalDirtyFieldIssue incoming) {
        if (stored == null || incoming == null) {
            return false;
        }
        return same(stored.getFieldCode(), incoming.fieldCode())
                && same(stored.getErrorType(), incoming.errorType())
                && same(stored.getRawValue(), incoming.rawValue())
                && same(stored.getNormalizedValue(), incoming.normalizedValue())
                && same(stored.getValueDomainCode(), incoming.valueDomainCode())
                && same(stored.getValueDomainMode(), incoming.valueDomainMode());
    }

    private static void upgradeRowSeverityAndAction(MedicalDirtyRow row, MedicalDirtyRowIssue issue) {
        if ("BLOCKER".equalsIgnoreCase(blankToNull(issue.severity()))) {
            row.setSeverity("BLOCKER");
        }
        if ("EXCLUDED".equalsIgnoreCase(blankToNull(issue.rowAction()))) {
            row.setRowAction("EXCLUDED");
        }
    }

    private static MedicalDirtyField toEntity(MedicalDirtyRow row, MedicalDirtyFieldIssue issue) {
        MedicalDirtyField field = new MedicalDirtyField();
        field.setDirtyRow(row);
        field.setFieldCode(requireText(issue.fieldCode(), "fieldCode"));
        field.setFieldName(blankToNull(issue.fieldName()));
        field.setSourceColumn(blankToNull(issue.sourceColumn()));
        field.setTargetColumn(blankToNull(issue.targetColumn()));
        field.setErrorType(requireText(issue.errorType(), "errorType"));
        field.setStandardRule(blankToNull(issue.standardRule()));
        field.setValueDomainCode(blankToNull(issue.valueDomainCode()));
        field.setValueDomainMode(blankToNull(issue.valueDomainMode()));
        field.setValueDomainAllowedCount(issue.valueDomainAllowedCount());
        field.setRawValue(issue.rawValue());
        field.setNormalizedValue(issue.normalizedValue());
        field.setMessage(blankToNull(issue.message()));
        field.setSeverity(blankToDefault(issue.severity(), "BLOCKER"));
        return field;
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean same(String first, String second) {
        String left = blankToNull(first);
        String right = blankToNull(second);
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String xml(String value) {
        return nullToEmpty(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

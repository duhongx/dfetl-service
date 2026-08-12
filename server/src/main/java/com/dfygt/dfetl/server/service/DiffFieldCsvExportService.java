package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.EtlVerifyDiffField;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffFieldRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 差异导出服务：导出运维人员可直接阅读的差异报告。
 *
 * <p>格式：每条差异记录一行，包含主键值、差异类型（中文）、修复状态、关键字段差异摘要。
 * <p>使用分页查询（每批 500 条）配合 StreamingResponseBody 逐批写出，避免大量差异时内存压力。
 */
@Service
@RequiredArgsConstructor
public class DiffFieldCsvExportService {

    private final EtlVerifyDiffFieldRepository diffFieldRepo;
    private final EtlVerifyDiffRepository diffRepo;
    private final ValidationRunService validationRunService;

    /** 每批查询的差异记录数 */
    private static final int BATCH_SIZE = 500;

    private static final Map<String, String> DIFF_TYPE_LABEL = Map.of(
            "INSERT_MISSING", "目标库少了这行",
            "UPDATE_DIFF", "字段值对不上",
            "DELETE_MISSING", "目标库多了这行",
            "ROW_AUDIT_MISSING", "目标库少了这行",
            "ROW_AUDIT_MISMATCH", "字段值对不上"
    );

    private static final Map<String, String> REPAIR_LABEL = Map.of(
            "PENDING", "待修复",
            "DONE", "已修复",
            "FAILED", "修复失败",
            "SKIPPED", "已跳过"
    );

    public ResponseEntity<StreamingResponseBody> exportByExecId(Long taskId, Long verifyExecId) {
        return buildStreamingResponse(taskId, verifyExecId, null, "差异报告_exec" + verifyExecId + ".csv");
    }

    public ResponseEntity<StreamingResponseBody> exportByRunId(Long taskId, Long runId) {
        ValidationRun run = validationRunService.findByIdAndTaskId(runId, taskId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + runId));
        return buildStreamingResponse(taskId, null, run.getId(), "差异报告_run" + runId + ".csv");
    }

    private ResponseEntity<StreamingResponseBody> buildStreamingResponse(Long taskId,
                                                                         Long execId,
                                                                         Long validationRunId,
                                                                         String filename) {
        StreamingResponseBody body = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF'); // BOM for Excel
                // spec validation-workbench-redesign · Task P1-6.3：CSV 头追加 repair_source 列
                // 列顺序固定在 repair_status 列之后
                writer.write("序号,主键值,差异类型,修复状态,修复来源,差异字段摘要,说明\n");

                int seq = 1;
                int page = 0;
                Slice<EtlVerifyDiff> slice;
                do {
                    PageRequest pageRequest = PageRequest.of(page, BATCH_SIZE, Sort.by("id"));
                    slice = validationRunId != null
                            ? diffRepo.findByTaskIdAndValidationRunId(taskId, validationRunId, pageRequest)
                            : diffRepo.findByTaskIdAndExecId(taskId, execId, pageRequest);
                    List<EtlVerifyDiff> diffs = slice.getContent();
                    if (diffs.isEmpty()) break;

                    // 批量查询当前批次的字段差异
                    List<Long> diffIds = diffs.stream().map(EtlVerifyDiff::getId).toList();
                    List<EtlVerifyDiffField> fields = diffFieldRepo.findByDiffIdInOrderByDiffIdAscIdAsc(diffIds);
                    Map<Long, List<EtlVerifyDiffField>> fieldsByDiffId = fields.stream()
                            .collect(Collectors.groupingBy(EtlVerifyDiffField::getDiffId));

                    for (EtlVerifyDiff diff : diffs) {
                        String diffTypeLabel = DIFF_TYPE_LABEL.getOrDefault(diff.getDiffType(), diff.getDiffType());
                        String repairLabel = REPAIR_LABEL.getOrDefault(diff.getRepairStatus(), diff.getRepairStatus());
                        // spec validation-workbench-redesign · Task P1-6.3：repair_source 直接写枚举值，便于运维筛选
                        String repairSource = diff.getRepairSource() == null ? "" : diff.getRepairSource();
                        String fieldSummary = buildFieldSummary(diff, fieldsByDiffId.get(diff.getId()));
                        String description = buildDescription(diff);

                        writer.write(String.valueOf(seq++));
                        writer.write(',');
                        writer.write(csvEscape(diff.getPkValue()));
                        writer.write(',');
                        writer.write(csvEscape(diffTypeLabel));
                        writer.write(',');
                        writer.write(csvEscape(repairLabel));
                        writer.write(',');
                        writer.write(csvEscape(repairSource));
                        writer.write(',');
                        writer.write(csvEscape(fieldSummary));
                        writer.write(',');
                        writer.write(csvEscape(description));
                        writer.write('\n');
                    }
                    writer.flush();
                    page++;
                } while (slice.hasNext());
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    /**
     * 构建差异字段摘要：列出不一致的字段名和值对比。
     * 如：name: 张三→张三丰; age: 30→31
     */
    private String buildFieldSummary(EtlVerifyDiff diff, List<EtlVerifyDiffField> fields) {
        if (fields == null || fields.isEmpty()) {
            if ("INSERT_MISSING".equals(diff.getDiffType()) || "ROW_AUDIT_MISSING".equals(diff.getDiffType())) {
                return "整行缺失";
            }
            if ("DELETE_MISSING".equals(diff.getDiffType())) {
                return "目标端多余";
            }
            return "（无字段级详情）";
        }

        // 只取有实际差异的字段（diffKind != SAME）
        List<String> diffFields = new ArrayList<>();
        for (EtlVerifyDiffField f : fields) {
            if ("SAME".equalsIgnoreCase(f.getDiffKind())) continue;
            String src = f.getSrcValueDisplay() != null ? f.getSrcValueDisplay() : "（空）";
            String tgt = f.getTgtValueDisplay() != null ? f.getTgtValueDisplay() : "（空）";

            if ("EXTRA_IN_TARGET".equalsIgnoreCase(f.getDiffKind())) {
                continue;
            }
            if ("EXTRA_IN_SOURCE".equalsIgnoreCase(f.getDiffKind())) {
                continue;
            }

            String entry = f.getColumnName() + ": " + truncate(src, 30) + " → " + truncate(tgt, 30);
            diffFields.add(entry);
            if (diffFields.size() >= 5) {
                diffFields.add("...(共" + fields.stream().filter(x -> !"SAME".equalsIgnoreCase(x.getDiffKind())).count() + "个字段)");
                break;
            }
        }

        if (diffFields.isEmpty()) {
            return "hash不一致（字段级差异未预计算）";
        }
        return String.join("; ", diffFields);
    }

    private String buildDescription(EtlVerifyDiff diff) {
        return switch (diff.getDiffType()) {
            case "INSERT_MISSING" -> "源端有此记录但目标端缺失，需要同步";
            case "UPDATE_DIFF" -> "源端和目标端数据不同，需要修复";
            case "DELETE_MISSING" -> "目标端有此记录但源端已无，可能需要删除";
            case "ROW_AUDIT_MISSING" -> "行级核查发现目标端缺失此记录";
            case "ROW_AUDIT_MISMATCH" -> "行级核查发现数据不一致";
            default -> "";
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

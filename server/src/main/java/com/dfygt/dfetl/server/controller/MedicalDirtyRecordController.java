package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.MedicalDirtyRecordDetailDto;
import com.dfygt.dfetl.server.entity.MedicalDirtyRow;
import com.dfygt.dfetl.server.medical.quality.MedicalDirtyRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/medical-dirty-records")
@RequiredArgsConstructor
public class MedicalDirtyRecordController {

    private final MedicalDirtyRecordService service;

    @GetMapping
    public ApiResponse<Page<MedicalDirtyRow>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String datasetCode,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "foundAt"));
        return ApiResponse.ok(service.list(taskId, executionId, datasetCode, ownerName, status, severity, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<MedicalDirtyRecordDetailDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String datasetCode,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        byte[] body = service.exportCsv(taskId, executionId, datasetCode, ownerName, status, severity)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("medical-dirty-records.csv", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String datasetCode,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        byte[] body = service.exportXlsx(taskId, executionId, datasetCode, ownerName, status, severity);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("medical-dirty-records.xlsx", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    @PatchMapping("/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id, @RequestBody HandleRequest request) {
        String status = request == null ? null : request.status();
        String handledBy = request == null ? null : request.handledBy();
        String note = request == null ? null : request.note();
        service.handle(id, status, handledBy, note);
        return ApiResponse.ok();
    }

    @PostMapping("/mark-sent")
    public ApiResponse<MarkSentResult> markSent(@RequestBody MarkSentRequest request) {
        Long taskId = request == null ? null : request.taskId();
        Long executionId = request == null ? null : request.executionId();
        String datasetCode = request == null ? null : request.datasetCode();
        String ownerName = request == null ? null : request.ownerName();
        String severity = request == null ? null : request.severity();
        int updatedCount = service.markSent(taskId, executionId, datasetCode, ownerName, severity);
        return ApiResponse.ok(new MarkSentResult("SENT_TO_OWNER", updatedCount));
    }

    public record HandleRequest(String status, String handledBy, String note) {}

    public record MarkSentRequest(
            Long taskId,
            Long executionId,
            String datasetCode,
            String ownerName,
            String severity) {}

    public record MarkSentResult(String status, int updatedCount) {}
}

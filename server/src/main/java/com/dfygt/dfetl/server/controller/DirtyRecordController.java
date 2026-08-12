package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.entity.DirtyRecord;
import com.dfygt.dfetl.server.repository.DirtyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/dirty-records")
@RequiredArgsConstructor
public class DirtyRecordController {

    private final DirtyRecordRepository repository;

    /**
     * 脏数据分页查询。
     * 支持按 taskId、errorType 过滤；默认按发现时间降序。
     */
    @GetMapping
    public ApiResponse<Page<DirtyRecord>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) Boolean handled
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "foundAt"));
        Page<DirtyRecord> result = repository.findByFilter(taskId, executionId, errorType, handled, pageable);
        return ApiResponse.ok(result.map(this::sanitize));
    }

    /** 脏数据详情 */
    @GetMapping("/{id}")
    public ApiResponse<DirtyRecord> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(this::sanitize)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new NoSuchElementException("DirtyRecord not found: " + id));
    }

    private DirtyRecord sanitize(DirtyRecord record) {
        record.setErrorMsg(ExecutionErrorSanitizer.sanitize(record.getErrorMsg()));
        record.setRawData(ExecutionErrorSanitizer.sanitize(record.getRawData()));
        return record;
    }

    /** 标记为已处理 */
    @PatchMapping("/{id}/handle")
    public ApiResponse<Void> handle(@PathVariable Long id) {
        repository.findById(id).ifPresent(r -> {
            r.setHandled(true);
            r.setHandledAt(java.time.Instant.now());
            repository.save(r);
        });
        return ApiResponse.ok();
    }
}

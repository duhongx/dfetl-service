package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByUserNameContainingIgnoreCase(String userName, Pageable pageable);

    Page<AuditLog> findByTargetTypeAndTargetId(String targetType, Long targetId, Pageable pageable);

    /**
     * 动态过滤查询：null 参数自动忽略，避免 PostgreSQL 类型推断错误（bytea→timestamptz）。
     */
    default Page<AuditLog> findByFilter(String userName, String action, String targetType,
                                        Long targetId, Instant startTime, Instant endTime,
                                        Pageable pageable) {
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (userName != null && !userName.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("userName")),
                        "%" + userName.toLowerCase() + "%"));
            }
            if (action != null && !action.isBlank()) {
                ps.add(cb.equal(root.get("action"), action));
            }
            if (targetType != null && !targetType.isBlank()) {
                ps.add(cb.equal(root.get("targetType"), targetType));
            }
            if (targetId != null) {
                ps.add(cb.equal(root.get("targetId"), targetId));
            }
            if (startTime != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("actionTime"), startTime));
            }
            if (endTime != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("actionTime"), endTime));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return findAll(spec, pageable);
    }
}

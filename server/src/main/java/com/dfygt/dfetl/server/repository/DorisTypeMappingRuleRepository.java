package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.DorisTypeMappingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DorisTypeMappingRuleRepository extends JpaRepository<DorisTypeMappingRule, Long> {

    List<DorisTypeMappingRule> findByEnabledTrueOrderByPriorityDescIdAsc();

    List<DorisTypeMappingRule> findAllByOrderBySourceDialectAscPriorityDescIdAsc();

    boolean existsBySourceDialectAndSourceTypePattern(String sourceDialect, String sourceTypePattern);
}

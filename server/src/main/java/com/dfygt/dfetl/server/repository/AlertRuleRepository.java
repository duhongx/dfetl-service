package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findAllByOrderByCreatedAtDesc();

    List<AlertRule> findByEnabledTrue();
}

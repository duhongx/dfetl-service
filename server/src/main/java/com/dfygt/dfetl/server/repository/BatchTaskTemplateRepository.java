package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.BatchTaskTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatchTaskTemplateRepository extends JpaRepository<BatchTaskTemplate, Long> {

    boolean existsByName(String name);

    Optional<BatchTaskTemplate> findByName(String name);
}

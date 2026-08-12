package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.BatchTaskTemplateSource;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BatchTaskTemplateSourceRepository extends JpaRepository<BatchTaskTemplateSource, Long> {

    List<BatchTaskTemplateSource> findByTemplateId(Long templateId);

    List<BatchTaskTemplateSource> findByTemplateIdAndEnabled(Long templateId, Boolean enabled);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BatchTaskTemplateSource s WHERE s.id = :id")
    Optional<BatchTaskTemplateSource> findByIdForUpdate(@Param("id") Long id);

    void deleteByTemplateId(Long templateId);
}

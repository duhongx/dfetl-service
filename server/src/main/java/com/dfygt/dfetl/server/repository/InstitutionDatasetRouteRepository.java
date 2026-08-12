package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstitutionDatasetRouteRepository extends JpaRepository<InstitutionDatasetRoute, Long> {

    List<InstitutionDatasetRoute> findByInstitutionIdOrderByIdAsc(Long institutionId);

    List<InstitutionDatasetRoute> findByDatasetIdOrderByIdAsc(Long datasetId);

    Optional<InstitutionDatasetRoute> findByInstitutionIdAndDatasetIdAndEnabledTrue(
            Long institutionId,
            Long datasetId);

    boolean existsByInstitutionIdAndDatasetIdAndEnabledTrueAndIdNot(
            Long institutionId,
            Long datasetId,
            Long id);
}

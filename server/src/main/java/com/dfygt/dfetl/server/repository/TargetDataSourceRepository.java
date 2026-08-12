package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.TargetDataSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TargetDataSourceRepository extends JpaRepository<TargetDataSource, Long> {

    Optional<TargetDataSource> findByName(String name);

    boolean existsByName(String name);
}

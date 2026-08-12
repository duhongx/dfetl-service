package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.Institution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 机构主表仓储层。
 *
 * <p>提供按 code、parent、enabled 维度的查询，以及多条件组合查询，
 * 任一过滤参数为 {@code null} 时跳过对应条件，供管理页面与业务查询服务复用。
 */
public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    Optional<Institution> findByCode(String code);

    List<Institution> findByParentId(Long parentId);

    List<Institution> findByEnabled(boolean enabled);

    boolean existsByCode(String code);

    @Query("SELECT i FROM Institution i WHERE " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:level IS NULL OR i.level = :level) AND " +
           "(:regionCode IS NULL OR i.regionCode = :regionCode) AND " +
           "(:enabled IS NULL OR i.enabled = :enabled)")
    List<Institution> search(@Param("type") String type,
                             @Param("level") String level,
                             @Param("regionCode") String regionCode,
                             @Param("enabled") Boolean enabled);
}

package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.SourceDataSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceDataSourceRepository extends JpaRepository<SourceDataSource, Long> {

    Optional<SourceDataSource> findByName(String name);

    boolean existsByName(String name);

    /**
     * 按机构 ID 查询数据源列表。
     *
     * <p>用于 {@link com.dfygt.dfetl.server.service.SourceDataSourceService#findByInstitutionId(Long)}
     * 与 {@code GET /api/datasource/source?institutionId=...} 列表过滤场景。
     */
    List<SourceDataSource> findByInstitutionId(Long institutionId);

    /**
     * 统计指定机构关联的源端数据源数量。
     *
     * <p>用于 {@link com.dfygt.dfetl.server.service.InstitutionService#delete(Long)} 等
     * 删除保护逻辑，判断机构是否仍被数据源引用。
     */
    long countByInstitutionId(Long institutionId);

    /**
     * 按 {@code source_code} 前缀模糊查询数据源列表。
     *
     * <p>用于 spec 070 数据源稳定编码生成（{@code SourceCodeGenerator}）：
     * 拼好 {@code {机构拼音首字母}-{库类型}} 前缀后，查询同前缀已落库的
     * source_code 集合，从中提取最大序号并 +1，得到下一个两位序号（如 {@code xrmyy-mysql-01}）。
     *
     * <p>由 Spring Data JPA 按方法名自动派生 SQL：
     * {@code WHERE source_code LIKE ?1%}（前缀匹配）。
     */
    List<SourceDataSource> findBySourceCodeStartingWith(String prefix);
}

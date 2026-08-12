package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.SyncTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SyncTaskRepository
        extends JpaRepository<SyncTask, Long>, JpaSpecificationExecutor<SyncTask> {

    List<SyncTask> findByStatus(String status);

    /**
     * 统计指定机构关联的同步任务数。
     *
     * <p>用于 {@link com.dfygt.dfetl.server.service.InstitutionService#delete(Long)} 等
     * 删除保护逻辑，判断机构是否仍被任务引用。
     */
    long countByInstitutionId(Long institutionId);

    /**
     * 按机构 ID 集合批量过滤同步任务，用于
     * {@link com.dfygt.dfetl.server.service.SyncTaskService#listByInstitution(Long, boolean)}。
     *
     * <p>当 includeChildren=true 时调用方需先通过
     * {@link com.dfygt.dfetl.server.service.InstitutionService#getDescendantIds(Long)}
     * 取子树 ID 集合再传入；为空集合时返回空列表（Spring Data 行为兼容）。
     */
    List<SyncTask> findByInstitutionIdIn(Collection<Long> institutionIds);

    /**
     * 分页变体：用于 spec institution-management 任务 15.2，
     * 在 {@link com.dfygt.dfetl.server.service.InstitutionQueryService} 与
     * {@link com.dfygt.dfetl.server.service.SyncTaskService#listByInstitution(Long, boolean, org.springframework.data.domain.Pageable)}
     * 中按机构维度查询任务时下推分页/排序到数据库，避免一次性拉取全表内存。
     */
    Page<SyncTask> findByInstitutionIdIn(Collection<Long> institutionIds, Pageable pageable);

    boolean existsByName(String name);

    Optional<SyncTask> findByName(String name);

    /**
     * 查询同一目标数据源上的任务，用于共享物理表范围保护。
     *
     * <p>TRUNCATE / DROP_DATA 是目标物理表级操作，不能只依赖任务行内的
     * {@code _etl_job_id} 隔离。因此保存期和运行期都需要比较同一目标数据源上
     * 其它任务解析后的目标表集合。
     */
    List<SyncTask> findByTargetDataSourceId(Long targetDataSourceId);

    @Query("""
            SELECT t FROM SyncTask t
             WHERE t.institutionId = :institutionId
               AND t.sourceDataSourceId = :sourceDataSourceId
               AND t.targetDataSourceId = :targetDataSourceId
               AND t.sourceMode = 'TABLE_VIEW'
               AND (:sourceSchema IS NULL OR t.sourceSchema = :sourceSchema)
             ORDER BY t.id ASC
            """)
    List<SyncTask> findExternalDuplicateCandidates(@Param("institutionId") Long institutionId,
                                                   @Param("sourceDataSourceId") Long sourceDataSourceId,
                                                   @Param("targetDataSourceId") Long targetDataSourceId,
                                                   @Param("sourceSchema") String sourceSchema);

    /**
     * validation / checksum / repair 派单互斥锁。
     *
     * <p>不能只锁 {@code validation_run.status='RUNNING'} 行，因为没有 RUNNING 行时
     * {@code SELECT ... FOR UPDATE} 不会锁住任何记录。锁任务行可序列化同一个 taskId 下的
     * 校验启动路径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SyncTask t WHERE t.id = :id")
    Optional<SyncTask> findByIdForUpdate(@Param("id") Long id);

    // ── spec institution-management 任务 18：数据治理 ────────────────────────

    /**
     * 查询孤儿任务：{@code sync_task.institution_id IS NULL} 且其引用的
     * {@code source_data_source.institution_id} 非空。
     *
     * <p>用于 spec institution-management 任务 18.1（{@code GET /api/institution/orphans}），
     * 由 {@link com.dfygt.dfetl.server.service.InstitutionGovernanceService} 调用。
     *
     * <p>返回字段顺序：{@code [taskId, taskName, sourceDataSourceId,
     * sourceDataSourceName, sourceInstitutionId]}，便于服务层零拷贝映射 DTO。
     * 未关联数据源（{@code source_datasource_id IS NULL}）的任务直接被排除。
     */
    @Query("""
            SELECT t.id, t.name, t.sourceDataSourceId, ds.name, ds.institutionId
              FROM SyncTask t
              JOIN SourceDataSource ds ON ds.id = t.sourceDataSourceId
             WHERE t.institutionId IS NULL
               AND ds.institutionId IS NOT NULL
             ORDER BY t.id ASC
            """)
    List<Object[]> findOrphanTaskRows();

    /**
     * 查询机构关联不一致的任务：双方 {@code institution_id} 都非空但不相等。
     *
     * <p>用于 spec institution-management 任务 18.2（{@code GET /api/institution/mismatches}），
     * 由 {@link com.dfygt.dfetl.server.service.InstitutionGovernanceService} 调用。
     *
     * <p>返回字段顺序：{@code [taskId, taskName, sourceDataSourceId,
     * sourceDataSourceName, taskInstitutionId, sourceInstitutionId]}。机构名称
     * 由服务层一次性补齐（避免外连接 institution 引入额外笛卡尔积）。
     */
    @Query("""
            SELECT t.id, t.name, t.sourceDataSourceId, ds.name, t.institutionId, ds.institutionId
              FROM SyncTask t
              JOIN SourceDataSource ds ON ds.id = t.sourceDataSourceId
             WHERE t.institutionId IS NOT NULL
               AND ds.institutionId IS NOT NULL
               AND t.institutionId <> ds.institutionId
             ORDER BY t.id ASC
            """)
    List<Object[]> findMismatchTaskRows();

    /**
     * 一次性回填孤儿任务的 institution_id：把
     * {@code source_data_source.institution_id} 赋值给关联的
     * {@code sync_task.institution_id}（仅当任务自身为 null 且数据源非空）。
     *
     * <p>用于 spec institution-management 任务 18.3 的 admin 修复端点。返回值为受影响行数。
     *
     * <p>注意：JPQL 不支持跨实体 UPDATE…JOIN，因此使用 IN 子查询；可读性优于原生 SQL，
     * 也避免了 schema 名硬编码（PG 的 {@code df_etl.sync_task}）。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE SyncTask t
               SET t.institutionId = (
                   SELECT ds.institutionId FROM SourceDataSource ds WHERE ds.id = t.sourceDataSourceId
               )
             WHERE t.institutionId IS NULL
               AND t.sourceDataSourceId IN (
                   SELECT ds2.id FROM SourceDataSource ds2 WHERE ds2.institutionId IS NOT NULL
               )
            """)
    int repairOrphanInstitutionIds();
}

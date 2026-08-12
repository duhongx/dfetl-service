package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.InstitutionDto;
import com.dfygt.dfetl.server.dto.InstitutionMismatchTaskDto;
import com.dfygt.dfetl.server.dto.InstitutionOrphanTaskDto;
import com.dfygt.dfetl.server.dto.InstitutionRepairReportDto;
import com.dfygt.dfetl.server.dto.InstitutionTargetRowCountDto;
import com.dfygt.dfetl.server.dto.InstitutionWithStatsDto;
import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.service.InstitutionGovernanceService;
import com.dfygt.dfetl.server.service.InstitutionQueryService;
import com.dfygt.dfetl.server.service.InstitutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机构主表 REST API — 机构 CRUD + 业务维度查询 + 数据治理。
 *
 * <p>覆盖三类端点：
 * <ul>
 *   <li>CRUD（{@link InstitutionService}）：机构列表 / 详情 / 创建 / 更新 / 删除 / 禁用 / 树查询。</li>
 *   <li>业务查询（{@link InstitutionQueryService}）：机构维度的同步任务、数据源、按目标表查贡献机构、
 *       机构 + 关联资产统计。</li>
 *   <li>数据治理（{@link InstitutionGovernanceService}）：孤儿任务检测、机构关联不一致检测、
 *       admin 一次性修复孤儿（spec institution-management 任务 18.1/18.2/18.3）。</li>
 * </ul>
 *
 * <p>异常映射统一由 {@link com.dfygt.dfetl.server.common.GlobalExceptionHandler} 处理：
 * <ul>
 *   <li>{@link IllegalArgumentException}（code 重复 / parent 不存在 / 环引用 / 非法参数）→ HTTP 400</li>
 *   <li>{@link IllegalStateException}（含关联仍删除）→ HTTP 400，提示改用禁用</li>
 *   <li>{@link java.util.NoSuchElementException}（机构不存在）→ HTTP 404</li>
 * </ul>
 *
 * <p>相关 spec：{@code .kiro/specs/institution-management}（任务 5.1 与 6.2）。
 */
@RestController
@RequestMapping("/api/institution")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService institutionService;
    private final InstitutionQueryService queryService;
    private final InstitutionGovernanceService governanceService;

    // ── CRUD（任务 5.1）────────────────────────────────────────────────────

    /** 多条件列表查询；所有参数为空等价于全表。 */
    @GetMapping
    public ApiResponse<List<InstitutionDto>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(institutionService.list(type, level, regionCode, enabled));
    }

    /**
     * 树形展示数据：当前阶段返回完整列表（含 parent_id），由前端基于 parent_id
     * 自行渲染树。机构数量在万级以内，无需服务端构造嵌套结构。
     */
    @GetMapping("/tree")
    public ApiResponse<List<InstitutionDto>> tree() {
        return ApiResponse.ok(institutionService.list(null, null, null, null));
    }

    @GetMapping("/{id}")
    public ApiResponse<InstitutionDto> get(@PathVariable Long id) {
        return ApiResponse.ok(institutionService.get(id));
    }

    @PostMapping
    public ApiResponse<InstitutionDto> create(@RequestBody @Valid InstitutionDto dto) {
        return ApiResponse.ok(institutionService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<InstitutionDto> update(
            @PathVariable Long id,
            @RequestBody @Valid InstitutionDto dto) {
        return ApiResponse.ok(institutionService.update(id, dto));
    }

    /**
     * 物理删除；存在 sync_task / source_data_source 关联时返回 400 并提示
     * 调用方改用 {@link #disable(Long)}。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        institutionService.delete(id);
        return ApiResponse.ok();
    }

    /** 软删除：将 enabled 置为 false，保留记录与关联引用。 */
    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        institutionService.disable(id);
        return ApiResponse.ok();
    }

    // ── 业务查询（任务 6.2）─────────────────────────────────────────────────

    /**
     * 列出机构关联的同步任务；{@code includeChildren=true} 时含子机构（基于 parent_id 子树）。
     *
     * <p>spec institution-management 任务 15.2：默认 size=50，最大 500，
     * 防止机构关联任务规模上升后大查询阻塞前端。返回形态保持 {@code List<SyncTaskDto>}
     * 以兼容已有前端调用（详见 web/src/api/institution.ts 的 syncTasks 方法）。
     *
     * <p>对应设计文档 Property 7「任务机构过滤与子机构包含」
     * （Validates: Requirements 3.3, 3.4, 4.2）。
     *
     * @param page 0-based 页号，默认 0
     * @param size 页大小，默认 50；服务层会 clamp 到最大 500
     */
    @GetMapping("/{id}/sync-tasks")
    public ApiResponse<List<SyncTaskDto>> syncTasks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeChildren,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(queryService.listTasksByInstitution(id, includeChildren, page, size));
    }

    /** 列出机构关联的源端数据源（不含子机构）。 */
    @GetMapping("/{id}/datasources")
    public ApiResponse<List<SourceDataSourceDto>> datasources(@PathVariable Long id) {
        return ApiResponse.ok(queryService.listDatasourcesByInstitution(id));
    }

    /**
     * 列出贡献到指定 Doris 目标表的机构，含 taskCount / lastSyncTime / statusSummary。
     *
     * <p>spec institution-management 任务 15.2：默认 size=50，最大 500，
     * 防止 sync_task 表规模增长后单次返回过多机构。返回形态保持
     * {@code List<InstitutionWithStatsDto>} 以兼容已有前端调用
     * （详见 web/src/api/institution.ts 的 byTargetTable 方法）。
     *
     * <p>对应设计文档 Property 8「按目标表查机构的等价性」
     * （Validates: Requirements 4.1, 4.3）。
     *
     * @param page 0-based 页号，默认 0
     * @param size 页大小，默认 50；服务层会 clamp 到最大 500
     */
    @GetMapping("/by-target-table")
    public ApiResponse<List<InstitutionWithStatsDto>> byTargetTable(
            @RequestParam String table,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(queryService.listSourceInstitutionsByTargetTable(table, page, size));
    }

    /**
     * 按 Doris 目标表统计各贡献机构的「实际行数」（spec 069 P2）。
     *
     * <p>与 {@link #byTargetTable} 互补：后者统计口径来自 sync_task 元数据（任务数/状态/最近同步），
     * 本端点对 Doris 目标表按 {@code _etl_job_id} 实测 {@code COUNT(*)}，回答「某机构在该表里
     * 实际落了多少行」。属多机构共表场景的机构维度数据可观测性。
     *
     * <p>注意：本端点走目标端 Doris 实时连接，大表上有读负载，前端按需懒加载（点击/展开时调用），
     * 不与 {@link #byTargetTable} 的快路径耦合。单机构/单表查询失败以结果项的 {@code error} 字段
     * 标注，不抛断整个请求。
     *
     * @param table Doris 目标表名（不区分大小写；空白返回空列表）
     */
    @GetMapping("/target-table-row-counts")
    public ApiResponse<List<InstitutionTargetRowCountDto>> targetTableRowCounts(
            @RequestParam String table) {
        return ApiResponse.ok(queryService.countInstitutionRowsByTargetTable(table));
    }

    /**
     * 单机构 + 关联资产统计；用于机构管理页列表行展示
     * （Validates: Requirements 6.3）。
     */
    @GetMapping("/{id}/stats")
    public ApiResponse<InstitutionWithStatsDto> stats(@PathVariable Long id) {
        return ApiResponse.ok(queryService.getInstitutionWithStats(id));
    }

    // ── 数据治理（任务 18）─────────────────────────────────────────────────

    /**
     * 列出孤儿任务：{@code sync_task.institution_id IS NULL} 且其引用的
     * {@code source_data_source.institution_id IS NOT NULL}。
     *
     * <p>spec institution-management 任务 18.1（Validates: Requirements 5.2）。
     * 该端点为只读检测视图，不修改数据；运维确认无误后调用
     * {@link #repairOrphans(boolean)} 执行实际修复。
     */
    @GetMapping("/orphans")
    public ApiResponse<List<InstitutionOrphanTaskDto>> orphans() {
        return ApiResponse.ok(governanceService.findOrphanTasks());
    }

    /**
     * 列出任务与数据源机构关联不一致的记录：双方 institution_id 都非空但不相等。
     *
     * <p>spec institution-management 任务 18.2（Validates: Requirements 5.2）。
     * 该端点为只读报告，不会自动覆盖——共享数据源被多个机构复用是合法场景，
     * 由人工根据上下文逐条决策。
     */
    @GetMapping("/mismatches")
    public ApiResponse<List<InstitutionMismatchTaskDto>> mismatches() {
        return ApiResponse.ok(governanceService.findMismatchTasks());
    }

    /**
     * 一次性修复孤儿任务（仅 admin 可调用）：把 {@code source_data_source.institution_id}
     * 回填到关联的 {@code sync_task.institution_id}。
     *
     * <p>spec institution-management 任务 18.3（Validates: Requirements 5.2）。
     *
     * <p>权限说明：当前路径前缀 {@code /api/institution/admin/...} 与
     * {@code /api/admin/column-type-correction} 的现有 admin 端点约定一致——
     * 由 SecurityConfig 的 {@code anyRequest().authenticated()} 保证已登录，
     * 后续接入 RBAC 时仅需在此处加 {@code @PreAuthorize("hasRole('ADMIN')")}。
     *
     * <p>报告输出：{@link InstitutionRepairReportDto} 包含操作前后孤儿数、本次实际
     * affectedRows、被修复的任务 ID 列表，便于运维核查与回滚。
     *
     * @param dryRun 默认 false=实际执行修复；true=仅返回当前孤儿列表，不修改数据
     */
    @PostMapping("/admin/repair-orphans")
    public ApiResponse<InstitutionRepairReportDto> repairOrphans(
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(governanceService.repairOrphans(dryRun));
    }
}

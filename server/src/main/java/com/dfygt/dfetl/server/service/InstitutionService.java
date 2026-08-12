package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.InstitutionDto;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 机构主表（{@link Institution}）的 CRUD 与树查询服务。
 *
 * <p>核心约束：
 * <ul>
 *     <li>{@code code} 全局唯一；创建时若重复抛 {@link IllegalArgumentException}（HTTP 400）。</li>
 *     <li>更新时 {@code code} 不可变更，强制保留持久化值；其余业务字段均允许修改。</li>
 *     <li>{@code parent_id} 非空时必须指向已存在的机构；更新时检测父链不能形成环引用。</li>
 *     <li>删除采用「关联保护」语义：若仍有 {@code sync_task} 或 {@code source_data_source}
 *     引用本机构，则拒绝物理删除并提示调用方使用 {@link #disable(Long)} 禁用。</li>
 * </ul>
 *
 * <p>树查询使用迭代 BFS / 父链上溯实现，并对脏数据中可能出现的环引用做防御性
 * {@code visited} 去重，保证服务在不一致数据下仍能终止返回。
 *
 * <p>与设计文档 {@code .kiro/specs/institution-management/design.md} 中
 * Property 1~4 的映射详见各方法 Javadoc。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstitutionService {

    private final InstitutionRepository repository;
    private final SyncTaskRepository syncTaskRepository;
    private final SourceDataSourceRepository sourceDataSourceRepository;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    /**
     * 创建机构记录。
     *
     * <p>校验：
     * <ol>
     *     <li>{@code code} 必须未被占用，否则抛 {@link IllegalArgumentException}。</li>
     *     <li>{@code parentId} 非空时必须指向已存在机构。</li>
     * </ol>
     * 由于新建记录尚未分配 id，结构上不可能形成环，无需父链上溯校验。
     *
     * <p>{@code enabled} 缺省为 true（与 {@link Institution} 实体默认值保持一致）。
     */
    @Transactional
    public InstitutionDto create(InstitutionDto dto) {
        if (dto.getCode() == null || dto.getCode().isBlank()) {
            throw new IllegalArgumentException("机构 code 不能为空");
        }
        if (repository.existsByCode(dto.getCode())) {
            throw new IllegalArgumentException("机构 code 已存在: " + dto.getCode());
        }
        if (dto.getParentId() != null && !repository.existsById(dto.getParentId())) {
            throw new IllegalArgumentException("上级机构不存在: parentId=" + dto.getParentId());
        }
        Institution entity = new Institution();
        copyToEntity(dto, entity, /* preserveCode */ false);
        if (entity.getEnabled() == null) entity.setEnabled(true);
        Institution saved = repository.save(entity);
        log.info("Institution created: id={}, code={}", saved.getId(), saved.getCode());
        return toDto(saved);
    }

    /**
     * 更新机构记录。
     *
     * <p>{@code code} 不可修改：dto 中的 code 字段被忽略，始终保留实体已持久化值。
     * <p>{@code parentId} 变更时执行父链上溯环检测：从新 parent 沿 parent_id 一路向上，
     * 若回到 {@code id} 自身则视为环引用，抛 {@link IllegalArgumentException}。
     */
    @Transactional
    public InstitutionDto update(Long id, InstitutionDto dto) {
        Institution entity = getOrThrow(id);
        if (dto.getParentId() != null) {
            if (dto.getParentId().equals(id)) {
                throw new IllegalArgumentException("上级机构不能为自身: id=" + id);
            }
            if (!repository.existsById(dto.getParentId())) {
                throw new IllegalArgumentException("上级机构不存在: parentId=" + dto.getParentId());
            }
            // 父链上溯环检测：若沿新 parent 上溯能回到 id，则拒绝
            if (wouldFormCycle(id, dto.getParentId())) {
                throw new IllegalArgumentException(
                        "设置 parentId=" + dto.getParentId() + " 会导致环引用，机构 id=" + id);
            }
        }
        copyToEntity(dto, entity, /* preserveCode */ true);
        Institution saved = repository.save(entity);
        log.info("Institution updated: id={}, code={}", saved.getId(), saved.getCode());
        return toDto(saved);
    }

    /** 按 id 读取单条机构。不存在时抛 {@link NoSuchElementException}（→ HTTP 404）。 */
    public InstitutionDto get(Long id) {
        return toDto(getOrThrow(id));
    }

    /**
     * 多条件列表查询，所有过滤参数为 {@code null} 时跳过对应条件，全部为 null 等价于全表。
     */
    public List<InstitutionDto> list(String type, String level, String regionCode, Boolean enabled) {
        return repository.search(type, level, regionCode, enabled).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 物理删除机构记录。
     *
     * <p>关联保护：若仍有 {@code sync_task.institution_id == id} 或
     * {@code source_data_source.institution_id == id} 的记录，则拒绝删除并抛
     * {@link IllegalStateException}（→ HTTP 400），提示调用方改用 {@link #disable(Long)}。
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("Institution not found: " + id);
        }
        long taskCount = syncTaskRepository.countByInstitutionId(id);
        long dsCount = sourceDataSourceRepository.countByInstitutionId(id);
        if (taskCount > 0 || dsCount > 0) {
            throw new IllegalStateException(
                    "机构仍被引用，无法删除（同步任务=" + taskCount + " 个，数据源=" + dsCount
                            + " 个）；请改用禁用（disable）");
        }
        repository.deleteById(id);
        log.info("Institution deleted: id={}", id);
    }

    /** 软删除：将 {@code enabled} 置为 false，保留记录与关联引用。 */
    @Transactional
    public void disable(Long id) {
        Institution entity = getOrThrow(id);
        entity.setEnabled(false);
        repository.save(entity);
        log.info("Institution disabled: id={}", id);
    }

    // ── 树查询（Task 3.3） ───────────────────────────────────────────────────

    /**
     * 返回以 {@code id} 为根的整棵子树（含根自身），按层序排列。
     *
     * <p>实现：迭代 BFS，每层调用 {@link InstitutionRepository#findByParentId(Long)}；
     * {@code visited} 集合在遍历中既用于结果去重，也用于在脏数据出现父子环时
     * 强制终止，确保函数总能返回。
     *
     * <p>对应 Property 4「树查询互逆」：在无环数据下与 {@link #getAncestors(Long)} 满足
     * {@code j ∈ getDescendants(i.id) ⟺ i ∈ getAncestors(j.id) ∪ {j}}。
     */
    public List<InstitutionDto> getDescendants(Long id) {
        Institution root = repository.findById(id).orElse(null);
        if (root == null) return Collections.emptyList();
        List<InstitutionDto> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<Institution> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root.getId());
        result.add(toDto(root));
        while (!queue.isEmpty()) {
            Institution current = queue.poll();
            for (Institution child : repository.findByParentId(current.getId())) {
                if (!visited.add(child.getId())) {
                    log.warn("Institution descendant cycle/duplicate detected at id={}, skipped", child.getId());
                    continue;
                }
                result.add(toDto(child));
                queue.add(child);
            }
        }
        return result;
    }

    /**
     * 返回 {@code id} 的所有祖先链（不含自身），从直接父节点向根方向排列。
     *
     * <p>实现：沿 {@code parent_id} 迭代上溯，{@code visited} 防御父链环引用，
     * 一旦回到访问过的节点立即终止并记录告警。
     */
    public List<InstitutionDto> getAncestors(Long id) {
        Institution self = repository.findById(id).orElse(null);
        if (self == null) return Collections.emptyList();
        List<InstitutionDto> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(self.getId());
        Long cursor = self.getParentId();
        while (cursor != null) {
            if (!visited.add(cursor)) {
                log.warn("Institution ancestor cycle detected at id={}, abort traversal", cursor);
                break;
            }
            Institution parent = repository.findById(cursor).orElse(null);
            if (parent == null) break;
            result.add(toDto(parent));
            cursor = parent.getParentId();
        }
        return result;
    }

    /**
     * 返回 {@code id} 子树（含自身）的所有机构 ID 集合，专供
     * {@code SyncTaskService.list(institutionId, includeChildren=true)} 等
     * 需要 IN 过滤的过滤查询使用。
     *
     * <p>使用 {@link LinkedHashSet} 保留 BFS 访问顺序便于排查；
     * 与 {@link #getDescendants(Long)} 共享相同的环防御逻辑。
     */
    public Set<Long> getDescendantIds(Long id) {
        Institution root = repository.findById(id).orElse(null);
        if (root == null) return Collections.emptySet();
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(root.getId());
        result.add(root.getId());
        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            for (Institution child : repository.findByParentId(currentId)) {
                if (result.add(child.getId())) {
                    queue.add(child.getId());
                } else {
                    log.warn("getDescendantIds: cycle/duplicate detected at id={}, skipped", child.getId());
                }
            }
        }
        return result;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Institution getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Institution not found: " + id));
    }

    /**
     * 检测把 {@code targetId} 的 parent 设为 {@code newParentId} 是否会形成环：
     * 从 {@code newParentId} 沿 parent_id 上溯，若途中遇到 {@code targetId} 则视为环。
     */
    private boolean wouldFormCycle(Long targetId, Long newParentId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = newParentId;
        while (cursor != null) {
            if (cursor.equals(targetId)) return true;
            if (!visited.add(cursor)) {
                // 已存在环，按保守策略也拒绝
                log.warn("wouldFormCycle: existing cycle detected at id={}", cursor);
                return true;
            }
            Institution parent = repository.findById(cursor).orElse(null);
            if (parent == null) break;
            cursor = parent.getParentId();
        }
        return false;
    }

    /**
     * 把 dto 字段写入 entity；所有非空字段全部覆盖，{@code enabled} 显式提供时也覆盖。
     *
     * @param preserveCode true 时（更新场景）忽略 dto.code，避免破坏 code 不可变约束
     */
    private void copyToEntity(InstitutionDto dto, Institution entity, boolean preserveCode) {
        if (!preserveCode && dto.getCode() != null) entity.setCode(dto.getCode());
        if (dto.getName() != null)        entity.setName(dto.getName());
        if (dto.getShortName() != null)   entity.setShortName(dto.getShortName());
        if (dto.getType() != null)        entity.setType(dto.getType());
        if (dto.getLevel() != null)       entity.setLevel(dto.getLevel());
        if (dto.getRegionCode() != null)  entity.setRegionCode(dto.getRegionCode());
        // parentId 允许显式置空（顶级机构），故不能用 != null 判断
        entity.setParentId(dto.getParentId());
        if (dto.getEnabled() != null)     entity.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) entity.setDescription(dto.getDescription());
    }

    private InstitutionDto toDto(Institution e) {
        InstitutionDto dto = new InstitutionDto();
        dto.setId(e.getId());
        dto.setCode(e.getCode());
        dto.setName(e.getName());
        dto.setShortName(e.getShortName());
        dto.setType(e.getType());
        dto.setLevel(e.getLevel());
        dto.setRegionCode(e.getRegionCode());
        dto.setParentId(e.getParentId());
        dto.setEnabled(e.getEnabled());
        dto.setDescription(e.getDescription());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}

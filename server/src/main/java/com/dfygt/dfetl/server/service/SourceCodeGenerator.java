package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.PinyinInitialUtil;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源稳定编码（{@code source_code}）生成器（spec 070 §5）。
 *
 * <p>组成：{@code {机构名拼音首字母}-{库类型小写}-{两位序号}}，
 * 例如 {@code xrmyy-mysql-01}。已存在数据源的编码保持不变。
 *
 * <p>算法（参见 design.md §2 SourceCodeGenerator）：
 * <ol>
 *   <li>校验 institutionId / type 非空。</li>
 *   <li>按 ID 查 {@link Institution#getName()}，查不到抛
 *       {@link IllegalArgumentException}（service 层会转 HTTP 400）。</li>
 *   <li>用 {@link PinyinInitialUtil#toInitials(String)} 把机构名转拼音首字母小写码。</li>
 *   <li>{@code type.toLowerCase().trim()} 作为库类型段。</li>
 *   <li>拼前缀 {@code {机构}-{库类型}};查 {@code findBySourceCodeStartingWith(prefix + "-")}
 *       （末尾 {@code -} 防止 {@code mysql} 误匹配 {@code mysql2} 等同前缀重叠）。</li>
 *   <li>遍历结果提取尾部 {@code -(\d+)$} 序号取最大值，{@code +1};列表为空或无可解析序号则从 1 开始;
 *       用 {@code "%02d"} 格式化（序号 ≥ 100 时 Java 自动用 3 位，不报错）。</li>
 *   <li>返回 {@code prefix + "-" + 序号}。</li>
 * </ol>
 *
 * <p><b>并发与唯一冲突</b>:本生成器仅做「读 + 计算」,不处理并发重试。落库撞唯一约束
 * （{@link org.springframework.dao.DataIntegrityViolationException}）时，外层
 * {@link SourceDataSourceService#create} 使用新实体重试；每次落库由
 * {@link SourceDataSourceCreateAttemptService} 在独立事务中完成。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceCodeGenerator {

    /** 提取 source_code 尾部 {@code -<数字>} 段的正则,如 {@code xrmyy-mysql-01} → 捕获组 {@code 01}。 */
    private static final Pattern TAIL_SEQ_PATTERN = Pattern.compile("-(\\d+)$");

    private final InstitutionRepository institutionRepository;
    private final SourceDataSourceRepository sourceDataSourceRepository;

    /**
     * 生成数据源稳定编码:{@code {机构首字母}-{库类型}-{序号}}。
     *
     * @param institutionId 机构 ID（必填,空则抛 {@link IllegalArgumentException}）
     * @param type          库类型（必填,如 {@code MYSQL}/{@code ORACLE},空则抛 {@link IllegalArgumentException}）
     * @return 唯一稳定编码,如 {@code xrmyy-mysql-01}
     * @throws IllegalArgumentException 入参为空、或机构 ID 在主表中不存在
     */
    public String generate(Long institutionId, String type) {
        if (institutionId == null) {
            throw new IllegalArgumentException("机构不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("库类型不能为空");
        }

        Institution institution = institutionRepository.findById(institutionId)
                .orElseThrow(() -> new IllegalArgumentException("机构不存在: id=" + institutionId));

        String instInitials = PinyinInitialUtil.toInitials(institution.getName());
        String typeLower = type.trim().toLowerCase();
        String prefix = instInitials + "-" + typeLower;

        int nextSeq = nextSequence(prefix);
        String code = prefix + "-" + String.format("%02d", nextSeq);
        log.debug("SourceCodeGenerator.generate: institutionId={} type={} prefix={} nextSeq={} code={}",
                institutionId, type, prefix, nextSeq, code);
        return code;
    }

    /**
     * 计算指定前缀下一个可用序号:查 {@code source_code LIKE '{prefix}-%'}
     * 的现有记录,提取尾部数字段取 max,+1;无匹配则从 1 开始。
     *
     * <p>查询时拼接末尾 {@code -} 防止前缀误匹配:例如 prefix={@code xrmyy-mysql} 查询
     * {@code xrmyy-mysql-} 才不会扫到 {@code xrmyy-mysql2-...} 等同前缀重叠的库类型。
     */
    private int nextSequence(String prefix) {
        List<SourceDataSource> existing = sourceDataSourceRepository.findBySourceCodeStartingWith(prefix + "-");
        int max = 0;
        for (SourceDataSource ds : existing) {
            String code = ds.getSourceCode();
            if (code == null) {
                continue;
            }
            Matcher m = TAIL_SEQ_PATTERN.matcher(code);
            if (m.find()) {
                try {
                    int seq = Integer.parseInt(m.group(1));
                    if (seq > max) {
                        max = seq;
                    }
                } catch (NumberFormatException ignore) {
                    // 序号段超出 int 范围属于异常数据,忽略不阻断
                }
            }
        }
        return max + 1;
    }
}

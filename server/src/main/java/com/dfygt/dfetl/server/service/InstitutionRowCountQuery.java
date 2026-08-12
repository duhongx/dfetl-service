package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 「按目标表 + 任务（{@code _etl_job_id}）分组统计行数」的 Doris 实时查询协作者（spec 069 P2）。
 *
 * <p>从 {@link InstitutionQueryService} 抽出，单一职责是「连 Doris 跑一条
 * {@code GROUP BY _etl_job_id} 的 COUNT 查询」，把副作用（外部库 IO）与
 * {@link InstitutionQueryService} 的纯聚合逻辑解耦，便于后者单测时 mock 本类。
 *
 * <p>查询形态：
 * <pre>{@code
 *   SELECT `_etl_job_id`, COUNT(*) FROM `db`.`table`
 *   WHERE `_etl_job_id` IN (id1, id2, ...)
 *   GROUP BY `_etl_job_id`
 * }</pre>
 * 其中 {@code _etl_job_id} 为 BIGINT，值即 {@code sync_task.id}（spec 069 强制启用，目标表必有此列）。
 *
 * <p>连接方式与 {@code ChangeDataReader} / {@code SeaTunnelConfBuilder.countTargetRows} 一致：
 * Doris 走 MySQL 协议，经 {@link JdbcConnectionPoolManager} 池化复用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstitutionRowCountQuery {

    /** spec 069：任务范围标识列，与 {@code EtlSystemFieldsService} / ValidationWhereBuilder 一致。 */
    static final String ETL_JOB_ID_COL = "_etl_job_id";

    private final JdbcConnectionPoolManager connectionPoolManager;
    private final AesUtil aesUtil;

    /**
     * 在指定目标 Doris 表上，按 {@code _etl_job_id} 统计给定任务集合各自的行数。
     *
     * @param target   目标端数据源（提供 feHost/fePort/dbName/username/passwordEnc）
     * @param table    Doris 目标表名（由调用方从 targetTableMap/viewNames 解析得到）
     * @param jobIds   待统计的 {@code _etl_job_id}（即 task id）集合；空集合直接返回空 map
     * @return {@code jobId → rowCount}（只含有数据的 jobId；未出现的视为 0 行）
     * @throws Exception 连接/查询失败时抛出，由调用方按机构粒度兜底（标注 error，不阻断其它机构）
     */
    public Map<Long, Long> countRowsByJobId(TargetDataSource target, String table,
                                            Collection<Long> jobIds) throws Exception {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (target == null || jobIds == null || jobIds.isEmpty()) {
            return result;
        }
        String safeTable = sanitizeIdentifier(table);
        String safeDb = sanitizeIdentifier(target.getDbName());

        // jobIds 均来自 sync_task.id（Long），数值字面量直接拼入，无注入风险
        StringJoiner inList = new StringJoiner(", ");
        for (Long id : jobIds) {
            if (id != null) inList.add(Long.toString(id));
        }
        if (inList.length() == 0) {
            return result;
        }

        String sql = "SELECT `" + ETL_JOB_ID_COL + "`, COUNT(*) FROM `" + safeDb + "`.`" + safeTable + "`"
                + " WHERE `" + ETL_JOB_ID_COL + "` IN (" + inList + ")"
                + " GROUP BY `" + ETL_JOB_ID_COL + "`";

        String url = "jdbc:mysql://" + target.getFeHost() + ":" + target.getFePort()
                + "/" + target.getDbName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String pwd = aesUtil.decrypt(target.getPasswordEnc());

        try (Connection conn = connectionPoolManager.getConnection(url, target.getUsername(), pwd);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long jobId = rs.getLong(1);
                long count = rs.getLong(2);
                result.put(jobId, count);
            }
        }
        return result;
    }

    /**
     * Doris 标识符（库名/表名）白名单校验，防 SQL 注入。
     * 与 {@code ChangeDataReader.sanitizeTable} 同款规则：仅允许字母/数字/下划线，首字符非数字。
     */
    private String sanitizeIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("非法的 Doris 标识符: " + identifier);
        }
        return identifier.toLowerCase(Locale.ROOT);
    }
}

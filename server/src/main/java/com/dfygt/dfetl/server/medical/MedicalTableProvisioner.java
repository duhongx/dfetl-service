package com.dfygt.dfetl.server.medical;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 表供给服务：在 df_etl 库中批量执行 DDL（DROP + CREATE）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalTableProvisioner {

    private final TargetDataSourceRepository targetDsRepository;
    private final AesUtil aesUtil;

    /**
     * 批量在 df_etl 中执行建表（DROP + CREATE）。
     *
     * @param targetDsId df_etl 目标数据源 ID（target_datasource 表主键）
     * @param ddlList    DDL 生成结果列表
     * @return 操作结果摘要
     */
    public ProvisionResult provision(Long targetDsId, List<DdlResult> ddlList) {
        if (ddlList == null || ddlList.isEmpty()) {
            return new ProvisionResult(0, 0, List.of());
        }

        int successCount = 0;
        int failedCount = 0;
        List<FailureDetail> failures = new ArrayList<>();

        try (Connection conn = openTargetConnection(targetDsId)) {
            for (DdlResult result : ddlList) {
                // 跳过 ddl 为 null 的（errorMessage 不为空的 DdlResult）
                if (result.ddl() == null) {
                    continue;
                }

                String tableName = result.tableName();
                try {
                    // 先执行 DROP TABLE IF EXISTS
                    try (Statement stmt = conn.createStatement()) {
                        String dropSql = "DROP TABLE IF EXISTS `" + tableName + "`";
                        stmt.execute(dropSql);
                    }

                    // 再执行 CREATE TABLE DDL
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(result.ddl());
                    }

                    successCount++;
                    log.info("[MedicalRegistry] 建表成功: {}", tableName);
                } catch (Exception e) {
                    failedCount++;
                    failures.add(new FailureDetail(tableName, e.getMessage()));
                    log.error("[MedicalRegistry] 建表失败: {}, 错误: {}", tableName, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("连接 df_etl 目标数据源失败 (targetDsId=" + targetDsId + "): " + e.getMessage(), e);
        }

        return new ProvisionResult(successCount, failedCount, failures);
    }

    /**
     * 打开目标 Doris 的 JDBC 连接（MySQL 协议）。
     */
    private Connection openTargetConnection(Long targetDsId) throws Exception {
        TargetDataSource tgt = targetDsRepository.findById(targetDsId)
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found: " + targetDsId));
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=30000&socketTimeout=120000";
        String password = aesUtil.decrypt(tgt.getPasswordEnc());
        return DriverManager.getConnection(url, tgt.getUsername(), password);
    }
}

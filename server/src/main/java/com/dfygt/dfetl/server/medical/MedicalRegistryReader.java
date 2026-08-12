package com.dfygt.dfetl.server.medical;

import com.dfygt.dfetl.server.service.SourceDataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范注册表读取服务，从 df_ygt Doris 读取 dm_shujuji / dm_shujujzd 数据集定义。
 * <p>
 * 优先使用全局配置（MedicalRegistryConfig），如果全局配置未设置则回退到旧的 sourceDataSourceService 方式。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicalRegistryReader {

    private final SourceDataSourceService sourceDataSourceService;
    private final MedicalRegistryConfig medicalRegistryConfig;

    /**
     * 从全局配置读取所有有效数据集定义（含完整字段列表）。
     * <p>使用 MedicalRegistryConfig 中的全局连接信息。</p>
     *
     * @return 有效数据集列表，每个含完整字段定义；连接失败抛 RuntimeException
     */
    public List<DatasetDefinition> loadDatasets() {
        if (!medicalRegistryConfig.isEnabled() || !medicalRegistryConfig.isConfigured()) {
            throw new IllegalStateException("[MedicalRegistry] 全局配置未启用或未完整配置");
        }

        String datasetTable = medicalRegistryConfig.getDatasetTable();
        String fieldTable = medicalRegistryConfig.getFieldTable();
        String dataItemTable = medicalRegistryConfig.getDataItemTable();
        String datasetPrefix = medicalRegistryConfig.getDatasetPrefix();

        String sqlDatasets = buildDatasetsSql(datasetTable);
        String sqlFields = buildFieldsSql(fieldTable, dataItemTable);

        List<RegistryDatasetRow> datasetRows = new ArrayList<>();
        try (Connection conn = medicalRegistryConfig.openConnection();
             PreparedStatement statement = conn.prepareStatement(sqlDatasets)) {
            statement.setString(1, datasetPrefix);
            statement.setString(2, datasetPrefix);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    datasetRows.add(new RegistryDatasetRow(
                            rs.getString("shujujid"),
                            rs.getString("shujujdm"),
                            rs.getString("shujujmc"),
                            rs.getString("banben"),
                            rs.getInt("zuofeibz")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("[MedicalRegistry] 连接全局配置 Doris 或查询 " + datasetTable + " 失败: " + e.getMessage(), e);
        }

        if (datasetRows.isEmpty()) {
            throw new IllegalStateException("[MedicalRegistry] " + datasetTable + " 未返回任何有效数据集");
        }

        List<DatasetDefinition> result = new ArrayList<>();
        try (Connection conn = medicalRegistryConfig.openConnection();
             PreparedStatement ps = conn.prepareStatement(sqlFields)) {

            for (RegistryDatasetRow row : datasetRows) {
                ps.setString(1, row.shujujid());
                List<FieldDefinition> fields = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fields.add(mapField(rs, row.shujujdm()));
                    }
                }
                if (fields.isEmpty()) {
                    throw new IllegalStateException("[MedicalRegistry] 数据集 " + row.shujujdm()
                            + " (" + row.shujujid() + ") 无有效字段定义");
                }
                result.add(new DatasetDefinition(
                        row.shujujid(),
                        row.shujujdm(),
                        row.shujujmc(),
                        row.banben(),
                        fields
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("[MedicalRegistry] 查询 " + fieldTable + " 失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 从 df_ygt 读取所有有效数据集定义（含完整字段列表）。
     * <p>向后兼容：通过 sourceDataSourceService 连接。</p>
     *
     * @param registryDsId df_ygt 数据源 ID（在 source_data_source 表中的主键）
     * @return 有效数据集列表，每个含完整字段定义；连接失败抛 RuntimeException
     * @deprecated 使用无参数版本 {@link #loadDatasets()} 代替
     */
    @Deprecated
    public List<DatasetDefinition> loadDatasets(Long registryDsId) {
        // 如果全局配置已启用且完整，优先使用全局配置
        if (medicalRegistryConfig.isEnabled() && medicalRegistryConfig.isConfigured()) {
            return loadDatasets();
        }

        // 回退到旧的 sourceDataSourceService 方式
        String sqlDatasets = buildDatasetsSql("dm_shujuji");
        String sqlFields = buildFieldsSql("dm_shujujzd", "dm_shujuxiang");

        List<RegistryDatasetRow> datasetRows = queryDatasetsLegacy(
                registryDsId, sqlDatasets, medicalRegistryConfig.getDatasetPrefix());
        if (datasetRows.isEmpty()) {
            throw new IllegalStateException("[MedicalRegistry] dm_shujuji 未返回任何有效数据集");
        }

        List<DatasetDefinition> result = new ArrayList<>();
        try (Connection conn = sourceDataSourceService.openConnection(registryDsId);
             PreparedStatement ps = conn.prepareStatement(sqlFields)) {

            for (RegistryDatasetRow row : datasetRows) {
                ps.setString(1, row.shujujid());
                List<FieldDefinition> fields = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fields.add(mapField(rs, row.shujujdm()));
                    }
                }
                if (fields.isEmpty()) {
                    throw new IllegalStateException("[MedicalRegistry] 数据集 " + row.shujujdm()
                            + " (" + row.shujujid() + ") 无有效字段定义");
                }
                result.add(new DatasetDefinition(
                        row.shujujid(),
                        row.shujujdm(),
                        row.shujujmc(),
                        row.banben(),
                        fields
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("[MedicalRegistry] 查询 dm_shujujzd 失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 返回数据集摘要列表（用于前端列表页展示）。
     * <p>使用全局配置。</p>
     */
    public List<DatasetSummary> listDatasetSummaries() {
        List<DatasetDefinition> datasets = loadDatasets();
        return toSummaries(datasets);
    }

    /**
     * 返回数据集摘要列表（用于前端列表页展示）。
     *
     * @param registryDsId df_ygt 数据源 ID
     * @return 摘要列表
     * @deprecated 使用无参数版本 {@link #listDatasetSummaries()} 代替
     */
    @Deprecated
    public List<DatasetSummary> listDatasetSummaries(Long registryDsId) {
        List<DatasetDefinition> datasets = loadDatasets(registryDsId);
        return toSummaries(datasets);
    }

    // ── 内部方法 ──────────────────────────────────────────────────────

    private List<DatasetSummary> toSummaries(List<DatasetDefinition> datasets) {
        return datasets.stream().map(ds -> {
            int pkCount = (int) ds.fields().stream().filter(FieldDefinition::primaryKey).count();
            boolean hasZuofeibz = ds.fields().stream()
                    .anyMatch(f -> "ZUOFEIBZ".equalsIgnoreCase(f.ziduandm()));
            return new DatasetSummary(
                    ds.shujujid(),
                    ds.shujujdm(),
                    ds.shujujmc(),
                    ds.fields().size(),
                    pkCount,
                    hasZuofeibz
            );
        }).toList();
    }

    private List<RegistryDatasetRow> queryDatasetsLegacy(Long registryDsId, String sql, String datasetPrefix) {
        List<RegistryDatasetRow> rows = new ArrayList<>();
        try (Connection conn = sourceDataSourceService.openConnection(registryDsId);
             PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, datasetPrefix);
            statement.setString(2, datasetPrefix);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RegistryDatasetRow(
                            rs.getString("shujujid"),
                            rs.getString("shujujdm"),
                            rs.getString("shujujmc"),
                            rs.getString("banben"),
                            rs.getInt("zuofeibz")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("[MedicalRegistry] 连接 df_ygt 或查询 dm_shujuji 失败: " + e.getMessage(), e);
        }
        return rows;
    }

    static FieldDefinition mapField(ResultSet rs, String datasetCode) throws SQLException {
        String masterDataItemId = rs.getString("master_shujuxid");
        if (masterDataItemId == null || masterDataItemId.isBlank()) {
            throw new MedicalRegistryDataItemException(
                    datasetCode,
                    rs.getString("ziduanid"),
                    rs.getString("association_shujuxid"));
        }
        Integer zhujianbz = rs.getObject("zhujianbz") != null ? rs.getInt("zhujianbz") : 0;
        Integer feikongbz = rs.getObject("feikongbz") != null ? rs.getInt("feikongbz") : 0;
        return new FieldDefinition(
                rs.getString("ziduanid"),
                rs.getString("ziduandm"),
                rs.getString("ziduanmc"),
                rs.getString("shujulx"),
                rs.getString("biaoshigs"),
                rs.getObject("shunxuhao") != null ? rs.getInt("shunxuhao") : 0,
                zhujianbz == 1,
                feikongbz == 1,
                rs.getString("final_zhiyuid")
        );
    }

    static String buildFieldsSql(String fieldTable, String dataItemTable) {
        return "SELECT f.ziduanid, f.shujujid, f.shujuxid AS association_shujuxid, "
                + "x.shujuxbsf AS ziduandm, x.shujuxmc AS ziduanmc, "
                + "x.shujulx AS shujulx, x.biaoshigs AS biaoshigs, "
                + "f.shunxuhao, f.zhujianbz, f.feikongbz, f.zuofeibz, "
                + "COALESCE(f.zhiyuid, x.zhiyuid) AS final_zhiyuid, "
                + "x.shujuxid AS master_shujuxid "
                + "FROM " + sanitize(fieldTable) + " f "
                + "LEFT JOIN " + sanitize(dataItemTable) + " x "
                + "ON x.shujuxid=f.shujuxid AND x.zuofeibz=0 "
                + "WHERE f.shujujid=? AND f.zuofeibz=0 "
                + "ORDER BY f.shunxuhao, f.ziduanid";
    }

    static String buildDatasetsSql(String datasetTable) {
        return "SELECT shujujid, shujujdm, shujujmc, banben, zuofeibz FROM "
                + sanitize(datasetTable)
                + " WHERE zuofeibz=0 AND LEFT(UPPER(shujujdm), LENGTH(?))=? ORDER BY shujujdm";
    }

    private static String sanitize(String identifier) {
        if (identifier == null || identifier.isBlank()) return "dm_shujuji";
        return identifier.replaceAll("[^a-zA-Z0-9_]", "");
    }
}

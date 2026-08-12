package com.dfygt.dfetl.server.medical.quality;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.engine.doris.DorisTableEnsurer;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** 读取 Doris 实际列并生成预检、DDL校验和 Writer 共用的目标写入合同。 */
@Service
@RequiredArgsConstructor
public class TargetWriteContractService {

    /**
     * 必须与 {@link com.dfygt.dfetl.server.medical.SdvTypeMappingPolicy} 的正式输入集合一致。
     * L/BY 当前按文本写入 Doris；预检不能因为它们不是数值/时间类型而误判为未知合同。
     */
    private static final Set<String> SUPPORTED_STANDARD_TYPES =
            Set.of("S1", "S2", "S3", "N", "D", "DT", "L", "BY");

    private final TargetDataSourceRepository targetRepository;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;

    public TargetWriteContract resolve(
            Long targetDatasourceId,
            String targetTable,
            MedicalDatasetContract standardContract) {
        TargetDataSource target = targetRepository.findById(targetDatasourceId)
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found: " + targetDatasourceId));
        Map<String, TargetWriteContract.PhysicalColumn> physicalColumns = loadColumns(target, targetTable);
        if (physicalColumns.isEmpty()) {
            throw new IllegalStateException("Doris 目标表不存在或没有字段: "
                    + target.getDbName() + "." + targetTable);
        }
        return validate(target.getDbName(), targetTable, standardContract, physicalColumns);
    }

    public TargetWriteContract validate(
            String database,
            String table,
            MedicalDatasetContract standardContract,
            Map<String, TargetWriteContract.PhysicalColumn> physicalColumns) {
        if (standardContract == null || standardContract.fields() == null) {
            throw new IllegalArgumentException("标准数据集合同不能为空");
        }
        Map<String, TargetWriteContract.PhysicalColumn> normalized = normalize(physicalColumns);
        List<String> blockers = new ArrayList<>();
        for (MedicalFieldContract field : standardContract.fields()) {
            String standardType = field.sdvType() == null
                    ? ""
                    : field.sdvType().trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_STANDARD_TYPES.contains(standardType)) {
                blockers.add(field.code() + ": 不支持的标准类型 " + standardType);
                continue;
            }
            TargetWriteContract.PhysicalColumn physical = normalized.get(normalize(field.dorisColumn()));
            if (physical == null) {
                blockers.add(field.dorisColumn() + ": 缺少目标列");
                continue;
            }
            if (physical.dataType() == null || physical.dataType().isBlank()) {
                blockers.add(field.dorisColumn() + ": 目标列类型元数据缺失");
                continue;
            }
            DorisTableEnsurer.MedicalCompatibility compatibility =
                    DorisTableEnsurer.checkMedicalWritableCompatibility(
                            field,
                            physical.dataType(),
                            physical.characterCapacity(),
                            physical.numericPrecision(),
                            physical.numericScale(),
                            physical.datetimePrecision(),
                            physical.nullable());
            if (compatibility.level() == DorisTableEnsurer.MedicalCompatibilityLevel.FAIL) {
                blockers.add(field.dorisColumn() + ": 期望 " + field.dorisType()
                        + "，实际 " + displayType(physical)
                        + "，原因 " + compatibility.reason());
            }
        }
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("Doris 目标写入合同不兼容: " + String.join("；", blockers));
        }
        return new TargetWriteContract(database, table, normalized);
    }

    private Map<String, TargetWriteContract.PhysicalColumn> loadColumns(
            TargetDataSource target,
            String table) {
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + target.getFePort() + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        String sql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, "
                + "numeric_scale, datetime_precision, is_nullable FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name=?";
        Map<String, TargetWriteContract.PhysicalColumn> columns = new LinkedHashMap<>();
        try (Connection connection = connectionPoolManager.getConnection(
                url, target.getUsername(), aesUtil.decrypt(target.getPasswordEnc()));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.getDbName());
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("column_name");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    TargetWriteContract.PhysicalColumn column = new TargetWriteContract.PhysicalColumn(
                            name,
                            rs.getString("data_type"),
                            nullableInteger(rs.getObject("character_maximum_length")),
                            nullableInteger(rs.getObject("numeric_precision")),
                            nullableInteger(rs.getObject("numeric_scale")),
                            nullableInteger(rs.getObject("datetime_precision")),
                            nullableBoolean(rs.getString("is_nullable")));
                    columns.put(normalize(name), column);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 Doris 目标写入合同失败: " + e.getMessage(), e);
        }
        return columns;
    }

    private static Map<String, TargetWriteContract.PhysicalColumn> normalize(
            Map<String, TargetWriteContract.PhysicalColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            return Map.of();
        }
        Map<String, TargetWriteContract.PhysicalColumn> normalized = new LinkedHashMap<>();
        columns.forEach((key, value) -> {
            String name = value != null && value.name() != null ? value.name() : key;
            if (name != null && !name.isBlank() && value != null) {
                normalized.put(normalize(name), value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String displayType(TargetWriteContract.PhysicalColumn physical) {
        if (physical.dataType() == null) {
            return "UNKNOWN";
        }
        String base = physical.dataType().trim().toUpperCase(Locale.ROOT);
        if (("VARCHAR".equals(base) || "CHAR".equals(base))
                && physical.characterCapacity() != null) {
            return base + "(" + physical.characterCapacity() + ")";
        }
        if (("DECIMAL".equals(base) || "NUMERIC".equals(base))
                && physical.numericPrecision() != null && physical.numericScale() != null) {
            return base + "(" + physical.numericPrecision() + "," + physical.numericScale() + ")";
        }
        if ("DATETIME".equals(base) && physical.datetimePrecision() != null) {
            return base + "(" + physical.datetimePrecision() + ")";
        }
        return base;
    }

    private static Integer nullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
    }

    private static Boolean nullableBoolean(String value) {
        if (value == null) {
            return null;
        }
        return "YES".equalsIgnoreCase(value) || "TRUE".equalsIgnoreCase(value) || "1".equals(value);
    }
}

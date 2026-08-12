package com.dfygt.dfetl.server.medical.quality;

import java.util.Locale;
import java.util.Map;

/** 标准字段合同与 Doris 实际物理列合并后的只读写入合同。 */
public record TargetWriteContract(
        String database,
        String table,
        Map<String, PhysicalColumn> columns) {

    public TargetWriteContract {
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public PhysicalColumn column(String name) {
        if (name == null) {
            return null;
        }
        return columns.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public record PhysicalColumn(
            String name,
            String dataType,
            Integer characterCapacity,
            Integer numericPrecision,
            Integer numericScale,
            Integer datetimePrecision,
            Boolean nullable) {
    }
}

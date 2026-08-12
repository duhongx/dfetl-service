package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回平台固定注入的 ETL 系统字段。
 *
 * <p>对应配置 key（与 web/SettingsPage 一致）：
 * <ul>
 *   <li>{@code etl.system_field.batch_id}</li>
 *   <li>{@code etl.system_field.job_id}</li>
 *   <li>{@code etl.system_field.job_version}</li>
 *   <li>{@code etl.system_field.sync_time}</li>
 *   <li>{@code etl.system_field.source}</li>
 *   <li>{@code etl.system_field.window_start}</li>
 *   <li>{@code etl.system_field.window_end}</li>
 * </ul>
 *
 * <p>前端已不再展示这些开关；历史 {@code system_setting} 中的 false 配置不再影响运行时。
 */
@Service
@RequiredArgsConstructor
public class EtlSystemFieldsService {

    /** 字段定义：列名 → Doris 类型。LinkedHashMap 保证顺序稳定。 */
    public static final Map<String, String> FIELD_TYPES;
    static {
        FIELD_TYPES = new LinkedHashMap<>();
        FIELD_TYPES.put("_etl_batch_id",      "BIGINT");
        FIELD_TYPES.put("_etl_job_id",        "BIGINT");
        FIELD_TYPES.put("_etl_job_version",   "VARCHAR(20)");
        FIELD_TYPES.put("_etl_sync_time",     "DATETIME");
        FIELD_TYPES.put("_etl_source_system", "VARCHAR(100)");
        FIELD_TYPES.put("_etl_window_start",  "VARCHAR(50)");
        FIELD_TYPES.put("_etl_window_end",    "VARCHAR(50)");
    }

    private final SystemSettingRepository repo;

    /**
     * 返回固定启用的系统字段名 → Doris 类型，按固定顺序。
     * 历史 {@code etl.system_field.*} 配置只保留兼容读取，不再参与开关判断。
     */
    public Map<String, String> enabledFields() {
        return new LinkedHashMap<>(FIELD_TYPES);
    }

    /** 至少启用了一个系统字段 */
    public boolean anyEnabled() {
        return !enabledFields().isEmpty();
    }
}

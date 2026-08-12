package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.SystemSetting;
import com.dfygt.dfetl.server.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * spec 022：全局校验策略读写。
 *
 * <p>底层复用 {@code system_setting} KV 表（spec 021 已建），key 前缀统一为 {@code validation_}，
 * 与 SettingsPage / 数据库初始化脚本保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSettingsService {

    static final String K_AUTO_ENABLED      = "validation_auto_enabled";
    static final String K_TRIGGER           = "validation_trigger";
    static final String K_METHOD            = "validation_method";
    static final String K_ROW_TOLERANCE     = "validation_row_tolerance";
    static final String K_FAIL_BLOCK        = "validation_fail_block";
    static final String K_REVALIDATE        = "validation_revalidate";
    static final String K_REVALIDATE_DELAY  = "validation_revalidate_delay";
    /** spec 047：强制所有任务必须有校验配置 */
    static final String K_ENFORCE_VALIDATION = "validation_enforce";
    /** 校验回看窗口（小时） */
    static final String K_LOOKBACK_HOURS     = "validation_lookback_hours";
    /** 允许视图源开启 autoRepair */
    static final String K_VIEW_AUTO_REPAIR   = "validation_view_auto_repair";

    private final SystemSettingRepository repo;

    public ValidationPolicy getValidationPolicy() {
        Map<String, String> all = loadAll();
        ValidationPolicy d = ValidationPolicy.defaults();
        return new ValidationPolicy(
                parseBool(all.get(K_AUTO_ENABLED), d.autoEnabled()),
                orDefault(all.get(K_TRIGGER), d.trigger()),
                normalizeValidationMethodForRead(all.get(K_METHOD), d.method()),
                parseDouble(all.get(K_ROW_TOLERANCE), d.rowTolerance()),
                parseBool(all.get(K_FAIL_BLOCK), d.failBlock()),
                parseBool(all.get(K_REVALIDATE), d.revalidate()),
                parseInt(all.get(K_REVALIDATE_DELAY), d.revalidateDelay()),
                parseInt(all.get(K_LOOKBACK_HOURS), d.lookbackHours())
        );
    }

    @Transactional
    public void saveValidationPolicy(ValidationPolicy p) {
        if (p == null) throw new IllegalArgumentException("policy 不能为空");
        if (p.trigger() == null || (!"after_sync".equals(p.trigger()) && !"manual_only".equals(p.trigger()))) {
            throw new IllegalArgumentException("trigger 仅支持 after_sync / manual_only");
        }
        String method = normalizeValidationMethod(p.method());
        if (p.rowTolerance() < 0 || p.rowTolerance() > 100) {
            throw new IllegalArgumentException("rowTolerance 必须 0~100");
        }
        if (p.revalidateDelay() < 0) {
            throw new IllegalArgumentException("revalidateDelay 必须 >= 0");
        }
        // lookback 输入范围校验：0=只验本次窗口，>0=向前扩展 N 小时。
        if (p.lookbackHours() < 0 || p.lookbackHours() > 168) {
            throw new IllegalArgumentException("lookbackHours 必须在 0~168 小时范围内；0 表示只验本次增量窗口");
        }
        // 写入审计日志（操作人/IP 由 SecurityContext 拿，此处只记录前后值；详细审计由调用层补足）
        ValidationPolicy oldPolicy = getValidationPolicy();
        if (oldPolicy.lookbackHours() != p.lookbackHours()) {
            log.info("validation lookback default changed: oldHours={} newHours={}",
                    oldPolicy.lookbackHours(), p.lookbackHours());
        }
        upsert(K_AUTO_ENABLED,     String.valueOf(p.autoEnabled()));
        upsert(K_TRIGGER,          p.trigger());
        upsert(K_METHOD,           method);
        upsert(K_ROW_TOLERANCE,    String.valueOf(p.rowTolerance()));
        upsert(K_FAIL_BLOCK,       String.valueOf(p.failBlock()));
        upsert(K_REVALIDATE,       String.valueOf(p.revalidate()));
        upsert(K_REVALIDATE_DELAY, String.valueOf(p.revalidateDelay()));
        upsert(K_LOOKBACK_HOURS,   String.valueOf(p.lookbackHours()));
    }

    private void upsert(String key, String value) {
        SystemSetting s = repo.findById(key).orElseGet(() -> {
            SystemSetting ns = new SystemSetting();
            ns.setSettingKey(key);
            return ns;
        });
        s.setSettingValue(value);
        repo.save(s);
    }

    private Map<String, String> loadAll() {
        Map<String, String> m = new HashMap<>();
        repo.findAll().forEach(s -> m.put(s.getSettingKey(),
                s.getSettingValue() == null ? "" : s.getSettingValue()));
        return m;
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null || v.isBlank()) return def;
        return "true".equalsIgnoreCase(v.trim());
    }

    private static int parseInt(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String v, double def) {
        if (v == null || v.isBlank()) return def;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static String normalizeValidationMethod(String method) {
        String normalized = method == null || method.isBlank()
                ? "row_count"
                : method.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "row_count", "checksum", "row_count_checksum", "all" -> normalized;
            default -> throw new IllegalArgumentException(
                    "validation method 仅支持 ROW_COUNT / CHECKSUM / ROW_COUNT_CHECKSUM / ALL，当前值: " + method);
        };
    }

    private String normalizeValidationMethodForRead(String method, String def) {
        String fallback = def == null || def.isBlank() ? "row_count" : def.trim().toLowerCase(Locale.ROOT);
        String normalized = method == null || method.isBlank() ? fallback : method.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "row_count", "checksum", "row_count_checksum", "all" -> normalized;
            default -> {
                log.warn("legacy validation_method is unsupported, fallback to {}: value={}", fallback, method);
                yield fallback;
            }
        };
    }

    /** spec 047：是否强制所有任务都必须配置校验。默认 false（向后兼容）。 */
    public boolean isEnforceValidation() {
        return repo.findById(K_ENFORCE_VALIDATION)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue() == null ? "" : s.getSettingValue().trim()))
                .orElse(false);
    }

    @Transactional
    public void setEnforceValidation(boolean enabled) {
        upsert(K_ENFORCE_VALIDATION, String.valueOf(enabled));
    }

    /** 是否允许视图源开启 autoRepair。默认 false。 */
    public boolean isViewAutoRepairAllowed() {
        return repo.findById(K_VIEW_AUTO_REPAIR)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue() == null ? "" : s.getSettingValue().trim()))
                .orElse(false);
    }

    @Transactional
    public void setViewAutoRepairAllowed(boolean allowed) {
        upsert(K_VIEW_AUTO_REPAIR, String.valueOf(allowed));
    }
}

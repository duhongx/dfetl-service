package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.SystemSetting;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.medical.MedicalRegistryConfig;
import com.dfygt.dfetl.server.repository.SystemSettingRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.service.EffectiveValidationMethodResolver;
import com.dfygt.dfetl.server.service.GlobalSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SystemSettingController {

    private final SystemSettingRepository settingRepository;
    private final GlobalSettingsService globalSettingsService;
    private final TaskValidationConfigRepository validationConfigRepo;
    private final SyncTaskRepository syncTaskRepository;
    private final EffectiveValidationMethodResolver methodResolver;
    private final MedicalRegistryConfig medicalRegistryConfig;
    private final AesUtil aesUtil;

    /** 获取全部配置，返回 Map<key, value>。密码字段脱敏。 */
    @GetMapping
    public ApiResponse<Map<String, String>> getAll() {
        Map<String, String> result = settingRepository.findAll()
                .stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey, s -> {
                    String v = s.getSettingValue();
                    return v != null ? v : "";
                }));
        // 密码字段脱敏：前端只看到 ****
        if (result.containsKey("medical.registry.doris.password")) {
            String pwd = result.get("medical.registry.doris.password");
            if (pwd != null && !pwd.isBlank()) {
                result.put("medical.registry.doris.password", "****");
            }
        }
        return ApiResponse.ok(result);
    }

    /** 批量更新/新增配置，仅写入请求中包含的 key。密码字段自动加密。 */
    @PutMapping
    public ApiResponse<Void> updateBatch(@RequestBody Map<String, String> updates) {
        updates.forEach((key, value) -> {
            // 密码字段：**** 表示未修改，跳过；否则加密存储
            if ("medical.registry.doris.password".equals(key)) {
                if ("****".equals(value) || value == null || value.isBlank()) {
                    return; // 未修改，跳过
                }
                value = aesUtil.encrypt(value);
            }
            SystemSetting setting = settingRepository.findById(key)
                    .orElseGet(() -> {
                        SystemSetting s = new SystemSetting();
                        s.setSettingKey(key);
                        return s;
                    });
            setting.setSettingValue(value);
            settingRepository.save(setting);
        });
        return ApiResponse.ok();
    }

    /** spec 022：获取全局校验策略 */
    @GetMapping("/validation")
    public ApiResponse<ValidationPolicy> getValidation() {
        return ApiResponse.ok(globalSettingsService.getValidationPolicy());
    }

    /** spec 022：保存全局校验策略 */
    @PostMapping("/validation")
    public ApiResponse<Void> saveValidation(@RequestBody ValidationPolicy policy) {
        globalSettingsService.saveValidationPolicy(policy);
        return ApiResponse.ok();
    }

    /** spec 047：获取「强制所有任务必须配置校验」开关 */
    @GetMapping("/validation/enforce")
    public ApiResponse<Map<String, Boolean>> getEnforceValidation() {
        return ApiResponse.ok(Map.of("enabled", globalSettingsService.isEnforceValidation()));
    }

    /** spec 047：保存「强制所有任务必须配置校验」开关 */
    @PostMapping("/validation/enforce")
    public ApiResponse<Void> setEnforceValidation(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            return ApiResponse.error(400, "缺少字段 enabled");
        }
        globalSettingsService.setEnforceValidation(enabled);
        return ApiResponse.ok();
    }

    /** 获取「允许视图源自动修复」开关 */
    @GetMapping("/validation/view-auto-repair")
    public ApiResponse<Map<String, Boolean>> getViewAutoRepair() {
        return ApiResponse.ok(Map.of("allowed", globalSettingsService.isViewAutoRepairAllowed()));
    }

    /** 保存「允许视图源自动修复」开关 */
    @PostMapping("/validation/view-auto-repair")
    public ApiResponse<Void> setViewAutoRepair(@RequestBody Map<String, Boolean> body) {
        Boolean allowed = body == null ? null : body.get("allowed");
        if (allowed == null) {
            return ApiResponse.error(400, "缺少字段 allowed");
        }
        globalSettingsService.setViewAutoRepairAllowed(allowed);
        return ApiResponse.ok();
    }

    /**
     * spec 047 C-4 + spec 063 Task 5：批量获取所有任务级校验配置摘要。
     * 返回 Map&lt;taskId, {enabled, method, checksumScope}&gt;。
     * 前端「校验状态」列可据此显示校验强度与范围。
     */
    @GetMapping("/validation/summary")
    public ApiResponse<Map<Long, Map<String, Object>>> getValidationSummary() {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        List<TaskValidationConfig> configs = validationConfigRepo.findAll();
        Map<Long, TaskValidationConfig> configByTaskId = configs.stream()
                .filter(config -> config.getTaskId() != null)
                .collect(Collectors.toMap(
                        TaskValidationConfig::getTaskId, config -> config, (first, ignored) -> first));
        for (SyncTask task : syncTaskRepository.findAll()) {
            if (task.getId() == null) continue;
            TaskValidationConfig c = configByTaskId.get(task.getId());
            Map<String, Object> summary = new HashMap<>();
            summary.put("enabled", c != null && Boolean.TRUE.equals(c.getEnabled()));
            summary.put("taskConfigEnabled", methodResolver.isTaskConfigActive(c));
            summary.put("effectiveEnabled", methodResolver.resolveEffectiveEnabled(c));
            summary.put("method", methodResolver.resolveTriggeredMethod(task, c));
            if (c != null && c.getMethod() != null && !c.getMethod().isBlank()) {
                summary.put("rawMethod", c.getMethod());
            }
            String methodSource = methodResolver.resolveMethodSource(c);
            summary.put("inheritedMethod", "GLOBAL".equals(methodSource)
                    || (methodSource == null
                    && (c == null || c.getMethod() == null || c.getMethod().isBlank())));
            if (c != null && c.getAutoTrigger() != null) {
                summary.put("autoTrigger", c.getAutoTrigger());
            }
            summary.put("effectiveAutoTrigger", methodResolver.resolveEffectiveAutoTrigger(c));
            summary.put("methodSource", methodResolver.resolveMethodSource(c));
            summary.put("effectiveBlockOnFail", methodResolver.resolveEffectiveBlockOnFail(c));
            summary.put("blockOnFailSource", methodResolver.resolveBlockOnFailSource(c));
            summary.put("autoTriggerSource", methodResolver.resolveAutoTriggerSource(c));
            summary.put("checksumScope",
                    c != null && c.getChecksumScope() != null ? c.getChecksumScope() : "FULL");
            result.put(task.getId(), summary);
        }
        return ApiResponse.ok(result);
    }

    /**
     * 医共体规范库连接测试。
     * 接收前端传来的连接信息，测试连接并返回结果（包括数据集数量）。
     */
    @PostMapping("/medical-registry/test")
    public ApiResponse<Map<String, Object>> testMedicalRegistry(@RequestBody Map<String, String> config) {
        String host = config.getOrDefault("host", "");
        String portStr = config.getOrDefault("port", "9030");
        String username = config.getOrDefault("username", "");
        String password = config.getOrDefault("password", "");
        String database = config.getOrDefault("database", "df_ygt");
        String datasetTable = config.getOrDefault("datasetTable", "dm_shujuji");
        String datasetPrefix = MedicalRegistryConfig.STANDARD_DATASET_PREFIX;

        if (host.isBlank() || username.isBlank()) {
            return ApiResponse.ok(Map.of(
                    "success", false,
                    "message", "主机地址和用户名不能为空",
                    "datasetCount", 0
            ));
        }

        // 如果密码是 ****，说明前端未修改，从数据库读取已存储的密码
        if ("****".equals(password)) {
            password = medicalRegistryConfig.getPassword();
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return ApiResponse.ok(Map.of(
                    "success", false,
                    "message", "端口号格式错误",
                    "datasetCount", 0
            ));
        }

        Map<String, Object> result = medicalRegistryConfig.testConnection(
                host, port, database, username, password, datasetTable, datasetPrefix);
        return ApiResponse.ok(result);
    }
}

package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.DfetlMessagePolicyDto;
import com.dfygt.dfetl.server.dto.DfetlSyncPolicyDto;
import com.dfygt.dfetl.server.dto.DfetlValidationPolicyDto;
import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.DfetlMessagePolicy;
import com.dfygt.dfetl.server.entity.DfetlSyncPolicy;
import com.dfygt.dfetl.server.entity.DfetlValidationPolicy;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.medical.FieldDefinition;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import com.dfygt.dfetl.server.repository.DfetlMessagePolicyRepository;
import com.dfygt.dfetl.server.repository.DfetlSyncPolicyRepository;
import com.dfygt.dfetl.server.repository.DfetlValidationPolicyRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** 数据集级同步、校验和消息策略的唯一读写入口。 */
@Service
@RequiredArgsConstructor
public class DfetlPolicyService {
    private static final Map<String, String> MESSAGE_ROUTES = Map.of(
            "ODS_YL_HUANZHEJBXX", "YL_HUANZHEJBXX",
            "ODS_YL_KESHIXX", "YL_KESHIXX",
            "ODS_YL_ZHIGONGXX", "YL_ZHIGONGXX");

    private final DfetlDatasetRepository datasetRepository;
    private final DfetlFieldRepository fieldRepository;
    private final DfetlSyncPolicyRepository syncRepository;
    private final DfetlValidationPolicyRepository validationRepository;
    private final DfetlMessagePolicyRepository messageRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final GlobalSettingsService globalSettingsService;

    @Transactional
    public void initializeMissing(DfetlDataset dataset, List<FieldDefinition> definitions) {
        Long datasetId = requiredId(dataset == null ? null : dataset.getId(), "datasetId");
        List<FieldDefinition> fields = definitions == null ? List.of() : definitions;
        if (syncRepository.findByDatasetId(datasetId).isEmpty()) {
            DfetlSyncPolicy policy = new DfetlSyncPolicy();
            policy.setDatasetId(datasetId);
            boolean hasPrimaryKey = fields.stream().filter(Objects::nonNull).anyMatch(FieldDefinition::primaryKey);
            String incrementalField = hasPrimaryKey ? findIncrementalField(fields) : null;
            policy.setWriteMode(hasPrimaryKey ? "UPSERT" : "TRUNCATE");
            policy.setSyncTemplate(incrementalField == null ? "FULL_ONLY" : "FULL_THEN_INCREMENT");
            policy.setIncrementalField(incrementalField);
            syncRepository.save(policy);
        }
        if (validationRepository.findByDatasetId(datasetId).isEmpty()) {
            ValidationPolicy defaults = globalSettingsService.getValidationPolicy();
            if (defaults == null) defaults = ValidationPolicy.defaults();
            DfetlValidationPolicy policy = new DfetlValidationPolicy();
            policy.setDatasetId(datasetId);
            policy.setInheritGlobal(true);
            applyValidationDefaults(policy, defaults);
            validationRepository.save(policy);
        }
        if (messageRepository.findByDatasetId(datasetId).isEmpty()) {
            DfetlMessagePolicy policy = new DfetlMessagePolicy();
            policy.setDatasetId(datasetId);
            String route = MESSAGE_ROUTES.get(upper(dataset.getDatasetCode()));
            policy.setEnabled(route != null);
            policy.setRoutingKey(route);
            policy.setTopic(route);
            policy.setKeyTemplate(route == null ? null : messageKeyTemplate(fields));
            messageRepository.save(policy);
        }
    }

    @Transactional(readOnly = true)
    public DfetlSyncPolicyDto getSyncPolicy(Long datasetId) {
        activeDataset(datasetId);
        return toDto(requireSync(datasetId));
    }

    @Transactional
    public DfetlSyncPolicyDto updateSyncPolicy(Long datasetId, DfetlSyncPolicyDto request) {
        activeDataset(datasetId);
        if (request == null) throw new IllegalArgumentException("同步策略不能为空");
        DfetlSyncPolicy policy = requireSync(datasetId);
        List<DfetlField> fields = activeFields(datasetId);
        String writeMode = allowed(request.getWriteMode(), "writeMode", Set.of("UPSERT", "TRUNCATE"), policy.getWriteMode());
        if ("UPSERT".equals(writeMode) && fields.stream().noneMatch(field -> Boolean.TRUE.equals(field.getPrimaryKey()))) {
            throw new IllegalArgumentException("UPSERT 数据集必须存在有效标准主键");
        }
        String template = allowed(request.getSyncTemplate(), "syncTemplate",
                Set.of("FULL_ONLY", "FULL_THEN_INCREMENT"), policy.getSyncTemplate());
        if ("TRUNCATE".equals(writeMode) && "FULL_THEN_INCREMENT".equals(template)) {
            throw new IllegalArgumentException("TRUNCATE 只支持 FULL_ONLY，不允许增量模板");
        }
        String incrementalField = upperToNull(request.getIncrementalField());
        if ("FULL_THEN_INCREMENT".equals(template)) {
            String requestedIncrementalField = incrementalField;
            if (requestedIncrementalField == null || fields.stream().noneMatch(field -> requestedIncrementalField.equalsIgnoreCase(field.getFieldCode())
                    && Set.of("D", "DT").contains(upper(field.getStandardType())))) {
                throw new IllegalArgumentException("增量字段必须是有效的日期或时间标准字段");
            }
        } else {
            incrementalField = null;
        }
        policy.setWriteMode(writeMode);
        policy.setSyncTemplate(template);
        policy.setIncrementalField(incrementalField);
        policy.setIncrementMode(allowed(request.getIncrementMode(), "incrementMode", Set.of("TIME_FIELD"), "TIME_FIELD"));
        policy.setUpperBoundStrategy(allowed(request.getUpperBoundStrategy(), "upperBoundStrategy",
                Set.of("CURRENT_TIME", "DELAY_MINUTES"), "CURRENT_TIME"));
        policy.setUpperBoundDelayMinutes(nonNegative(request.getUpperBoundDelayMinutes(), 5, "upperBoundDelayMinutes"));
        policy.setLookbackSeconds(nonNegative(request.getLookbackSeconds(), 0, "lookbackSeconds"));
        policy.setReaderParallelism(range(request.getReaderParallelism(), 4, 1, 64, "readerParallelism"));
        policy.setFetchSize(positiveOrNull(request.getFetchSize(), "fetchSize"));
        policy.setRateLimit(nonNegative(request.getRateLimit(), 0, "rateLimit"));
        boolean scheduleEnabled = request.getScheduleEnabled() == null || request.getScheduleEnabled();
        policy.setScheduleEnabled(scheduleEnabled);
        String scheduleMode = scheduleEnabled
                ? allowed(request.getScheduleMode(), "scheduleMode", Set.of("EVERY_N_HOURS", "ADVANCED"), "EVERY_N_HOURS")
                : "MANUAL";
        policy.setScheduleMode(scheduleMode);
        policy.setScheduleIntervalHours("EVERY_N_HOURS".equals(scheduleMode)
                ? range(request.getScheduleIntervalHours(), 4, 1, 8760, "scheduleIntervalHours") : null);
        String cron = trimToNull(request.getScheduleCron());
        if ("ADVANCED".equals(scheduleMode) && cron == null) throw new IllegalArgumentException("高级调度的 scheduleCron 不能为空");
        policy.setScheduleCron("ADVANCED".equals(scheduleMode) ? cron : null);
        policy.setScheduleTimezone(defaultText(request.getScheduleTimezone(), "Asia/Shanghai"));
        policy.setPolicyRevision(nextRevision(policy.getPolicyRevision()));
        DfetlSyncPolicy saved = syncRepository.save(policy);
        invalidateRoutes(datasetId);
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public DfetlValidationPolicyDto getValidationPolicy(Long datasetId) {
        activeDataset(datasetId);
        return toDto(requireValidation(datasetId));
    }

    @Transactional
    public DfetlValidationPolicyDto updateValidationPolicy(Long datasetId, DfetlValidationPolicyDto request) {
        activeDataset(datasetId);
        if (request == null) throw new IllegalArgumentException("校验策略不能为空");
        DfetlValidationPolicy policy = requireValidation(datasetId);
        policy.setInheritGlobal(Boolean.TRUE.equals(request.getInheritGlobal()));
        policy.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        policy.setTriggerMode(allowed(request.getTriggerMode(), "triggerMode", Set.of("AFTER_SYNC", "MANUAL_ONLY"), "AFTER_SYNC"));
        policy.setValidationMethod(allowed(request.getValidationMethod(), "validationMethod",
                Set.of("ROW_COUNT", "CHECKSUM", "ROW_COUNT_CHECKSUM"), "ROW_COUNT"));
        BigDecimal tolerance = request.getRowTolerance() == null ? BigDecimal.ZERO : request.getRowTolerance();
        if (tolerance.signum() < 0 || tolerance.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("rowTolerance 必须在 0~100 之间");
        }
        policy.setRowTolerance(tolerance);
        policy.setFailBlock(Boolean.TRUE.equals(request.getFailBlock()));
        policy.setRevalidateEnabled(request.getRevalidateEnabled() == null || request.getRevalidateEnabled());
        policy.setRevalidateDelay(nonNegative(request.getRevalidateDelay(), 30, "revalidateDelay"));
        policy.setLookbackHours(range(request.getLookbackHours(), 2, 0, 168, "lookbackHours"));
        policy.setPolicyRevision(nextRevision(policy.getPolicyRevision()));
        return toDto(validationRepository.save(policy));
    }

    @Transactional(readOnly = true)
    public DfetlMessagePolicyDto getMessagePolicy(Long datasetId) {
        activeDataset(datasetId);
        return toDto(requireMessage(datasetId));
    }

    @Transactional
    public DfetlMessagePolicyDto updateMessagePolicy(Long datasetId, DfetlMessagePolicyDto request) {
        DfetlDataset dataset = activeDataset(datasetId);
        if (request == null) throw new IllegalArgumentException("消息策略不能为空");
        DfetlMessagePolicy policy = requireMessage(datasetId);
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        if (enabled && !isMessageDataset(dataset.getDatasetCode())) {
            throw new IllegalArgumentException("RabbitMQ 仅支持 ODS_YL_HUANZHEJBXX / ODS_YL_KESHIXX / ODS_YL_ZHIGONGXX");
        }
        String routingKey = upperToNull(request.getRoutingKey());
        if (enabled && routingKey == null) throw new IllegalArgumentException("启用消息发布时 routingKey 不能为空");
        policy.setEnabled(enabled);
        policy.setTransport(allowed(request.getTransport(), "transport", Set.of("RABBITMQ"), "RABBITMQ"));
        policy.setFullSyncMode(allowed(request.getFullSyncMode(), "fullSyncMode",
                Set.of("ALL", "SKIP", "NOTIFY_ONLY"), "ALL"));
        policy.setRateLimit(nonNegative(request.getRateLimit(), 1000, "rateLimit"));
        policy.setRoutingKey(routingKey);
        policy.setTopic(defaultText(request.getTopic(), routingKey));
        policy.setKeyTemplate(trimToNull(request.getKeyTemplate()));
        policy.setPageSize(range(request.getPageSize(), 1000, 1, Integer.MAX_VALUE, "pageSize"));
        policy.setTenantId(defaultText(request.getTenantId(), "0"));
        policy.setSourceSystem(defaultText(request.getSourceSystem(), "HIS"));
        policy.setPolicyRevision(nextRevision(policy.getPolicyRevision()));
        return toDto(messageRepository.save(policy));
    }

    static boolean isMessageDataset(String datasetCode) {
        return MESSAGE_ROUTES.containsKey(upper(datasetCode));
    }

    public DfetlSyncPolicy requireSync(Long datasetId) {
        return syncRepository.findByDatasetId(datasetId)
                .orElseThrow(() -> new IllegalStateException("SYNC_POLICY_MISSING: datasetId=" + datasetId));
    }

    public DfetlValidationPolicy requireValidation(Long datasetId) {
        return validationRepository.findByDatasetId(datasetId)
                .orElseThrow(() -> new IllegalStateException("VALIDATION_POLICY_MISSING: datasetId=" + datasetId));
    }

    public DfetlMessagePolicy requireMessage(Long datasetId) {
        return messageRepository.findByDatasetId(datasetId)
                .orElseThrow(() -> new IllegalStateException("MESSAGE_POLICY_MISSING: datasetId=" + datasetId));
    }

    private DfetlDataset activeDataset(Long datasetId) {
        DfetlDataset dataset = datasetRepository.findById(requiredId(datasetId, "datasetId"))
                .orElseThrow(() -> new NoSuchElementException("标准数据集不存在: " + datasetId));
        if (!"ACTIVE".equalsIgnoreCase(dataset.getDatasetStatus())) throw new IllegalStateException("DATASET_VOID: " + datasetId);
        return dataset;
    }

    private List<DfetlField> activeFields(Long datasetId) {
        return fieldRepository.findByDatasetIdOrderByFieldOrderAscIdAsc(datasetId).stream()
                .filter(field -> "ACTIVE".equalsIgnoreCase(field.getFieldStatus())).toList();
    }

    private void invalidateRoutes(Long datasetId) {
        for (InstitutionDatasetRoute route : routeRepository.findByDatasetIdOrderByIdAsc(datasetId)) {
            route.setEnabled(false);
            route.setValidationStatus("PENDING");
            route.setValidationSummary("同步策略已变化，请重新预检");
            route.setValidationDetailsJson(null);
            route.setLastValidatedAt(null);
            route.setValidatedContractHash(null);
            route.setValidatedRouteRevision(null);
            routeRepository.save(route);
        }
    }

    private static void applyValidationDefaults(DfetlValidationPolicy target, ValidationPolicy source) {
        target.setEnabled(source.autoEnabled());
        target.setTriggerMode(upper(source.trigger()));
        target.setValidationMethod(upper(source.method()));
        target.setRowTolerance(BigDecimal.valueOf(source.rowTolerance()));
        target.setFailBlock(source.failBlock());
        target.setRevalidateEnabled(source.revalidate());
        target.setRevalidateDelay(source.revalidateDelay());
        target.setLookbackHours(source.lookbackHours());
    }

    private static String findIncrementalField(List<FieldDefinition> fields) {
        for (String preferred : List.of("XIUGAISJ", "GENGXINSJ")) {
            for (FieldDefinition field : fields) {
                if (field != null && preferred.equalsIgnoreCase(field.ziduandm())
                        && Set.of("D", "DT").contains(upper(field.sdvType()))) return preferred;
            }
        }
        return null;
    }

    private static String messageKeyTemplate(List<FieldDefinition> fields) {
        String value = fields.stream().filter(Objects::nonNull).filter(FieldDefinition::primaryKey)
                .sorted(java.util.Comparator.comparingInt(field -> field.shunxuhao() == null ? 0 : field.shunxuhao()))
                .map(field -> "{" + field.ziduandm().trim().toLowerCase(Locale.ROOT) + "}")
                .collect(java.util.stream.Collectors.joining(":"));
        return value.isBlank() ? null : value;
    }

    public static DfetlSyncPolicyDto toDto(DfetlSyncPolicy value) {
        DfetlSyncPolicyDto dto = new DfetlSyncPolicyDto();
        dto.setId(value.getId()); dto.setDatasetId(value.getDatasetId()); dto.setWriteMode(value.getWriteMode());
        dto.setSyncTemplate(value.getSyncTemplate()); dto.setIncrementalField(value.getIncrementalField());
        dto.setIncrementMode(value.getIncrementMode()); dto.setUpperBoundStrategy(value.getUpperBoundStrategy());
        dto.setUpperBoundDelayMinutes(value.getUpperBoundDelayMinutes()); dto.setLookbackSeconds(value.getLookbackSeconds());
        dto.setReaderParallelism(value.getReaderParallelism()); dto.setFetchSize(value.getFetchSize()); dto.setRateLimit(value.getRateLimit());
        dto.setScheduleEnabled(value.getScheduleEnabled()); dto.setScheduleMode(value.getScheduleMode());
        dto.setScheduleIntervalHours(value.getScheduleIntervalHours()); dto.setScheduleCron(value.getScheduleCron());
        dto.setScheduleTimezone(value.getScheduleTimezone()); dto.setPolicyRevision(value.getPolicyRevision());
        dto.setRowVersion(value.getRowVersion()); dto.setCreatedAt(value.getCreatedAt()); dto.setUpdatedAt(value.getUpdatedAt());
        return dto;
    }

    public static DfetlValidationPolicyDto toDto(DfetlValidationPolicy value) {
        DfetlValidationPolicyDto dto = new DfetlValidationPolicyDto();
        dto.setId(value.getId()); dto.setDatasetId(value.getDatasetId()); dto.setInheritGlobal(value.getInheritGlobal());
        dto.setEnabled(value.getEnabled()); dto.setTriggerMode(value.getTriggerMode()); dto.setValidationMethod(value.getValidationMethod());
        dto.setRowTolerance(value.getRowTolerance()); dto.setFailBlock(value.getFailBlock()); dto.setRevalidateEnabled(value.getRevalidateEnabled());
        dto.setRevalidateDelay(value.getRevalidateDelay()); dto.setLookbackHours(value.getLookbackHours());
        dto.setPolicyRevision(value.getPolicyRevision()); dto.setRowVersion(value.getRowVersion());
        dto.setCreatedAt(value.getCreatedAt()); dto.setUpdatedAt(value.getUpdatedAt());
        return dto;
    }

    public static DfetlMessagePolicyDto toDto(DfetlMessagePolicy value) {
        DfetlMessagePolicyDto dto = new DfetlMessagePolicyDto();
        dto.setId(value.getId()); dto.setDatasetId(value.getDatasetId()); dto.setEnabled(value.getEnabled());
        dto.setTransport(value.getTransport()); dto.setFullSyncMode(value.getFullSyncMode()); dto.setRateLimit(value.getRateLimit());
        dto.setRoutingKey(value.getRoutingKey()); dto.setTopic(value.getTopic()); dto.setKeyTemplate(value.getKeyTemplate());
        dto.setPageSize(value.getPageSize()); dto.setTenantId(value.getTenantId()); dto.setSourceSystem(value.getSourceSystem());
        dto.setPolicyRevision(value.getPolicyRevision()); dto.setRowVersion(value.getRowVersion());
        dto.setCreatedAt(value.getCreatedAt()); dto.setUpdatedAt(value.getUpdatedAt());
        return dto;
    }

    private static long nextRevision(Long value) { return value == null ? 1L : value + 1L; }
    private static Long requiredId(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
        return value;
    }
    private static String allowed(String value, String field, Set<String> allowed, String fallback) {
        String normalized = upperToNull(value);
        if (normalized == null) normalized = upper(fallback);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " 不支持: " + normalized);
        return normalized;
    }
    private static int nonNegative(Integer value, int fallback, String field) {
        int result = value == null ? fallback : value;
        if (result < 0) throw new IllegalArgumentException(field + " 不能小于0");
        return result;
    }
    private static Integer positiveOrNull(Integer value, String field) {
        if (value == null) return null;
        if (value <= 0) throw new IllegalArgumentException(field + " 必须大于0");
        return value;
    }
    private static int range(Integer value, int fallback, int min, int max, String field) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw new IllegalArgumentException(field + " 超出范围 " + min + "~" + max);
        return result;
    }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String upperToNull(String value) { String result = trimToNull(value); return result == null ? null : upper(result); }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String defaultText(String value, String fallback) { String result = trimToNull(value); return result == null ? fallback : result; }
}

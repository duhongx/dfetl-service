package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.SourceTypeDescriptor;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.SdvTypeMappingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Doris 列类型统一解析入口。
 *
 * <p>医共体 contract-driven 任务优先使用医共体字段契约，普通任务继续复用现有
 * {@link DorisTypeMappingRuleService}/{@link DorisTypeMappingPolicy}。</p>
 */
@Component
@RequiredArgsConstructor
public class DorisColumnTypeResolver {

    private static final Set<String> ILLEGAL_KEY_BASE_TYPES = Set.of(
            "STRING", "JSON", "ARRAY", "BITMAP", "HLL", "BINARY", "VARBINARY", "BLOB", "CLOB");

    private final MedicalDatasetContractService contractService;
    private final SdvTypeMappingPolicy sdvTypeMappingPolicy;
    private final DorisTypeMappingPolicy fallbackPolicy;
    private final DorisTypeMappingRuleService ruleService;

    public DorisTypeResolveResult resolve(DorisTypeResolveContext context) {
        String dorisType;
        String source;
        if (context.explicitOverride() != null && !context.explicitOverride().isBlank()) {
            dorisType = normalizeType(context.explicitOverride());
            source = "EXPLICIT_OVERRIDE";
        } else if (context.medicalTask()) {
            dorisType = resolveMedicalType(context);
            source = "MEDICAL_CONTRACT";
        } else {
            SourceTypeDescriptor descriptor = new SourceTypeDescriptor(
                    context.sourceDialect(),
                    context.sourceJdbcType(),
                    context.sourceJdbcTypeCode(),
                    context.sourceLength(),
                    context.sourceScale(),
                    context.sourceLength(),
                    true,
                    context.fieldCode(),
                    true);
            dorisType = ruleService != null
                    ? ruleService.recommend(descriptor).recommendedDorisType()
                    : fallbackPolicy.recommend(descriptor).recommendedDorisType();
            source = "DORIS_TYPE_MAPPING";
        }
        validateKeyType(context, dorisType);
        return new DorisTypeResolveResult(dorisType, source);
    }

    private String resolveMedicalType(DorisTypeResolveContext context) {
        if (context.datasetCode() != null && !context.datasetCode().isBlank()) {
            return contractService.loadByDatasetCode(context.datasetCode()).fields().stream()
                    .filter(field -> field.code().equalsIgnoreCase(context.fieldCode()))
                    .findFirst()
                    .map(MedicalFieldContract::dorisType)
                    .orElseGet(() -> mapMedicalTypeFromContext(context));
        }
        return mapMedicalTypeFromContext(context);
    }

    private String mapMedicalTypeFromContext(DorisTypeResolveContext context) {
        return sdvTypeMappingPolicy.mapToDorisType(context.medicalSdvType(), context.medicalFormat());
    }

    private void validateKeyType(DorisTypeResolveContext context, String dorisType) {
        if (!context.keyColumn()) {
            return;
        }
        String base = baseType(dorisType);
        if (ILLEGAL_KEY_BASE_TYPES.contains(base)) {
            throw new IllegalArgumentException("字段 " + context.fieldCode()
                    + " 是 Doris KEY 列，解析类型为 " + dorisType
                    + "，Doris KEY 不支持 " + base + " 类型");
        }
    }

    private static String normalizeType(String type) {
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static String baseType(String type) {
        String normalized = normalizeType(type);
        int idx = normalized.indexOf('(');
        return idx > 0 ? normalized.substring(0, idx) : normalized;
    }

    public record DorisTypeResolveContext(
            boolean medicalTask,
            String datasetCode,
            String fieldCode,
            String medicalSdvType,
            String medicalFormat,
            String sourceDialect,
            String sourceJdbcType,
            Integer sourceLength,
            Integer sourceScale,
            Integer sourceJdbcTypeCode,
            boolean keyColumn,
            String explicitOverride
    ) {}

    public record DorisTypeResolveResult(
            String dorisType,
            String source
    ) {}
}

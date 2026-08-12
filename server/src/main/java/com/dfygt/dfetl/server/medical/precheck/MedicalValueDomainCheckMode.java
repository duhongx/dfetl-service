package com.dfygt.dfetl.server.medical.precheck;

/**
 * 医共体值域预检策略。
 */
public enum MedicalValueDomainCheckMode {
    /** 小值域：SQL 中直接按允许编码集合阻断。 */
    STRICT_BLOCK,
    /** 大值域初始态：先按当前窗口 distinct 源值核对注册库明细，再决定是否生成实际非法值阻断。 */
    ACTUAL_DISTINCT_CHECK,
    /** 大值域解析后：SQL 只按源端当前实际非法编码集合阻断。 */
    ACTUAL_INVALID_BLOCK,
    /** 大值域：默认不阻断写入，只输出复核提示，避免拼接巨大 IN。 */
    WARN_ONLY,
    /** 仅做格式类校验；当前预留策略值。 */
    FORMAT_ONLY,
    /** 不校验。 */
    DISABLED
}

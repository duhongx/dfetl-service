package com.dfygt.dfetl.server.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * spec 054 - PolicyRecommendService 输出。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyRecommendOutput {
    /** Doris 写入模式 TRUNCATE/APPEND/UPSERT */
    private String writeMode;

    /** Doris 表模型 UNIQUE_KEY/DUPLICATE_KEY/AGGREGATE_KEY */
    private String dorisTableModel;

    /** 校验方法 ROW_COUNT/CHECKSUM/PK_DIFF */
    private String validationMethod;

    /** 校验范围 FULL/WINDOW */
    private String validationScope;

    /** 同步成功后自动触发校验 */
    private Boolean autoTrigger;

    /** DIFF 自动修复 */
    private Boolean autoRepair;

    /** 自动修复行数上限 */
    private Long autoRepairMaxRows;

    /** 是否启用快照对账 */
    private Boolean snapshotEnabled;

    /** 删除处理模式 NONE/SOFT_DELETE/HARD_DELETE */
    private String snapshotDeleteMode;

    /** 是否启用 Drift-Watch */
    private Boolean driftWatchEnabled;

    /** Lookback 秒数（视图源/不可靠增量字段时建议大于 0） */
    private Integer lookbackSeconds;

    /** 推荐说明（人类可读，逐条） */
    private List<String> reasons = new ArrayList<>();

    /** 警告（业务上可能有风险） */
    private List<String> warnings = new ArrayList<>();
}

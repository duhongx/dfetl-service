package com.dfygt.dfetl.server.policy;

import lombok.Data;
import java.util.List;

/**
 * spec 054 - PolicyRecommendService 输入。
 *
 * <p>普通用户回答的"业务/数据特征"问题集合。
 */
@Data
public class PolicyRecommendInput {
    /** 同步模式（FULL / FULL_INCREMENT / INCREMENT_ONLY / CUSTOM_WINDOW） */
    private String syncMode;

    /** 是否有稳定主键 */
    private Boolean hasPrimaryKey;

    /** 主键字段（用于推荐 UNIQUE 模型时填 keys） */
    private List<String> primaryKeyColumns;

    /** 源端是否会物理删除数据 */
    private Boolean hasPhysicalDelete;

    /** 是否有删除标记字段 */
    private Boolean hasDeleteFlag;
    private String deleteFlagColumn;

    /** 是否会更新已有数据（影响 UPSERT vs APPEND） */
    private Boolean willUpdateExisting;

    /** 增量字段（如 update_time）是否可靠（每次 UPDATE 都会刷新） */
    private Boolean updateTimeReliable;

    /** 增量字段名（用于 PK_DIFF / WINDOW CHECKSUM 的 lookback 计算） */
    private String incrementalField;

    /** 数据规模（影响 ROW_COUNT vs CHECKSUM 推荐与并发） */
    private String dataScale; // SMALL/MEDIUM/LARGE

    /** 源对象类型 TABLE/VIEW/MATERIALIZED_VIEW */
    private String sourceKind;

    /** INSERT 是否写入增量字段（视图源常见 false） */
    private Boolean insertWritesIncrField;

    /** 数据敏感度（HIGH=偏严校验；LOW=偏快） */
    private String sensitivity;
}

package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * spec validation-workbench-redesign · Task P1-10.1
 *
 * <p>校验配置推荐结果。Validates: Requirement 16 (AC 1)。
 *
 * <p>推荐规则（与 design.md 「2.4 推荐校验组合」一致）：
 * <ul>
 *   <li>小表（< 1 万行）+ 全量同步：driftCron=null（不需要兜底，全量本身= 兜底）</li>
 *   <li>中表（1 万~100 万）+ 增量、不删：driftCron 每周日凌晨；snapshotCron=null</li>
 *   <li>大表（100 万~1 亿）+ 增量、硬删：driftCron 每天凌晨 02:00；snapshotCron 02:30</li>
 *   <li>超大表（>1 亿）：driftCron 每月 1 号凌晨；snapshotCron 每周日凌晨</li>
 *   <li>视图源：snapshotCron 不适用（用 ChangeAudit 替代）</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedCronDto {

    /** 推荐 driftCron（可能为 null 表示不推荐） */
    private String driftCron;

    /** 推荐 snapshotCron（可能为 null 表示不推荐或不适用） */
    private String snapshotCron;

    /** 表行数级别：SMALL / MEDIUM / LARGE / XLARGE / UNKNOWN */
    private String rowsLevel;

    /** 估算行数（来源：snapshot key count / TABLE_ROWS / readRows） */
    private Long estimatedRows;

    /** 是否硬删任务（enableSnapshotDelete = true）。 */
    private Boolean isHardDelete;

    /** 是否视图源（viewNames.size() == 1 且 source_datasource.type == VIEW）。 */
    private Boolean isViewSource;

    /** 推荐理由（中文，便于运维理解）。 */
    private String reason;
}

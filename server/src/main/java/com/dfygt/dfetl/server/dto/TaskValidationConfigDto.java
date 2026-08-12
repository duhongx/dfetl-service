package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
public class TaskValidationConfigDto {

    private Long id;
    private Long taskId;
    private String taskName;
    private Boolean enabled;
    /** null=继承全局；ROW_COUNT | CHECKSUM | ROW_COUNT_CHECKSUM */
    private String method;
    /** XXHASH64 | MD5 | SHA256 | CRC32 */
    private String checksumAlgo;
    private BigDecimal sampleRate;
    private Long toleranceRows;
    private BigDecimal tolerancePct;
    /** null=继承全局 autoEnabled */
    private Boolean autoTrigger;
    /** null=继承全局 failBlock */
    private Boolean blockOnFail;
    /** 校验表范围，null = 全部 */
    private List<String> targetTables;
    /** spec 030：drift-watch 周期 cron，空则不调度 */
    private String driftCron;
    /** spec 033：DIFF 仍未消除时是否自动 Repair */
    private Boolean autoRepair;
    /** spec 033：自动 Repair 单次行数上限 */
    private Long autoRepairMaxRows;
    /** spec 048：Checksum 范围：FULL（全表）| WINDOW（仅增量窗口）。默认 FULL。 */
    private String checksumScope;
    /** spec 054：保存时显式绕过视图源 autoRepair 拒绝（其它硬约束不可绕过） */
    private Boolean forceAllow;
    /** 校验回看窗口（小时）：null=继承全局，0=只验本次窗口，>0=向前扩展 N 小时 */
    private Integer validationLookbackHours;
    private Instant createdAt;
    private Instant updatedAt;

    /** 只读运行态：是否存在启用的任务级配置。 */
    private Boolean taskConfigEnabled;
    /** 只读运行态：合并任务配置与全局策略后的实际状态。 */
    private Boolean effectiveEnabled;
    private String effectiveMethod;
    private Boolean effectiveAutoTrigger;
    private Boolean effectiveBlockOnFail;
    private String methodSource;
    private String autoTriggerSource;
    private String blockOnFailSource;
}

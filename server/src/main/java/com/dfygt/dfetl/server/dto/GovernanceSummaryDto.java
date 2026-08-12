package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * spec validation-workbench-redesign Phase 2：多任务治理仪表盘响应。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceSummaryDto {

    /** 总同步任务数 */
    private int totalTasks;

    /** 已启用校验配置的任务数 */
    private int configuredTasks;

    /** 已启用全量校验（driftCron 非空）的任务数 */
    private int fullChecksumEnabled;

    /** 已启用 Snapshot 删除校验的任务数 */
    private int snapshotEnabled;

    /** 最近 7 天有差异的任务数 */
    private int recentDiffTasks;

    /** 未启用全量校验的任务列表（id + name） */
    private List<TaskBrief> noFullChecksum;

    /** 硬删任务但未启用 Snapshot 的列表 */
    private List<TaskBrief> hardDeleteNoSnapshot;

    /** 最近 7 天有差异的任务列表 */
    private List<TaskBrief> recentDiffTaskList;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskBrief {
        private Long id;
        private String name;
        private String viewName;
    }
}

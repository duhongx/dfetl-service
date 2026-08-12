package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.util.List;

/**
 * 模板配置与已创建任务之间的差异对比结果。
 */
@Data
public class BatchConfigDiffDto {

    /** 模板 ID */
    private Long templateId;

    /** 模板名称 */
    private String templateName;

    /** 有差异的任务列表 */
    private List<TaskDiff> diffs;

    /** 无差异的任务数 */
    private int upToDateCount;

    /** 未创建任务的数据源数 */
    private int pendingCount;

    @Data
    public static class TaskDiff {
        /** 关联数据源 ID */
        private Long sourceId;

        /** 机构名称 */
        private String institutionName;

        /** 已创建的 sync_task ID */
        private Long syncTaskId;

        /** 差异字段列表 */
        private List<FieldDiff> fields;
    }

    @Data
    public static class FieldDiff {
        /** 字段名（中文标签） */
        private String label;

        /** 字段 key */
        private String field;

        /** 模板值 */
        private String templateValue;

        /** 任务当前值 */
        private String taskValue;
    }
}

package com.dfygt.dfetl.server.external.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 对外创建同步任务请求。
 *
 * <p>调用方只提交医疗机构编码和标准数据集编码清单，不传 DFETL 内部数据源、路由、
 * Schema、源对象、目标表或同步策略；这些内容由已校验并启用的机构数据集路由解析。
 */
@Data
public class ExternalSyncTaskRequest {

    /** 调用方提供的幂等请求号。 */
    @NotBlank
    private String requestId;

    /** 本次要建立任务的标准数据集编码，最多 500 个。 */
    @NotEmpty
    @Size(max = 500)
    private List<@NotBlank String> datasetCodes;

    /** 是否在成功创建后立即提交执行；只运行本次新建的任务。 */
    private Boolean runAfterCreate = false;

    /** BEST_EFFORT | ALL_OR_NOTHING。 */
    private String failurePolicy = "BEST_EFFORT";

    /** 医疗机构代码，不是 tenantId。 */
    @NotBlank
    private String yiLiaoJgDm;
}

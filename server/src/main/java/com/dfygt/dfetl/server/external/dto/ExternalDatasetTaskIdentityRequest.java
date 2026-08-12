package com.dfygt.dfetl.server.external.dto;

import jakarta.validation.constraints.NotBlank;

/** 外部系统按业务身份查询、运行或删除一个任务。 */
public record ExternalDatasetTaskIdentityRequest(
        @NotBlank String yiLiaoJgDm,
        @NotBlank String datasetCode) {
}

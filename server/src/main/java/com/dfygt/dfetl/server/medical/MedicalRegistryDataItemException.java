package com.dfygt.dfetl.server.medical;

/**
 * 数据集字段关联不到活动数据项主记录时抛出，禁止静默丢字段或回退无效冗余列。
 */
public class MedicalRegistryDataItemException extends IllegalStateException {

    private final String datasetCode;
    private final String fieldId;
    private final String dataItemId;

    public MedicalRegistryDataItemException(String datasetCode, String fieldId, String dataItemId) {
        super("医共体数据集 " + datasetCode + " 的字段关联缺少活动数据项: fieldId="
                + fieldId + ", dataItemId=" + dataItemId);
        this.datasetCode = datasetCode;
        this.fieldId = fieldId;
        this.dataItemId = dataItemId;
    }

    public String datasetCode() {
        return datasetCode;
    }

    public String fieldId() {
        return fieldId;
    }

    public String dataItemId() {
        return dataItemId;
    }
}

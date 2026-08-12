package com.dfygt.dfetl.server.medical;

/**
 * 医共体字段逻辑类型与表示格式无法解析。
 */
public class MedicalFormatException extends IllegalArgumentException {

    private final String field;
    private final String sdvType;
    private final String format;

    public MedicalFormatException(String field, String sdvType, String format, String message) {
        super(message);
        this.field = field;
        this.sdvType = sdvType;
        this.format = format;
    }

    public MedicalFormatException withField(String fieldCode) {
        if (field != null && !field.isBlank()) {
            return this;
        }
        return new MedicalFormatException(fieldCode, sdvType, format,
                "字段 " + fieldCode + " " + getMessage());
    }

    public String field() {
        return field;
    }

    public String sdvType() {
        return sdvType;
    }

    public String format() {
        return format;
    }
}

package com.dfygt.dfetl.server.service.publish;

import java.util.Locale;

/**
 * 全量同步时的消息发布模式。
 */
public enum FullSyncMode {
    /** 全量发送所有数据 */
    ALL,
    /** 跳过不发送 */
    SKIP,
    /** 只发一条通知消息 */
    NOTIFY_ONLY;

    /**
     * 解析配置值。未提供值时使用安全默认值 {@link #SKIP}，未知非空值拒绝执行。
     */
    public static FullSyncMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SKIP;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return FullSyncMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported fullSyncMode '" + value
                            + "', allowed values: ALL/SKIP/NOTIFY_ONLY",
                    ex);
        }
    }
}

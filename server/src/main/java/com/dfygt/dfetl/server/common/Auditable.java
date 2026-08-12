package com.dfygt.dfetl.server.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写入审计日志的 Controller 方法。
 * AuditLogAspect 拦截后自动记录用户操作。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    /** 操作描述，例如：创建任务、修改任务、删除任务 */
    String action();

    /** 目标实体类型，例如：sync_task、source_datasource */
    String targetType() default "";
}

package com.dfygt.dfetl.server.common;

import com.dfygt.dfetl.server.entity.AuditLog;
import com.dfygt.dfetl.server.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;

/**
 * AOP 切面：自动记录 Controller 写操作到 audit_log 表。
 * 只处理 POST / PUT / PATCH / DELETE 方法（忽略 GET 只读操作）。
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    /**
     * 拦截 controller 包下所有 Controller 的写操作。
     */
    @Around("within(com.dfygt.dfetl.server.controller..*) && " +
            "(@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            " @annotation(org.springframework.web.bind.annotation.PutMapping)  || " +
            " @annotation(org.springframework.web.bind.annotation.PatchMapping) || " +
            " @annotation(org.springframework.web.bind.annotation.DeleteMapping))")
    public Object auditWrite(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();

        try {
            recordAudit(pjp, result);
        } catch (Exception e) {
            // 审计写入失败不影响主业务
            log.warn("AuditLog write failed: {}", e.getMessage());
        }

        return result;
    }

    private void recordAudit(ProceedingJoinPoint pjp, Object result) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = method.getName();

        // 推断 action 描述
        String action = resolveAction(className, methodName, method);
        if (action == null) {
            return; // 不需要记录（如 test、channel 测试等内部操作）
        }

        // 推断 targetType
        String targetType = resolveTargetType(className);

        // 尝试从返回值中提取 targetId / targetName
        Long targetId = null;
        String targetName = null;
        if (result instanceof ApiResponse<?> apiResp) {
            Object data = apiResp.getData();
            if (data != null) {
                targetId = extractField(data, "id", Long.class);
                targetName = extractField(data, "name", String.class);
            }
        }

        // 获取 clientIp
        String clientIp = resolveClientIp();

        // 若返回值未提供 targetId，回退从 @PathVariable 入参提取
        // （如 resetWatermark / resetInitialFullSync / cancel 等返回 Void/Map 的端点）
        if (targetId == null) {
            targetId = extractPathVariableId(pjp);
        }

        // detail：在 className.methodName 之后追加格式化后的入参，便于审计追溯
        // 形如：SyncTaskController.resetWatermark args={id=42, value=2026-05-29T00:00:00Z}
        String argsDesc = formatArgs(pjp);
        String detail = className + "." + methodName + (argsDesc.isEmpty() ? "" : " args=" + argsDesc);

        AuditLog log = new AuditLog();
        log.setActionTime(Instant.now());
        log.setUserName(resolveUsername());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);
        log.setClientIp(clientIp);
        log.setDetail(detail);
        auditLogRepository.save(log);
    }

    /**
     * 提取 @PathVariable 标注的 Long 类型入参（通常是资源 ID）。
     * 用于返回 Void/Map 的端点（如 resetWatermark/cancel）补齐 audit_log.target_id。
     */
    private Long extractPathVariableId(ProceedingJoinPoint pjp) {
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method m = sig.getMethod();
            java.lang.annotation.Annotation[][] paramAnnos = m.getParameterAnnotations();
            Object[] args = pjp.getArgs();
            for (int i = 0; i < paramAnnos.length; i++) {
                for (java.lang.annotation.Annotation anno : paramAnnos[i]) {
                    if (anno instanceof PathVariable) {
                        if (args[i] instanceof Long longVal) return longVal;
                        if (args[i] instanceof Number num) return num.longValue();
                        if (args[i] instanceof String str) {
                            try { return Long.parseLong(str); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 把 @PathVariable / @RequestParam 入参格式化成 {key=value, ...} 字符串，
     * 用于审计 detail 字段的可追溯性。复杂对象（@RequestBody DTO）只输出类名，避免日志膨胀。
     * 单个值长度限制 200 字符，整体 detail 由调用方控制不超过表字段约束。
     */
    private String formatArgs(ProceedingJoinPoint pjp) {
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method m = sig.getMethod();
            String[] paramNames = sig.getParameterNames();
            java.lang.annotation.Annotation[][] paramAnnos = m.getParameterAnnotations();
            Object[] args = pjp.getArgs();
            if (paramNames == null || args == null) return "";
            StringBuilder sb = new StringBuilder("{");
            int included = 0;
            for (int i = 0; i < args.length; i++) {
                boolean isPath = false;
                boolean isQuery = false;
                boolean isBody = false;
                for (java.lang.annotation.Annotation anno : paramAnnos[i]) {
                    if (anno instanceof PathVariable) isPath = true;
                    else if (anno instanceof RequestParam) isQuery = true;
                    else if (anno instanceof RequestBody) isBody = true;
                }
                String key = (i < paramNames.length) ? paramNames[i] : ("arg" + i);
                String value;
                if (isBody) {
                    value = args[i] == null ? "null" : args[i].getClass().getSimpleName();
                } else if (isPath || isQuery) {
                    value = formatScalar(args[i]);
                } else {
                    continue; // 忽略 HttpServletRequest / SseEmitter 等基础设施参数
                }
                if (included++ > 0) sb.append(", ");
                sb.append(key).append("=").append(value);
            }
            sb.append("}");
            return included == 0 ? "" : sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String formatScalar(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** 根据类名和方法名推断操作描述 */
    private String resolveAction(String className, String methodName, Method method) {
        if (className.contains("TaskExecution")) {
            if (methodName.startsWith("markReconcileHandled")) {
                return "标记人工核对已处理";
            }
            if (methodName.startsWith("reopenReconcile")) {
                return "重新打开人工核对";
            }
            if (methodName.startsWith("probeReconcile")) {
                return "重新探测执行状态";
            }
            if (methodName.startsWith("cancel")) {
                return "取消执行记录";
            }
            return "操作执行记录";
        }

        String entity;
        if (className.contains("SyncTask")) {
            entity = "任务";
        } else if (className.contains("SourceDataSource")) {
            entity = "来源数据源";
        } else if (className.contains("TargetDataSource")) {
            entity = "目标数据源";
        } else if (className.contains("Alert")) {
            entity = "告警规则";
        } else if (className.contains("Validation")) {
            entity = "校验任务";
        } else if (className.contains("SystemSetting")) {
            entity = "系统设置";
        } else {
            return null;  // 其他 Controller 不记录
        }

        if (methodName.startsWith("create") || hasAnnotation(method, PostMapping.class)) {
            return "创建" + entity;
        } else if (methodName.startsWith("update") || hasAnnotation(method, PutMapping.class)) {
            return "修改" + entity;
        } else if (methodName.startsWith("delete") || hasAnnotation(method, DeleteMapping.class)) {
            return "删除" + entity;
        } else if (methodName.startsWith("run")) {
            return "运行" + entity;
        } else if (methodName.startsWith("toggle") || methodName.contains("enabled")) {
            return "切换状态";
        } else {
            return "操作" + entity;
        }
    }

    private String resolveTargetType(String className) {
        if (className.contains("SyncTask")) return "sync_task";
        if (className.contains("SourceDataSource")) return "source_datasource";
        if (className.contains("TargetDataSource")) return "target_datasource";
        if (className.contains("TaskExecution")) return "task_execution";
        if (className.contains("Alert")) return "alert";
        if (className.contains("Validation")) return "validation_task";
        if (className.contains("SystemSetting")) return "system_setting";
        return "unknown";
    }

    private <T> T extractField(Object obj, String fieldName, Class<T> type) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(obj));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasAnnotation(Method method, Class<?> annotationClass) {
        return method.isAnnotationPresent(annotationClass.asSubclass(java.lang.annotation.Annotation.class));
    }

    private String resolveUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
        }
        return "system";
    }
}

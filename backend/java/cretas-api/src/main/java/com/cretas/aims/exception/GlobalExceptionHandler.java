package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.hibernate.PropertyValueException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.slf4j.MDC;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Collectors;
/**
 * 全局异常处理器
 *
 * 负责捕获所有异常并进行脱敏处理，确保：
 * 1. 敏感信息（堆栈、数据库错误、内部路径）不暴露给用户
 * 2. 日志记录完整的异常信息供排查
 * 3. 返回用户友好的错误消息和标准化错误码
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 生成错误追踪ID，用于关联日志和用户反馈。
     * Reuses the request's correlation ID from MDC when available so that
     * the trace ID shown to users matches the log correlation ID.
     * Falls back to a random UUID segment if MDC is empty.
     */
    private String generateTraceId() {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            // Use first 8 chars of the correlation ID for user-facing trace code
            return correlationId.substring(0, Math.min(8, correlationId.length())).toUpperCase();
        }
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * 构建脱敏后的错误响应
     */
    private ApiResponse<?> buildSanitizedResponse(ErrorCode errorCode, String traceId) {
        String message = errorCode.getUserMessage();
        if (traceId != null) {
            message = message + " (追踪码: " + traceId + ")";
        }
        return ApiResponse.error(errorCode.getHttpStatus(), message);
    }

    /**
     * 判断消息是否可以安全展示给用户（不包含敏感信息）
     */
    private boolean isSafeMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        // 敏感关键词检测
        String lowerMsg = message.toLowerCase();
        return !lowerMsg.contains("exception")
                && !lowerMsg.contains("error at")
                && !lowerMsg.contains("stacktrace")
                && !lowerMsg.contains("sql")
                && !lowerMsg.contains("jdbc")
                && !lowerMsg.contains("hibernate")
                && !lowerMsg.contains("jpa")
                && !lowerMsg.contains("mysql")
                && !lowerMsg.contains("postgresql")
                && !lowerMsg.contains("oracle")
                && !lowerMsg.contains("connection")
                && !lowerMsg.contains("localhost")
                && !lowerMsg.contains("127.0.0.1")
                && !lowerMsg.contains("/home/")
                && !lowerMsg.contains("/www/")
                && !lowerMsg.contains("c:\\")
                && !lowerMsg.contains("d:\\")
                && !lowerMsg.contains(".java:")
                && !lowerMsg.contains("at com.")
                && !lowerMsg.contains("at org.")
                && !lowerMsg.contains("null pointer")
                && !lowerMsg.contains("nullpointer")
                && !lowerMsg.contains("class cast")
                && !lowerMsg.contains("classcast");
    }

    /**
     * 处理限流超限异常 — 返回 HTTP 429 Too Many Requests
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleRateLimitExceededException(
            RateLimitExceededException e) {
        log.warn("限流触发: {}", e.getMessage());
        return org.springframework.http.ResponseEntity.status(429)
                .body(ApiResponse.error(429, e.getMessage()));
    }

    /**
     * 处理业务异常 - 业务异常消息通常是安全的
     */
    @ExceptionHandler(BusinessException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        // 业务异常的消息通常是开发者定义的，但仍需检查
        String message = isSafeMessage(e.getMessage()) ? e.getMessage() : ErrorCode.BUSINESS_RULE_VIOLATION.getUserMessage();
        // UX 2026-04-18 进阶: propagate actionHint/severity/hintTarget so frontend
        // interceptor can render ElNotification with button, ElMessageBox modal,
        // or pulse-hint animation instead of plain ElMessage toast.
        //
        // R73-FIX-A (2026-04-30): default severity from code when not set explicitly.
        // R67/R68 转过来的 77+ BusinessException(403) 没设 severity. R72-FIX-B 真窗暴露
        // 此 gap. 默认: 403/401/500/502/503/504 → "error"; 409/429 → "warning"; 其他 → null.
        // 这让所有未显式 .withSeverity() 的 throw 自动有合理 severity, 不需要 audit 77 处.
        Integer code = e.getCode() != null ? e.getCode() : 400;
        String effectiveSeverity = e.getSeverity();
        if (effectiveSeverity == null) {
            effectiveSeverity = switch (code) {
                case 401, 403, 500, 502, 503, 504 -> "error";
                case 409, 429 -> "warning";
                default -> null;
            };
        }
        ApiResponse<?> body;
        if (e.getActionHint() != null || effectiveSeverity != null || e.getHintTarget() != null) {
            body = ApiResponse.errorWithHint(e.getCode(), message,
                    e.getActionHint(), effectiveSeverity, e.getHintTarget());
        } else {
            body = ApiResponse.error(e.getCode(), message);
        }
        // Map business code to HTTP status. Default 400 for unknown / 4xx-validation paths.
        HttpStatus status = switch (code) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            // 410 GONE — PR #872 share link expired / revoked.
            case 410 -> HttpStatus.GONE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }

    /**
     * Canvas-Rules Phase 4a — REJECT action raises this. Maps to HTTP 400 with
     * standard {@code {success, code, message, actionHint}} envelope per fool-proof Rule 5.
     */
    @ExceptionHandler(com.cretas.aims.service.rules.RuleViolationException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleRuleViolation(
            com.cretas.aims.service.rules.RuleViolationException e) {
        log.warn("Canvas-Rules REJECT: ruleCode={}, reason={}", e.getRuleCode(), e.getReason());
        ApiResponse<?> body = ApiResponse.errorWithHint(400, e.getReason(),
                /* actionHint */ null, "warning", /* hintTarget */ null);
        // Code marker so frontend can branch UX (vs generic 400). Stash in message header? — use code.
        body.setCode(400);
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理未找到资源异常
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleResourceNotFoundException(ResourceNotFoundException e) {
        // 404 — 客户端请求了不存在的资源, 不是服务端 bug
        log.warn("资源未找到: {}", e.getMessage());
        return ApiResponse.error(404, e.getMessage());
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<?> handleAuthenticationException(AuthenticationException e) {
        // 401 — 客户端没登录 / token 过期, 不是服务端 bug
        log.warn("认证失败: {}", e.getMessage());
        return ApiResponse.error(401, e.getMessage());
    }

    /**
     * 处理权限异常
     */
    @ExceptionHandler(AuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<?> handleAuthorizationException(AuthorizationException e) {
        // 403 — 客户端用了没权限的操作, 不是服务端 bug (但有价值审计)
        log.warn("权限不足: {}", e.getMessage());
        return ApiResponse.error(403, e.getMessage());
    }

    /**
     * 处理参数验证异常
     *
     * W-08 fix (Round 12, qa-prompt v2.4 Rule 8): previous version joined only
     * FieldError::getDefaultMessage, so entity annotations written as bare
     * `@NotNull` / `@NotBlank` (without custom message) produced useless
     * "must not be null" response with no field name. Now include the field
     * name + Chinese phrasing for the common default messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining(", "));
        // 4xx — 客户端 form 没填全, 不是服务端 bug
        log.warn("参数验证失败: {}", message);
        // actionHint: first field with violation
        String firstField = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField).findFirst().orElse(null);
        if (firstField != null) {
            return ApiResponse.errorWithHint(400, message,
                    "请检查「" + firstField + "」字段", "error", firstField);
        }
        return ApiResponse.error(400, message);
    }

    /**
     * Format a single field validation error into a user-readable Chinese string.
     * Intercepts Bean Validation's default English messages for common constraints.
     */
    private static String formatFieldError(FieldError err) {
        String msg = err.getDefaultMessage();
        String field = err.getField();
        if (msg == null) return "字段 '" + field + "' 校验失败";
        // Common Bean Validation defaults — translate to Chinese + include field
        if ("must not be null".equals(msg) || "must not be blank".equals(msg) || "must not be empty".equals(msg)) {
            return "字段 '" + field + "' 不能为空";
        }
        // Custom messages (entity/DTO provided @NotNull(message = "xxx不能为空")) — keep as-is
        // since they already read naturally in Chinese.
        return msg.contains(field) ? msg : field + ": " + msg;
    }

    /**
     * 处理 Spring MVC Controller 方法级参数校验异常 (Spring 6 新增).
     * 之前这类异常会落到 RuntimeException handler 变成 500,导致客户端收到
     * "系统处理异常" 而非 "参数错误". 必须显式映射为 400.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleHandlerMethodValidationException(
            org.springframework.web.method.annotation.HandlerMethodValidationException e) {
        String message = e.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream())
                .map(err -> err.getDefaultMessage())
                .filter(m -> m != null)
                .collect(Collectors.joining(", "));
        if (message.isEmpty()) {
            message = "参数校验失败";
        }
        log.warn("Controller 参数校验失败: {}", message);
        return ApiResponse.error(400, message);
    }

    /**
     * 处理参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return ApiResponse.error(400, message);
    }

    /**
     * 处理约束验证异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("约束验证失败: {}", message);
        return ApiResponse.error(400, message);
    }

    /**
     * 处理方法参数类型不匹配异常（如枚举值无效）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String paramName = e.getName();
        String invalidValue = e.getValue() != null ? e.getValue().toString() : "null";
        Class<?> requiredType = e.getRequiredType();

        String message;
        if (requiredType != null && requiredType.isEnum()) {
            Object[] enumConstants = requiredType.getEnumConstants();
            String validValues = java.util.Arrays.stream(enumConstants)
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            message = String.format("参数 '%s' 的值 '%s' 无效，有效值为: %s", paramName, invalidValue, validValues);
        } else {
            message = String.format("参数 '%s' 的值 '%s' 类型不匹配", paramName, invalidValue);
        }

        log.warn("参数类型不匹配: {}", message);
        return ApiResponse.error(400, message);
    }

    /**
     * 处理非法参数异常 - 需脱敏
     *
     * R68 (qa-prompt v2.4 reviewer): 加 generic actionHint 让 FE 错误 UX 四件套至少有 hint 字段
     * (虽然 services 层应该用 typed BusinessException + withHint() + withHintTarget(), 但残留的
     * IllegalArgumentException 在被 R69 sweep 完前先用 handler-level fallback 保底).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        // 检查消息是否安全
        String message = isSafeMessage(e.getMessage()) ? e.getMessage() : ErrorCode.PARAM_INVALID.getUserMessage();
        ApiResponse<?> resp = ApiResponse.error(400, message);
        resp.setActionHint("请检查输入字段后重试");
        resp.setSeverity("warning");
        return resp;
    }

    /**
     * 处理实体不存在异常
     */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleEntityNotFoundException(EntityNotFoundException e) {
        log.warn("Entity not found: {}", e.getMessage());
        return ApiResponse.error(404, e.getMessage());
    }

    /**
     * 处理验证异常
     */
    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleValidationException(ValidationException e) {
        log.warn("Validation error: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    /**
     * 处理乐观锁冲突异常 (BUG-047: P5-004)
     * 当多个用户同时修改同一条记录时，后提交的会触发此异常
     * 返回 409 Conflict，提示用户刷新后重试
     */
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleOptimisticLockException(Exception e) {
        log.warn("乐观锁冲突: {}", e.getMessage());
        return ApiResponse.error(409, "数据已被其他用户修改，请刷新后重试");
    }

    /**
     * 处理非法状态异常 (BUG-048: P5-005) - 需脱敏
     * 用于处理重复操作、状态流转错误等业务规则违反
     * 例如：重复确认告警、完成已完成的批次等
     *
     * R67 (qa-prompt v2.4 reviewer): R39 BUG-9 策略要求状态机错误返回 409, 不是 400.
     * 调整 status code 并加 actionHint 让 FE 知道这是状态冲突 (而非参数错).
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleIllegalStateException(IllegalStateException e) {
        log.warn("非法状态: {}", e.getMessage());
        // 检查消息是否安全
        String message = isSafeMessage(e.getMessage()) ? e.getMessage() : ErrorCode.STATE_CONFLICT.getUserMessage();
        ApiResponse<?> resp = ApiResponse.error(409, message);
        resp.setActionHint("请刷新页面查看最新状态后再操作");
        resp.setSeverity("warning");
        return resp;
    }

    // ==================== 数据库相关异常 - 需严格脱敏 ====================

    /**
     * 业务层抛 DuplicateKeyException (e.g. invoice request 去重) — 专用 handler 保留具体消息.
     * 优先匹配, 避免被下方通用 DataIntegrityViolation handler 脱敏.
     * R4 2026-04-16: 支持 Bug #2 G1 并发去重的友好 409 消息.
     * R25 (qa-prompt v2.4 reviewer #14 concern #3): emit actionHint so FE
     * interceptor differentiates from vanilla optimistic-lock 409 and shows
     * rich toast (pre-R25 was silenced by R24 P2 fix's vanilla-409 suppress).
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleDuplicateKeyException(org.springframework.dao.DuplicateKeyException e) {
        log.warn("重复提交拒绝: {}", e.getMessage());
        String msg = isSafeMessage(e.getMessage()) ? e.getMessage() : "数据已存在，请勿重复提交";
        // R27 P4 (reviewer #15 IMPORTANT-2 wording fix): pre-fix actionHint mentioned
        // "刷新列表确认" which sounds like optimistic-lock refresh suggestion. DuplicateKey
        // is "user submitted same data twice" — refresh doesn't help, the data is committed.
        // Replace with action-oriented hint: "请检查表单或返回列表查看已存在记录".
        return ApiResponse.errorWithHint(409, msg, "请检查表单内容,或返回列表查看已存在记录", "warning", null);
    }

    /**
     * 处理数据完整性异常（唯一约束、外键约束等）
     *
     * BUG-1 fix (depth-e2e qa-v2.4, PR #370): FK violation 之前对所有 HTTP method
     * 都返回 "无法删除" wording, 这对 POST/PUT (新建/更新关联资源) 极其困惑.
     * 现在根据 request.getMethod() 分发到不同的 message + actionHint:
     *   - POST  → "新建失败: 关联资源不存在" + "请确认引用的 X 已存在再重试"
     *   - PUT   → "更新失败: 关联资源已失效" + "请刷新页面后重新选择"
     *   - DELETE/其他 → 保留原 "无法删除" wording (已经准确)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleDataIntegrityViolationException(
            DataIntegrityViolationException e, HttpServletRequest request) {
        String traceId = generateTraceId();
        String raw = e.getMessage() != null ? e.getMessage() : "";
        String method = request != null ? request.getMethod() : null;

        // 分级: FK / 唯一约束是客户端操作问题 (e.g. 删引用的产品 / 重复提交), 不是服务端 bug
        // 其他 DataIntegrityException (真的数据损坏) 才 ERROR
        boolean isFkViolation = raw.contains("foreign key") || raw.contains("FOREIGN KEY")
            || raw.contains("violates foreign key constraint");
        boolean isUniqueViolation = raw.contains("Duplicate entry") || raw.contains("unique constraint")
            || raw.contains("duplicate key value violates unique constraint");

        if (isFkViolation || isUniqueViolation) {
            // 这类属"客户端想做但业务不允许", WARN 足够
            log.warn("[{}] 数据冲突 ({}): method={}, raw={}", traceId,
                isFkViolation ? "FK 引用" : "唯一约束", method, raw.split("\n")[0]);
        } else {
            log.error("[{}] 数据完整性异常: {}", traceId, raw, e);
        }

        // R25 (qa-prompt v2.4 reviewer #14 concern #3): every branch emits actionHint so
        // FE interceptor differentiates from vanilla optimistic-lock 409 (which has no
        // actionHint and routes to caller's "并发编辑冲突" dialog). Pre-R25 these returned
        // ApiResponse.error(409, ...) — R24 P2 vanilla-409 suppress swallowed them, users
        // saw nothing on duplicate / FK violation submissions.
        String message;
        String actionHint;
        String hintTarget = null;
        if (isUniqueViolation) {
            message = "数据已存在，请勿重复提交";
            // R27 P4 (reviewer #15 IMPORTANT-2): same wording fix as DuplicateKey above —
            // "刷新列表确认" implied opt-lock semantics. Unique violation is "field uniqueness
            // exhausted by prior commit" — user should check form, not refresh.
            // R27.1 follow-up (reviewer #17 IMPORTANT-1): "(如编号/名称)" too narrow —
            // codebase has unique constraints on phone/token/batch_number/employee_code/etc.
            // Extract actual column from PG's "Key (col)=(val) already exists" pattern, or
            // fall back to generic if extraction fails. Mirror of the FK-branch hintTarget logic.
            java.util.regex.Matcher uvm = java.util.regex.Pattern
                .compile("Key \\(([^)]+)\\)=\\(").matcher(raw);
            if (uvm.find()) {
                String col = uvm.group(1);
                actionHint = "请检查表单中字段「" + col + "」是否已存在,或返回列表查看已存在记录";
                hintTarget = col;
            } else {
                actionHint = "请检查表单中的唯一字段是否已存在,或返回列表查看已存在记录";
            }
        } else if (isFkViolation) {
            // BUG-1: HTTP-method-aware dispatch. PG 的 referenced 关系在 FK 错误消息里:
            //   - "referenced from table \"X\""  → 删除场景下被 X 引用
            //   - 一般 violates FK 不指明 referenced from, 是 CREATE/UPDATE 引用了不存在的目标
            // 用 m.find() 区分 referenced-from (被引用) vs plain FK (引用了不存在的)
            java.util.regex.Matcher mReferenced = java.util.regex.Pattern
                .compile("referenced from table \"([^\"]+)\"").matcher(raw);
            // PG FK 错误也常含 "violates foreign key constraint \"fk_xxx\"" — 提取 target table
            // 从 "Key (col)=(val) is not present in table \"X\"" 拿 X
            java.util.regex.Matcher mTarget = java.util.regex.Pattern
                .compile("is not present in table \"([^\"]+)\"").matcher(raw);

            if ("POST".equalsIgnoreCase(method)) {
                // CREATE: 引用了不存在的目标资源
                message = "新建失败: 关联资源不存在";
                actionHint = "请确认所引用的关联数据已存在再重试";
                if (mTarget.find()) {
                    String target = mTarget.group(1);
                    message = "新建失败: 关联资源「" + target + "」不存在";
                    actionHint = "请确认「" + target + "」已存在再重试";
                    hintTarget = target;
                }
            } else if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                // UPDATE: 引用的资源在并发删除后失效
                message = "更新失败: 关联资源已失效";
                actionHint = "请刷新页面后重新选择有效的关联数据";
                if (mTarget.find()) {
                    String target = mTarget.group(1);
                    message = "更新失败: 关联资源「" + target + "」已失效";
                    actionHint = "请刷新页面后重新选择「" + target + "」";
                    hintTarget = target;
                }
            } else {
                // DELETE / 其他 (legacy 行为, 保持向后兼容)
                message = "无法删除: 该数据仍被其他记录引用";
                actionHint = "先处理引用该数据的相关记录后再删除";
                if (mReferenced.find()) {
                    message = "无法删除: 该数据仍被 " + mReferenced.group(1) + " 引用，请先处理相关数据";
                    hintTarget = mReferenced.group(1);
                }
            }
        } else {
            message = ErrorCode.DATA_INTEGRITY_ERROR.getUserMessage();
            actionHint = "请联系管理员检查数据,traceId=" + traceId;
        }
        return ApiResponse.errorWithHint(409, message, actionHint, "warning", hintTarget);
    }

    /**
     * R27-F2: Hibernate not-null property violations → 400 with field name
     * (Spring wraps PropertyValueException as InvalidDataAccessApiUsageException,
     *  which extends DataAccessException but NOT DataIntegrityViolationException,
     *  so without this specific handler it falls through to generic 500.)
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleInvalidDataAccessApiUsageException(InvalidDataAccessApiUsageException e) {
        String traceId = generateTraceId();
        Throwable root = e.getCause();
        while (root != null && root.getCause() != null && root.getCause() != root) {
            if (root instanceof PropertyValueException) break;
            root = root.getCause();
        }
        if (root instanceof PropertyValueException pve) {
            log.warn("[{}] 必填字段缺失: entity={}, property={}", traceId,
                pve.getEntityName(), pve.getPropertyName());
            return ApiResponse.error(400, "必填字段缺失: " + pve.getPropertyName());
        }
        log.warn("[{}] 数据访问参数非法: {}", traceId, e.getMessage());
        return ApiResponse.error(400, "请求参数不符合要求");
    }

    /**
     * 处理数据库查询超时
     */
    @ExceptionHandler(QueryTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public ApiResponse<?> handleQueryTimeoutException(QueryTimeoutException e) {
        String traceId = generateTraceId();
        log.error("[{}] 数据库查询超时: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.SERVICE_TIMEOUT, traceId);
    }

    /**
     * 处理通用数据访问异常
     */
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleDataAccessException(DataAccessException e) {
        String traceId = generateTraceId();
        log.error("[{}] 数据访问异常: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.DATABASE_ERROR, traceId);
    }

    /**
     * 处理 JPA 持久化异常
     */
    @ExceptionHandler(PersistenceException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handlePersistenceException(PersistenceException e) {
        String traceId = generateTraceId();
        log.error("[{}] JPA持久化异常: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.DATABASE_ERROR, traceId);
    }

    /**
     * 处理 SQL 异常
     */
    @ExceptionHandler(SQLException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleSQLException(SQLException e) {
        String traceId = generateTraceId();
        log.error("[{}] SQL异常: SQLState={}, ErrorCode={}, Message={}",
                traceId, e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.DATABASE_ERROR, traceId);
    }

    // ==================== 网络相关异常 - 需严格脱敏 ====================

    /**
     * 处理连接异常
     */
    @ExceptionHandler(ConnectException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<?> handleConnectException(ConnectException e) {
        String traceId = generateTraceId();
        log.error("[{}] 连接异常: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.EXTERNAL_SERVICE_ERROR, traceId);
    }

    /**
     * 处理网络超时异常
     */
    @ExceptionHandler(SocketTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public ApiResponse<?> handleSocketTimeoutException(SocketTimeoutException e) {
        String traceId = generateTraceId();
        log.error("[{}] 网络超时: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.SERVICE_TIMEOUT, traceId);
    }

    // ==================== HTTP 请求相关异常 ====================

    /**
     * 处理请求参数缺失
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return ApiResponse.error(400, "缺少必要参数: " + e.getParameterName());
    }

    /**
     * 处理 multipart 请求缺少 @RequestPart。MissingServletRequestPartException 继承
     * ServletException 而非 MultipartException, 没有专门 handler 时会落到 generic
     * RuntimeException 兜底,返回 500。这里映射为 400 并带出 part 名称。
     */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMissingServletRequestPartException(
            org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        log.warn("缺少 multipart 请求部分: {}", e.getRequestPartName());
        return ApiResponse.error(400, "缺少必要文件字段: " + e.getRequestPartName());
    }

    /**
     * 处理请求体解析失败
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        // PR1.5 O4 (revised QA Round 6): broader Jackson date error detection.
        // Patterns covered:
        //   - "Cannot deserialize value of type `java.time.LocalDate` from String \"TODAY\""
        //   - "JSON parse error: Text 'NOT_A_DATE' could not be parsed at index 0"
        //   - DateTimeParseException class name in stack
        String msg = e.getMessage() == null ? "" : e.getMessage();
        boolean isDateError = msg.contains("java.time.LocalDate") || msg.contains("java.time.LocalDateTime")
            || msg.contains("DateTimeParseException") || msg.contains("could not be parsed at index");
        if (isDateError) {
            // extract problematic value: try double-quote pattern first, then single-quote
            String badValue = "";
            java.util.regex.Matcher dq = java.util.regex.Pattern
                .compile("from String \"([^\"]+)\"").matcher(msg);
            if (dq.find()) {
                badValue = dq.group(1);
            } else {
                java.util.regex.Matcher sq = java.util.regex.Pattern
                    .compile("Text '([^']+)' could not be parsed").matcher(msg);
                if (sq.find()) badValue = sq.group(1);
            }
            String fieldHint = badValue.isEmpty() ? "" : "（值: \"" + badValue + "\"）";
            return ApiResponse.error(400, "日期格式不正确" + fieldHint + "，请重新选择日期");
        }
        if (msg.contains("Cannot deserialize value of type")) {
            // W-10 fix (Round 12, qa-prompt v2.4 Rule 8): previous message was
            // "字段类型不正确，请检查表单后重新提交" — no field name, no type hint,
            // no actionHint. Users got "请检查表单" with no clue which field is wrong.
            // Mirror the O4 date-error pattern: extract field + expected type from
            // Jackson's message, build a specific Chinese message, populate actionHint.
            // Jackson message shape:
            //   "Cannot deserialize value of type `com.cretas.aims.entity.Department`
            //    from String \"FINANCE\": ... (through reference chain: ...["department"])"
            String expectedType = "";
            String badValue = "";
            String fieldName = "";
            java.util.regex.Matcher typeM = java.util.regex.Pattern
                .compile("type `([^`]+)`").matcher(msg);
            if (typeM.find()) {
                String fq = typeM.group(1);
                expectedType = fq.substring(fq.lastIndexOf('.') + 1); // simple class name
            }
            java.util.regex.Matcher valM = java.util.regex.Pattern
                .compile("from String \"([^\"]+)\"").matcher(msg);
            if (valM.find()) badValue = valM.group(1);
            java.util.regex.Matcher fieldM = java.util.regex.Pattern
                .compile("\\[\"([^\"]+)\"\\](?!.*\\[\")").matcher(msg); // last [""] in chain
            if (fieldM.find()) fieldName = fieldM.group(1);
            StringBuilder userMsg = new StringBuilder("字段");
            if (!fieldName.isEmpty()) userMsg.append(" '").append(fieldName).append("' ");
            userMsg.append("类型不正确");
            if (!expectedType.isEmpty()) userMsg.append("，期望 ").append(expectedType);
            if (!badValue.isEmpty()) userMsg.append("，收到 \"").append(badValue).append("\"");
            String hint = fieldName.isEmpty()
                    ? "请检查表单字段类型后重新提交"
                    : "请检查「" + fieldName + "」字段的值";
            return ApiResponse.errorWithHint(400, userMsg.toString(), hint, "error",
                    fieldName.isEmpty() ? null : fieldName);
        }
        return ApiResponse.error(400, "请求格式不正确，请检查JSON格式");
    }

    /**
     * 处理请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<?> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {}", e.getMethod());
        return ApiResponse.error(405, "不支持的请求方法: " + e.getMethod());
    }

    /**
     * 处理 Content-Type 不匹配. 客户端发错类型(例 text/plain 发给 application/json endpoint)
     * 时 Spring 抛 HttpMediaTypeNotSupportedException, 无 handler 落到 generic RuntimeException
     * 兜底返回 500. 这里映射到 415 + 明确提示支持的 Content-Type.
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResponse<?> handleHttpMediaTypeNotSupportedException(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        String supported = e.getSupportedMediaTypes().isEmpty() ? "application/json"
                : e.getSupportedMediaTypes().toString();
        String actual = e.getContentType() != null ? e.getContentType().toString() : "(empty)";
        log.warn("不支持的 Content-Type: 实际={}, 期望={}", actual, supported);
        return ApiResponse.error(415, "不支持的 Content-Type (" + actual + "), 请改用: " + supported);
    }

    /**
     * 处理路由未找到
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("请求路径不存在: {}", e.getRequestURL());
        return ApiResponse.error(404, "请求的接口不存在");
    }

    /**
     * 处理 Spring 6 ResourceHttpRequestHandler 抛出的 NoResourceFoundException
     *
     * 该异常发生在 DispatcherServlet 路由时没有匹配任何 controller,
     * 请求被 fall-through 到静态资源 handler, 然后静态资源也不存在。
     *
     * 路径分级日志策略:
     *   - /api/ 开头    → WARN  (真实的前端 bug, 客户端在调错误的 URL, 需要排查)
     *   - 其他所有路径  → DEBUG (扫描器噪音: /.git/config / /wp-admin / /favicon.ico / /)
     *
     * 不再用 ERROR 级别记录 404, 避免 error.log 被扫描器流量淹没。真实的
     * 前端 bug 仍然在 WARN 级别可见, 不会被掩盖。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleNoResourceFoundException(
            NoResourceFoundException e, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/")) {
            log.warn("API 路径无 handler: method={}, path={}", request.getMethod(), path);
        } else {
            log.debug("Non-API 404 (scanner/probe): method={}, path={}", request.getMethod(), path);
        }
        return ApiResponse.error(404, "请求的资源不存在");
    }

    // ==================== 空指针和运行时异常 - 需严格脱敏 ====================

    /**
     * 处理空指针异常 - 严格脱敏
     */
    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleNullPointerException(NullPointerException e) {
        String traceId = generateTraceId();
        log.error("[{}] 空指针异常", traceId, e);
        return buildSanitizedResponse(ErrorCode.SYSTEM_ERROR, traceId);
    }

    /**
     * 处理类型转换异常 - 严格脱敏
     */
    @ExceptionHandler(ClassCastException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleClassCastException(ClassCastException e) {
        String traceId = generateTraceId();
        log.error("[{}] 类型转换异常: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.SYSTEM_ERROR, traceId);
    }

    /**
     * 处理数组越界异常 - 严格脱敏
     */
    @ExceptionHandler(ArrayIndexOutOfBoundsException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleArrayIndexOutOfBoundsException(ArrayIndexOutOfBoundsException e) {
        String traceId = generateTraceId();
        log.error("[{}] 数组越界异常: {}", traceId, e.getMessage(), e);
        return buildSanitizedResponse(ErrorCode.SYSTEM_ERROR, traceId);
    }

    /**
     * 处理数字格式异常
     */
    @ExceptionHandler(NumberFormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleNumberFormatException(NumberFormatException e) {
        log.warn("数字格式异常: {}", e.getMessage());
        return ApiResponse.error(400, "数字格式不正确");
    }

    // ==================== 客户端中断 ====================

    /**
     * Multipart 上传异常 — 区分客户端中断 vs 真实解析失败.
     *
     * <p>Tomcat 在客户端提前断开上传 (用户取消/切后台/网络抖动) 时会抛
     * {@link ClientAbortException} → {@link EOFException}, Spring 包装为
     * {@link MultipartException}. 不该按 ERROR 刷警报 (服务器行为正常,
     * 就是对方没把 request body 发完).
     *
     * <p>真实的 multipart 解析失败 (错误边界/格式错乱) 仍按 WARN (客户端 bug,
     * 但不是我们崩了). ERROR 级别保留给真正的服务端崩溃.
     *
     * @since 2026-04-15
     */
    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMultipartException(MultipartException e) {
        if (isClientAbort(e)) {
            log.warn("客户端中断上传 (EOF/ClientAbort): {}", e.getMessage());
            return ApiResponse.error(400, "上传被中断");
        }
        log.warn("Multipart 请求解析失败: {}", e.getMessage());
        return ApiResponse.error(400, "上传格式错误");
    }

    /**
     * 单独兜底 ClientAbortException (非 multipart 路径, e.g. SSE/长连接断开).
     */
    @ExceptionHandler(ClientAbortException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleClientAbort(ClientAbortException e) {
        log.warn("客户端主动断开连接: {}", e.getMessage());
        // 客户端已断, 返回什么都写不过去, 框架会 silently fail — 给个占位.
        return ApiResponse.error(499, "client aborted");
    }

    /**
     * Spring 6 AsyncRequestNotUsableException — 客户端在 async 完成阶段断开,
     * ServletOutputStream 写入失败 (ClosedChannelException / Broken pipe).
     * 这类异常绕过了 @ExceptionHandler(RuntimeException.class) 的兜底 (Spring
     * async resolver 不走 @ExceptionHandler chain), 需单独 handler 避免刷 ERROR.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    @ResponseStatus(HttpStatus.OK)
    public void handleAsyncRequestNotUsable(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        // SSE (text/event-stream) 不支持 ApiResponse JSON 序列化, 返 void 不写 body 避免
        // HttpMessageNotWritableException 二次噪音. 客户端已断连, 写什么也没人读.
        log.warn("Async 请求通道已关闭 (客户端断开): {}", e.getMessage());
    }

    /**
     * Unwrap 异常链判断 root cause 是不是 client-abort / EOF.
     * (Tomcat 版本差异: 可能嵌套 2-3 层, e.g. MultipartException →
     * IOFileUploadException → ClientAbortException → EOFException)
     */
    private boolean isClientAbort(Throwable t) {
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth < 10) {
            if (cursor instanceof ClientAbortException || cursor instanceof EOFException) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;  // self-cycle 保护
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    // ==================== 兜底异常处理 ====================

    /**
     * 处理其他 RuntimeException - 需脱敏
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleRuntimeException(RuntimeException e) {
        // 即使在兜底也防护一下 client-abort, 以防 ClientAbortException 被 wrap
        // 在非 MultipartException 的 RuntimeException 里 (e.g. AsyncListener 回调)
        if (isClientAbort(e)) {
            log.warn("客户端中断 (包装于 RuntimeException): {}", e.getMessage());
            return ApiResponse.error(499, "client aborted");
        }
        String traceId = generateTraceId();
        log.error("[{}] 运行时异常: {}", traceId, e.getClass().getName(), e);
        return buildSanitizedResponse(ErrorCode.SYSTEM_ERROR, traceId);
    }

    /**
     * 处理所有其他异常 - 严格脱敏
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleException(Exception e) {
        // 兜底也查一次 client-abort 链, 以防新的 async 包装类绕过上面的
        // 专用 handler (Spring 6 迭代里这类 wrapper 还会增加).
        if (isClientAbort(e)) {
            log.warn("客户端中断 (未预期的包装类): {}", e.getClass().getSimpleName(), e.getMessage());
            return ApiResponse.error(499, "client aborted");
        }
        String traceId = generateTraceId();
        log.error("[{}] 未捕获异常: {}", traceId, e.getClass().getName(), e);
        return buildSanitizedResponse(ErrorCode.SYSTEM_ERROR, traceId);
    }
}

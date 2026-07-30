package com.cretas.aims.exception;

/**
 * 业务异常基类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class BusinessException extends RuntimeException {
    private Integer code;
    /**
     * Semantic error code for frontend branching (e.g. "OVER_RECEIPT", "RULE_VIOLATION").
     * When present, GlobalExceptionHandler propagates it to ApiResponse.errorCode field.
     */
    private String errorCode;
    /**
     * UX: optional hint text telling the user what to do next.
     * Rendered as ElNotification with action button on frontend.
     * Example: "请先点'分配批次'再发货" / "请先处理或撤销已有的发票申请".
     */
    private String actionHint;
    /**
     * UX severity: BLOCKING → ElMessageBox (modal, must click OK),
     * NORMAL (default) → ElMessage (sticky toast, 方案 A).
     * Use BLOCKING for errors users MUST acknowledge (permission
     * denied, destructive-confirm, critical business-rule violation).
     */
    private String severity;
    /**
     * UX: optional DOM target label for pulse-hint animation.
     * Example: "分配批次" → frontend finds button labeled "分配批次"
     * and applies pulse ring for 5s as visual breadcrumb.
     */
    private String hintTarget;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 400;
    }

    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }

    public String getErrorCode() { return errorCode; }
    public String getActionHint() { return actionHint; }
    public String getSeverity() { return severity; }
    public String getHintTarget() { return hintTarget; }

    /** Fluent builder — chain after constructor. */
    public BusinessException withCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    public BusinessException withHint(String actionHint) {
        this.actionHint = actionHint;
        return this;
    }

    public BusinessException withSeverity(String severity) {
        this.severity = severity;
        return this;
    }

    public BusinessException withHintTarget(String hintTarget) {
        this.hintTarget = hintTarget;
        return this;
    }
}

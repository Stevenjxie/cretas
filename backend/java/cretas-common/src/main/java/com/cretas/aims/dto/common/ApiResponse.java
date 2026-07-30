package com.cretas.aims.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一API响应对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一API响应对象")
public class ApiResponse<T> implements Serializable {

    @Schema(description = "响应状态码", example = "200")
    private Integer code;

    @Schema(description = "响应消息", example = "操作成功")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "响应时间戳")
    private LocalDateTime timestamp;

    @Schema(description = "请求是否成功", example = "true")
    private Boolean success;

    // UX 2026-04-18 进阶: optional fields rendered by frontend interceptor
    @Schema(description = "行动提示 (用户下一步建议)")
    private String actionHint;

    @Schema(description = "错误严重度 BLOCKING 用 ElMessageBox 阻塞; 空/NORMAL 用 ElMessage")
    private String severity;

    @Schema(description = "UI pulse 目标 button label (提示用户点该按钮)")
    private String hintTarget;

    // Phase 4a (2026-05-19): String-valued error category for FE branching.
    // `code` is Integer HTTP status (400/403/...). errorCode is semantic ("RULE_VIOLATION",
    // "DUPLICATE", "WORKFLOW_NOT_FOUND") so FE can branch without parsing message.
    @Schema(description = "语义错误码 (FE 用于分支处理, 如 RULE_VIOLATION/DUPLICATE)")
    private String errorCode;

    // 成功响应
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return success("操作成功", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(200);
        response.setMessage(message);
        response.setData(data);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(true);
        return response;
    }

    /**
     * 成功响应 - 仅消息，无数据 (用于 Void 返回类型)
     */
    public static ApiResponse<Void> successMessage(String message) {
        ApiResponse<Void> response = new ApiResponse<>();
        response.setCode(200);
        response.setMessage(message);
        response.setData(null);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(true);
        return response;
    }

    // 失败响应
    public static <T> ApiResponse<T> error(String message) {
        return error(400, message);
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setData(null);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(false);
        return response;
    }

    // 自定义响应
    public static <T> ApiResponse<T> of(Integer code, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setData(data);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(code >= 200 && code < 300);
        return response;
    }

    /**
     * UX: builder for error with actionHint/severity/hintTarget.
     * Use when you want the frontend to render a notification-with-button
     * or blocking modal, not just a toast.
     */
    public static <T> ApiResponse<T> errorWithHint(Integer code, String message,
                                                   String actionHint,
                                                   String severity,
                                                   String hintTarget) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setData(null);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(false);
        response.setActionHint(actionHint);
        response.setSeverity(severity);
        response.setHintTarget(hintTarget);
        return response;
    }

    public String getActionHint() { return actionHint; }
    public void setActionHint(String actionHint) { this.actionHint = actionHint; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getHintTarget() { return hintTarget; }
    public void setHintTarget(String hintTarget) { this.hintTarget = hintTarget; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    /**
     * Phase 4a (2026-05-19): error response with semantic errorCode for FE branching.
     * Use when FE needs to differentiate error category (e.g. RULE_VIOLATION vs generic 400).
     */
    public static <T> ApiResponse<T> errorWithCode(Integer code, String errorCode,
                                                   String message, String actionHint,
                                                   String severity) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setErrorCode(errorCode);
        response.setMessage(message);
        response.setData(null);
        response.setTimestamp(LocalDateTime.now());
        response.setSuccess(false);
        response.setActionHint(actionHint);
        response.setSeverity(severity);
        return response;
    }

    // Manual getters and setters (Lombok @Data not working)
    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }
}
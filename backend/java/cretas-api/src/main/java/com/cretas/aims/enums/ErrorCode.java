package com.cretas.aims.enums;

/**
 * 统一错误码枚举
 *
 * 用于错误信息脱敏，确保敏感信息不暴露给用户。
 * 错误码格式：分类前缀 + 三位数字
 * - SYS: 系统级错误 (500-599)
 * - AUTH: 认证授权错误 (401, 403)
 * - BIZ: 业务逻辑错误 (400-499)
 * - DATA: 数据相关错误 (400-499)
 * - AI: AI服务相关错误 (500-599)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-23
 */
public enum ErrorCode {

    // ==================== 系统级错误 (SYS_xxx) ====================

    /** 系统内部错误 - 通用 */
    SYSTEM_ERROR("SYS_001", 500, "系统处理异常，请稍后重试"),

    /** 服务不可用 */
    SERVICE_UNAVAILABLE("SYS_002", 503, "服务暂时不可用，请稍后重试"),

    /** 服务超时 */
    SERVICE_TIMEOUT("SYS_003", 504, "服务响应超时，请稍后重试"),

    /** 配置错误 */
    CONFIG_ERROR("SYS_004", 500, "系统配置异常，请联系管理员"),

    /** 外部服务调用失败 */
    EXTERNAL_SERVICE_ERROR("SYS_005", 502, "外部服务调用失败，请稍后重试"),

    // ==================== 认证授权错误 (AUTH_xxx) ====================

    /** 未认证 */
    UNAUTHORIZED("AUTH_001", 401, "请先登录"),

    /** Token无效或过期 */
    TOKEN_INVALID("AUTH_002", 401, "登录已过期，请重新登录"),

    /** 权限不足 */
    FORBIDDEN("AUTH_003", 403, "您没有执行此操作的权限"),

    /** 账号被禁用 */
    ACCOUNT_DISABLED("AUTH_004", 403, "账号已被禁用，请联系管理员"),

    // ==================== 业务逻辑错误 (BIZ_xxx) ====================

    /** 参数验证失败 */
    PARAM_INVALID("BIZ_001", 400, "请检查输入参数"),

    /** 参数缺失 */
    PARAM_MISSING("BIZ_002", 400, "缺少必要参数"),

    /** 参数格式错误 */
    PARAM_FORMAT_ERROR("BIZ_003", 400, "参数格式不正确"),

    /** 业务规则违反 */
    BUSINESS_RULE_VIOLATION("BIZ_004", 400, "操作不符合业务规则"),

    /** 资源不存在 */
    RESOURCE_NOT_FOUND("BIZ_005", 404, "请求的资源不存在"),

    /** 资源已存在（重复） */
    RESOURCE_ALREADY_EXISTS("BIZ_006", 409, "资源已存在"),

    /** 状态冲突 */
    STATE_CONFLICT("BIZ_007", 409, "操作与当前状态冲突"),

    /** 并发冲突（乐观锁） */
    CONCURRENT_CONFLICT("BIZ_008", 409, "数据已被其他用户修改，请刷新后重试"),

    /** 操作频率限制 */
    RATE_LIMITED("BIZ_009", 429, "操作过于频繁，请稍后重试"),

    // ==================== 数据相关错误 (DATA_xxx) ====================

    /** 数据库连接错误 */
    DATABASE_ERROR("DATA_001", 500, "数据服务暂时不可用"),

    /** 数据完整性错误 */
    DATA_INTEGRITY_ERROR("DATA_002", 500, "数据处理异常"),

    /** 数据查询错误 */
    DATA_QUERY_ERROR("DATA_003", 500, "数据查询失败"),

    /** 数据写入错误 */
    DATA_WRITE_ERROR("DATA_004", 500, "数据保存失败"),

    // ==================== AI服务错误 (AI_xxx) ====================

    /** AI服务不可用 */
    AI_SERVICE_UNAVAILABLE("AI_001", 503, "AI服务暂时不可用"),

    /** AI服务超时 */
    AI_SERVICE_TIMEOUT("AI_002", 504, "AI服务响应超时"),

    /** AI处理失败 */
    AI_PROCESSING_ERROR("AI_003", 500, "智能处理失败，请稍后重试"),

    /** 意图识别失败 */
    AI_INTENT_ERROR("AI_004", 500, "无法理解您的请求，请换一种方式表达"),

    /** 工具执行失败 */
    AI_TOOL_ERROR("AI_005", 500, "操作执行失败，请稍后重试");

    private final String code;
    private final int httpStatus;
    private final String userMessage;

    ErrorCode(String code, int httpStatus, String userMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.userMessage = userMessage;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getUserMessage() {
        return userMessage;
    }

    /**
     * 根据 code 查找 ErrorCode
     */
    public static ErrorCode fromCode(String code) {
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return SYSTEM_ERROR;
    }
}

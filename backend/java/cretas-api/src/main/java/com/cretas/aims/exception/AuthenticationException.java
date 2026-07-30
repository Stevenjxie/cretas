package com.cretas.aims.exception;

/**
 * 认证异常
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class AuthenticationException extends BusinessException {
    public AuthenticationException(String message) {
        super(401, message);
    }
    public AuthenticationException(String message, Throwable cause) {
        super(401, message, cause);
}
}

package com.cretas.aims.exception;

/**
 * 授权异常
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class AuthorizationException extends BusinessException {
    public AuthorizationException(String message) {
        super(403, message);
    }
    public AuthorizationException(String message, Throwable cause) {
        super(403, message, cause);
}
}

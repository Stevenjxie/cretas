package com.cretas.aims.exception;

/**
 * 资源未找到异常
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(404, message);
    }
    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(404, String.format("%s not found with %s : '%s'", resourceName, fieldName, fieldValue));
}
}

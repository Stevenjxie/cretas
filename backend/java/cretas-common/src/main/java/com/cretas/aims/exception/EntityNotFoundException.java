package com.cretas.aims.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 实体不存在异常
 * 当请求的资源不存在时抛出，返回 HTTP 404
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entityName, String id) {
        super(String.format("%s not found with id: %s", entityName, id));
    }

    public EntityNotFoundException(String entityName, Long id) {
        super(String.format("%s not found with id: %s", entityName, id));
    }

    public EntityNotFoundException(String entityName, Object id) {
        super(String.format("%s not found with id: %s", entityName, id));
    }

    public EntityNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.cretas.aims.exception;

/**
 * 限流超限异常
 *
 * 当请求超过 @RateLimit 配置的阈值时抛出。
 * 由 GlobalExceptionHandler 捕获并返回 HTTP 429 Too Many Requests。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-28
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}

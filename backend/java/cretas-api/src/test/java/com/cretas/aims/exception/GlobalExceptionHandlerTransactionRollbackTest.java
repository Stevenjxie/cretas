package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #929 族 backlog #3 (2026-06-16) — doomed-tx 防御网。
 *
 * <p>验证 {@link GlobalExceptionHandler#handleTransactionRollbackException(RuntimeException)}
 * 把 {@link UnexpectedRollbackException} / {@link TransactionSystemException} 映射为干净的
 * 409 (而非裸 500), 并带 actionHint + severity, 不泄漏内部细节。
 *
 * <p>背景: 当某 {@code @Transactional} 方法在 DB 写之后才抛业务校验异常, Spring 把事务标记
 * rollback-only, proxy commit 阶段抛 {@code UnexpectedRollbackException}。没有专用 handler 时
 * 它落到 {@code handleRuntimeException} → 500。本 handler 把它兜成 409 + 提示重试。
 */
@DisplayName("#929 doomed-tx 防御网 — UnexpectedRollbackException/TransactionSystemException → 409")
class GlobalExceptionHandlerTransactionRollbackTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("UnexpectedRollbackException → 409 + actionHint + severity warning")
    void unexpectedRollback_mapsTo_409_envelope() {
        UnexpectedRollbackException ex = new UnexpectedRollbackException(
                "Transaction silently rolled back because it has been marked as rollback-only");

        ApiResponse<?> body = handler.handleTransactionRollbackException(ex);

        assertEquals((Integer) 409, body.getCode(), "doom 应映射为 409 而非 500");
        assertEquals(Boolean.FALSE, body.getSuccess());
        assertNotNull(body.getMessage(), "user-facing message present");
        assertTrue(body.getMessage().contains("请刷新后重试"),
                "message 含 next action 提示: " + body.getMessage());
        assertNotNull(body.getActionHint(), "actionHint 非空 (fool-proof rule 5)");
        assertEquals("warning", body.getSeverity(),
                "severity=warning → FE sticky toast");
    }

    @Test
    @DisplayName("TransactionSystemException → 同样映射为 409")
    void transactionSystemException_mapsTo_409() {
        TransactionSystemException ex = new TransactionSystemException(
                "Could not commit JPA transaction");

        ApiResponse<?> body = handler.handleTransactionRollbackException(ex);

        assertEquals((Integer) 409, body.getCode());
        assertEquals(Boolean.FALSE, body.getSuccess());
        assertTrue(body.getMessage().contains("请刷新后重试"));
    }

    @Test
    @DisplayName("响应体不泄漏 Spring/事务内部细节")
    void responseBody_does_not_leak_internals() {
        UnexpectedRollbackException ex = new UnexpectedRollbackException(
                "Transaction silently rolled back ... at org.springframework.transaction ...");

        ApiResponse<?> body = handler.handleTransactionRollbackException(ex);

        String msg = body.getMessage().toLowerCase();
        assertFalse(msg.contains("rollback-only"), "不泄漏 rollback-only 内部术语");
        assertFalse(msg.contains("transaction"), "不泄漏 transaction 内部术语");
        assertFalse(msg.contains("org.springframework"), "不泄漏 Java 包路径");
    }
}

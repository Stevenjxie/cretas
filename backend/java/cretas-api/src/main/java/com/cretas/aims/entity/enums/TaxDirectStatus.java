package com.cretas.aims.entity.enums;

/**
 * 数电票税局直连开票状态 (F-TAX-DIRECT-1 Sprint 5 spike).
 *
 * <p>State machine:
 * <pre>
 *   NOT_REQUESTED  (default — 用户未启用直连)
 *        │
 *        ▼  applyForDirect() 调用
 *   REQUESTED      (已提交到 Provider, 等税局异步出票)
 *        │
 *        ├──→  SUCCESS   (税局出票成功, PDF 已回写)
 *        │
 *        └──→  FAILED    (税局拒绝 / Provider 失败, error_message 含原因)
 * </pre>
 *
 * <p>失败后可重试:retry 会重置 status 回 REQUESTED 并清空 error_message。
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.3
 */
public enum TaxDirectStatus {
    NOT_REQUESTED,
    REQUESTED,
    SUCCESS,
    FAILED
}

package com.cretas.aims.logistics.dto.plan;

import lombok.Data;

/**
 * {@code POST /plans/{planId}/trips/{tripId}/confirm} 和
 * {@code POST /plans/{planId}/confirm} 共用请求体 — 乐观锁 version（trip 或 plan 各自的版本）。
 */
@Data
public class ConfirmRequest {
    private Long version;
}

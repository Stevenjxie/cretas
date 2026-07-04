package com.cretas.aims.dto.finance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量过账 — 单张凭证的过账结果 (follow-up to #1228 finding).
 *
 * <p>批量过账不是"全有全无"事务 — 每张凭证独立校验 + 独立事务 (REQUIRES_NEW), 一张不平/已锁期间
 * 的凭证过账失败, 不影响其余凭证。前端据此展示 "N 成功, M 失败 + 原因" (fool-proof Rule 1: 预先
 * 显示结果, 不是笼统一句"部分失败")。
 *
 * @since 2026-07-04
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VoucherBatchPostResultDTO {

    /** 凭证 id (回显, 前端据此匹配行). */
    private String voucherId;

    /** true = 已成功过账 (含幂等 skip 场景), false = 失败. */
    private boolean success;

    /** true = 幂等跳过 (已是 POSTED, 未重复过账动作). */
    private boolean skipped;

    /** 过账成功/跳过时回显凭证号, 便于前端展示. */
    private String voucherNumber;

    /** 结果说明 — 成功 "过账成功" / 跳过 "已过账, 跳过" / 失败具体原因 (不平/期间已锁/凭证不存在等). */
    private String message;
}

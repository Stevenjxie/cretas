package com.cretas.aims.entity.enums;

import java.util.Map;
import java.util.Set;

/**
 * 业务单凭证生成状态. 标在 SalesOrder / PurchaseOrder / ProductionPlan /
 * ReturnOrder / InternalTransfer / WastageRecord / PayrollRecord 等 7 类业务单上.
 *
 * 状态机:
 * <pre>
 *   UNCREATED ─→ PENDING ─→ CREATED  (终态; voidVoucher 走业务流不自动回退)
 *                       └→ FAILED ─→ PENDING  (retry loop)
 * </pre>
 */
public enum VoucherFlag {
    /** 未生成凭证 (初始) */
    UNCREATED,
    /** 凭证生成中 (event listener 触发, async) */
    PENDING,
    /** 凭证已生成 */
    CREATED,
    /** 凭证生成失败 (可 retry) */
    FAILED;

    private static final Map<VoucherFlag, Set<VoucherFlag>> ALLOWED_TRANSITIONS = Map.of(
            UNCREATED, Set.of(PENDING),
            PENDING, Set.of(CREATED, FAILED),
            FAILED, Set.of(PENDING),
            CREATED, Set.of()
    );

    public static VoucherFlag initial() {
        return UNCREATED;
    }

    public boolean canTransitionTo(VoucherFlag target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}

package com.cretas.aims.entity.enums;

/**
 * 凭证自身状态. 与 {@link VoucherFlag} 不同 — VoucherFlag 是业务单上的标记,
 * VoucherStatus 是 Voucher entity 自己的生命周期.
 */
public enum VoucherStatus {
    /** 草稿 — generator 刚生成, 未过账 */
    DRAFT,
    /** 已过账 — 财务审核通过 */
    POSTED,
    /** 作废 — voidVoucher 后状态 (仅 DRAFT 凭证直接作废) */
    VOID,
    /**
     * 已冲销 — 已过账 (POSTED) 凭证经红字冲销后状态 (金蝶规范).
     *
     * <p>已过账凭证不可直接 VOID 抹掉 (已进账簿), 应生成一张红字冲销凭证
     * (借贷方向互换) 关联原凭证, 原凭证标记为 REVERSED 而非物理删除, 账簿可追溯。
     */
    REVERSED
}

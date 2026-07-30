package com.cretas.aims.entity.enums;

/**
 * 付款方式
 */
public enum PaymentMethod {
    CASH("现金"),
    BANK_TRANSFER("银行转账"),
    WECHAT("微信支付"),
    ALIPAY("支付宝"),
    CHECK("支票"),
    CREDIT("赊账/挂账"),
    POS("POS刷卡"),
    OTHER("其他");

    private final String displayName;

    PaymentMethod(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}

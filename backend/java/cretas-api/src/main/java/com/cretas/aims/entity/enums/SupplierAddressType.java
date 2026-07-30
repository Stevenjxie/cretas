package com.cretas.aims.entity.enums;

/**
 * 供应商地址类型（客户反馈 F006 / LIUSHANMEN: 一个供应商要维护多个地址）。
 *
 * <p>注册地 / 发货地 / 开票地 常常不同 —— 采购单打印取的是发货地,
 * 开票资料取的是开票地, 混在一个 {@code suppliers.address} 里必然出错。
 *
 * <p>⚠️ 加值必须同步 {@code supplier_addresses.ck_supplier_address_type} 白名单。
 */
public enum SupplierAddressType {
    BUSINESS("注册/办公地址"),
    SHIPPING("发货地址"),
    BILLING("开票地址"),
    WAREHOUSE("仓库地址"),
    OTHER("其他");

    private final String displayName;

    SupplierAddressType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

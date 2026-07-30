package com.cretas.aims.entity.enums;

/**
 * 供应商联系人类型（客户反馈 F006 / LIUSHANMEN: 一个供应商要维护多个联系人）。
 *
 * <p>做成枚举而非自由文本, 是防呆规范 Rule 3「自由文本改约束选择」——
 * 采购员填"张三 业务"和"张三 销售"会让同一角色分不清。
 *
 * <p>⚠️ 加值必须同步 {@code supplier_contacts.ck_supplier_contact_type} 白名单,
 * 否则 PG 直接拒绝写入。{@code EnumCheckConstraintDriftTest} 会挡。
 */
public enum SupplierContactType {
    OWNER("负责人"),
    SALES("业务对接"),
    FINANCE("财务对账"),
    LOGISTICS("送货/物流"),
    AFTER_SALES("售后"),
    OTHER("其他");

    private final String displayName;

    SupplierContactType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

package com.cretas.aims.entity.enums;

/**
 * SP11: 凭证导出目标财务系统.
 * 决定 Excel 文件的列名映射（客户可通过 VoucherExportConfig 进一步自定义）.
 */
public enum VoucherTargetSystem {
    KINGDEE("金蝶"),
    YONYOU("用友"),
    CUSTOM("自定义"),
    KINGDEE_YXSKY("金蝶云星空"),
    /** 金蝶 KIS专业版/标准版/K3 — 标准格式凭证 Excel 引入模板 (列集与云星空不同, 见 VoucherExportServiceImpl). */
    KINGDEE_KIS("金蝶KIS/K3");

    private final String displayName;

    VoucherTargetSystem(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

package com.cretas.aims.entity.enums;

public enum ReturnOrderStatus {
    DRAFT("草稿", "退货单草稿，尚未提交"),
    SUBMITTED("已提交", "已提交等待审批"),
    APPROVED("已审批", "业务审批通过，等待财务审批"),
    // 六扇门 Tier0 #16 (catalog 行2399-2416): 退货跟钱有关，完成/出货前必须先财务审批。
    // 状态机: APPROVED → FINANCE_APPROVED → COMPLETED。未经财务审批不得完成。
    FINANCE_APPROVED("财务已审", "财务审批通过，可交仓管出货完成"),
    REJECTED("已驳回", "审批驳回"),
    PROCESSING("处理中", "退货正在处理"),
    COMPLETED("已完成", "退货流程已完成");

    private final String displayName;
    private final String description;

    ReturnOrderStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

package com.cretas.aims.entity.enums;

/**
 * 销售订单状态枚举
 * 通用：工厂出货 = 餐饮外卖/堂食
 */
public enum SalesOrderStatus {
    DRAFT("草稿", "销售订单草稿"),
    CONFIRMED("已确认", "客户已确认订单"),
    PENDING_FINANCE_REVIEW("待财务审核", "已提交财务审核，等待审批"),
    FINANCE_APPROVED("财务已批准", "财务审核通过，可触发生产"),
    FINANCE_REJECTED("财务已驳回", "财务审核不通过，需修改后重新提交"),
    PROCESSING("处理中", "拣货/备货中"),
    PARTIAL_DELIVERED("部分发货", "部分商品已发货"),
    COMPLETED("已完成", "全部发货完成"),
    CANCELLED("已取消", "订单已取消");

    private final String displayName;
    private final String description;

    SalesOrderStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

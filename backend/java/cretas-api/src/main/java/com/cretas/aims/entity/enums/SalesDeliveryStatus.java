package com.cretas.aims.entity.enums;

/**
 * 发货/出库状态枚举
 *
 * Issue #740 (六扇门 May10 会议):
 *   销售创建发货单 (DRAFT / PENDING_WAREHOUSE_CONFIRM, NOT 扣库存)
 *     → 仓库确认实发数量 (POST /warehouse/deliveries/{id}/confirm — 扣库存)
 *     → SHIPPED → DELIVERED
 *
 * 状态流转:
 *   DRAFT                       — 销售刚创建, 待仓库接手 (兼容旧逻辑)
 *   PENDING_WAREHOUSE_CONFIRM   — 销售已提交, 等待仓库确认实发数量
 *   PICKED                      — 仓库已拣货 (旧字段, 保留兼容)
 *   SHIPPED                     — 仓库确认扣库存, 已发货
 *   DELIVERED                   — 客户签收
 *   RETURNED                    — 退回
 */
public enum SalesDeliveryStatus {
    DRAFT("草稿", "发货单草稿"),
    PENDING_WAREHOUSE_CONFIRM("待仓库确认", "销售已提交, 等待仓库确认实发数量"),
    PICKED("已拣货", "已完成拣货/备货"),
    PENDING_SPLIT("待分批", "母发货单等待创建子发运单"),
    PARTIALLY_SCHEDULED("部分已安排", "母发货单仅部分数量已生成子发运单"),
    FULLY_SCHEDULED("已全部安排", "母发货单计划数量已全部生成子发运单"),
    PARTIALLY_SHIPPED("部分已发货", "母发货单部分子发运单已确认发货"),
    SHIPPED("已发货", "已交付物流/已出门店"),
    DELIVERED("已签收", "客户已签收确认"),
    CANCELLED("已取消", "发货安排已取消并释放占用"),
    RETURNED("已退回", "货物退回");

    private final String displayName;
    private final String description;

    SalesDeliveryStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

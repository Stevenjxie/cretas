package com.cretas.aims.entity.enums;

/**
 * 核价单状态枚举 (P-NUCLEAR-1: 28-Backlog item #30)
 *
 * <p>询价 → 核价 → 采购 三阶段 pipeline 状态:
 * <ul>
 *   <li>DRAFT: 草稿, buyer 编辑中</li>
 *   <li>INQUIRING: 已发出询价, 等待供应商报价</li>
 *   <li>QUOTED: 已收到 ≥1 个供应商报价, buyer 可对比选择</li>
 *   <li>SELECTED: 已选定中标供应商, 等待转化为采购单</li>
 *   <li>CONVERTED: 已转化为采购单 (selectAndConvertToPurchaseOrder 完成, 不可重复)</li>
 *   <li>CANCELLED: 已取消</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
public enum InquiryQuoteStatus {
    DRAFT("草稿", "核价单草稿, 编辑中"),
    INQUIRING("询价中", "已发出询价, 等待供应商报价"),
    QUOTED("已报价", "已收到供应商报价, 待对比选择"),
    SELECTED("已选定", "已选定中标供应商, 待转采购单"),
    CONVERTED("已转采购", "已生成采购订单 (不可逆)"),
    CANCELLED("已取消", "核价流程已取消");

    private final String displayName;
    private final String description;

    InquiryQuoteStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

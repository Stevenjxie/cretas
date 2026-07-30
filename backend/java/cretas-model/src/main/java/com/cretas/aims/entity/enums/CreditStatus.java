package com.cretas.aims.entity.enums;

/**
 * 客户信用状态 (P1 #23 S-CREDIT-1, 2026-05-17).
 *
 * <p>3 阶段, 由 CustomerCreditCheckService 计算或由销售管理员手工设置:
 * <ul>
 *   <li>NORMAL: 信用正常, 销售订单创建无阻塞</li>
 *   <li>WARNING: 已用 ≥ 信用额度 80% 或有逾期账款, 创建 SO 时 soft warn 提示销售但不阻塞</li>
 *   <li>SUSPENDED: 销售管理员手工冻结 (恶意逾期/合规问题), 创建 SO 时硬阻塞 (HTTP 409 + R5 跳转 CTA)</li>
 * </ul>
 *
 * <p>背景: 28-Backlog 客户信用管理 (line 101) — 大客户场景下需要信用额度 / 信用账期 / 超期警告.
 * 与 Customer.customerStatus (LIFECYCLE: LEAD/RECURRING/...) 互补, 后者是销售漏斗状态,
 * 前者是财务风控状态.
 */
public enum CreditStatus {
    NORMAL("正常", "信用正常, 新单不限制"),
    WARNING("预警", "已接近/超过信用额度, 创建 SO 时 soft warn"),
    SUSPENDED("冻结", "销售管理员手工冻结, 创建 SO 硬阻塞");

    private final String displayName;
    private final String description;

    CreditStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

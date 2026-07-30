package com.cretas.aims.entity.enums;

/**
 * 客户生命周期状态 (Sprint 4 W2 S-CRM-FULL-1)
 *
 * 11 阶段覆盖销售漏斗 + 客户存续状态。
 * LEAD → INITIAL_CONTACT → SAMPLE_SENT → QUOTING → NEGOTIATING → SIGNING → RECURRING
 * 终止: INACTIVE / LOST / BLACKLIST. 回血: RECOVERED.
 */
public enum CustomerStatus {
    LEAD("潜在客户", "尚未首次接洽的线索"),
    INITIAL_CONTACT("初步接触", "已建立首次沟通"),
    SAMPLE_SENT("样品已寄", "样品/打样已寄出, 待反馈"),
    QUOTING("报价中", "已提交报价, 等待客户决策"),
    NEGOTIATING("商务谈判", "价格/条款谈判进行中"),
    SIGNING("签约中", "合同流程进行中"),
    RECURRING("成交复购", "已稳定下单复购的客户"),
    INACTIVE("沉睡客户", "长期无交易但未流失"),
    LOST("已流失", "确定不再合作"),
    BLACKLIST("黑名单", "禁止合作 (信用/合规问题)"),
    RECOVERED("回流复活", "曾流失后被重新激活");

    private final String displayName;
    private final String description;

    CustomerStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

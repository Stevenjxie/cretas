package com.cretas.aims.entity.enums;

/**
 * 客户来源渠道 (Sprint 4 W2 S-CRM-FULL-1)
 *
 * 用于销售漏斗归因分析 — 哪个渠道带来的客户最终成交率/客单价更高。
 */
public enum CustomerSource {
    EXHIBITION("展会", "线下展会获客"),
    REFERRAL("客户介绍", "老客户/合作伙伴推荐"),
    WEBSITE("官网", "企业官网咨询"),
    SEARCH_ENGINE("搜索引擎", "百度/Google 搜索导入"),
    WECHAT("微信", "公众号/朋友圈/社群"),
    PHONE("电话", "电话主动联系"),
    COLD_VISIT("陌拜", "扫楼/陌生拜访"),
    PLATFORM("平台", "B2B 电商平台 (阿里/京东/拼多多等)"),
    PARTNER("合作伙伴", "战略合作伙伴渠道"),
    REPEAT_PURCHASE("老客户复购", "已有客户的新增订单"),
    OTHER("其他", "其他未分类来源");

    private final String displayName;
    private final String description;

    CustomerSource(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

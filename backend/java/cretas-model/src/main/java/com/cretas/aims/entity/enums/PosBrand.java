package com.cretas.aims.entity.enums;

/**
 * POS品牌枚举
 * 新增POS品牌只需：1)添加枚举值 2)编写对应Adapter实现类
 */
public enum PosBrand {
    KERUYUN("客如云", "OAuth2.0"),
    ERWEIHUO("二维火", "SHA1签名"),
    YINBAO("银豹", "HMAC-SHA256"),
    MEITUAN("美团收银", "商务SDK"),
    HUALALA("哗啦啦", "私有Token");

    private final String displayName;
    private final String authType;

    PosBrand(String displayName, String authType) {
        this.displayName = displayName;
        this.authType = authType;
    }

    public String getDisplayName() { return displayName; }
    public String getAuthType() { return authType; }
}

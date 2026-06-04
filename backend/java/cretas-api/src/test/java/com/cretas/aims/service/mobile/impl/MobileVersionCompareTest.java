package com.cretas.aims.service.mobile.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本网关 versionName 比较逻辑单元测试。
 *
 * <p>直接断言 package-private static {@link MobileBusinessServiceImpl#compareVersion(String, String)}，
 * 该方法驱动 {@code /api/mobile/version/check} 的 updateAvailable (低于 latest) /
 * updateRequired (低于 min) 判定。纯函数，不加载 Spring 上下文。
 */
class MobileVersionCompareTest {

    private static int cmp(String a, String b) {
        return MobileBusinessServiceImpl.compareVersion(a, b);
    }

    @Test
    @DisplayName("相等版本返回 0（含缺段补 0）")
    void equalVersions() {
        assertEquals(0, cmp("1.0.1", "1.0.1"));
        assertEquals(0, cmp("1.0", "1.0.0"));
        assertEquals(0, cmp("2", "2.0.0"));
    }

    @Test
    @DisplayName("较低版本返回负数（触发 updateAvailable / updateRequired）")
    void lowerVersions() {
        assertTrue(cmp("1.0.0", "1.0.1") < 0);
        assertTrue(cmp("1.0.1", "1.1.0") < 0);
        assertTrue(cmp("0.9.9", "1.0.0") < 0);
    }

    @Test
    @DisplayName("较高版本返回正数（不更新）")
    void higherVersions() {
        assertTrue(cmp("1.0.2", "1.0.1") > 0);
        assertTrue(cmp("2.0.0", "1.9.9") > 0);
    }

    @Test
    @DisplayName("数字段按数值比较而非字典序（1.0.10 > 1.0.2）")
    void numericNotLexical() {
        assertTrue(cmp("1.0.10", "1.0.2") > 0);
        assertTrue(cmp("1.0.9", "1.0.10") < 0);
    }

    @Test
    @DisplayName("null / 空 / 非数字后缀健壮处理")
    void robustParsing() {
        assertTrue(cmp(null, "1.0.0") < 0);          // 缺失当最旧 → 强制更新
        assertTrue(cmp("", "1.0.0") < 0);
        assertEquals(0, cmp(null, null));
        assertEquals(0, cmp("1.0.0-rc1", "1.0.0"));  // 前导数字解析，rc 后缀忽略
    }
}

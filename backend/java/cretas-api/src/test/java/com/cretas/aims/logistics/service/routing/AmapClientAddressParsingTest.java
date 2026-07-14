package com.cretas.aims.logistics.service.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AmapClient 地址解析纯函数单测 — 用客户真实踩坑地址回归（2026-07-14 漂移事故）：
 * 「无锡市梁溪区凤凰城20-6」被高德 geocode 匹配到江阴市的「凤凰城」小区（同城跨区漂移），
 * 区级校验 + POI 兜底靠这些解析函数拿到「预期区」和「核心搜索词」。
 */
class AmapClientAddressParsingTest {

    // ===== extractCity（既有，回归保护）=====

    @Test
    void extractCityFromFullAddress() {
        assertEquals("常州", AmapClient.extractCity("江苏省常州市新北区万达金街2-31号渝八两重庆鸡公煲"));
        assertEquals("无锡", AmapClient.extractCity("江苏省无锡市梁溪区凤凰城20-6"));
    }

    @Test
    void extractCityNullWhenAbsent() {
        assertNull(AmapClient.extractCity("凤凰城20-6"));
        assertNull(AmapClient.extractCity(null));
    }

    // ===== extractDistrict =====

    @Test
    void extractDistrictFromFullAddress() {
        assertEquals("梁溪区", AmapClient.extractDistrict("江苏省无锡市梁溪区凤凰城20-6"));
        assertEquals("钟楼区", AmapClient.extractDistrict("江苏省常州市钟楼区延陵地铁印巷负一楼"));
        assertEquals("金坛区", AmapClient.extractDistrict("江苏省常州市金坛区沿河西路54号鸡柳大人"));
    }

    @Test
    void extractDistrictNullWhenAbsentOrCountyLevelCity() {
        // 县级市（昆山市）不是「区/县」→ 保守不抽，回落城市级校验
        assertNull(AmapClient.extractDistrict("江苏省苏州市昆山市前进东路"));
        assertNull(AmapClient.extractDistrict("凤凰城20-6"));
        assertNull(AmapClient.extractDistrict(null));
    }

    @Test
    void extractDistrictSkipsWhenNoCityPrefix() {
        // 「无锡惠山区」缺「市」前缀 → 保守不抽（避免把「经济开发区」之类误当行政区）
        assertNull(AmapClient.extractDistrict("江苏省无锡惠山区市崇文路前洲店"));
    }

    // ===== deriveKeywords（POI 兜底搜索核心词）=====

    @Test
    void deriveKeywordsStripsPrefixAndHouseNumber() {
        assertEquals("凤凰城", AmapClient.deriveKeywords("江苏省无锡市梁溪区凤凰城20-6"));
        assertEquals("万达金街", AmapClient.deriveKeywords("江苏省常州市新北区万达金街2-31号"));
    }

    @Test
    void deriveKeywordsKeepsNonNumericTail() {
        // 「负一楼」不是门牌数字尾巴，保留（宁多勿错删）
        assertEquals("延陵地铁印巷负一", AmapClient.deriveKeywords("江苏省常州市钟楼区延陵地铁印巷负一楼"));
    }

    @Test
    void deriveKeywordsNullWhenNothingLeft() {
        assertNull(AmapClient.deriveKeywords("江苏省无锡市梁溪区20-6"));
        assertNull(AmapClient.deriveKeywords(null));
    }
}

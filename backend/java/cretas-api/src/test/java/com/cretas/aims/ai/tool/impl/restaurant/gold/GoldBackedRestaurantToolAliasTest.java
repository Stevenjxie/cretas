package com.cretas.aims.ai.tool.impl.restaurant.gold;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Alias deletion test for {@link GoldBackedRestaurantTool#resolveGoldFactoryId}.
 *
 * <p>After the DEMO_REST → RES_3101_009 alias is removed, Gold queries must
 * fall through to the requesting tenant directly, avoiding the cross-tenant
 * data visibility issue (Java Gold path reads RES_3101_009 while Python
 * tiered path reads DEMO_REST itself).
 *
 * <p>断言钉住「返回值等于入参」这个行为，而不是钉住某个字符串常量消失 ——
 * 后者换个写法就绕过去了。
 */
class GoldBackedRestaurantToolAliasTest {

    private final RestaurantPeakMonthGoldTool tool = new RestaurantPeakMonthGoldTool();

    @Test
    @DisplayName("DEMO_REST no longer aliased to RES_3101_009")
    void demoRestNoLongerAliased() {
        assertThat(tool.resolveGoldFactoryId("DEMO_REST"))
                .isEqualTo("DEMO_REST");
    }

    @Test
    @DisplayName("MOCK_REST unchanged")
    void mockRestUnchanged() {
        assertThat(tool.resolveGoldFactoryId("MOCK_REST"))
                .isEqualTo("MOCK_REST");
    }

    @Test
    @DisplayName("Lowercase variant also not aliased")
    void lowercaseDemoRestUnchanged() {
        assertThat(tool.resolveGoldFactoryId("demo_rest"))
                .isEqualTo("demo_rest");
    }
}

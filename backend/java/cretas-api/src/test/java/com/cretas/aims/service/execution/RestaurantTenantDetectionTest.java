package com.cretas.aims.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MOCK_REST 的 ID 不匹配 RES_/REST_/DEMO_REST 任何一种前缀, 只能靠
 * factories.type='RESTAURANT' 解析出的 domain 认出来。这组断言钉住
 * 「ID 认不出时必须回落到 domain」, 防止再退回纯前缀判定。
 */
class RestaurantTenantDetectionTest {

    private static final String MOCK = "MOCK_REST";
    private static final String RESTAURANT_DOMAIN = "RESTAURANT";

    @Test
    @DisplayName("orchestrator: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void orchestratorRecognizesMockRestByDomain() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("orchestrator: domain 缺失时 MOCK_REST 判否 —— 契约是「靠 domain」, 不是「靠名字里有 REST」")
    void orchestratorRejectsMockRestWithoutDomain() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId(MOCK, null)).isFalse();
    }

    @Test
    @DisplayName("orchestrator: 传统 RES_ 前缀租户不依赖 domain 仍判是")
    void orchestratorKeepsPrefixBehaviour() {
        assertThat(IntentExecutionOrchestrator.isRestaurantTenantId("RES_3101_009", null)).isTrue();
    }

    @Test
    @DisplayName("SSE: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void sseRecognizesMockRestByDomain() {
        assertThat(SseStreamingService.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("SSE: 工厂型租户即使 id 含 REST 字样也判否")
    void sseRejectsFactoryTenant() {
        assertThat(SseStreamingService.isRestaurantTenantId("F006", "FACTORY")).isFalse();
    }

    @Test
    @DisplayName("DynamicToolSelection: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void toolSelectionRecognizesMockRestByDomain() {
        assertThat(DynamicToolSelectionService.isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("AIIntentConfig: domain=RESTAURANT 时 MOCK_REST 被认成餐饮租户")
    void intentConfigRecognizesMockRestByDomain() {
        assertThat(com.cretas.aims.controller.AIIntentConfigController
                .isRestaurantTenantId(MOCK, RESTAURANT_DOMAIN)).isTrue();
    }

    @Test
    @DisplayName("hasRestaurantOwnerActionSignal 传真实 domain 而不是 null")
    void ownerActionSignalResolvesDomainInsteadOfPassingNull() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java"));

        // 取 hasRestaurantOwnerActionSignal 方法体的前若干行
        int start = src.indexOf("boolean hasRestaurantOwnerActionSignal(");
        assertThat(start).as("找不到 hasRestaurantOwnerActionSignal").isGreaterThan(0);
        String body = src.substring(start, Math.min(start + 400, src.length()));

        assertThat(body)
            .as("该方法内对 isRestaurantOwnerActionFactory 的调用必须传解析出的 domain")
            .contains("isRestaurantOwnerActionFactory(factoryId, resolveFactoryDomainSafe(factoryId))");
        assertThat(body)
            .as("不得再出现传 null 的调用 —— 那会让 domain 分支永远走不到")
            .doesNotContain("isRestaurantOwnerActionFactory(factoryId, null)");
    }
}

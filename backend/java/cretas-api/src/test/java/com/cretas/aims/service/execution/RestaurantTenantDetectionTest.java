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

    /**
     * 钉住的是**不变量**而不是某一行: 该文件里每一处会执行的 {@code startsWith("RES_")}
     * 都必须待在一个「先认 domain」的方法里(方法体内出现
     * {@code equalsIgnoreCase(factoryDomain)})。裸写在业务分支里的前缀判定会让这条红 ——
     * 这正是 2026-08-06 第六处漏网的形状(`handleEarlyQuestionTypeDetection` 里自己
     * 算 businessDomain, 于是 MOCK_REST 被标成 FACTORY)。
     */
    @Test
    @DisplayName("不变量: orchestrator 里的 RES_ 前缀判定只允许出现在先认 domain 的方法内")
    void resPrefixChecksOnlyLiveInsideDomainFirstHelpers() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java");
        java.util.List<String> lines = java.nio.file.Files.readAllLines(src);

        // 恰好 4 空格缩进 = 类成员层级; (?! ) 把方法体内 8 空格的 if/return 排除掉,
        // 关键字黑名单再兜一层(万一某天出现 4 空格缩进的控制流)。
        java.util.regex.Pattern methodSig = java.util.regex.Pattern.compile(
                "^ {4}(?! )(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|return\\b|else\\b|do\\b|try\\b)"
                        + "(?:(?:public|private|protected|static|final|abstract|synchronized)\\s+)*"
                        + "[\\w<>\\[\\],.?\\s]+\\s+\\w+\\s*\\(");

        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("startsWith(\"RES_\")")) {
                continue;
            }
            // 注释里提到这串(包括本次修复的 javadoc)不算承载点 —— 只有会执行的语句算。
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            // 向上找最近的方法签名
            int start = -1;
            for (int j = i; j >= 0; j--) {
                if (methodSig.matcher(lines.get(j)).find()) {
                    start = j;
                    break;
                }
            }
            assertThat(start).as("第 %d 行的 RES_ 判定找不到所属方法", i + 1).isGreaterThanOrEqualTo(0);
            // 向下取到该方法的收尾 "    }"
            int end = lines.size() - 1;
            for (int j = start + 1; j < lines.size(); j++) {
                if (lines.get(j).equals("    }")) {
                    end = j;
                    break;
                }
            }
            String body = String.join("\n", lines.subList(start, end + 1));
            if (!body.contains("equalsIgnoreCase(factoryDomain)")) {
                offenders.add("L" + (i + 1) + " in: " + lines.get(start).trim());
            }
        }

        assertThat(offenders)
                .as("这些 RES_ 前缀判定没有先认 domain —— MOCK_REST 会在这里被判成工厂租户")
                .isEmpty();
    }

    @Test
    @DisplayName("hasRestaurantOwnerActionSignal 必须解析 domain、禁止传 null（不绑定代码格式）")
    void ownerActionSignalResolvesDomainInsteadOfPassingNull() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java"));

        // 取 hasRestaurantOwnerActionSignal 方法体的前若干行
        int start = src.indexOf("boolean hasRestaurantOwnerActionSignal(");
        assertThat(start).as("找不到 hasRestaurantOwnerActionSignal").isGreaterThan(0);
        String body = src.substring(start, Math.min(start + 400, src.length()));

        // 语义检查（不绑定确切变量名）：调用 isRestaurantOwnerActionFactory 时必须传 factoryId + 某个 domain
        // 例如 isRestaurantOwnerActionFactory(factoryId, resolveFactoryDomainSafe(...))
        // 或   isRestaurantOwnerActionFactory(factoryId, ownerActionDomain) 都应该通过。
        // 关键是不能是 isRestaurantOwnerActionFactory(factoryId, null)，那会让 domain 分支永远走不到。
        assertThat(body)
            .as("该方法内对 isRestaurantOwnerActionFactory 的调用必须传解析出的 domain（允许任何提取方式），不能传 null")
            .contains("isRestaurantOwnerActionFactory(factoryId,");
        assertThat(body)
            .as("确保没有回滑到传 null 的调用形式")
            .doesNotContain("isRestaurantOwnerActionFactory(factoryId, null)");
    }
}

package com.cretas.aims.service.intent.impl;

import com.cretas.aims.ai.tool.NegationTwinPolicy;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Piece 2 反投毒守卫: {@link IntentRecognitionPipelineServiceImpl#shouldLearnExpression} 单元测试。
 *
 * <h2>三守卫语义</h2>
 * <ul>
 *   <li><b>Guard B</b> — tool_name=NULL 意图拒绝学习 (无 executor = 死路由)</li>
 *   <li><b>Guard C</b> — 业态不兼容拒绝学习 (餐饮工厂不学制造业意图)</li>
 *   <li><b>Passthrough</b> — 正常意图 (有 tool_name + 业态兼容) 仍然学习</li>
 * </ul>
 *
 * <p>测试驱动 package-private 方法，无 Spring 上下文。</p>
 */
class IntentPipelineShouldLearnGuardTest {

    private IntentConfigManagementService configService;
    private IntentRecognitionPipelineServiceImpl service;

    /** 工厂 ID 常量 */
    private static final String RESTAURANT_FACTORY = "QHJ_RESTAURANT";
    private static final String FACTORY_FACTORY    = "F001";

    @BeforeEach
    void setUp() {
        configService = mock(IntentConfigManagementService.class);
        NegationTwinPolicy policy = new NegationTwinPolicy(new WriteGuardService());

        // shouldLearnExpression 只使用 configService(pos25). 其他 25 个 deps 置 null.
        service = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,   // 1-5
                null, null, null, null, null,   // 6-10
                null, null, null, null,         // 11-14
                null,                           // 15 queryPreprocessorService
                null, null, null, null, null, null, // 16-21
                null,                           // 22 knowledgeBase
                null, null,                     // 23-24
                configService,                  // 25
                policy);                        // 26
    }

    // ── Guard B 测试 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Guard B: tool_name=NULL → 拒绝学习")
    void guardB_nullToolName_rejectsLearning() {
        AIIntentConfig cfg = cfg("RESTAURANT_DISH_DELETE", null, "RESTAURANT");
        // Guard B 不需要 configService.resolveBusinessDomain, 直接拦截
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isFalse();
    }

    @Test
    @DisplayName("Guard B: tool_name 空字符串 → 拒绝学习")
    void guardB_blankToolName_rejectsLearning() {
        AIIntentConfig cfg = cfg("RESTAURANT_PEAK_HOURS_ANALYSIS", "   ", "RESTAURANT");
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isFalse();
    }

    @Test
    @DisplayName("Guard B: cfg=null → 放行 (保守: 配置不存在时不拦截)")
    void guardB_nullCfg_allowsLearning() {
        // null cfg: 无法判断 tool_name, 保守放行
        assertThat(service.shouldLearnExpression(null, RESTAURANT_FACTORY)).isTrue();
    }

    // ── Guard C 测试 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Guard C: 餐饮工厂学习 FACTORY 意图 → 拒绝 (业态不兼容)")
    void guardC_restaurantFactory_factoryIntent_rejectsLearning() {
        // SKU_GROSS_MARGIN 是 FACTORY 业态意图
        AIIntentConfig cfg = cfg("SKU_GROSS_MARGIN", "sku_gross_margin", "FACTORY");
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isFalse();
    }

    @Test
    @DisplayName("Guard C: 制造业工厂学习 RESTAURANT 意图 → 拒绝 (业态不兼容)")
    void guardC_factoryFactory_restaurantIntent_rejectsLearning() {
        AIIntentConfig cfg = cfg("RESTAURANT_BESTSELLER_QUERY", "restaurant_bestseller_query", "RESTAURANT");
        when(configService.resolveBusinessDomain(FACTORY_FACTORY)).thenReturn("FACTORY");
        assertThat(service.shouldLearnExpression(cfg, FACTORY_FACTORY)).isFalse();
    }

    @Test
    @DisplayName("Guard C: resolveBusinessDomain 抛异常 → 放行 (fail-open)")
    void guardC_resolveThrows_allowsLearning_failOpen() {
        AIIntentConfig cfg = cfg("SKU_GROSS_MARGIN", "sku_gross_margin", "FACTORY");
        when(configService.resolveBusinessDomain(anyString())).thenThrow(new RuntimeException("db down"));
        // Guard C fail-open: 解析失败不拦截
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isTrue();
    }

    // ── Passthrough 测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常情况: tool_name 非空 + 业态兼容 → 放行 (不破坏正常学习)")
    void passthrough_validToolAndCompatibleBiz_allowsLearning() {
        AIIntentConfig cfg = cfg("RESTAURANT_BESTSELLER_QUERY", "restaurant_bestseller_query", "RESTAURANT");
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isTrue();
    }

    @Test
    @DisplayName("正常情况: COMMON 业态意图 + 任意工厂 → 放行 (COMMON 与任何 biz 兼容)")
    void passthrough_commonBizIntent_allowsLearning() {
        AIIntentConfig cfg = cfg("ALERT_ACTIVE", "alert_active", "COMMON");
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isTrue();
    }

    @Test
    @DisplayName("正常情况: 制造业工厂学习 FACTORY 意图 → 放行")
    void passthrough_factoryFactory_factoryIntent_allowsLearning() {
        AIIntentConfig cfg = cfg("SKU_GROSS_MARGIN", "sku_gross_margin", "FACTORY");
        when(configService.resolveBusinessDomain(FACTORY_FACTORY)).thenReturn("FACTORY");
        assertThat(service.shouldLearnExpression(cfg, FACTORY_FACTORY)).isTrue();
    }

    @Test
    @DisplayName("Guard B 优先: tool_name=NULL 即使业态兼容也拒绝 (Guard B 在 Guard C 之前)")
    void guardB_takesOverGuardC_nullToolNameBlocks() {
        AIIntentConfig cfg = cfg("RESTAURANT_DISH_DELETE", null, "RESTAURANT");
        // 即使 factory 是餐饮, tool_name=NULL 仍然被 Guard B 拦截
        when(configService.resolveBusinessDomain(RESTAURANT_FACTORY)).thenReturn("RESTAURANT");
        assertThat(service.shouldLearnExpression(cfg, RESTAURANT_FACTORY)).isFalse();
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────────

    /** 构建最小 AIIntentConfig，只设置 shouldLearnExpression 需要的字段。 */
    private static AIIntentConfig cfg(String intentCode, String toolName, String businessType) {
        AIIntentConfig c = new AIIntentConfig();
        c.setIntentCode(intentCode);
        c.setToolName(toolName);
        c.setBusinessType(businessType);
        return c;
    }
}

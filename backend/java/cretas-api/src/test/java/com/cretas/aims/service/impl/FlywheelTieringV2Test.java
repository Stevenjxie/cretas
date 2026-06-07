package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Flywheel Tiering v2 单元测试。
 *
 * <h2>测试目标</h2>
 * <ol>
 *   <li>分层写入: confidence >= 0.90 → active; 0.70-0.89 → staged (is_active=false)。</li>
 *   <li>Promote 真路径: dedup 命中 staged 行 → proposalCount++ → 第3次 promote 为 active。</li>
 *   <li>跨域/NULL-tool 守卫: 即便 3x 提议也不 promote（由 shouldLearnExpression 控制，
 *       但本测试关注 service 层 incrementProposalAndMaybePromote 自身守卫 confidence < 0.70）。</li>
 *   <li>Dormant (NULL is_active) 行被重提议 3x → 复活为 active。</li>
 * </ol>
 *
 * <h2>设计</h2>
 * <p>纯单元测试，无 Spring 上下文。
 * 直接实例化 {@link ExpressionLearningServiceImpl}，mock 全部 repo / client 依赖。
 * 测试 package-private 方法 {@code incrementProposalAndMaybePromote} 及公开方法 {@code learnExpression}。
 */
class FlywheelTieringV2Test {

    private LearnedExpressionRepository repo;
    private IntentConfigManagementService configService;
    private ExpressionLearningServiceImpl service;

    private static final String FACTORY_ID   = "F001";
    private static final String INTENT_CODE  = "INVENTORY_QUERY";
    private static final String EXPRESSION   = "查一下库存";

    @BeforeEach
    void setUp() {
        repo = mock(LearnedExpressionRepository.class);
        // Other deps: all null — tiering/proposal logic doesn't touch them
        service = new ExpressionLearningServiceImpl(
                repo,
                null,    // TrainingSampleRepository — not needed for tiering tests
                null,    // EmbeddingClient
                null,    // RequestScopedEmbeddingCache
                null     // IntentEmbeddingCacheService
        );

        // #553 守卫: inject a default "allow-promote" configService mock so that existing
        // promote-positive tests (2b, 2c, 2d, 4a, 4c) continue to pass unmodified.
        // The guard checks tool_name != null && BusinessTypeScope.isCompatible.
        configService = mock(IntentConfigManagementService.class);
        AIIntentConfig validCfg = new AIIntentConfig();
        validCfg.setIntentCode(INTENT_CODE);
        validCfg.setToolName("inventory_query");    // non-blank → Guard B passes
        validCfg.setBusinessType("FACTORY");        // FACTORY intent on FACTORY domain → Guard C passes
        when(configService.getIntentConfigByCode(anyString(), anyString())).thenReturn(validCfg);
        when(configService.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
        ReflectionTestUtils.setField(service, "intentConfigService", configService);

        // Default: no existing row (fresh insert path)
        when(repo.findOneByHashAndFactory(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any(LearnedExpression.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // 1. 分层写入 tests
    // =====================================================================

    @Nested
    @DisplayName("1. 分层写入 — confidence 决定初始 tier")
    class TieringOnWrite {

        @Test
        @DisplayName("1a. confidence >= 0.90 → 新行 is_active=true (直接 active)")
        void highConf_writesActiveRow() {
            LearnedExpression result = service.learnExpression(
                    FACTORY_ID, INTENT_CODE, EXPRESSION, 0.95,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            assertThat(result).isNotNull();
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getProposalCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("1b. confidence = 0.90 (边界) → active")
        void exactBoundary_0_90_writesActiveRow() {
            LearnedExpression result = service.learnExpression(
                    FACTORY_ID, INTENT_CODE, EXPRESSION, 0.90,
                    LearnedExpression.SourceType.SEMANTIC_HIGH);

            assertThat(result).isNotNull();
            assertThat(result.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("1c. confidence 0.70-0.89 → staged (is_active=false)")
        void midConf_writesStagedRow() {
            LearnedExpression result = service.learnExpression(
                    FACTORY_ID, INTENT_CODE, EXPRESSION, 0.80,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            assertThat(result).isNotNull();
            assertThat(result.getIsActive()).isFalse();
            assertThat(result.getProposalCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("1d. confidence 0.89 (边界) → staged")
        void justBelowBoundary_writesStagedRow() {
            LearnedExpression result = service.learnExpression(
                    FACTORY_ID, INTENT_CODE, EXPRESSION, 0.89,
                    LearnedExpression.SourceType.LLM_RERANKING);

            assertThat(result).isNotNull();
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("1e. 已存在且 active → 返回 null (无重复写入)")
        void existingActiveRow_returnsNull() {
            LearnedExpression activeRow = buildRow(true, 1);
            String hash = LearnedExpression.computeHash(EXPRESSION.toLowerCase().trim());
            when(repo.findOneByHashAndFactory(eq(hash), eq(FACTORY_ID)))
                    .thenReturn(Optional.of(activeRow));

            LearnedExpression result = service.learnExpression(
                    FACTORY_ID, INTENT_CODE, EXPRESSION, 0.95,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            assertThat(result).isNull();
            verify(repo, never()).save(any());
        }
    }

    // =====================================================================
    // 2. Promote 真路径: dedup 命中 staged 行 → proposalCount++，第3次 promote
    // =====================================================================

    @Nested
    @DisplayName("2. Promote 真路径 — staged 行多次重提议后激活")
    class PromotePath {

        @Test
        @DisplayName("2a. staged 行 proposalCount=1 → 重提议后变 2 (未达阈值，保持 staged)")
        void secondProposal_incrementsCount_staysStaged() {
            LearnedExpression staged = buildRow(false, 1);
            String hash = LearnedExpression.computeHash(EXPRESSION.toLowerCase().trim());
            when(repo.findOneByHashAndFactory(eq(hash), eq(FACTORY_ID)))
                    .thenReturn(Optional.of(staged));

            service.learnExpression(FACTORY_ID, INTENT_CODE, EXPRESSION, 0.80,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            // Verify save was called with proposalCount=2, is_active still false
            verify(repo).save(argThat(row ->
                    row.getProposalCount() == 2
                    && Boolean.FALSE.equals(row.getIsActive())
            ));
        }

        @Test
        @DisplayName("2b. staged 行 proposalCount=2 → 第3次提议 promote 为 active (核心路径)")
        void thirdProposal_promotesToActive() {
            LearnedExpression staged = buildRow(false, 2);
            String hash = LearnedExpression.computeHash(EXPRESSION.toLowerCase().trim());
            when(repo.findOneByHashAndFactory(eq(hash), eq(FACTORY_ID)))
                    .thenReturn(Optional.of(staged));

            service.learnExpression(FACTORY_ID, INTENT_CODE, EXPRESSION, 0.80,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            // Must be promoted: is_active=true, proposalCount=3
            verify(repo).save(argThat(row ->
                    row.getProposalCount() == 3
                    && Boolean.TRUE.equals(row.getIsActive())
            ));
        }

        @Test
        @DisplayName("2c. staged 行 proposalCount=2, confidence=0.70 (边界) → promote")
        void thirdProposal_minConf_promotes() {
            LearnedExpression staged = buildRow(false, 2);
            String hash = LearnedExpression.computeHash(EXPRESSION.toLowerCase().trim());
            when(repo.findOneByHashAndFactory(eq(hash), eq(FACTORY_ID)))
                    .thenReturn(Optional.of(staged));

            service.learnExpression(FACTORY_ID, INTENT_CODE, EXPRESSION, 0.70,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            verify(repo).save(argThat(row -> Boolean.TRUE.equals(row.getIsActive())));
        }

        @Test
        @DisplayName("2d. 直接调用 incrementProposalAndMaybePromote: proposalCount=2+1=3 → promote")
        void directCall_incrementPromote_promotesAtThreshold() {
            LearnedExpression staged = buildRow(false, 2);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, INTENT_CODE, 0.85, FACTORY_ID);

            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getLastProposedAt()).isNotNull();
        }

        @Test
        @DisplayName("2e. proposalCount=1+1=2 (below threshold) → stays staged")
        void directCall_belowThreshold_staysStaged() {
            LearnedExpression staged = buildRow(false, 1);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, INTENT_CODE, 0.85, FACTORY_ID);

            assertThat(result.getProposalCount()).isEqualTo(2);
            assertThat(result.getIsActive()).isFalse();
        }
    }

    // =====================================================================
    // 3. 守卫: confidence < 0.70 → 即便达到 proposalCount 阈值也不 promote
    // =====================================================================

    @Nested
    @DisplayName("3. Confidence 守卫 — 低置信度即便多次重提议也不 promote")
    class ConfidenceGuard {

        @Test
        @DisplayName("3a. proposalCount=2, confidence=0.69 → 不 promote (低于守卫)")
        void thirdProposalLowConf_doesNotPromote() {
            LearnedExpression staged = buildRow(false, 2);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, INTENT_CODE, 0.69, FACTORY_ID);

            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isFalse();  // NOT promoted
        }

        @Test
        @DisplayName("3b. proposalCount=10, confidence=0.50 → 永不 promote")
        void manyProposalsVeryLowConf_neverPromotes() {
            LearnedExpression staged = buildRow(false, 10);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, INTENT_CODE, 0.50, FACTORY_ID);

            assertThat(result.getIsActive()).isFalse();
        }
    }

    // =====================================================================
    // 4. Dormant (NULL is_active) 行被重提议 3x → 复活
    // =====================================================================

    @Nested
    @DisplayName("4. Dormant NULL 行重提议 → 有机复活")
    class DormantRevival {

        @Test
        @DisplayName("4a. dormant (is_active=null) 行 proposalCount=2 → 第3次 promote")
        void dormantRow_thirdProposal_getsRevived() {
            LearnedExpression dormant = buildRow(null, 2);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    dormant, INTENT_CODE, 0.80, FACTORY_ID);

            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isTrue();  // revived
        }

        @Test
        @DisplayName("4b. dormant 行 proposalCount=1 → 重提议后变 2, 保持 dormant")
        void dormantRow_secondProposal_staysDormant() {
            LearnedExpression dormant = buildRow(null, 1);

            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    dormant, INTENT_CODE, 0.80, FACTORY_ID);

            assertThat(result.getProposalCount()).isEqualTo(2);
            assertThat(result.getIsActive()).isNull();  // null stays null until promote
        }

        @Test
        @DisplayName("4c. learnExpression 遇 dormant 行 → 走重提议流程")
        void learnExpression_dormantRow_triggersReproposal() {
            LearnedExpression dormant = buildRow(null, 2);
            String hash = LearnedExpression.computeHash(EXPRESSION.toLowerCase().trim());
            when(repo.findOneByHashAndFactory(eq(hash), eq(FACTORY_ID)))
                    .thenReturn(Optional.of(dormant));

            service.learnExpression(FACTORY_ID, INTENT_CODE, EXPRESSION, 0.80,
                    LearnedExpression.SourceType.LLM_FALLBACK);

            // Third proposal = promote
            verify(repo).save(argThat(row ->
                    row.getProposalCount() == 3
                    && Boolean.TRUE.equals(row.getIsActive())
            ));
        }
    }

    // =====================================================================
    // 5. #553 Promote 守卫 — 跨域/NULL-tool 遗留行不得 promote
    // =====================================================================

    @Nested
    @DisplayName("5. #553 Promote 守卫 — 跨域毒/NULL-tool 行即便 3x 提议也不 promote")
    class PromoteGuard {

        @Test
        @DisplayName("5a. Guard C: 餐饮专属意图 (business_type=RESTAURANT) 在工厂租户 3x 重提议 → 不 promote")
        void crossDomainIntent_thirdProposal_doesNotPromote() {
            // Arrange: intent is RESTAURANT-only, but factory domain is FACTORY (non-restaurant)
            AIIntentConfig restaurantCfg = new AIIntentConfig();
            restaurantCfg.setIntentCode("RESTAURANT_PEAK_HOURS");
            restaurantCfg.setToolName("restaurant_peak_hours_query");
            restaurantCfg.setBusinessType("RESTAURANT");         // 餐饮专属
            when(configService.getIntentConfigByCode(eq(FACTORY_ID), eq("RESTAURANT_PEAK_HOURS")))
                    .thenReturn(restaurantCfg);
            when(configService.resolveBusinessDomain(eq(FACTORY_ID))).thenReturn("FACTORY");

            LearnedExpression dormant = buildRowForIntent("RESTAURANT_PEAK_HOURS", null, 2);

            // Act
            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    dormant, "RESTAURANT_PEAK_HOURS", 0.85, FACTORY_ID);

            // Assert: proposalCount incremented but NOT promoted (cross-domain guard blocks)
            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isNull();   // stays dormant (null), not promoted
        }

        @Test
        @DisplayName("5b. Guard B: tool_name=NULL 的遗留行 3x 重提议 → 不 promote (死路由投毒防御)")
        void nullToolIntent_thirdProposal_doesNotPromote() {
            // Arrange: cfg exists but tool_name is null (legacy orphan intent)
            AIIntentConfig orphanCfg = new AIIntentConfig();
            orphanCfg.setIntentCode("ORPHAN_INTENT");
            orphanCfg.setToolName(null);                        // NULL tool_name → Guard B blocks
            orphanCfg.setBusinessType("COMMON");
            when(configService.getIntentConfigByCode(eq(FACTORY_ID), eq("ORPHAN_INTENT")))
                    .thenReturn(orphanCfg);
            when(configService.resolveBusinessDomain(eq(FACTORY_ID))).thenReturn("FACTORY");

            LearnedExpression staged = buildRowForIntent("ORPHAN_INTENT", false, 2);

            // Act
            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, "ORPHAN_INTENT", 0.80, FACTORY_ID);

            // Assert: NOT promoted; stays staged
            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isFalse();  // stays staged (false)
        }

        @Test
        @DisplayName("5c. 正路径: 同业态 + tool_name 非空 → 3x 提议正常 promote (守卫不误伤)")
        void compatibleIntent_thirdProposal_promotesNormally() {
            // Arrange: factory intent on factory domain — should be allowed through guards
            // (uses the default setUp configService mock which already returns FACTORY/non-null tool)
            LearnedExpression staged = buildRow(false, 2);  // uses default INTENT_CODE

            // Act
            LearnedExpression result = service.incrementProposalAndMaybePromote(
                    staged, INTENT_CODE, 0.80, FACTORY_ID);

            // Assert: promoted as normal
            assertThat(result.getProposalCount()).isEqualTo(3);
            assertThat(result.getIsActive()).isTrue();
        }
    }

    // =====================================================================
    // Helper
    // =====================================================================

    /** Build a minimal LearnedExpression with given is_active and proposalCount. */
    private static LearnedExpression buildRow(Boolean isActive, int proposalCount) {
        return buildRowForIntent(INTENT_CODE, isActive, proposalCount);
    }

    /** Build a minimal LearnedExpression with a custom intentCode (for guard tests). */
    private static LearnedExpression buildRowForIntent(String intentCode, Boolean isActive, int proposalCount) {
        String normalized = EXPRESSION.toLowerCase().trim();
        return LearnedExpression.builder()
                .id(java.util.UUID.randomUUID().toString())
                .factoryId(FACTORY_ID)
                .intentCode(intentCode)
                .expression(normalized)
                .expressionHash(LearnedExpression.computeHash(normalized))
                .sourceType(LearnedExpression.SourceType.LLM_FALLBACK)
                .confidence(java.math.BigDecimal.valueOf(0.80))
                .hitCount(0)
                .isVerified(false)
                .isActive(isActive)
                .proposalCount(proposalCount)
                .build();
    }
}

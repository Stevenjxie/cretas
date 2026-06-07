package com.cretas.aims.service.impl;

import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 置信度分层写入测试 (TDD Piece 1).
 *
 * <h2>分层规则</h2>
 * <ul>
 *   <li>confidence ≥ 0.90 → is_active = true  (active, 立即路由)</li>
 *   <li>confidence 0.70-0.89 → is_active = false (staged, 隔离等待 promote)</li>
 *   <li>confidence < 0.70 → 不应调用 learnExpression（caller-side gate）</li>
 * </ul>
 *
 * <p>无 Spring 上下文，直接 mock repo + embedding deps.</p>
 */
class ExpressionLearningTieringTest {

    private LearnedExpressionRepository repo;
    private ExpressionLearningServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(LearnedExpressionRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.isAvailable()).thenReturn(false); // disable embedding for simplicity

        service = new ExpressionLearningServiceImpl(
                repo,
                mock(com.cretas.aims.repository.learning.TrainingSampleRepository.class),
                embeddingClient,
                mock(RequestScopedEmbeddingCache.class),
                mock(IntentEmbeddingCacheService.class)
        );

        // 默认: hash 不存在，可以学习
        when(repo.existsByHashAndFactory(anyString(), any())).thenReturn(false);
        when(repo.save(any(LearnedExpression.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── 高置信度 ≥ 0.90 → active ────────────────────────────────────────────

    @Test
    @DisplayName("confidence=0.90 → is_active=true (active threshold)")
    void highConfidence_exactBoundary_active() {
        service.learnExpression("F001", "SKU_QUERY", "查询库存", 0.90,
                LearnedExpression.SourceType.LLM_FALLBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("confidence=0.95 → is_active=true")
    void highConfidence_095_active() {
        service.learnExpression("F001", "SKU_QUERY", "今天库存多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("confidence=1.0 (USER_FEEDBACK) → is_active=true")
    void userFeedback_confidence1_active() {
        service.learnExpression("F001", "SKU_QUERY", "库存够用", 1.0,
                LearnedExpression.SourceType.USER_FEEDBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isTrue();
    }

    // ── 中置信度 0.70-0.89 → staged (is_active=false) ─────────────────────

    @Test
    @DisplayName("confidence=0.89 (just-below 0.90) → is_active=false (staged)")
    void midConfidence_089_staged() {
        service.learnExpression("F001", "SKU_QUERY", "看下原料", 0.89,
                LearnedExpression.SourceType.LLM_FALLBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive())
                .as("confidence=0.89 must be staged (false)")
                .isFalse();
    }

    @Test
    @DisplayName("confidence=0.80 → is_active=false (staged)")
    void midConfidence_080_staged() {
        service.learnExpression("F001", "SKU_QUERY", "原材料库存", 0.80,
                LearnedExpression.SourceType.LLM_RERANKING);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isFalse();
    }

    @Test
    @DisplayName("confidence=0.70 (lower bound) → is_active=false (staged)")
    void midConfidence_070_staged() {
        service.learnExpression("F001", "SKU_QUERY", "查原料库存", 0.70,
                LearnedExpression.SourceType.LLM_FALLBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive())
                .as("confidence=0.70 is below threshold 0.90, must be staged")
                .isFalse();
    }

    // ── 低置信度 < 0.70 → 仍保存 staged (caller 应 gate, 但服务层保守处理) ───

    @Test
    @DisplayName("confidence=0.50 → is_active=false (staged, safety net)")
    void lowConfidence_050_staged() {
        service.learnExpression("F001", "SKU_QUERY", "我想看一下", 0.50,
                LearnedExpression.SourceType.LLM_FALLBACK);

        ArgumentCaptor<LearnedExpression> cap = ArgumentCaptor.forClass(LearnedExpression.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isFalse();
    }

    // ── 已存在则不重复写 ────────────────────────────────────────────────────

    @Test
    @DisplayName("hash 已存在 → 不写入 (幂等)")
    void alreadyExists_noWrite() {
        when(repo.existsByHashAndFactory(anyString(), any())).thenReturn(true);
        LearnedExpression result = service.learnExpression(
                "F001", "SKU_QUERY", "库存查询", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK);

        assertThat(result).isNull();
        verify(repo, never()).save(any());
    }

    // ── 阈值常量 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("STAGED_PROMOTION_THRESHOLD 常量值为 0.90")
    void stagedPromotionThresholdConstant() {
        assertThat(ExpressionLearningServiceImpl.STAGED_PROMOTION_THRESHOLD)
                .isEqualTo(0.90);
    }

    // ── promoteStaged 路径 ──────────────────────────────────────────────────

    @Test
    @DisplayName("promoteStaged: 候选为空 → 返回 0")
    void promoteStaged_noCandidates_returnsZero() {
        when(repo.findStagedForPromotion(any(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        int promoted = service.promoteStaged("F001", 0.90, 3);
        assertThat(promoted).isZero();
        verify(repo, never()).promoteToActive(any(), any());
    }

    @Test
    @DisplayName("promoteStaged: 有候选 → 调用 promoteToActive 并返回 promoted 数")
    void promoteStaged_withCandidates_callsPromote() {
        LearnedExpression e1 = buildStagedExpr("id-1", "INTENT_A", 0.92, 5);
        LearnedExpression e2 = buildStagedExpr("id-2", "INTENT_B", 0.91, 3);
        when(repo.findStagedForPromotion(eq("F001"), eq(0.90), eq(3)))
                .thenReturn(List.of(e1, e2));
        when(repo.promoteToActive(argThat(ids -> ids.size() == 2), any())).thenReturn(2);

        int promoted = service.promoteStaged("F001", 0.90, 3);
        assertThat(promoted).isEqualTo(2);

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(repo).promoteToActive(idsCaptor.capture(), any());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder("id-1", "id-2");
    }

    // ── auditNullIsActive ────────────────────────────────────────────────────

    @Test
    @DisplayName("auditNullIsActive: 返回 nullCount 和 sample list")
    void auditNullIsActive_returnsCounts() {
        when(repo.countNullIsActive()).thenReturn(2588L);
        LearnedExpression sample1 = buildStagedExpr("s-1", "INTENT_X", 0.75, 0);
        when(repo.sampleNullIsActive(30)).thenReturn(List.of(sample1));

        com.cretas.aims.service.ExpressionLearningService.NullAuditResult result =
                service.auditNullIsActive(30);

        assertThat(result.getNullCount()).isEqualTo(2588L);
        assertThat(result.getSample()).hasSize(1);
        assertThat(result.getSample().get(0).getId()).isEqualTo("s-1");
    }

    @Test
    @DisplayName("auditNullIsActive: nullCount=0 时返回空 sample")
    void auditNullIsActive_zeroNull() {
        when(repo.countNullIsActive()).thenReturn(0L);
        when(repo.sampleNullIsActive(anyInt())).thenReturn(List.of());

        var result = service.auditNullIsActive(30);
        assertThat(result.getNullCount()).isZero();
        assertThat(result.getSample()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static LearnedExpression buildStagedExpr(String id, String intentCode,
                                                       double confidence, int hitCount) {
        return LearnedExpression.builder()
                .id(id)
                .factoryId("F001")
                .intentCode(intentCode)
                .expression("test expr " + id)
                .expressionHash(LearnedExpression.computeHash("test expr " + id))
                .sourceType(LearnedExpression.SourceType.LLM_FALLBACK)
                .confidence(java.math.BigDecimal.valueOf(confidence))
                .hitCount(hitCount)
                .isActive(false)
                .isVerified(false)
                .build();
    }
}

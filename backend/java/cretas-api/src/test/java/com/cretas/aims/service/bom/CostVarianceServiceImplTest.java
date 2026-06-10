package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.ProductCostVarianceConfig;
import com.cretas.aims.repository.bom.ProductCostVarianceConfigRepository;
import com.cretas.aims.service.bom.impl.CostVarianceServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * C-058: CostVarianceServiceImpl 阈值解析 + computeVariancePct 精确数值测试。
 *
 * <p>验证要点:
 * <ul>
 *   <li>系统默认阈值 = 10% (hardcoded DEFAULT_THRESHOLD = "10.00")</li>
 *   <li>75%/150% 并不存在于 CostVarianceServiceImpl — 它们在 LaborEfficiencyServiceImpl</li>
 *   <li>阈值优先级链: 产品专属 → 工厂全局 → 系统默认 10%</li>
 *   <li>computeVariancePct: (actual-standard)/standard*100, scale-2 HALF_UP</li>
 * </ul>
 *
 * <p>C-058 调查结论:
 * 任务描述中的"75%/150% 达成率阈值"位于 LaborEfficiencyServiceImpl (人工效率服务),
 * 与 CostVarianceServiceImpl (成本超支阈值) 是两个独立服务。
 * CostVarianceServiceImpl 系统默认阈值为 10%，非 75/150。
 * 详见 {@code com.cretas.aims.service.impl.LaborEfficiencyServiceImpl} 常量
 * ACHIEVEMENT_BELOW="75.00" / ACHIEVEMENT_ABOVE="150.00"。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("C-058: CostVarianceServiceImpl — 阈值解析 + 超支百分比计算")
class CostVarianceServiceImplTest {

    @Mock
    private ProductCostVarianceConfigRepository configRepo;

    @InjectMocks
    private CostVarianceServiceImpl service;

    // ─────────────────────────────────────────────────────────────────────────
    // resolveThreshold — 优先级链
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveThreshold — 优先级链")
    class ResolveThresholdTests {

        @Test
        @DisplayName("CV-1: null factoryId → 系统默认 10% (直接返回, 不查数据库)")
        void nullFactoryId_returnsDefault() {
            BigDecimal result = service.resolveThreshold(null, "PT-001");
            // Hardcoded DEFAULT_THRESHOLD = "10.00"
            assertEquals(new BigDecimal("10.00"), result,
                    "System default threshold must be 10.00% when factoryId is null");
            // No DB interaction
            verifyNoInteractions(configRepo);
        }

        @Test
        @DisplayName("CV-2: 无任何配置行 → 系统默认 10%")
        void noConfig_returnsDefault() {
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F001", "PT-001"))
                    .thenReturn(Optional.empty());
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F001"))
                    .thenReturn(Optional.empty());

            BigDecimal result = service.resolveThreshold("F001", "PT-001");
            assertEquals(new BigDecimal("10.00"), result,
                    "Fallback to default 10.00% when no config rows exist");
        }

        @Test
        @DisplayName("CV-3: 仅有工厂全局配置 (productTypeId=null) → 使用全局阈值")
        void factoryGlobalConfig_usedWhenNoProductSpecific() {
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F001", "PT-001"))
                    .thenReturn(Optional.empty());
            ProductCostVarianceConfig globalConfig = ProductCostVarianceConfig.builder()
                    .factoryId("F001")
                    .productTypeId(null)
                    .varianceThreshold(new BigDecimal("15.00"))
                    .isActive(true)
                    .build();
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F001"))
                    .thenReturn(Optional.of(globalConfig));

            BigDecimal result = service.resolveThreshold("F001", "PT-001");
            assertEquals(new BigDecimal("15.00"), result,
                    "Factory global config (15%) takes precedence over system default");
        }

        @Test
        @DisplayName("CV-4: 产品专属配置存在 → 优先使用专属阈值, 不查全局")
        void productSpecificConfig_takesHighestPriority() {
            ProductCostVarianceConfig specific = ProductCostVarianceConfig.builder()
                    .factoryId("F001")
                    .productTypeId("PT-PREMIUM")
                    .varianceThreshold(new BigDecimal("5.00"))
                    .isActive(true)
                    .build();
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F001", "PT-PREMIUM"))
                    .thenReturn(Optional.of(specific));

            BigDecimal result = service.resolveThreshold("F001", "PT-PREMIUM");
            assertEquals(new BigDecimal("5.00"), result,
                    "Product-specific config (5%) takes highest priority");
            // Global config should NOT be queried
            verify(configRepo, never()).findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull(anyString());
        }

        @Test
        @DisplayName("CV-5: productTypeId=null → 跳过专属查询, 直接查工厂全局")
        void nullProductTypeId_skipsProductSpecificQuery() {
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F001"))
                    .thenReturn(Optional.empty());

            BigDecimal result = service.resolveThreshold("F001", null);
            assertEquals(new BigDecimal("10.00"), result,
                    "null productTypeId should skip product-specific query and fall to default");
            verify(configRepo, never()).findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull(anyString(), anyString());
        }

        @Test
        @DisplayName("CV-6: 产品专属配置值为 0 → 返回 0 (配置值优先, 不被默认值覆盖)")
        void zeroThresholdConfig_notOverriddenByDefault() {
            // Edge case: factory explicitly configures 0% threshold (no tolerance)
            ProductCostVarianceConfig strict = ProductCostVarianceConfig.builder()
                    .factoryId("F002")
                    .productTypeId("PT-STRICT")
                    .varianceThreshold(BigDecimal.ZERO)
                    .isActive(true)
                    .build();
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F002", "PT-STRICT"))
                    .thenReturn(Optional.of(strict));

            BigDecimal result = service.resolveThreshold("F002", "PT-STRICT");
            assertEquals(BigDecimal.ZERO, result,
                    "Explicitly configured threshold of 0 should not be replaced by default 10");
        }

        // ── C-058 发现: 75/150 不在 CostVarianceServiceImpl ──

        @Test
        @DisplayName("C-058 调查: 系统默认阈值 = 10.00%, 不是 75 或 150")
        void c058_defaultThresholdIs10NotAchievementRate() {
            /*
             * The B-47 task description mentions "75%/150% 达成率阈值".
             * Investigation result: those constants live in LaborEfficiencyServiceImpl:
             *   ACHIEVEMENT_BELOW = "75.00"  (人工效率达成率下限)
             *   ACHIEVEMENT_ABOVE = "150.00" (人工效率达成率上限)
             *
             * CostVarianceServiceImpl.DEFAULT_THRESHOLD = "10.00" (成本超支默认容差, package-private).
             * These are two completely separate services with unrelated thresholds.
             *
             * This test locks down the actual system default = 10.00 and serves as
             * living documentation of the C-058 investigation result.
             * (DEFAULT_THRESHOLD is package-private; tested via public resolveThreshold behavior.)
             */
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F999", "PT-X"))
                    .thenReturn(Optional.empty());
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F999"))
                    .thenReturn(Optional.empty());

            BigDecimal resultWithMocks = service.resolveThreshold("F999", "PT-X");
            assertEquals(new BigDecimal("10.00"), resultWithMocks,
                    "Without any DB config, cost variance threshold = 10.00% (not 75/150). " +
                    "CostVarianceServiceImpl.DEFAULT_THRESHOLD = '10.00'");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // computeVariancePct — 精确数值
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("computeVariancePct — 超支百分比精确计算")
    class ComputeVariancePctTests {

        @Test
        @DisplayName("CV-10: 超支场景 — actual>standard → 正值")
        void actualAboveStandard_positiveVariance() {
            /*
             * Hand-calc: actual=110, standard=100
             * (110-100)/100 * 100 = 10.00%
             */
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("110"), new BigDecimal("100"));
            assertEquals(new BigDecimal("10.00"), result,
                    "(110-100)/100×100 = 10.00%");
        }

        @Test
        @DisplayName("CV-11: 节约场景 — actual<standard → 负值")
        void actualBelowStandard_negativeVariance() {
            /*
             * Hand-calc: actual=90, standard=100
             * (90-100)/100 * 100 = -10.00%
             */
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("90"), new BigDecimal("100"));
            assertEquals(new BigDecimal("-10.00"), result,
                    "(90-100)/100×100 = -10.00%");
        }

        @Test
        @DisplayName("CV-12: 无偏差 — actual=standard → 0.00%")
        void actualEqualsStandard_zeroVariance() {
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("100"), new BigDecimal("100"));
            assertEquals(new BigDecimal("0.00"), result,
                    "(100-100)/100×100 = 0.00%");
        }

        @Test
        @DisplayName("CV-13: 非整除超支 — scale-2 HALF_UP 验证")
        void fractionalVariance_halfUpScale2() {
            /*
             * Hand-calc: actual=103, standard=300
             * (103-300)/300 * 100 = (-197/300) * 100
             * -197/300 = -0.656666... → scale-6 HALF_UP = -0.656667
             * × 100 = -65.6667
             * setScale(2, HALF_UP) = -65.67
             */
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("103"), new BigDecimal("300"));
            assertEquals(new BigDecimal("-65.67"), result,
                    "(103-300)/300×100 = -65.67% (HALF_UP scale-2)");
        }

        @Test
        @DisplayName("CV-14: 超支百分比超 100% — 无上限限制")
        void varianceExceeds100Pct_noUpperBound() {
            /*
             * Hand-calc: actual=250, standard=100
             * (250-100)/100 * 100 = 150.00%
             */
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("250"), new BigDecimal("100"));
            assertEquals(new BigDecimal("150.00"), result,
                    "(250-100)/100×100 = 150.00% (no upper bound)");
        }

        @Test
        @DisplayName("CV-15: standard=0 → null (防止除以零)")
        void zeroStandard_returnsNull() {
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("100"), BigDecimal.ZERO);
            assertNull(result, "standard=0 must return null (no division by zero)");
        }

        @Test
        @DisplayName("CV-16: standard<0 → null")
        void negativeStandard_returnsNull() {
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("100"), new BigDecimal("-10"));
            assertNull(result, "standard<0 must return null");
        }

        @Test
        @DisplayName("CV-17: actual=null → null")
        void nullActual_returnsNull() {
            assertNull(service.computeVariancePct(null, new BigDecimal("100")));
        }

        @Test
        @DisplayName("CV-18: standard=null → null")
        void nullStandard_returnsNull() {
            assertNull(service.computeVariancePct(new BigDecimal("100"), null));
        }

        @Test
        @DisplayName("CV-19: 精确小数超支 — HALF_UP 向上舍入")
        void precisionRoundingHalfUp() {
            /*
             * Hand-calc: actual=100.5, standard=100
             * (100.5-100)/100 * 100 = 0.5 / 100 * 100 = 0.5
             * setScale(2, HALF_UP) = 0.50
             */
            BigDecimal result = service.computeVariancePct(
                    new BigDecimal("100.5"), new BigDecimal("100"));
            assertEquals(new BigDecimal("0.50"), result,
                    "(100.5-100)/100×100 = 0.50%");
        }

        @Test
        @DisplayName("CV-20: 临界值 — variance=10.00% 等于系统默认阈值 (boundary at-threshold)")
        void thresholdBoundaryExact() {
            /*
             * Verify: if actual = standard * 1.10 → variance = exactly 10.00%
             * This locks that variance = system default threshold = "exactly at the boundary".
             * Hand-calc: actual=110, standard=100 → (110-100)/100×100 = 10.00
             * DEFAULT_THRESHOLD is package-private; tested via literal "10.00".
             */
            BigDecimal variance = service.computeVariancePct(
                    new BigDecimal("110"), new BigDecimal("100"));
            // system default threshold = "10.00" (verified via CV-2 / CV-6 behavior)
            BigDecimal defaultThreshold = new BigDecimal("10.00");

            assertEquals(0, variance.compareTo(defaultThreshold),
                    "variance(110, 100)=10.00 should equal DEFAULT_THRESHOLD=10.00 (boundary)");
        }
    }
}

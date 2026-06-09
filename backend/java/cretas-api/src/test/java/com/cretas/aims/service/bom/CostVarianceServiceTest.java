package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.ProductCostVarianceConfig;
import com.cretas.aims.repository.bom.ProductCostVarianceConfigRepository;
import com.cretas.aims.service.bom.impl.CostVarianceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SP3: CostVarianceService 阈值解析单元测试.
 */
@DisplayName("SP3: CostVarianceService 单元测试")
class CostVarianceServiceTest {

    @Mock
    private ProductCostVarianceConfigRepository configRepo;

    @InjectMocks
    private CostVarianceServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ─────────────────── resolveThreshold ───────────────────

    @Nested
    @DisplayName("resolveThreshold")
    class ResolveThreshold {

        @Test
        @DisplayName("产品专属配置优先 > 工厂全局 > 系统默认")
        void productSpecificHasPriority() {
            var specific = new ProductCostVarianceConfig();
            specific.setVarianceThreshold(new BigDecimal("5.00"));
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull("F001", "PT001"))
                    .thenReturn(Optional.of(specific));

            BigDecimal result = service.resolveThreshold("F001", "PT001");
            assertEquals(0, result.compareTo(new BigDecimal("5.00")));
        }

        @Test
        @DisplayName("无产品配置 → 工厂全局")
        void fallsBackToFactoryGlobal() {
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull(any(), any()))
                    .thenReturn(Optional.empty());
            var global = new ProductCostVarianceConfig();
            global.setVarianceThreshold(new BigDecimal("8.50"));
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F001"))
                    .thenReturn(Optional.of(global));

            BigDecimal result = service.resolveThreshold("F001", "PT001");
            assertEquals(0, result.compareTo(new BigDecimal("8.50")));
        }

        @Test
        @DisplayName("无任何配置 → 系统默认 10%")
        void fallsBackToSystemDefault() {
            when(configRepo.findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNull(any(), any()))
                    .thenReturn(Optional.empty());
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull(any()))
                    .thenReturn(Optional.empty());

            BigDecimal result = service.resolveThreshold("F001", "PT001");
            assertEquals(0, result.compareTo(new BigDecimal("10.00")),
                    "系统默认阈值应为 10%");
        }

        @Test
        @DisplayName("productTypeId=null → 直接查工厂全局")
        void nullProductTypeLookupGlobal() {
            var global = new ProductCostVarianceConfig();
            global.setVarianceThreshold(new BigDecimal("12.00"));
            when(configRepo.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrueAndDeletedAtIsNull("F001"))
                    .thenReturn(Optional.of(global));

            BigDecimal result = service.resolveThreshold("F001", null);
            assertEquals(0, result.compareTo(new BigDecimal("12.00")));
        }

        @Test
        @DisplayName("factoryId=null → 直接返系统默认")
        void nullFactoryIdReturnsDefault() {
            BigDecimal result = service.resolveThreshold(null, "PT001");
            assertEquals(0, result.compareTo(new BigDecimal("10.00")),
                    "factoryId null 时应返系统默认 10%");
        }
    }

    // ─────────────────── computeVariancePct ───────────────────

    @Nested
    @DisplayName("computeVariancePct")
    class ComputeVariancePct {

        @Test
        @DisplayName("超支: actual=115, standard=100 → 15%")
        void overrun() {
            BigDecimal pct = service.computeVariancePct(
                    new BigDecimal("115"), new BigDecimal("100"));
            assertEquals(0, pct.compareTo(new BigDecimal("15.00")));
        }

        @Test
        @DisplayName("节约: actual=90, standard=100 → -10%")
        void saving() {
            BigDecimal pct = service.computeVariancePct(
                    new BigDecimal("90"), new BigDecimal("100"));
            assertEquals(0, pct.compareTo(new BigDecimal("-10.00")));
        }

        @Test
        @DisplayName("零差: actual=standard → 0%")
        void zeroVariance() {
            BigDecimal pct = service.computeVariancePct(
                    new BigDecimal("100"), new BigDecimal("100"));
            assertEquals(0, pct.compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("actual=null → null")
        void nullActualReturnsNull() {
            assertNull(service.computeVariancePct(null, new BigDecimal("100")));
        }

        @Test
        @DisplayName("standard=null → null")
        void nullStandardReturnsNull() {
            assertNull(service.computeVariancePct(new BigDecimal("100"), null));
        }

        @Test
        @DisplayName("standard=0 → null (防除零)")
        void zeroStandardReturnsNull() {
            assertNull(service.computeVariancePct(new BigDecimal("100"), BigDecimal.ZERO));
        }

        @Test
        @DisplayName("scale-2 HALF_UP: actual=103, standard=97 → 6.19% (not 6.18%)")
        void scaleRounding() {
            // (103 - 97) / 97 * 100 = 6.1855... → 6.19 HALF_UP
            BigDecimal pct = service.computeVariancePct(
                    new BigDecimal("103"), new BigDecimal("97"));
            assertEquals(0, pct.compareTo(new BigDecimal("6.19")));
        }
    }
}

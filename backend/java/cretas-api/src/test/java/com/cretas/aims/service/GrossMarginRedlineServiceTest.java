package com.cretas.aims.service;

import com.cretas.aims.dto.pricing.GrossMarginCheckResult;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.pricing.FactoryGrossMarginConfigRepository;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.pricing.impl.GrossMarginRedlineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SP5: GrossMarginRedlineService 单元测试.
 *
 * <p>覆盖毛利红线检查的三条主路径:
 * <ol>
 *   <li>未配置标准成本 / 毛利率 → skipped</li>
 *   <li>报价 ≥ minPrice → pass</li>
 *   <li>报价 < minPrice → warn</li>
 * </ol>
 * 配置优先级: product-level > factory-wide default.
 *
 * <p><strong>安全回归</strong>: GrossMarginCheckResult 不含 minPrice / standardCost / targetGrossMargin.
 */
@ExtendWith(MockitoExtension.class)
class GrossMarginRedlineServiceTest {

    @Mock
    private ProductTypeRepository productTypeRepository;

    @Mock
    private FactoryGrossMarginConfigRepository configRepository;

    private GrossMarginRedlineService service;

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "PT-001";

    @BeforeEach
    void setUp() {
        service = new GrossMarginRedlineServiceImpl(productTypeRepository, configRepository);
    }

    // ========================================================
    // 路径 1: 未配置 → skipped
    // ========================================================

    @Test
    @DisplayName("SP5-GM-01: 产品无标准成本 → skipped")
    void checkMargin_noStandardCost_skipped() {
        ProductType pt = stubProductType(null, null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowRedline()).isNull();
        assertThat(result.getWarningMessage()).contains("跳过");
    }

    @Test
    @DisplayName("SP5-GM-02: 产品有标准成本但无毛利率配置（产品级+工厂级均无）→ skipped")
    void checkMargin_noGrossMarginConfig_skipped() {
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.empty());
        when(configRepository.findFactoryDefault(FACTORY_ID)).thenReturn(Optional.empty());

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowRedline()).isNull();
        assertThat(result.getWarningMessage()).contains("跳过");
    }

    // ========================================================
    // 路径 2: pass (报价 >= minPrice)
    // ========================================================

    @Test
    @DisplayName("SP5-GM-03: 产品级毛利率配置，报价 == minPrice → pass")
    void checkMargin_priceEqualsMinPrice_pass() {
        // standardCost=80, targetGrossMargin=0.20 → minPrice = 80 / (1-0.20) = 100.00
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubConfig(new BigDecimal("0.2000"))));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowRedline()).isFalse();
        assertThat(result.getWarningMessage()).contains("满足");
    }

    @Test
    @DisplayName("SP5-GM-04: 报价 > minPrice → pass")
    void checkMargin_priceAboveMinPrice_pass() {
        // standardCost=80, targetGrossMargin=0.30 → minPrice = 80 / 0.70 ≈ 114.29
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubConfig(new BigDecimal("0.3000"))));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("120.00"));

        assertThat(result.getBelowRedline()).isFalse();
    }

    // ========================================================
    // 路径 3: warn (报价 < minPrice)
    // ========================================================

    @Test
    @DisplayName("SP5-GM-05: 报价 < minPrice → warn")
    void checkMargin_priceBelowMinPrice_warn() {
        // standardCost=80, targetGrossMargin=0.30 → minPrice ≈ 114.29, 报价100 < 114.29
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubConfig(new BigDecimal("0.3000"))));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowRedline()).isTrue();
        assertThat(result.getWarningMessage()).contains("低于毛利红线");
    }

    // ========================================================
    // 路径 4: 配置优先级 — product-level > factory-wide
    // ========================================================

    @Test
    @DisplayName("SP5-GM-06: 产品级配置存在，应优先使用，工厂级不被调用")
    void checkMargin_productLevelConfigTakesPriority() {
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubConfig(new BigDecimal("0.2000"))));

        service.checkMargin(FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("105.00"));

        verify(configRepository, never()).findFactoryDefault(anyString());
    }

    @Test
    @DisplayName("SP5-GM-07: 产品级无配置，回退工厂全局配置")
    void checkMargin_fallbackToFactoryDefault() {
        // standardCost=80, factoryDefault=0.25 → minPrice = 80/0.75 ≈ 106.67, 报价110 >= 106.67 pass
        ProductType pt = stubProductType(new BigDecimal("80.00"), null);
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));
        when(configRepository.findByFactoryIdAndProductTypeIdAndIsActiveTrue(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.empty());
        when(configRepository.findFactoryDefault(FACTORY_ID))
                .thenReturn(Optional.of(stubConfig(new BigDecimal("0.2500"))));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("110.00"));

        assertThat(result.getBelowRedline()).isFalse();
        verify(configRepository).findFactoryDefault(FACTORY_ID);
    }

    @Test
    @DisplayName("SP5-GM-08: ProductType 上有 targetGrossMargin override，直接使用，不查 config repo")
    void checkMargin_productTypeHasTargetGrossMarginOverride_usedDirectly() {
        // ProductType.targetGrossMargin=0.20, standardCost=80 → minPrice=100
        ProductType pt = stubProductType(new BigDecimal("80.00"), new BigDecimal("0.2000"));
        when(productTypeRepository.findById(PRODUCT_TYPE_ID)).thenReturn(Optional.of(pt));

        GrossMarginCheckResult result = service.checkMargin(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowRedline()).isFalse();
        verify(configRepository, never()).findByFactoryIdAndProductTypeIdAndIsActiveTrue(anyString(), anyString());
        verify(configRepository, never()).findFactoryDefault(anyString());
    }

    // ========================================================
    // 安全回归: GrossMarginCheckResult 不得包含 price-sensitive 字段
    // ========================================================

    @Test
    @DisplayName("SP5-GM-09: [安全] GrossMarginCheckResult 仅含 belowRedline + warningMessage，无 minPrice/standardCost/targetGrossMargin")
    void checkMargin_resultHasNoSensitiveFields() throws NoSuchFieldException {
        // 验证 DTO class 结构 — 不含敏感字段
        Class<GrossMarginCheckResult> clazz = GrossMarginCheckResult.class;

        assertThat(clazz.getDeclaredFields())
                .as("GrossMarginCheckResult 不得包含 minPrice 字段")
                .noneMatch(f -> f.getName().equals("minPrice"));
        assertThat(clazz.getDeclaredFields())
                .as("GrossMarginCheckResult 不得包含 standardCost 字段")
                .noneMatch(f -> f.getName().equals("standardCost"));
        assertThat(clazz.getDeclaredFields())
                .as("GrossMarginCheckResult 不得包含 targetGrossMargin 字段")
                .noneMatch(f -> f.getName().equals("targetGrossMargin"));

        // 只能有这两个业务字段
        long fieldCount = java.util.Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .count();
        assertThat(fieldCount).as("GrossMarginCheckResult 应恰好有 2 个字段 (belowRedline + warningMessage)").isEqualTo(2);
    }

    // ========================================================
    // Helpers
    // ========================================================

    private ProductType stubProductType(BigDecimal standardCost, BigDecimal targetGrossMargin) {
        ProductType pt = new ProductType();
        pt.setId(PRODUCT_TYPE_ID);
        pt.setFactoryId(FACTORY_ID);
        pt.setName("测试产品");
        pt.setUnit("kg");
        pt.setIsActive(true);
        pt.setCreatedBy(1L);
        pt.setStandardCost(standardCost);
        pt.setTargetGrossMargin(targetGrossMargin);
        return pt;
    }

    private FactoryGrossMarginConfig stubConfig(BigDecimal targetGrossMargin) {
        FactoryGrossMarginConfig config = new FactoryGrossMarginConfig();
        config.setId("cfg-001");
        config.setFactoryId(FACTORY_ID);
        config.setTargetGrossMargin(targetGrossMargin);
        config.setIsActive(true);
        config.setCreatedBy(1L);
        return config;
    }
}

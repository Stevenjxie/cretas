package com.cretas.aims.service;

import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.impl.EstimatePriceCheckServiceImpl;
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
 * P1 #33: EstimatePriceCheckService 单元测试 (销售价 ≥ 研发预估价 跨流校验).
 *
 * <p>覆盖估价校验各路径:
 * <ol>
 *   <li>无报价任务 → skipped</li>
 *   <li>报价任务有 finalPrice, 销售价 < finalPrice → belowEstimate (warn)</li>
 *   <li>报价任务有 finalPrice, 销售价 ≥ finalPrice → pass</li>
 *   <li>finalPrice 为 null, 用 suggestedPrice 比较</li>
 *   <li>finalPrice + suggestedPrice 均 null (研发未报价) → skipped (诚实跳过)</li>
 *   <li>预估价 ≤ 0 → skipped (无效预估价)</li>
 *   <li>finalPrice 优先于 suggestedPrice</li>
 *   <li>入参 null → skipped (双保险)</li>
 * </ol>
 *
 * <p><strong>安全回归</strong>: EstimatePriceCheckResult 不含 estimatePrice / suggestedPrice /
 * finalPrice (只 boolean + 文案)。
 */
@ExtendWith(MockitoExtension.class)
class EstimatePriceCheckServiceTest {

    @Mock
    private QuotationTaskRepository quotationTaskRepository;

    private EstimatePriceCheckService service;

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "PT-001";

    @BeforeEach
    void setUp() {
        service = new EstimatePriceCheckServiceImpl(quotationTaskRepository);
    }

    // ========================================================
    // 路径 1: 无报价任务 → skipped
    // ========================================================

    @Test
    @DisplayName("#33-EP-01: 无研发报价任务 → skipped (诚实跳过, 不警告)")
    void check_noQuotationTask_skipped() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(Optional.empty());

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowEstimate()).isNull();
        assertThat(result.getWarningMessage()).contains("跳过");
    }

    // ========================================================
    // 路径 2: 销售价 < finalPrice → belowEstimate
    // ========================================================

    @Test
    @DisplayName("#33-EP-02: 销售价 80 < 研发预估价(finalPrice 100) → belowEstimate=true")
    void check_belowFinalPrice_warns() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(new BigDecimal("100.00"), null)));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("80.00"));

        assertThat(result.getBelowEstimate()).isTrue();
        assertThat(result.getWarningMessage()).contains("研发预估价");
        // 脱敏: 不含预估价绝对值 / 成本
        assertThat(result.getWarningMessage()).doesNotContain("100");
        assertThat(result.getWarningMessage()).doesNotContain("成本");
    }

    // ========================================================
    // 路径 3: 销售价 ≥ finalPrice → pass
    // ========================================================

    @Test
    @DisplayName("#33-EP-03: 销售价 120 ≥ 研发预估价(finalPrice 100) → pass")
    void check_aboveFinalPrice_pass() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(new BigDecimal("100.00"), null)));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("120.00"));

        assertThat(result.getBelowEstimate()).isFalse();
    }

    @Test
    @DisplayName("#33-EP-03b: 销售价 = 研发预估价 (边界相等) → pass (compareTo >= 0)")
    void check_equalsFinalPrice_pass() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(new BigDecimal("100.00"), null)));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("100.00"));

        assertThat(result.getBelowEstimate()).isFalse();
    }

    // ========================================================
    // 路径 4: finalPrice null → 用 suggestedPrice
    // ========================================================

    @Test
    @DisplayName("#33-EP-04: finalPrice 缺失 → 用 suggestedPrice(90) 比较, 销售价 80 < 90 → belowEstimate")
    void check_fallbackToSuggestedPrice_warns() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(null, new BigDecimal("90.00"))));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("80.00"));

        assertThat(result.getBelowEstimate()).isTrue();
    }

    // ========================================================
    // 路径 5: finalPrice + suggestedPrice 均 null → skipped
    // ========================================================

    @Test
    @DisplayName("#33-EP-05: 研发未报价 (finalPrice + suggestedPrice 均 null) → skipped (不伪造)")
    void check_noEstimatePrices_skipped() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(null, null)));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("80.00"));

        assertThat(result.getBelowEstimate()).isNull();
        assertThat(result.getWarningMessage()).contains("跳过");
    }

    // ========================================================
    // 路径 6: 预估价 <= 0 → skipped
    // ========================================================

    @Test
    @DisplayName("#33-EP-06: 预估价为 0 → skipped (不对 0 价做'低于'误判)")
    void check_zeroEstimate_skipped() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(BigDecimal.ZERO, null)));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("80.00"));

        assertThat(result.getBelowEstimate()).isNull();
    }

    // ========================================================
    // 路径 7: finalPrice 优先 suggestedPrice
    // ========================================================

    @Test
    @DisplayName("#33-EP-07: finalPrice(100) 优先于 suggestedPrice(50), 销售价 80 < 100 → belowEstimate")
    void check_finalPricePriorityOverSuggested() {
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(stubTask(new BigDecimal("100.00"), new BigDecimal("50.00"))));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, new BigDecimal("80.00"));

        // 用 finalPrice(100) → 80 < 100 → below; 若误用 suggestedPrice(50) → 80 > 50 → pass (会失败)
        assertThat(result.getBelowEstimate()).isTrue();
    }

    // ========================================================
    // 路径 8: 入参 null → skipped (双保险)
    // ========================================================

    @Test
    @DisplayName("#33-EP-08: sellingPrice null → skipped (不查库)")
    void check_nullSellingPrice_skipped() {
        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, PRODUCT_TYPE_ID, null);

        assertThat(result.getBelowEstimate()).isNull();
        verify(quotationTaskRepository, never())
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(anyString(), anyString());
    }

    @Test
    @DisplayName("#33-EP-08b: productTypeId null → skipped (不查库)")
    void check_nullProductTypeId_skipped() {
        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_ID, null, new BigDecimal("80.00"));

        assertThat(result.getBelowEstimate()).isNull();
        verify(quotationTaskRepository, never())
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(anyString(), anyString());
    }

    // ========================================================
    // Helpers
    // ========================================================

    private QuotationTask stubTask(BigDecimal finalPrice, BigDecimal suggestedPrice) {
        QuotationTask t = new QuotationTask();
        t.setId("QT-001");
        t.setFactoryId(FACTORY_ID);
        t.setProductTypeId(PRODUCT_TYPE_ID);
        t.setFinalPrice(finalPrice);
        t.setSuggestedPrice(suggestedPrice);
        return t;
    }
}

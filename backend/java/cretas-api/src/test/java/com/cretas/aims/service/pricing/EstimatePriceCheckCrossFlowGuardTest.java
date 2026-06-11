package com.cretas.aims.service.pricing;

import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.pricing.impl.EstimatePriceCheckServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * R11 専属テスト: 销售价 ≥ 研发预估价 跨流校验 — 安全约束 + 边界 + DTO 结构测试.
 *
 * <p>本测试补充 {@code EstimatePriceCheckServiceTest}（路径覆盖）的以下专属场景:
 * <ol>
 *   <li><strong>安全约束</strong>: 返回 DTO 绝对不含 estimatePrice / finalPrice /
 *       suggestedPrice 字段 (价格敏感, 防泄露给销售角色).</li>
 *   <li><strong>工厂隔离</strong>: 不同工厂的 QuotationTask 不交叉污染.</li>
 *   <li><strong>warningMessage 不含价格数值</strong>: belowEstimate=true 时文案不暴露具体价格.</li>
 *   <li><strong>DTO 静态工厂方法</strong>: pass/belowEstimate/skipped 三个 static factory 逻辑正确.</li>
 *   <li><strong>边界: 销售价 = 预估价</strong>: compareTo >=0 边界 (等于时应 pass).</li>
 *   <li><strong>结果 DTO 无 estimatePrice 字段</strong>: 反射验证 DTO 不包含价格字段.</li>
 * </ol>
 *
 * <p><strong>决策对齐</strong>: 低于研发预估价 = 警告放行 (#693 毛利红线范式), 非阻断 409.
 * 防呆 Rule 1 (fool-proof-design.md): 提交前显示预警边界, 而非提交后被拒.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("R11 EstimatePrice 跨流校验 — 安全约束 + 边界 + DTO 结构")
class EstimatePriceCheckCrossFlowGuardTest {

    @Mock
    private QuotationTaskRepository quotationTaskRepository;

    private EstimatePriceCheckService service;

    private static final String FACTORY_A = "F006";
    private static final String FACTORY_B = "F999";
    private static final String PRODUCT_TYPE_ID = "PT-猪舌-001";

    @BeforeEach
    void setUp() {
        service = new EstimatePriceCheckServiceImpl(quotationTaskRepository);
    }

    // =========================================================================
    // R11-SEC-01: 安全约束 — EstimatePriceCheckResult 无 estimatePrice 等敏感字段
    // =========================================================================

    @Test
    @DisplayName("R11-SEC-01: DTO 不含 estimatePrice / finalPrice / suggestedPrice / totalCost 字段 (防泄露)")
    void dto_hasNoPriceSensitiveFields() {
        Field[] fields = EstimatePriceCheckResult.class.getDeclaredFields();
        for (Field f : fields) {
            String name = f.getName();
            assertThat(name)
                    .as("DTO 不得含价格敏感字段: " + name)
                    .doesNotContain("estimatePrice")
                    .doesNotContain("finalPrice")
                    .doesNotContain("suggestedPrice")
                    .doesNotContain("totalCost")
                    .doesNotContain("materialCost")
                    .doesNotContain("overheadCost");
        }
    }

    // =========================================================================
    // R11-SEC-02: warningMessage 不含研发预估价绝对值
    // =========================================================================

    @Test
    @DisplayName("R11-SEC-02: belowEstimate=true 时 warningMessage 不含 estimatePrice 数值 (防泄露)")
    void warningMessage_doesNotContainPriceValue() {
        // 不管 estimatePrice 是多少, message 都不应泄露数值
        EstimatePriceCheckResult result = EstimatePriceCheckResult.belowEstimate();
        String msg = result.getWarningMessage();
        assertThat(msg).doesNotContain("100.00");
        assertThat(msg).doesNotContain("200");
        assertThat(msg).doesNotContain("¥");
        assertThat(msg).doesNotContain("元");
        assertThat(msg).contains("研发预估价"); // 语义存在
    }

    // =========================================================================
    // R11-SEC-03: pass 时 warningMessage 不含价格数值
    // =========================================================================

    @Test
    @DisplayName("R11-SEC-03: pass 时 warningMessage 不含任何价格数值")
    void passMessage_doesNotContainPriceValue() {
        EstimatePriceCheckResult result = EstimatePriceCheckResult.pass();
        String msg = result.getWarningMessage();
        assertThat(msg).doesNotContain("100.00");
        assertThat(msg).doesNotContain("元");
        assertThat(msg).contains("不低于");
    }

    // =========================================================================
    // R11-ISO-01: 工厂隔离 — 不同工厂的 QuotationTask 不交叉
    // =========================================================================

    @Test
    @DisplayName("R11-ISO-01: 工厂隔离 — F006 有报价任务但 F999 没有 → F999 返回 skipped (不串用)")
    void factoryIsolation_differentFactories() {
        // F006 有报价任务
        QuotationTask taskF006 = stubTask(FACTORY_A, new BigDecimal("100.00"), null);
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_A, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(taskF006));
        // F999 没有报价任务
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_B, PRODUCT_TYPE_ID))
                .thenReturn(Optional.empty());

        // F006: 80 < 100 → belowEstimate
        EstimatePriceCheckResult resultA = service.checkAgainstEstimate(
                FACTORY_A, PRODUCT_TYPE_ID, new BigDecimal("80.00"));
        assertThat(resultA.getBelowEstimate()).isTrue();

        // F999: 没有报价 → skipped
        EstimatePriceCheckResult resultB = service.checkAgainstEstimate(
                FACTORY_B, PRODUCT_TYPE_ID, new BigDecimal("80.00"));
        assertThat(resultB.getBelowEstimate()).isNull();
        assertThat(resultB.getWarningMessage()).contains("跳过");

        // 验证: F999 没有去查 F006 的数据
        verify(quotationTaskRepository, times(1))
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY_B, PRODUCT_TYPE_ID);
        verify(quotationTaskRepository, never())
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                        eq(FACTORY_B), argThat(pt -> pt != null && pt.equals(PRODUCT_TYPE_ID + "_leak")));
    }

    // =========================================================================
    // R11-BOUND-01: 边界 — 销售价精确等于预估价 → pass (compareTo ≥ 0)
    // =========================================================================

    @Test
    @DisplayName("R11-BOUND-01: 销售价精确 = 研发预估价 → pass (边界 compareTo == 0)")
    void exactBoundary_equalsEstimate_pass() {
        QuotationTask task = stubTask(FACTORY_A, new BigDecimal("99.99"), null);
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_A, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(task));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_A, PRODUCT_TYPE_ID, new BigDecimal("99.99"));

        assertThat(result.getBelowEstimate()).isFalse();
        assertThat(result.getWarningMessage()).contains("不低于");
    }

    // =========================================================================
    // R11-BOUND-02: 边界 — 销售价比预估价低 0.01 → belowEstimate
    // =========================================================================

    @Test
    @DisplayName("R11-BOUND-02: 销售价比研发预估价低 0.01 (99.98 vs 99.99) → belowEstimate")
    void nearBoundary_onecentBelow_warns() {
        QuotationTask task = stubTask(FACTORY_A, new BigDecimal("99.99"), null);
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_A, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(task));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_A, PRODUCT_TYPE_ID, new BigDecimal("99.98"));

        assertThat(result.getBelowEstimate()).isTrue();
    }

    // =========================================================================
    // R11-DTO-01: DTO 静态工厂方法 — pass() / belowEstimate() / skipped() 结构正确
    // =========================================================================

    @Test
    @DisplayName("R11-DTO-01: EstimatePriceCheckResult.pass() — belowEstimate=false, message 含 '不低于'")
    void dtoFactory_pass() {
        EstimatePriceCheckResult r = EstimatePriceCheckResult.pass();
        assertThat(r.getBelowEstimate()).isFalse();
        assertThat(r.getWarningMessage()).isNotBlank();
        assertThat(r.getWarningMessage()).contains("不低于");
    }

    @Test
    @DisplayName("R11-DTO-02: EstimatePriceCheckResult.belowEstimate() — belowEstimate=true, message 含 '研发预估价'")
    void dtoFactory_belowEstimate() {
        EstimatePriceCheckResult r = EstimatePriceCheckResult.belowEstimate();
        assertThat(r.getBelowEstimate()).isTrue();
        assertThat(r.getWarningMessage()).isNotBlank();
        assertThat(r.getWarningMessage()).contains("研发预估价");
    }

    @Test
    @DisplayName("R11-DTO-03: EstimatePriceCheckResult.skipped() — belowEstimate=null, message 含 '跳过'")
    void dtoFactory_skipped() {
        EstimatePriceCheckResult r = EstimatePriceCheckResult.skipped();
        assertThat(r.getBelowEstimate()).isNull();
        assertThat(r.getWarningMessage()).isNotBlank();
        assertThat(r.getWarningMessage()).contains("跳过");
    }

    // =========================================================================
    // R11-WARN-01: 非阻断验证 — checkAgainstEstimate 本身不抛异常 (只返 DTO)
    // =========================================================================

    @Test
    @DisplayName("R11-WARN-01: 无论结果如何 checkAgainstEstimate 不抛异常 (警告放行, 非阻断 409)")
    void checkAgainstEstimate_neverThrows() {
        QuotationTask task = stubTask(FACTORY_A, new BigDecimal("50.00"), null);
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_A, PRODUCT_TYPE_ID))
                .thenReturn(Optional.of(task));

        // 远低于预估价 — 只应返回 belowEstimate DTO, 不应抛异常
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.checkAgainstEstimate(FACTORY_A, PRODUCT_TYPE_ID,
                        new BigDecimal("0.01")));
    }

    // =========================================================================
    // R11-TENANT-01: 多产品类型 — 只检查匹配产品的报价任务
    // =========================================================================

    @Test
    @DisplayName("R11-TENANT-01: 多产品类型 — checkAgainstEstimate 仅查指定 productTypeId 的最新报价")
    void multiProduct_onlyQueriesMatchingProductType() {
        String ptCow = "PT-牛腱-002";
        QuotationTask taskCow = stubTask(FACTORY_A, new BigDecimal("150.00"), null);
        when(quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(
                FACTORY_A, ptCow))
                .thenReturn(Optional.of(taskCow));

        EstimatePriceCheckResult result = service.checkAgainstEstimate(
                FACTORY_A, ptCow, new BigDecimal("160.00")); // above → pass

        assertThat(result.getBelowEstimate()).isFalse();
        // 没有查猪舌的报价任务
        verify(quotationTaskRepository, never())
                .findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(FACTORY_A, PRODUCT_TYPE_ID);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private QuotationTask stubTask(String factoryId, BigDecimal finalPrice, BigDecimal suggestedPrice) {
        QuotationTask t = new QuotationTask();
        t.setId("QT-TEST-001");
        t.setFactoryId(factoryId);
        t.setProductTypeId(PRODUCT_TYPE_ID);
        t.setFinalPrice(finalPrice);
        t.setSuggestedPrice(suggestedPrice);
        t.setStatus("PENDING");
        return t;
    }
}

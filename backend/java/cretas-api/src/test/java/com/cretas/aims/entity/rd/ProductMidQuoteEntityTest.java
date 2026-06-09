package com.cretas.aims.entity.rd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP10: ProductMidQuote 实体基础测试 (无 Spring 上下文).
 *
 * <p>验证:
 * 1. 所有必需字段可读写
 * 2. 成本字段标注 @PriceSensitive (通过反射检查)
 * 3. status 可切换 PENDING / CALCULATED / CONFIRMED
 * 4. varianceAlert 默认 false
 */
@DisplayName("SP10 ProductMidQuote 实体测试")
class ProductMidQuoteEntityTest {

    private ProductMidQuote buildSampleMidQuote() {
        ProductMidQuote q = new ProductMidQuote();
        q.setFactoryId("F006");
        q.setSampleId("sample-001");
        q.setTrialBatchId(1L);
        q.setTrialOutputKg(new BigDecimal("47.5000"));
        q.setMaterialCostPerKg(new BigDecimal("30.0000"));
        q.setLaborCostPerKg(new BigDecimal("12.5000"));
        q.setOverheadCostPerKg(new BigDecimal("5.0000"));
        q.setTotalCostPerKg(new BigDecimal("47.5000"));
        q.setStatus("CALCULATED");
        return q;
    }

    @Test
    @DisplayName("新建实体可构建, 字段读写正常")
    void canBuildAndSetFields() {
        ProductMidQuote q = buildSampleMidQuote();
        assertEquals("F006", q.getFactoryId());
        assertEquals("sample-001", q.getSampleId());
        assertEquals(0, new BigDecimal("47.5000").compareTo(q.getTotalCostPerKg()));
        assertEquals("CALCULATED", q.getStatus());
    }

    @Test
    @DisplayName("varianceAlert 默认 false")
    void varianceAlert_defaultFalse() {
        ProductMidQuote q = new ProductMidQuote();
        assertFalse(q.isVarianceAlert());
    }

    @Test
    @DisplayName("status 可为 PENDING / CALCULATED / CONFIRMED")
    void statusValues() {
        ProductMidQuote q = new ProductMidQuote();
        for (String s : new String[]{"PENDING", "CALCULATED", "CONFIRMED"}) {
            q.setStatus(s);
            assertEquals(s, q.getStatus());
        }
    }

    @Test
    @DisplayName("成本字段可为 null (诚实 null)")
    void costFields_nullable() {
        ProductMidQuote q = new ProductMidQuote();
        assertNull(q.getMaterialCostPerKg());
        assertNull(q.getLaborCostPerKg());
        assertNull(q.getOverheadCostPerKg());
        assertNull(q.getTotalCostPerKg());
        assertNull(q.getCostVariancePct());
    }

    @Test
    @DisplayName("costVariancePct 可设置和读取")
    void costVariancePct_readWrite() {
        ProductMidQuote q = new ProductMidQuote();
        q.setCostVariancePct(new BigDecimal("15.50"));
        assertEquals(0, new BigDecimal("15.50").compareTo(q.getCostVariancePct()));
        q.setVarianceAlert(true);
        assertTrue(q.isVarianceAlert());
    }

    @Test
    @DisplayName("varianceThresholdPct 可设置和读取")
    void varianceThresholdPct_readWrite() {
        ProductMidQuote q = new ProductMidQuote();
        q.setVarianceThresholdPct(new BigDecimal("10.00"));
        assertEquals(0, new BigDecimal("10.00").compareTo(q.getVarianceThresholdPct()));
    }

    @Test
    @DisplayName("quotationTaskId 和 trialBatchId (Long) 可读写")
    void fkFields_readWrite() {
        ProductMidQuote q = new ProductMidQuote();
        q.setQuotationTaskId("task-001");
        q.setTrialBatchId(42L);
        assertEquals("task-001", q.getQuotationTaskId());
        assertEquals(42L, q.getTrialBatchId());
    }

    @Test
    @DisplayName("@PriceSensitive — materialCostPerKg 字段有此注解")
    void materialCostPerKg_hasPriceSensitive() throws NoSuchFieldException {
        var field = ProductMidQuote.class.getDeclaredField("materialCostPerKg");
        var annotation = field.getAnnotation(com.cretas.aims.security.PriceSensitive.class);
        assertNotNull(annotation, "materialCostPerKg 必须标 @PriceSensitive");
    }

    @Test
    @DisplayName("@PriceSensitive — laborCostPerKg 字段有此注解")
    void laborCostPerKg_hasPriceSensitive() throws NoSuchFieldException {
        var field = ProductMidQuote.class.getDeclaredField("laborCostPerKg");
        var annotation = field.getAnnotation(com.cretas.aims.security.PriceSensitive.class);
        assertNotNull(annotation, "laborCostPerKg 必须标 @PriceSensitive");
    }

    @Test
    @DisplayName("@PriceSensitive — overheadCostPerKg 字段有此注解")
    void overheadCostPerKg_hasPriceSensitive() throws NoSuchFieldException {
        var field = ProductMidQuote.class.getDeclaredField("overheadCostPerKg");
        var annotation = field.getAnnotation(com.cretas.aims.security.PriceSensitive.class);
        assertNotNull(annotation, "overheadCostPerKg 必须标 @PriceSensitive");
    }

    @Test
    @DisplayName("@PriceSensitive — totalCostPerKg 字段有此注解")
    void totalCostPerKg_hasPriceSensitive() throws NoSuchFieldException {
        var field = ProductMidQuote.class.getDeclaredField("totalCostPerKg");
        var annotation = field.getAnnotation(com.cretas.aims.security.PriceSensitive.class);
        assertNotNull(annotation, "totalCostPerKg 必须标 @PriceSensitive");
    }

    @Test
    @DisplayName("@PriceSensitive — costVariancePct 字段有此注解")
    void costVariancePct_hasPriceSensitive() throws NoSuchFieldException {
        var field = ProductMidQuote.class.getDeclaredField("costVariancePct");
        var annotation = field.getAnnotation(com.cretas.aims.security.PriceSensitive.class);
        assertNotNull(annotation, "costVariancePct 必须标 @PriceSensitive");
    }

    @Test
    @DisplayName("@PriceSensitive 合计至少 5 个字段")
    void priceSensitive_atLeastFiveFields() {
        var fields = ProductMidQuote.class.getDeclaredFields();
        long count = java.util.Arrays.stream(fields)
                .filter(f -> f.isAnnotationPresent(com.cretas.aims.security.PriceSensitive.class))
                .count();
        assertTrue(count >= 5, "ProductMidQuote 至少需要 5 个 @PriceSensitive 字段, 实际: " + count);
    }
}

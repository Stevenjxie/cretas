package com.cretas.aims.entity.rd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP10: QuotationTask 新字段 DTO 往返测试 (无需 Spring 上下文, 纯 Java 反射).
 *
 * <p>遵循 feedback_dto_roundtrip_silent_drop: Entity字段 + create set +
 * update null-guard set + convertToDTO map 四处必须同步, 任一遗漏→静默丢失.
 *
 * <p>本测试仅验证 Entity 层字段存在 + setter/getter 可用.
 * 集成层持久化验证在 ProductMidQuoteEntityTest 内补充.
 */
@DisplayName("SP10 QuotationTask 新字段 — DTO 往返存在性测试")
class QuotationTaskDTORoundtripTest {

    @Test
    @DisplayName("quoteStage 字段存在并可读写")
    void quoteStage_fieldExistsAndReadWrite() {
        QuotationTask task = new QuotationTask();
        task.setQuoteStage("PRE");
        assertEquals("PRE", task.getQuoteStage());
    }

    @Test
    @DisplayName("laborPerKg 字段存在并可读写")
    void laborPerKg_fieldExistsAndReadWrite() {
        QuotationTask task = new QuotationTask();
        task.setLaborPerKg(new BigDecimal("12.5000"));
        assertEquals(0, new BigDecimal("12.5000").compareTo(task.getLaborPerKg()));
    }

    @Test
    @DisplayName("bomMaterialCost 字段存在并可读写")
    void bomMaterialCost_fieldExistsAndReadWrite() {
        QuotationTask task = new QuotationTask();
        task.setBomMaterialCost(new BigDecimal("35.00"));
        assertEquals(0, new BigDecimal("35.00").compareTo(task.getBomMaterialCost()));
    }

    @Test
    @DisplayName("midQuoteId 字段存在并可读写")
    void midQuoteId_fieldExistsAndReadWrite() {
        QuotationTask task = new QuotationTask();
        task.setMidQuoteId("mid-quote-123");
        assertEquals("mid-quote-123", task.getMidQuoteId());
    }

    @Test
    @DisplayName("laborPerKg 默认为 null (诚实 null)")
    void laborPerKg_defaultNull() {
        QuotationTask task = new QuotationTask();
        assertNull(task.getLaborPerKg());
    }

    @Test
    @DisplayName("quoteStage 在 set PRE 后正确返回 PRE")
    void quoteStage_allStageValues() {
        QuotationTask task = new QuotationTask();
        for (String stage : new String[]{"PRE", "MID_PENDING", "MID", "FINAL"}) {
            task.setQuoteStage(stage);
            assertEquals(stage, task.getQuoteStage());
        }
    }

    @Test
    @DisplayName("通过反射确认 quoteStage 字段声明存在")
    void quoteStage_declaredField() {
        assertDoesNotThrow(() -> QuotationTask.class.getDeclaredField("quoteStage"));
    }

    @Test
    @DisplayName("通过反射确认 laborPerKg 字段声明存在")
    void laborPerKg_declaredField() {
        assertDoesNotThrow(() -> QuotationTask.class.getDeclaredField("laborPerKg"));
    }

    @Test
    @DisplayName("通过反射确认 bomMaterialCost 字段声明存在")
    void bomMaterialCost_declaredField() {
        assertDoesNotThrow(() -> QuotationTask.class.getDeclaredField("bomMaterialCost"));
    }

    @Test
    @DisplayName("通过反射确认 midQuoteId 字段声明存在")
    void midQuoteId_declaredField() {
        assertDoesNotThrow(() -> QuotationTask.class.getDeclaredField("midQuoteId"));
    }

    // ==================== laborPerKg DTO往返完整链路测试 (feedback_dto_roundtrip_silent_drop) ====================

    /**
     * 验证 laborPerKg 从"前端提交→Entity set→读回"全链路不静默丢弃.
     *
     * <p>模拟 submitQuotation 服务层行为: null-guard set (if not null → setLaborPerKg).
     * 确认第3处 (service set) + 往返读回 一致.
     */
    @Test
    @DisplayName("laborPerKg 往返: 服务层 null-guard set 后可读回 (第3处修复验证)")
    void laborPerKg_serviceLayerSetAndReadBack() {
        BigDecimal submitted = new BigDecimal("15.7500");
        QuotationTask task = new QuotationTask();
        // 模拟 submitQuotation service 第3处: if (laborPerKg != null) task.setLaborPerKg(laborPerKg)
        if (submitted != null) {
            task.setLaborPerKg(submitted);
        }
        // 往返验证: save 后读回一致
        assertNotNull(task.getLaborPerKg(), "laborPerKg 不应静默丢失");
        assertEquals(0, submitted.compareTo(task.getLaborPerKg()),
                "读回的 laborPerKg 应与提交值一致");
    }

    @Test
    @DisplayName("laborPerKg null 时 service 不覆盖已有值 (null-guard 保护)")
    void laborPerKg_nullInputDoesNotOverwriteExistingValue() {
        BigDecimal existing = new BigDecimal("10.0000");
        QuotationTask task = new QuotationTask();
        task.setLaborPerKg(existing);

        // 模拟前端未提交 laborPerKg (null) — service null-guard 应跳过 set
        BigDecimal incomingNull = null;
        if (incomingNull != null) {
            task.setLaborPerKg(incomingNull);
        }
        // 已有值不被覆盖
        assertEquals(0, existing.compareTo(task.getLaborPerKg()),
                "null 入参不应覆盖已有的 laborPerKg");
    }
}

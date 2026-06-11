package com.cretas.aims.entity.rd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * catalog 行31: 研发试样票务字段 — has_invoice 影响凭证科目.
 *
 * <p>需求原话: "澳阳的可能有票，另一家试样的没票；票务字段在研发试样阶段就需带，
 * 影响科目映射与试样成本核算"
 *
 * <p>科目映射规则:
 * <ul>
 *   <li>有票 (hasInvoice=true)  → 2221.02 应交税费-应交增值税-进项税额 (可抵扣)</li>
 *   <li>无票 (hasInvoice=false) → 6602    管理费用 (不可抵扣, 计入费用)</li>
 *   <li>未指定 (null)           → null    (向后兼容)</li>
 * </ul>
 */
@DisplayName("ProductSample 票务字段 (catalog 行31)")
class ProductSampleInvoiceFlagTest {

    // ──────────────────────────────────────────────────────────────────────────
    // 1. 字段存在性
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasInvoice 字段存在 (Boolean nullable)")
    void shouldHaveHasInvoiceField() {
        assertDoesNotThrow(() -> ProductSample.class.getDeclaredField("hasInvoice"));
    }

    @Test
    @DisplayName("invoiceNumber 字段存在")
    void shouldHaveInvoiceNumberField() {
        assertDoesNotThrow(() -> ProductSample.class.getDeclaredField("invoiceNumber"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. resolveVatSubjectCode — 有票 → 可抵扣科目
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("有票 → 科目 2221.02 (进项税可抵扣)")
    void withInvoice_subjectCodeIsInputVat() {
        ProductSample sample = new ProductSample();
        sample.setHasInvoice(true);

        assertEquals("2221.02", sample.resolveVatSubjectCode());
        assertEquals("应交税费-应交增值税-进项税额", sample.resolveVatSubjectName());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. resolveVatSubjectCode — 无票 → 费用科目
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无票 → 科目 6602 (管理费用, 不可抵扣)")
    void noInvoice_subjectCodeIsExpense() {
        ProductSample sample = new ProductSample();
        sample.setHasInvoice(false);

        assertEquals("6602", sample.resolveVatSubjectCode());
        assertEquals("管理费用", sample.resolveVatSubjectName());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. 向后兼容 — null 未指定
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("未指定 (null) → resolveVatSubjectCode 返回 null (向后兼容)")
    void unspecified_returnsNull() {
        ProductSample sample = new ProductSample();
        // hasInvoice 默认 null

        assertNull(sample.getHasInvoice());
        assertNull(sample.resolveVatSubjectCode());
        assertNull(sample.resolveVatSubjectName());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. invoiceNumber — 有票时可记录发票号, 无票时 null 合法
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("有票时可设置发票号")
    void withInvoice_invoiceNumberStored() {
        ProductSample sample = new ProductSample();
        sample.setHasInvoice(true);
        sample.setInvoiceNumber("VAT-20261023-001");

        assertEquals(true, sample.getHasInvoice());
        assertEquals("VAT-20261023-001", sample.getInvoiceNumber());
        assertEquals("2221.02", sample.resolveVatSubjectCode());
    }

    @Test
    @DisplayName("无票时 invoiceNumber 为 null 合法")
    void noInvoice_invoiceNumberIsNull() {
        ProductSample sample = new ProductSample();
        sample.setHasInvoice(false);

        assertNull(sample.getInvoiceNumber());
        assertEquals("6602", sample.resolveVatSubjectCode());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. 科目代码常量值校验
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("有票科目代码字面值精确匹配 2221.02")
    void vatSubjectCodeExactMatch() {
        ProductSample sample = new ProductSample();
        sample.setHasInvoice(true);

        // 严格断言: 不能是 "2221" 或 "22210200" 等变体
        assertEquals("2221.02", sample.resolveVatSubjectCode(),
                "有票进项税科目必须精确等于 2221.02 (与 PurchasePaymentVoucherGenerator.INPUT_VAT_CODE 一致)");
    }
}

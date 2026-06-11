package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherTemplate;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.finance.VoucherTemplateRepository;
import com.cretas.aims.service.impl.VoucherTemplateServiceImpl;
import com.cretas.aims.service.voucher.impl.PurchasePaymentVoucherGenerator;
import com.cretas.aims.service.voucher.impl.SalesReceiptVoucherGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #738 BLOCKING 回归测试 — 真实 template-first 路径 (非 null voucherTemplateService).
 *
 * <p><b>为什么必须这个测试</b>: {@link VoucherGeneratorsTest} 用 {@code new XxxGenerator()}
 * 直接构造, {@code voucherTemplateService} 字段未注入 (null) → 走 {@code buildEntries}
 * hardcoded 路径, 永远不触达 template-first。但 prod 真实路径
 * (VoucherController.createFromBusiness → registry → generate) 下, Spring 注入了
 * {@code voucherTemplateService}, 且 V20260605_03 已给每个工厂播了 is_default 模板 →
 * {@code AbstractVoucherGenerator.generate} 命中 template → renderEntries → **bypass
 * buildEntries**。Fable 抓到: #736 的三行价税分离 buildEntries 在真实路径下被旧两行 seed
 * 模板完全 bypass, 客户导金蝶拿到旧两行无税凭证。
 *
 * <p>本测试用<b>真实 {@link VoucherTemplateServiceImpl}</b> (mock 仅 repository) + 从
 * <b>迁移 V20261019_01 写入的同一份 entries_json</b> 解析出 template, 注入 generator,
 * 走完整 generate() 路径, 断言真实路径下产出三行价税分离 (含税) / 两行 (零税退化)。
 */
class VoucherTemplateTaxSplitRealPathTest {

    private VoucherTemplateRepository repository;
    private VoucherTemplateServiceImpl templateService;
    private final ObjectMapper mapper = new ObjectMapper();

    // ---- 与迁移 V20261019_01 完全一致的 entries_json (升级后的三行价税分离模板) ----
    private static final String SALES_RECEIPT_TPL_JSON = """
        [
          {"sortOrder":1,"subjectCode":"1122","subjectName":"应收账款","direction":"DEBIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"销售收入挂账(含税)"},
          {"sortOrder":2,"subjectCode":"6001","subjectName":"主营业务收入","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"销售订单收入(未税)"},
          {"sortOrder":3,"subjectCode":"2221.01","subjectName":"应交税费-应交增值税-销项税额","direction":"CREDIT","amountExpression":"#entity.taxAmount ?: 0","description":"销项税额","omitWhenZero":true}
        ]""";

    private static final String PURCHASE_PAYMENT_TPL_JSON = """
        [
          {"sortOrder":1,"subjectCode":"1405","subjectName":"库存商品","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"采购入库(未税)"},
          {"sortOrder":2,"subjectCode":"2202","subjectName":"应付账款","direction":"CREDIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"供应商应付(含税)"},
          {"sortOrder":3,"subjectCode":"2221.02","subjectName":"应交税费-应交增值税-进项税额","direction":"DEBIT","amountExpression":"#entity.taxAmount ?: 0","description":"进项税额","omitWhenZero":true}
        ]""";

    @BeforeEach
    void setUp() {
        repository = mock(VoucherTemplateRepository.class);
        templateService = new VoucherTemplateServiceImpl(repository);
    }

    private VoucherTemplate buildTemplateFromMigrationJson(VoucherType type, String name, String json) {
        try {
            List<VoucherTemplate.TemplateEntry> entries = mapper.readValue(
                    json, mapper.getTypeFactory().constructCollectionType(
                            List.class, VoucherTemplate.TemplateEntry.class));
            return VoucherTemplate.builder()
                    .id("tpl-" + type)
                    .factoryId("F006")
                    .voucherType(type)
                    .name(name)
                    .entries(entries)
                    .isDefault(true)
                    .isActive(true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("解析迁移模板 JSON 失败 (验证 omitWhenZero 字段可反序列化)", e);
        }
    }

    /** 注入真实 templateService + 让 repository 返回升级后的 seed 模板 (模拟 prod 已播默认模板). */
    private void wireTemplate(AbstractVoucherGenerator<?> gen, VoucherType type, String name, String json) {
        VoucherTemplate tpl = buildTemplateFromMigrationJson(type, name, json);
        when(repository.findActiveDefaultByFactoryAndType(eq("F006"), eq(type)))
                .thenReturn(Optional.of(tpl));
        ReflectionTestUtils.setField(gen, "voucherTemplateService", templateService);
    }

    // ============ SALES_RECEIPT 真实路径 ============

    @Test
    void salesReceipt_realTemplatePath_taxOrder_producesThreeLinesBalanced() {
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wireTemplate(gen, VoucherType.SALES_RECEIPT, "销售收款默认模板", SALES_RECEIPT_TPL_JSON);

        SalesOrder order = new SalesOrder();
        order.setId("so-real-tax");
        order.setOrderNumber("SO-REAL-TAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("1000.00"));  // 未税
        order.setTaxAmount(new BigDecimal("130.00"));     // 销项税 13%
        order.setCustomerId("cust-real");

        // 真实路径: generate() → voucherTemplateService.findActiveTemplate (非 null) → renderEntries
        Voucher v = gen.generate("F006", order);

        // 关键断言: 真实路径下产出三行 (证明 template-first 不再 bypass 税分离)
        assertEquals(3, v.getEntries().size(),
                "真实路径 (非 null voucherTemplateService) 含税订单必须三行 — 这是 #738 BLOCKING 核心");
        // 借 1122 应收 = 含税 1130.00
        VoucherEntry ar = entryByCode(v, "1122");
        assertEquals(0, new BigDecimal("1130.00").compareTo(ar.getDebit()));
        // 贷 6001 收入 = 未税 1000.00
        VoucherEntry rev = entryByCode(v, "6001");
        assertEquals(0, new BigDecimal("1000.00").compareTo(rev.getCredit()));
        // 贷 2221.01 销项税 = 130.00
        VoucherEntry vat = entryByCode(v, "2221.01");
        assertEquals(0, new BigDecimal("130.00").compareTo(vat.getCredit()));
        // 借贷加法平衡 (validateBalanced 在 generate() 内已跑, 这里再确认 totals)
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
        assertEquals(0, new BigDecimal("1130.00").compareTo(v.getTotalDebit()));
    }

    @Test
    void salesReceipt_realTemplatePath_zeroTax_degradesToTwoLines() {
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wireTemplate(gen, VoucherType.SALES_RECEIPT, "销售收款默认模板", SALES_RECEIPT_TPL_JSON);

        SalesOrder order = new SalesOrder();
        order.setId("so-real-notax");
        order.setOrderNumber("SO-REAL-NOTAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setTaxAmount(BigDecimal.ZERO);  // 零税
        order.setCustomerId("cust-real");

        Voucher v = gen.generate("F006", order);

        // 零税退化两行 (omitWhenZero 省略税行, 向后兼容)
        assertEquals(2, v.getEntries().size(),
                "零税订单真实路径应退化两行 (omitWhenZero 省略税行)");
        assertNull(entryByCodeOrNull(v, "2221.01"), "零税时销项税行应被省略");
        assertEquals(0, new BigDecimal("500.00").compareTo(entryByCode(v, "1122").getDebit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(entryByCode(v, "6001").getCredit()));
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
    }

    @Test
    void salesReceipt_realTemplatePath_nullTax_degradesToTwoLines() {
        // DB 列 NULL → SpEL Elvis (?: 0) 保证不 NPE, 退化两行
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wireTemplate(gen, VoucherType.SALES_RECEIPT, "销售收款默认模板", SALES_RECEIPT_TPL_JSON);

        SalesOrder order = new SalesOrder();
        order.setId("so-real-nulltax");
        order.setOrderNumber("SO-REAL-NULLTAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("800.00"));
        order.setTaxAmount(null);  // 显式 null (模拟 DB 列 NULL)

        Voucher v = gen.generate("F006", order);
        assertEquals(2, v.getEntries().size(), "null tax 应退化两行, SpEL Elvis 不 NPE");
        assertEquals(0, new BigDecimal("800.00").compareTo(entryByCode(v, "1122").getDebit()));
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
    }

    // ============ PURCHASE_PAYMENT 真实路径 ============

    @Test
    void purchasePayment_realTemplatePath_taxOrder_producesThreeLinesBalanced() {
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        wireTemplate(gen, VoucherType.PURCHASE_PAYMENT, "采购付款默认模板", PURCHASE_PAYMENT_TPL_JSON);

        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-real-tax");
        order.setOrderNumber("PO-REAL-TAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("2000.00"));  // 未税
        order.setTaxAmount(new BigDecimal("260.00"));     // 进项税 13%
        order.setSupplierId("sup-real");

        Voucher v = gen.generate("F006", order);

        assertEquals(3, v.getEntries().size(),
                "真实路径含税采购订单必须三行 — #738 BLOCKING 核心");
        // 借 1405 库存 = 未税 2000.00
        assertEquals(0, new BigDecimal("2000.00").compareTo(entryByCode(v, "1405").getDebit()));
        // 借 2221.02 进项税 = 260.00
        assertEquals(0, new BigDecimal("260.00").compareTo(entryByCode(v, "2221.02").getDebit()));
        // 贷 2202 应付 = 含税 2260.00
        assertEquals(0, new BigDecimal("2260.00").compareTo(entryByCode(v, "2202").getCredit()));
        // 借贷加法平衡: 借(2000+260) = 贷(2260)
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
        assertEquals(0, new BigDecimal("2260.00").compareTo(v.getTotalDebit()));
    }

    @Test
    void purchasePayment_realTemplatePath_zeroTax_degradesToTwoLines() {
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        wireTemplate(gen, VoucherType.PURCHASE_PAYMENT, "采购付款默认模板", PURCHASE_PAYMENT_TPL_JSON);

        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-real-notax");
        order.setOrderNumber("PO-REAL-NOTAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("700.00"));
        order.setTaxAmount(BigDecimal.ZERO);

        Voucher v = gen.generate("F006", order);

        assertEquals(2, v.getEntries().size(), "零税采购订单退化两行");
        assertNull(entryByCodeOrNull(v, "2221.02"), "零税时进项税行应被省略");
        assertEquals(0, new BigDecimal("700.00").compareTo(entryByCode(v, "1405").getDebit()));
        assertEquals(0, new BigDecimal("700.00").compareTo(entryByCode(v, "2202").getCredit()));
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
    }

    // ============ 防 mock-passes-real-path: 反向证明 buildEntries 被 bypass ============

    @Test
    void salesReceipt_proofTemplateBypassesBuildEntries_differentSubjectCodeWins() {
        // 给模板换一个非默认科目, 证明真实路径走的是 template (renderEntries) 而非 buildEntries.
        // 若仍走 buildEntries, 科目会是 hardcoded "1122"; 走 template 则是模板里的 "9999".
        String customJson = """
            [
              {"sortOrder":1,"subjectCode":"9999","subjectName":"自定义应收","direction":"DEBIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"x"},
              {"sortOrder":2,"subjectCode":"6001","subjectName":"主营业务收入","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"x"},
              {"sortOrder":3,"subjectCode":"2221.01","subjectName":"销项税","direction":"CREDIT","amountExpression":"#entity.taxAmount ?: 0","description":"x","omitWhenZero":true}
            ]""";
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wireTemplate(gen, VoucherType.SALES_RECEIPT, "销售收款默认模板", customJson);

        SalesOrder order = new SalesOrder();
        order.setId("so-proof");
        order.setOrderNumber("SO-PROOF");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setTaxAmount(new BigDecimal("13.00"));

        Voucher v = gen.generate("F006", order);
        assertNotNull(entryByCodeOrNull(v, "9999"),
                "真实路径必须走 template (renderEntries), 模板自定义科目 9999 应出现 — 证明 buildEntries 被 bypass");
        assertNull(entryByCodeOrNull(v, "1122"),
                "走 template 后不应出现 buildEntries hardcoded 的 1122");
    }

    // ---- helpers ----

    private static VoucherEntry entryByCode(Voucher v, String code) {
        VoucherEntry e = entryByCodeOrNull(v, code);
        assertNotNull(e, "缺少科目 " + code + " 的分录");
        return e;
    }

    private static VoucherEntry entryByCodeOrNull(Voucher v, String code) {
        return v.getEntries().stream()
                .filter(e -> code.equals(e.getSubjectCode()))
                .findFirst()
                .orElse(null);
    }
}

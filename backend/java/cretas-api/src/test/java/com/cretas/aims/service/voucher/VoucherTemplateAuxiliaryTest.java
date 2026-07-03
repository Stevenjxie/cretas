package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherTemplate;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.repository.finance.VoucherTemplateRepository;
import com.cretas.aims.service.impl.VoucherTemplateServiceImpl;
import com.cretas.aims.service.voucher.impl.DepreciationVoucherGenerator;
import com.cretas.aims.service.voucher.impl.ExpenseVoucherGenerator;
import com.cretas.aims.service.voucher.impl.InventoryTransferVoucherGenerator;
import com.cretas.aims.service.voucher.impl.PurchasePaymentVoucherGenerator;
import com.cretas.aims.service.voucher.impl.ReturnVoucherGenerator;
import com.cretas.aims.service.voucher.impl.SalesReceiptVoucherGenerator;
import com.cretas.aims.service.voucher.impl.WageVoucherGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔴🔒 #1207 回归测试 — 辅助核算 (auxiliary accounting) 在 <b>template-first 路径</b>下也生效。
 *
 * <p><b>为什么必须这个测试</b>: {@link com.cretas.aims.service.voucher.AbstractVoucherGenerator#generate}
 * 是 template-first —— 90/99 有 {@code VoucherTemplate} 的工厂 (含 F006) 走
 * {@code renderEntries}, 而 {@code TemplateEntry} <b>没有 auxiliary 字段</b>, 所以模板渲染的
 * 分录 {@code auxiliaryType/auxiliaryEntityId} 永远为 null → 金蝶导出的客户/供应商/职员/存货
 * 维度全空。修复: {@code AbstractVoucherGenerator} 在 template 分支调用 {@code applyAuxiliary}
 * hook, 各 generator 把辅助核算附到正确的模板分录上。
 *
 * <p>本测试用<b>真实 {@link VoucherTemplateServiceImpl}</b> (mock 仅 repository), 注入 generator,
 * 走完整 generate() 路径, 断言 template 渲染分录携带正确的 auxiliaryType+auxiliaryEntityId。
 * 与 {@link VoucherTemplateTaxSplitRealPathTest} 同一 real-path 手法。
 */
class VoucherTemplateAuxiliaryTest {

    private VoucherTemplateRepository repository;
    private VoucherTemplateServiceImpl templateService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(VoucherTemplateRepository.class);
        templateService = new VoucherTemplateServiceImpl(repository);
    }

    private VoucherTemplate buildTemplate(VoucherType type, String json) {
        try {
            List<VoucherTemplate.TemplateEntry> entries = mapper.readValue(
                    json, mapper.getTypeFactory().constructCollectionType(
                            List.class, VoucherTemplate.TemplateEntry.class));
            return VoucherTemplate.builder()
                    .id("tpl-" + type)
                    .factoryId("F006")
                    .voucherType(type)
                    .name("默认模板-" + type)
                    .entries(entries)
                    .isDefault(true)
                    .isActive(true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("解析模板 JSON 失败", e);
        }
    }

    private void wire(AbstractVoucherGenerator<?> gen, VoucherType type, String json) {
        VoucherTemplate tpl = buildTemplate(type, json);
        when(repository.findActiveDefaultByFactoryAndType(eq("F006"), eq(type)))
                .thenReturn(Optional.of(tpl));
        ReflectionTestUtils.setField(gen, "voucherTemplateService", templateService);
    }

    // ============ SALES_RECEIPT → CUSTOMER 在应收借方 ============

    private static final String SALES_TPL = """
        [
          {"sortOrder":1,"subjectCode":"1122","subjectName":"应收账款","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"销售挂账"},
          {"sortOrder":2,"subjectCode":"6001","subjectName":"主营业务收入","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"收入"}
        ]""";

    @Test
    void salesReceipt_templatePath_attachesCustomerToReceivable() {
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wire(gen, VoucherType.SALES_RECEIPT, SALES_TPL);

        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-1");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("1000.00"));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setCustomerId("cust-42");

        Voucher v = gen.generate("F006", order);

        VoucherEntry ar = byCode(v, "1122");
        assertEquals(AuxiliaryType.CUSTOMER, ar.getAuxiliaryType(),
                "template 渲染的应收借方行必须挂 CUSTOMER 辅助核算 (#1207 核心)");
        assertEquals("cust-42", ar.getAuxiliaryEntityId());
        // 收入行不挂
        assertNull(byCode(v, "6001").getAuxiliaryType(), "收入行不应挂客户维度");
    }

    @Test
    void salesReceipt_templatePath_honestNull_whenNoCustomer() {
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wire(gen, VoucherType.SALES_RECEIPT, SALES_TPL);

        SalesOrder order = new SalesOrder();
        order.setId("so-2");
        order.setOrderNumber("SO-2");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("500.00"));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setCustomerId(null); // 无客户 → honest-null

        Voucher v = gen.generate("F006", order);
        assertNull(byCode(v, "1122").getAuxiliaryType(), "无 customerId 时不造假辅助核算");
        assertNull(byCode(v, "1122").getAuxiliaryEntityId());
    }

    @Test
    void salesReceipt_templatePath_customSubjectCode_stillAttachesViaUniqueSideFallback() {
        // 客户模板把应收科目改成 "1131" (非默认 1122): 前缀匹配失败, 回退到"借方唯一行"仍命中。
        String customTpl = """
            [
              {"sortOrder":1,"subjectCode":"1131","subjectName":"自定义应收","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"x"},
              {"sortOrder":2,"subjectCode":"6001","subjectName":"主营业务收入","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"x"}
            ]""";
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        wire(gen, VoucherType.SALES_RECEIPT, customTpl);

        SalesOrder order = new SalesOrder();
        order.setId("so-3");
        order.setOrderNumber("SO-3");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("300.00"));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setCustomerId("cust-9");

        Voucher v = gen.generate("F006", order);
        VoucherEntry ar = byCode(v, "1131");
        assertEquals(AuxiliaryType.CUSTOMER, ar.getAuxiliaryType(),
                "客户自定义科目码时, 回退到借方唯一行仍挂 CUSTOMER");
        assertEquals("cust-9", ar.getAuxiliaryEntityId());
    }

    // ============ PURCHASE_PAYMENT → SUPPLIER 在应付贷方 ============

    @Test
    void purchasePayment_templatePath_attachesSupplierToPayable() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"1405","subjectName":"库存商品","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"入库"},
              {"sortOrder":2,"subjectCode":"2202","subjectName":"应付账款","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"应付"}
            ]""";
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        wire(gen, VoucherType.PURCHASE_PAYMENT, tpl);

        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-1");
        order.setOrderNumber("PO-1");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("2000.00"));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setSupplierId("sup-7");

        Voucher v = gen.generate("F006", order);

        VoucherEntry ap = byCode(v, "2202");
        assertEquals(AuxiliaryType.SUPPLIER, ap.getAuxiliaryType(),
                "template 渲染的应付贷方行必须挂 SUPPLIER 辅助核算");
        assertEquals("sup-7", ap.getAuxiliaryEntityId());
        assertNull(byCode(v, "1405").getAuxiliaryType(), "库存借方不挂供应商");
    }

    // ============ WAGE → EMPLOYEE 在应付职工薪酬借方 ============

    @Test
    void wage_templatePath_attachesEmployeeToSalaryPayable() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"2211","subjectName":"应付职工薪酬","direction":"DEBIT","amountExpression":"#entity.totalWage ?: 0","description":"发薪"},
              {"sortOrder":2,"subjectCode":"1002","subjectName":"银行存款","direction":"CREDIT","amountExpression":"#entity.totalWage ?: 0","description":"划款"}
            ]""";
        WageVoucherGenerator gen = new WageVoucherGenerator();
        wire(gen, VoucherType.WAGE, tpl);

        PayrollRecord r = new PayrollRecord();
        r.setId(101L);
        r.setWorkerId(55L);
        r.setWorkerName("张三");
        r.setPeriodStart(LocalDate.of(2026, 6, 1));
        r.setTotalWage(new BigDecimal("8000.00"));

        Voucher v = gen.generate("F006", r);

        VoucherEntry sp = byCode(v, "2211");
        assertEquals(AuxiliaryType.EMPLOYEE, sp.getAuxiliaryType(),
                "template 渲染的应付职工薪酬借方行必须挂 EMPLOYEE 辅助核算");
        assertEquals("55", sp.getAuxiliaryEntityId());
        assertNull(byCode(v, "1002").getAuxiliaryType(), "银行存款贷方不挂职员");
    }

    // ============ RETURN → CUSTOMER(销退)/SUPPLIER(采退) ============

    @Test
    void return_salesReturn_templatePath_attachesCustomerToReceivableCredit() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"6001","subjectName":"主营业务收入","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"冲收入"},
              {"sortOrder":2,"subjectCode":"1122","subjectName":"应收账款","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"冲应收"}
            ]""";
        ReturnVoucherGenerator gen = new ReturnVoucherGenerator();
        wire(gen, VoucherType.RETURN, tpl);

        ReturnOrder r = new ReturnOrder();
        r.setId("ret-1");
        r.setReturnNumber("RET-1");
        r.setReturnDate(LocalDate.of(2026, 6, 11));
        r.setReturnType(ReturnType.SALES_RETURN);
        r.setTotalAmount(new BigDecimal("400.00"));
        r.setCounterpartyId("cust-88");

        Voucher v = gen.generate("F006", r);
        VoucherEntry ar = byCode(v, "1122");
        assertEquals(AuxiliaryType.CUSTOMER, ar.getAuxiliaryType(), "销退应收贷方挂 CUSTOMER");
        assertEquals("cust-88", ar.getAuxiliaryEntityId());
    }

    @Test
    void return_purchaseReturn_templatePath_attachesSupplierToPayableDebit() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"2202","subjectName":"应付账款","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"冲应付"},
              {"sortOrder":2,"subjectCode":"1405","subjectName":"库存商品","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"退库"}
            ]""";
        ReturnVoucherGenerator gen = new ReturnVoucherGenerator();
        wire(gen, VoucherType.RETURN, tpl);

        ReturnOrder r = new ReturnOrder();
        r.setId("ret-2");
        r.setReturnNumber("RET-2");
        r.setReturnDate(LocalDate.of(2026, 6, 11));
        r.setReturnType(ReturnType.PURCHASE_RETURN);
        r.setTotalAmount(new BigDecimal("600.00"));
        r.setCounterpartyId("sup-88");

        Voucher v = gen.generate("F006", r);
        VoucherEntry ap = byCode(v, "2202");
        assertEquals(AuxiliaryType.SUPPLIER, ap.getAuxiliaryType(), "采退应付借方挂 SUPPLIER");
        assertEquals("sup-88", ap.getAuxiliaryEntityId());
    }

    // ============ EXPENSE → INVENTORY 在库存贷方 ============

    @Test
    void expense_templatePath_attachesInventoryToStockCredit() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"6602.01","subjectName":"管理费用-损耗","direction":"DEBIT","amountExpression":"#entity.estimatedCost ?: 0","description":"损耗"},
              {"sortOrder":2,"subjectCode":"1405","subjectName":"库存商品","direction":"CREDIT","amountExpression":"#entity.estimatedCost ?: 0","description":"减库存"}
            ]""";
        ExpenseVoucherGenerator gen = new ExpenseVoucherGenerator();
        wire(gen, VoucherType.EXPENSE, tpl);

        WastageRecord w = new WastageRecord();
        w.setId("wr-1");
        w.setWastageNumber("WR-1");
        w.setWastageDate(LocalDate.of(2026, 6, 11));
        w.setType(WastageRecord.WastageType.SPOILED);
        w.setEstimatedCost(new BigDecimal("120.00"));
        w.setMaterialBatchId("batch-x");

        Voucher v = gen.generate("F006", w);
        VoucherEntry stock = byCode(v, "1405");
        assertEquals(AuxiliaryType.INVENTORY, stock.getAuxiliaryType(), "损耗库存贷方挂 INVENTORY");
        assertEquals("batch-x", stock.getAuxiliaryEntityId());
    }

    // ============ DEPRECATION → DEPT 在管理费用借方 (Map 输入) ============

    @Test
    void depreciation_templatePath_attachesDeptToExpense() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"6602.02","subjectName":"管理费用-折旧","direction":"DEBIT","amountExpression":"#entity['amount']","description":"折旧"},
              {"sortOrder":2,"subjectCode":"1602","subjectName":"累计折旧","direction":"CREDIT","amountExpression":"#entity['amount']","description":"累计"}
            ]""";
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        wire(gen, VoucherType.DEPRECATION, tpl);

        Map<String, Object> input = new HashMap<>();
        input.put("businessId", "DEP-202606");
        input.put("amount", new BigDecimal("900.00"));
        input.put("voucherDate", LocalDate.of(2026, 6, 30));
        input.put("deptId", "dept-3");

        Voucher v = gen.generate("F006", input);
        VoucherEntry exp = byCode(v, "6602.02");
        assertEquals(AuxiliaryType.DEPT, exp.getAuxiliaryType(), "折旧费用借方挂 DEPT");
        assertEquals("dept-3", exp.getAuxiliaryEntityId());
    }

    // ============ INVENTORY_TRANSFER → INVENTORY 在借贷两侧 ============

    @Test
    void inventoryTransfer_templatePath_attachesInventoryToBothSides() {
        String tpl = """
            [
              {"sortOrder":1,"subjectCode":"1405","subjectName":"库存商品","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"调入"},
              {"sortOrder":2,"subjectCode":"1405","subjectName":"库存商品","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"调出"}
            ]""";
        InventoryTransferVoucherGenerator gen = new InventoryTransferVoucherGenerator();
        wire(gen, VoucherType.INVENTORY_TRANSFER, tpl);

        InternalTransfer t = new InternalTransfer();
        t.setId("it-1");
        t.setTransferNumber("IT-1");
        t.setTransferDate(LocalDate.of(2026, 6, 11));
        t.setTotalAmount(new BigDecimal("1500.00"));
        t.setSourceWarehouseId("wh-src");
        t.setTargetWarehouseId("wh-dst");
        InternalTransferItem i1 = new InternalTransferItem();
        i1.setMaterialTypeId("mat-1");
        InternalTransferItem i2 = new InternalTransferItem();
        i2.setMaterialTypeId("mat-1");
        List<InternalTransferItem> items = new ArrayList<>();
        items.add(i1);
        items.add(i2);
        t.setItems(items);

        Voucher v = gen.generate("F006", t);
        // 借贷两条 1405 行都挂 INVENTORY=mat-1
        long inventoryTagged = v.getEntries().stream()
                .filter(e -> e.getAuxiliaryType() == AuxiliaryType.INVENTORY
                        && "mat-1".equals(e.getAuxiliaryEntityId()))
                .count();
        assertEquals(2, inventoryTagged, "调拨借贷两条库存行都应挂 INVENTORY 辅助核算");
        // cost_center 仓库维度也补上 (借=调入, 贷=调出)
        VoucherEntry debit = v.getEntries().stream()
                .filter(e -> e.getDebit() != null && e.getDebit().signum() > 0).findFirst().orElseThrow();
        VoucherEntry credit = v.getEntries().stream()
                .filter(e -> e.getCredit() != null && e.getCredit().signum() > 0).findFirst().orElseThrow();
        assertNotNull(debit.getCostCenter());
        assertNotNull(credit.getCostCenter());
    }

    // ---- helpers ----

    private static VoucherEntry byCode(Voucher v, String code) {
        VoucherEntry e = v.getEntries().stream()
                .filter(x -> code.equals(x.getSubjectCode()))
                .findFirst().orElse(null);
        assertNotNull(e, "缺少科目 " + code + " 的分录");
        return e;
    }
}

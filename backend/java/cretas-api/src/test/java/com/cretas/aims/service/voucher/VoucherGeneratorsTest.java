package com.cretas.aims.service.voucher;

import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.dto.finance.VoucherSubjectMappingDTO;
import com.cretas.aims.entity.enums.SettlementType;
import com.cretas.aims.service.finance.VoucherSubjectMappingService;
import com.cretas.aims.service.voucher.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 7 VoucherGenerator 单测. 每 generator 验证:
 * 1. supports() 正确匹配业务类型
 * 2. generate() 返回 balanced Voucher (validateBalanced() not throw)
 * 3. 借贷分录正确科目
 */
class VoucherGeneratorsTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    // ==================== SalesReceiptVoucherGenerator ====================

    @Test
    void salesReceiptGeneratorBuildsBalancedVoucher() {
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        assertTrue(gen.supports("SALES_ORDER"));
        assertFalse(gen.supports("PURCHASE_ORDER"));

        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-2026-0001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(HUNDRED);
        order.setCustomerId("cust-99");  // Sprint 5 F-2: 客户辅助核算

        Voucher v = gen.generate("F001", order);
        assertEquals(VoucherType.SALES_RECEIPT, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals(2, v.getEntries().size());
        assertEquals("1122", v.getEntries().get(0).getSubjectCode());  // 应收账款
        assertEquals("6001", v.getEntries().get(1).getSubjectCode());  // 主营业务收入
        assertEquals("SALES_ORDER", v.getSourceBusinessType());
        assertEquals("so-1", v.getSourceBusinessId());
        // Sprint 5 F-2: 应收账款 line 携带 CUSTOMER 辅助核算
        VoucherEntry receivableLine = v.getEntries().get(0);
        assertEquals(AuxiliaryType.CUSTOMER, receivableLine.getAuxiliaryType());
        assertEquals("cust-99", receivableLine.getAuxiliaryEntityId());
        // 收入 line 不挂客户 (这是收入科目, 不分客户挂账)
        assertNull(v.getEntries().get(1).getAuxiliaryType());
    }

    // ==================== SP11: SalesReceipt 价税分离 ====================

    @Test
    void salesReceipt13PercentTaxSplitsThreeLinesBalanced() {
        // SP11: 含税销售 (13%) → 借应收(含税) / 贷收入(未税) / 贷销项税
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        SalesOrder order = new SalesOrder();
        order.setId("so-tax-13");
        order.setOrderNumber("SO-TAX-13");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("1000.00"));   // 未税净额
        order.setTaxAmount(new BigDecimal("130.00"));      // 销项税 13%
        order.setCustomerId("cust-13");

        Voucher v = gen.generate("F001", order);
        // 三行
        assertEquals(3, v.getEntries().size());
        // 借: 1122 应收账款 = 含税 1130.00
        VoucherEntry ar = v.getEntries().get(0);
        assertEquals("1122", ar.getSubjectCode());
        assertEquals(0, new BigDecimal("1130.00").compareTo(ar.getDebit()));
        // 贷: 6001 主营业务收入 = 未税 1000.00
        VoucherEntry rev = v.getEntries().get(1);
        assertEquals("6001", rev.getSubjectCode());
        assertEquals(0, new BigDecimal("1000.00").compareTo(rev.getCredit()));
        // 贷: 2221.01 销项税额 = 130.00
        VoucherEntry vat = v.getEntries().get(2);
        assertEquals("2221.01", vat.getSubjectCode());
        assertTrue(vat.getSubjectName().contains("销项税"));
        assertEquals(0, new BigDecimal("130.00").compareTo(vat.getCredit()));
        // 借贷恒平: 借 1130 = 贷 (1000 + 130)
        assertEquals(0, new BigDecimal("1130.00").compareTo(v.getTotalDebit()));
        assertEquals(0, new BigDecimal("1130.00").compareTo(v.getTotalCredit()));
        // 客户辅助核算保留在应收 line
        assertEquals(AuxiliaryType.CUSTOMER, ar.getAuxiliaryType());
        assertEquals("cust-13", ar.getAuxiliaryEntityId());
        // validateBalanced 不抛 (generate 已内部调用, 再显式确认)
        assertDoesNotThrow(v::validateBalanced);
    }

    @Test
    void salesReceiptZeroTaxDegradesToTwoLines() {
        // SP11 向后兼容: 零税 (tax_amount=0) → 退化两行, 字节与历史一致
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        SalesOrder order = new SalesOrder();
        order.setId("so-zero");
        order.setOrderNumber("SO-ZERO");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(HUNDRED);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setCustomerId("cust-z");

        Voucher v = gen.generate("F001", order);
        assertEquals(2, v.getEntries().size());            // 退化两行
        assertEquals("1122", v.getEntries().get(0).getSubjectCode());
        assertEquals("6001", v.getEntries().get(1).getSubjectCode());
        // 应收 = 未税 = 收入 = 100.00 (无税)
        assertEquals(0, HUNDRED.compareTo(v.getEntries().get(0).getDebit()));
        assertEquals(0, HUNDRED.compareTo(v.getEntries().get(1).getCredit()));
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
    }

    @Test
    void salesReceiptNullTaxDegradesToTwoLines() {
        // SP11 向后兼容: tax_amount=null (老数据未设) → nullToZero → 退化两行
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        SalesOrder order = new SalesOrder();
        order.setId("so-null-tax");
        order.setOrderNumber("SO-NULL-TAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(HUNDRED);
        order.setTaxAmount(null);   // 显式 null

        Voucher v = gen.generate("F001", order);
        assertEquals(2, v.getEntries().size());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
    }

    @Test
    void salesReceiptTaxIsAdditionNotRateRecompute() {
        // SP11 spec §3.4: 应收 = 未税 + 税 (加法), 不重算 net×rate, 无舍入裂缝.
        // 用一个 net×rate 与 stored tax 不整除的值, 验证凭证严格用 stored tax_amount.
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        SalesOrder order = new SalesOrder();
        order.setId("so-odd");
        order.setOrderNumber("SO-ODD");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("333.33"));    // 未税
        order.setTaxAmount(new BigDecimal("43.33"));       // stored 税 (≠ 333.33×0.13=43.3329)
        order.setCustomerId("cust-odd");

        Voucher v = gen.generate("F001", order);
        // 应收 = 333.33 + 43.33 = 376.66 (严格加法)
        assertEquals(0, new BigDecimal("376.66").compareTo(v.getEntries().get(0).getDebit()));
        assertEquals(0, new BigDecimal("43.33").compareTo(v.getEntries().get(2).getCredit()));
        // 借贷恒平 (加法保证)
        assertEquals(0, v.getTotalDebit().compareTo(v.getTotalCredit()));
    }

    // ==================== PurchasePaymentVoucherGenerator ====================

    @Test
    void purchasePaymentGeneratorBuildsBalancedVoucher() {
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        assertTrue(gen.supports("PURCHASE_ORDER"));

        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-1");
        order.setOrderNumber("PO-2026-0001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(HUNDRED);
        order.setSupplierId("sup-77");  // Sprint 5 F-2: 供应商辅助核算

        Voucher v = gen.generate("F001", order);
        assertEquals(VoucherType.PURCHASE_PAYMENT, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("1405", v.getEntries().get(0).getSubjectCode());  // 库存商品
        assertEquals("2202", v.getEntries().get(1).getSubjectCode());  // 应付账款
        // Sprint 5 F-2: 应付账款 line 携带 SUPPLIER 辅助核算
        VoucherEntry payableLine = v.getEntries().get(1);
        assertEquals(AuxiliaryType.SUPPLIER, payableLine.getAuxiliaryType());
        assertEquals("sup-77", payableLine.getAuxiliaryEntityId());
        // 库存 line 不挂供应商 (库存科目可挂 INVENTORY 维度但本 generator 先不分)
        assertNull(v.getEntries().get(0).getAuxiliaryType());
    }

    // ==================== SP11: PurchasePayment 价税分离 ====================

    @Test
    void purchasePayment9PercentTaxSplitsThreeLinesBalanced() {
        // SP11: 含税采购 (9%) → 借库存(未税) / 借进项税 / 贷应付(含税)
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-tax-9");
        order.setOrderNumber("PO-TAX-9");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(new BigDecimal("1000.00"));   // 未税净额
        order.setTaxAmount(new BigDecimal("90.00"));       // 进项税 9%
        order.setSupplierId("sup-9");

        Voucher v = gen.generate("F001", order);
        assertEquals(3, v.getEntries().size());
        // 借: 1405 库存商品 = 未税 1000.00 (进项税不进成本)
        VoucherEntry inv = v.getEntries().get(0);
        assertEquals("1405", inv.getSubjectCode());
        assertEquals(0, new BigDecimal("1000.00").compareTo(inv.getDebit()));
        // 贷: 2202 应付账款 = 含税 1090.00
        VoucherEntry ap = v.getEntries().get(1);
        assertEquals("2202", ap.getSubjectCode());
        assertEquals(0, new BigDecimal("1090.00").compareTo(ap.getCredit()));
        // 借: 2221.02 进项税额 = 90.00
        VoucherEntry vat = v.getEntries().get(2);
        assertEquals("2221.02", vat.getSubjectCode());
        assertTrue(vat.getSubjectName().contains("进项税"));
        assertEquals(0, new BigDecimal("90.00").compareTo(vat.getDebit()));
        // 借贷恒平: 借 (1000 + 90) = 贷 1090
        assertEquals(0, new BigDecimal("1090.00").compareTo(v.getTotalDebit()));
        assertEquals(0, new BigDecimal("1090.00").compareTo(v.getTotalCredit()));
        // 供应商辅助核算保留在应付 line
        assertEquals(AuxiliaryType.SUPPLIER, ap.getAuxiliaryType());
        assertEquals("sup-9", ap.getAuxiliaryEntityId());
        assertDoesNotThrow(v::validateBalanced);
    }

    @Test
    void purchasePaymentZeroTaxDegradesToTwoLines() {
        // SP11 向后兼容: 零税采购 → 退化两行, 字节与历史一致
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-zero");
        order.setOrderNumber("PO-ZERO");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(HUNDRED);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setSupplierId("sup-z");

        Voucher v = gen.generate("F001", order);
        assertEquals(2, v.getEntries().size());
        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("2202", v.getEntries().get(1).getSubjectCode());
        assertEquals(0, HUNDRED.compareTo(v.getEntries().get(0).getDebit()));
        assertEquals(0, HUNDRED.compareTo(v.getEntries().get(1).getCredit()));
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
    }

    @Test
    void purchasePaymentNullTaxDegradesToTwoLines() {
        // SP11 向后兼容: tax_amount=null → 退化两行
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-null-tax");
        order.setOrderNumber("PO-NULL-TAX");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(HUNDRED);
        order.setTaxAmount(null);

        Voucher v = gen.generate("F001", order);
        assertEquals(2, v.getEntries().size());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
    }

    // ==================== #754: settlement→subject mapping ====================

    private PurchaseOrder purchaseOrder(SettlementType settlement, BigDecimal net, BigDecimal tax) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId("po-settle");
        order.setOrderNumber("PO-SETTLE-1");
        order.setOrderDate(LocalDate.of(2026, 6, 11));
        order.setTotalAmount(net);
        order.setTaxAmount(tax);
        order.setSupplierId("sup-1");
        order.setSettlementType(settlement);
        return order;
    }

    @Test
    void purchasePaymentPrepaidMapsCreditToBankDeposit() {
        // #754: 预付结算 → 贷方科目从 2202 应付账款 映射为 1002 银行存款 (款已付)
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        VoucherSubjectMappingService mappingSvc = mock(VoucherSubjectMappingService.class);
        when(mappingSvc.findMapping("F001", SettlementType.PREPAID, "PURCHASE"))
                .thenReturn(Optional.of(VoucherSubjectMappingDTO.builder()
                        .debitSubjectCode("1405").debitSubjectName("原材料")
                        .creditSubjectCode("1002").creditSubjectName("银行存款")
                        .build()));
        ReflectionTestUtils.setField(gen, "subjectMappingService", mappingSvc);

        Voucher v = gen.generate("F001", purchaseOrder(SettlementType.PREPAID, HUNDRED, BigDecimal.ZERO));

        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("1002", v.getEntries().get(1).getSubjectCode());  // 银行存款, 不再是 2202
        assertEquals("银行存款", v.getEntries().get(1).getSubjectName());
        // 借贷恒平不受科目映射影响
        assertEquals(0, HUNDRED.compareTo(v.getTotalDebit()));
        assertEquals(0, HUNDRED.compareTo(v.getTotalCredit()));
        assertDoesNotThrow(v::validateBalanced);
        // 供应商辅助核算保留
        assertEquals(AuxiliaryType.SUPPLIER, v.getEntries().get(1).getAuxiliaryType());
    }

    @Test
    void purchasePaymentNoInvoiceMapsCreditToAccrued() {
        // #754: 未到票 → 贷 2241 暂估应付款
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        VoucherSubjectMappingService mappingSvc = mock(VoucherSubjectMappingService.class);
        when(mappingSvc.findMapping("F001", SettlementType.NO_INVOICE, "PURCHASE"))
                .thenReturn(Optional.of(VoucherSubjectMappingDTO.builder()
                        .debitSubjectCode("1405").debitSubjectName("原材料")
                        .creditSubjectCode("2241").creditSubjectName("暂估应付款")
                        .build()));
        ReflectionTestUtils.setField(gen, "subjectMappingService", mappingSvc);

        // 含税也走映射: 借库存(未税)/借进项税/贷暂估应付(含税)
        Voucher v = gen.generate("F001", purchaseOrder(SettlementType.NO_INVOICE,
                new BigDecimal("1000.00"), new BigDecimal("90.00")));

        assertEquals(3, v.getEntries().size());
        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("2241", v.getEntries().get(1).getSubjectCode());   // 暂估应付款
        assertEquals(0, new BigDecimal("1090.00").compareTo(v.getEntries().get(1).getCredit()));
        assertEquals("2221.02", v.getEntries().get(2).getSubjectCode()); // 进项税仍硬编码
        assertEquals(0, new BigDecimal("1090.00").compareTo(v.getTotalDebit()));
        assertEquals(0, new BigDecimal("1090.00").compareTo(v.getTotalCredit()));
        assertDoesNotThrow(v::validateBalanced);
    }

    @Test
    void purchasePaymentNoMappingServiceFallsBackToDefault() {
        // #754 向后兼容: mappingService 未注入 (null) → 硬编码 1405/2202, 字节不变
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        // 不设 subjectMappingService → null

        Voucher v = gen.generate("F001", purchaseOrder(SettlementType.MONTHLY, HUNDRED, BigDecimal.ZERO));

        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("2202", v.getEntries().get(1).getSubjectCode());  // 默认应付账款
        assertEquals(0, HUNDRED.compareTo(v.getTotalDebit()));
        assertEquals(0, HUNDRED.compareTo(v.getTotalCredit()));
    }

    @Test
    void purchasePaymentNullSettlementFallsBackToDefault() {
        // #754: settlementType 为 null → 不查映射, fall back 默认科目 (映射服务在场也不调)
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        VoucherSubjectMappingService mappingSvc = mock(VoucherSubjectMappingService.class);
        ReflectionTestUtils.setField(gen, "subjectMappingService", mappingSvc);

        Voucher v = gen.generate("F001", purchaseOrder(null, HUNDRED, BigDecimal.ZERO));

        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("2202", v.getEntries().get(1).getSubjectCode());
        verify(mappingSvc, never()).findMapping(anyString(), any(), anyString());
    }

    @Test
    void purchasePaymentMappingMissReturnsDefault() {
        // #754: 映射服务返回 empty (工厂未配该结算方式) → fall back 默认科目
        PurchasePaymentVoucherGenerator gen = new PurchasePaymentVoucherGenerator();
        VoucherSubjectMappingService mappingSvc = mock(VoucherSubjectMappingService.class);
        when(mappingSvc.findMapping(anyString(), any(), anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(gen, "subjectMappingService", mappingSvc);

        Voucher v = gen.generate("F001", purchaseOrder(SettlementType.CREDIT_PERIOD, HUNDRED, BigDecimal.ZERO));

        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("2202", v.getEntries().get(1).getSubjectCode());
        assertEquals(0, HUNDRED.compareTo(v.getTotalCredit()));
    }

    // ==================== Sprint 5 F-2: Auxiliary type 7 类 contract ====================

    @Test
    void auxiliaryTypeEnumExposes7Categories() {
        // R-HJ Round 13 §2 实测 — 宏见 ERP 凭证辅助核算 7 类 (含委外商)
        AuxiliaryType[] values = AuxiliaryType.values();
        assertEquals(7, values.length, "AuxiliaryType must be exactly 7 per R-HJ Round 13 §2");
        // Order-sensitive check (alphabetical-ish from spec)
        assertEquals(AuxiliaryType.CUSTOMER,   values[0]);
        assertEquals(AuxiliaryType.SUPPLIER,   values[1]);
        assertEquals(AuxiliaryType.DEPT,       values[2]);
        assertEquals(AuxiliaryType.EMPLOYEE,   values[3]);
        assertEquals(AuxiliaryType.PROJECT,    values[4]);
        assertEquals(AuxiliaryType.INVENTORY,  values[5]);
        assertEquals(AuxiliaryType.OUTSOURCER, values[6]);
    }

    @Test
    void salesReceiptWithoutCustomerIdSkipsAuxiliary() {
        // Defensive: 老 SO 数据 customerId=null 时, generator 不挂辅助核算 (而非崩溃)
        SalesReceiptVoucherGenerator gen = new SalesReceiptVoucherGenerator();
        SalesOrder order = new SalesOrder();
        order.setId("so-legacy");
        order.setOrderNumber("SO-LEGACY");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(HUNDRED);
        // intentionally NOT setting customerId

        Voucher v = gen.generate("F001", order);
        // 借贷必平仍然成立
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        // 应收 line 无辅助核算 (符合成对约束 chk_ve_auxiliary_paired)
        VoucherEntry receivable = v.getEntries().get(0);
        assertNull(receivable.getAuxiliaryType());
        assertNull(receivable.getAuxiliaryEntityId());
    }

    // ==================== InventoryTransferVoucherGenerator ====================

    @Test
    void inventoryTransferGeneratorBuildsBalancedVoucher() {
        // 老 (无 items) 仍工作 — 不挂 INVENTORY 辅助
        InventoryTransferVoucherGenerator gen = new InventoryTransferVoucherGenerator();
        assertTrue(gen.supports("INTERNAL_TRANSFER"));

        InternalTransfer t = new InternalTransfer();
        t.setId("tr-1");
        t.setTransferNumber("TR-2026-0001");
        t.setTransferDate(LocalDate.of(2026, 5, 16));
        t.setTotalAmount(HUNDRED);
        t.setSourceFactoryId("F001");
        t.setTargetFactoryId("F002");
        t.setSourceWarehouseId("WH-A");
        t.setTargetWarehouseId("WH-B");
        // items 为空 → 不挂 INVENTORY (新行为, 不破)

        Voucher v = gen.generate("F001", t);
        assertEquals(VoucherType.INVENTORY_TRANSFER, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("1405", v.getEntries().get(0).getSubjectCode());
        assertEquals("1405", v.getEntries().get(1).getSubjectCode());
        assertTrue(v.getEntries().get(0).getCostCenter().contains("调入"));
        assertTrue(v.getEntries().get(1).getCostCenter().contains("调出"));
        // 无 items → 无 INVENTORY 辅助
        assertNull(v.getEntries().get(0).getAuxiliaryType());
        assertNull(v.getEntries().get(1).getAuxiliaryType());
    }

    @Test
    void inventoryTransferGeneratorAttachesInventoryAuxWhenSingleMaterial() {
        // Sprint 6 W4-A: 单一品类调拨 — 借贷两行都挂 INVENTORY=materialTypeId
        InventoryTransferVoucherGenerator gen = new InventoryTransferVoucherGenerator();
        InternalTransfer t = new InternalTransfer();
        t.setId("tr-2");
        t.setTransferNumber("TR-SI-001");
        t.setTransferDate(LocalDate.of(2026, 5, 16));
        t.setTotalAmount(HUNDRED);
        t.setSourceFactoryId("F001");
        t.setTargetFactoryId("F001");
        InternalTransferItem item1 = new InternalTransferItem();
        item1.setMaterialTypeId("mat-50");
        InternalTransferItem item2 = new InternalTransferItem();
        item2.setMaterialTypeId("mat-50");  // 同一品类
        t.setItems(List.of(item1, item2));

        Voucher v = gen.generate("F001", t);
        assertEquals(AuxiliaryType.INVENTORY, v.getEntries().get(0).getAuxiliaryType());
        assertEquals("mat-50", v.getEntries().get(0).getAuxiliaryEntityId());
        assertEquals(AuxiliaryType.INVENTORY, v.getEntries().get(1).getAuxiliaryType());
        assertEquals("mat-50", v.getEntries().get(1).getAuxiliaryEntityId());
    }

    @Test
    void inventoryTransferGeneratorSkipsAuxWhenMultipleMaterials() {
        // Sprint 6 W4-A: 多品类调拨 → 不挂 INVENTORY (避免误导)
        InventoryTransferVoucherGenerator gen = new InventoryTransferVoucherGenerator();
        InternalTransfer t = new InternalTransfer();
        t.setId("tr-3");
        t.setTransferNumber("TR-MM-001");
        t.setTransferDate(LocalDate.of(2026, 5, 16));
        t.setTotalAmount(HUNDRED);
        t.setSourceFactoryId("F001");
        t.setTargetFactoryId("F001");
        InternalTransferItem item1 = new InternalTransferItem();
        item1.setMaterialTypeId("mat-50");
        InternalTransferItem item2 = new InternalTransferItem();
        item2.setMaterialTypeId("mat-51");  // 不同品类
        t.setItems(List.of(item1, item2));

        Voucher v = gen.generate("F001", t);
        assertNull(v.getEntries().get(0).getAuxiliaryType());
        assertNull(v.getEntries().get(1).getAuxiliaryType());
        // cost_center 仍保留 (仓库维度仍可观察)
        assertNotNull(v.getEntries().get(0).getCostCenter());
    }

    // ==================== ExpenseVoucherGenerator ====================

    @Test
    void expenseGeneratorBuildsBalancedVoucherFromWastage() {
        ExpenseVoucherGenerator gen = new ExpenseVoucherGenerator();
        assertTrue(gen.supports("WASTAGE_RECORD"));

        WastageRecord w = new WastageRecord();
        w.setId("w-1");
        w.setWastageNumber("WST-2026-0001");
        w.setWastageDate(LocalDate.of(2026, 5, 16));
        w.setEstimatedCost(HUNDRED);
        w.setType(WastageRecord.WastageType.EXPIRED);
        w.setRawMaterialTypeId("rm-99");  // Sprint 6 W4-A: SKU 粒度

        Voucher v = gen.generate("F001", w);
        assertEquals(VoucherType.EXPENSE, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("6602.01", v.getEntries().get(0).getSubjectCode());  // 管理费用-损耗
        assertEquals("1405", v.getEntries().get(1).getSubjectCode());
        // Sprint 6 W4-A: 库存 line 挂 INVENTORY 辅助 (无 batchId 时 fallback rawMaterialTypeId)
        VoucherEntry inv = v.getEntries().get(1);
        assertEquals(AuxiliaryType.INVENTORY, inv.getAuxiliaryType());
        assertEquals("rm-99", inv.getAuxiliaryEntityId());
    }

    @Test
    void expenseGeneratorPrefersMaterialBatchIdOverRawMaterialType() {
        // Sprint 6 W4-A: materialBatchId 非空时优先 (批次粒度比 SKU 更细)
        ExpenseVoucherGenerator gen = new ExpenseVoucherGenerator();
        WastageRecord w = new WastageRecord();
        w.setId("w-2");
        w.setWastageNumber("WST-2026-0002");
        w.setWastageDate(LocalDate.of(2026, 5, 16));
        w.setEstimatedCost(HUNDRED);
        w.setType(WastageRecord.WastageType.SPOILED);
        w.setRawMaterialTypeId("rm-99");
        w.setMaterialBatchId("batch-A77");  // 优先

        Voucher v = gen.generate("F001", w);
        VoucherEntry inv = v.getEntries().get(1);
        assertEquals(AuxiliaryType.INVENTORY, inv.getAuxiliaryType());
        assertEquals("batch-A77", inv.getAuxiliaryEntityId());  // batch 胜出
    }

    // ==================== WageVoucherGenerator ====================

    @Test
    void wageGeneratorBuildsBalancedVoucherFromPayroll() {
        WageVoucherGenerator gen = new WageVoucherGenerator();
        assertTrue(gen.supports("PAYROLL_RECORD"));

        PayrollRecord r = new PayrollRecord();
        r.setId(123L);
        r.setWorkerName("张三");
        r.setWorkerId(456L);  // Sprint 5 F-2: 职员辅助核算
        r.setPeriodStart(LocalDate.of(2026, 5, 1));
        r.setTotalWage(HUNDRED);

        Voucher v = gen.generate("F001", r);
        assertEquals(VoucherType.WAGE, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("2211", v.getEntries().get(0).getSubjectCode());  // 应付职工薪酬
        assertEquals("1002", v.getEntries().get(1).getSubjectCode());  // 银行存款
        assertEquals("123", v.getSourceBusinessId());  // Long → String
        // Sprint 5 F-2: 应付职工薪酬 line 携带 EMPLOYEE 辅助核算 (workerId String)
        VoucherEntry wageLine = v.getEntries().get(0);
        assertEquals(AuxiliaryType.EMPLOYEE, wageLine.getAuxiliaryType());
        assertEquals("456", wageLine.getAuxiliaryEntityId());
    }

    // ==================== ReturnVoucherGenerator ====================

    @Test
    void returnGeneratorBuildsBalancedVoucherFromReturnOrder() {
        // Legacy default (returnType=null) — backward compat: 走 SALES_RETURN 行为
        ReturnVoucherGenerator gen = new ReturnVoucherGenerator();
        assertTrue(gen.supports("RETURN_ORDER"));

        ReturnOrder r = new ReturnOrder();
        r.setId("ro-1");
        r.setReturnNumber("RT-2026-0001");
        r.setReturnDate(LocalDate.of(2026, 5, 16));
        r.setTotalAmount(HUNDRED);
        // returnType=null → fall through to SALES_RETURN default

        Voucher v = gen.generate("F001", r);
        assertEquals(VoucherType.RETURN, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("6001", v.getEntries().get(0).getSubjectCode());  // 主营业务收入 (red)
        assertEquals("1122", v.getEntries().get(1).getSubjectCode());  // 应收账款
        // legacy 无 counterpartyId → 无辅助核算
        assertNull(v.getEntries().get(1).getAuxiliaryType());
    }

    @Test
    void returnGeneratorSalesReturnAttachesCustomerAuxiliary() {
        // Sprint 6 W4-A: 销售退货, AR 贷方 line 挂 CUSTOMER 辅助
        ReturnVoucherGenerator gen = new ReturnVoucherGenerator();
        ReturnOrder r = new ReturnOrder();
        r.setId("ro-sr");
        r.setReturnNumber("RT-SR-001");
        r.setReturnDate(LocalDate.of(2026, 5, 16));
        r.setTotalAmount(HUNDRED);
        r.setReturnType(ReturnType.SALES_RETURN);
        r.setCounterpartyId("cust-200");

        Voucher v = gen.generate("F001", r);
        assertEquals("6001", v.getEntries().get(0).getSubjectCode());
        assertEquals("1122", v.getEntries().get(1).getSubjectCode());
        // AR 贷方挂 CUSTOMER
        VoucherEntry arLine = v.getEntries().get(1);
        assertEquals(AuxiliaryType.CUSTOMER, arLine.getAuxiliaryType());
        assertEquals("cust-200", arLine.getAuxiliaryEntityId());
        // 收入 line 不挂客户
        assertNull(v.getEntries().get(0).getAuxiliaryType());
    }

    @Test
    void returnGeneratorPurchaseReturnAttachesSupplierAuxiliary() {
        // Sprint 6 W4-A: 采购退货, 借应付/贷库存, AP 借方 line 挂 SUPPLIER 辅助
        ReturnVoucherGenerator gen = new ReturnVoucherGenerator();
        ReturnOrder r = new ReturnOrder();
        r.setId("ro-pr");
        r.setReturnNumber("RT-PR-001");
        r.setReturnDate(LocalDate.of(2026, 5, 16));
        r.setTotalAmount(HUNDRED);
        r.setReturnType(ReturnType.PURCHASE_RETURN);
        r.setCounterpartyId("sup-300");

        Voucher v = gen.generate("F001", r);
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        // 借应付账款 + 贷库存
        assertEquals("2202", v.getEntries().get(0).getSubjectCode());
        assertEquals("1405", v.getEntries().get(1).getSubjectCode());
        // AP 借方挂 SUPPLIER
        VoucherEntry apLine = v.getEntries().get(0);
        assertEquals(AuxiliaryType.SUPPLIER, apLine.getAuxiliaryType());
        assertEquals("sup-300", apLine.getAuxiliaryEntityId());
        // 库存 line 不挂供应商 (库存按 batch/SKU 维度独立)
        assertNull(v.getEntries().get(1).getAuxiliaryType());
    }

    // ==================== DepreciationVoucherGenerator ====================

    @Test
    void depreciationGeneratorBuildsBalancedVoucherFromMap() {
        // legacy (无 deptId / projectId) — backward compat: 不挂辅助
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        assertTrue(gen.supports("DEPRECATION"));

        Map<String, Object> input = Map.of(
                "businessId", "DEP-202605",
                "amount", HUNDRED,
                "voucherDate", LocalDate.of(2026, 5, 31),
                "assetCategory", "生产设备"
        );

        Voucher v = gen.generate("F001", input);
        assertEquals(VoucherType.DEPRECATION, v.getVoucherType());
        assertEquals(HUNDRED, v.getTotalDebit());
        assertEquals(HUNDRED, v.getTotalCredit());
        assertEquals("6602.02", v.getEntries().get(0).getSubjectCode());  // 管理费用-折旧
        assertEquals("1602", v.getEntries().get(1).getSubjectCode());     // 累计折旧
        assertEquals("DEP-202605", v.getSourceBusinessId());
        // legacy: 无 deptId/projectId → 无辅助
        assertNull(v.getEntries().get(0).getAuxiliaryType());
    }

    @Test
    void depreciationGeneratorAttachesDeptAuxiliary() {
        // Sprint 6 W4-A: deptId 输入 → 6602.02 line 挂 DEPT
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        Map<String, Object> input = new HashMap<>();
        input.put("businessId", "DEP-202606-DEPT-A");
        input.put("amount", HUNDRED);
        input.put("voucherDate", LocalDate.of(2026, 6, 30));
        input.put("assetCategory", "生产设备");
        input.put("deptId", "DEPT-100");

        Voucher v = gen.generate("F001", input);
        VoucherEntry expense = v.getEntries().get(0);
        assertEquals(AuxiliaryType.DEPT, expense.getAuxiliaryType());
        assertEquals("DEPT-100", expense.getAuxiliaryEntityId());
        // 累计折旧 line 不挂部门
        assertNull(v.getEntries().get(1).getAuxiliaryType());
    }

    @Test
    void depreciationGeneratorFallsBackToProjectWhenNoDept() {
        // Sprint 6 W4-A: 无 deptId 但有 projectId → 挂 PROJECT
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        Map<String, Object> input = new HashMap<>();
        input.put("businessId", "DEP-202606-PROJ");
        input.put("amount", HUNDRED);
        input.put("voucherDate", LocalDate.of(2026, 6, 30));
        input.put("projectId", "PRJ-RD-2026");

        Voucher v = gen.generate("F001", input);
        VoucherEntry expense = v.getEntries().get(0);
        assertEquals(AuxiliaryType.PROJECT, expense.getAuxiliaryType());
        assertEquals("PRJ-RD-2026", expense.getAuxiliaryEntityId());
    }

    @Test
    void depreciationGeneratorDeptOverridesProject() {
        // Sprint 6 W4-A: 二者都传 → DEPT 优先 (部门归集 > 项目归集 by spec)
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        Map<String, Object> input = new HashMap<>();
        input.put("businessId", "DEP-202606-BOTH");
        input.put("amount", HUNDRED);
        input.put("voucherDate", LocalDate.of(2026, 6, 30));
        input.put("deptId", "DEPT-200");
        input.put("projectId", "PRJ-RD-2026");

        Voucher v = gen.generate("F001", input);
        assertEquals(AuxiliaryType.DEPT, v.getEntries().get(0).getAuxiliaryType());
        assertEquals("DEPT-200", v.getEntries().get(0).getAuxiliaryEntityId());
    }

    @Test
    void depreciationGeneratorIgnoresBlankAuxiliaryIds() {
        // Defensive: 空字符串视为 null (符合 chk_ve_auxiliary_paired)
        DepreciationVoucherGenerator gen = new DepreciationVoucherGenerator();
        Map<String, Object> input = new HashMap<>();
        input.put("businessId", "DEP-202606-BLANK");
        input.put("amount", HUNDRED);
        input.put("voucherDate", LocalDate.of(2026, 6, 30));
        input.put("deptId", "   ");  // blank
        input.put("projectId", "");  // empty

        Voucher v = gen.generate("F001", input);
        assertNull(v.getEntries().get(0).getAuxiliaryType());
        assertNull(v.getEntries().get(0).getAuxiliaryEntityId());
    }
}

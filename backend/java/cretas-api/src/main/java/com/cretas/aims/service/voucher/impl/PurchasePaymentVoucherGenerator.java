package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购付款凭证 generator.
 *
 * 借: 1405 库存商品 (入库增加资产)
 * 贷: 2202 应付账款 (欠供应商款)
 *
 * 业务: PurchaseOrder 审批通过 → 财务记 "原材料入库 + 欠供应商款".
 */
@Component
public class PurchasePaymentVoucherGenerator extends AbstractVoucherGenerator<PurchaseOrder> {

    public static final String BUSINESS_TYPE = "PURCHASE_ORDER";

    @Override
    public VoucherType getType() {
        return VoucherType.PURCHASE_PAYMENT;
    }

    @Override
    public boolean supports(String businessType) {
        return BUSINESS_TYPE.equals(businessType);
    }

    @Override
    protected String extractSourceBusinessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String extractSourceBusinessId(PurchaseOrder order) {
        return order.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(PurchaseOrder order) {
        return order.getOrderDate();
    }

    @Override
    protected String extractDescription(PurchaseOrder order) {
        return "采购订单 " + order.getOrderNumber();
    }

    @Override
    public List<VoucherEntry> buildEntries(PurchaseOrder order) {
        BigDecimal amount = nullToZero(order.getTotalAmount());
        String supplierId = order.getSupplierId();  // Sprint 5 F-2: 供应商辅助核算
        return List.of(
                debitEntry(1, "1405", "库存商品", amount, "采购入库 " + order.getOrderNumber()),
                // 应付账款按供应商分账 (R-HJ Round 11 §G.1 供应商应付明细账)
                creditEntryWithAuxiliary(2, "2202", "应付账款", amount, "供应商应付",
                        supplierId != null ? AuxiliaryType.SUPPLIER : null, supplierId)
        );
    }
}

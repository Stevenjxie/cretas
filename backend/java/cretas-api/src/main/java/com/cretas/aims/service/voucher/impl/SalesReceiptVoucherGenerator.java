package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 销售收款凭证 generator.
 *
 * 借: 1122 应收账款
 * 贷: 6001 主营业务收入
 *
 * 业务: SalesOrder 审批通过 → 财务系统记录"客户欠款" + "收入入账".
 */
@Component
public class SalesReceiptVoucherGenerator extends AbstractVoucherGenerator<SalesOrder> {

    public static final String BUSINESS_TYPE = "SALES_ORDER";

    @Override
    public VoucherType getType() {
        return VoucherType.SALES_RECEIPT;
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
    protected String extractSourceBusinessId(SalesOrder order) {
        return order.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(SalesOrder order) {
        return order.getOrderDate();
    }

    @Override
    protected String extractDescription(SalesOrder order) {
        return "销售订单 " + order.getOrderNumber();
    }

    @Override
    public List<VoucherEntry> buildEntries(SalesOrder order) {
        BigDecimal amount = nullToZero(order.getTotalAmount());
        String customerId = order.getCustomerId();  // Sprint 5 F-2: 客户辅助核算
        return List.of(
                // 应收账款按客户分账 (R-HJ Round 11 §G.1 客户应收明细账)
                debitEntryWithAuxiliary(1, "1122", "应收账款", amount, "销售收入挂账",
                        customerId != null ? AuxiliaryType.CUSTOMER : null, customerId),
                creditEntry(2, "6001", "主营业务收入", amount, "销售订单 " + order.getOrderNumber())
        );
    }
}

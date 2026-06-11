package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购付款凭证 generator (SP11 价税分离).
 *
 * <p>含税订单 (tax_amount > 0):
 * <pre>
 * 借: 1405 库存商品                         = 未税净额
 * 借: 2221.02 应交税费-应交增值税-进项税额  = 进项税额
 * 贷: 2202 应付账款                         = 含税总额 (未税 + 进项税)   [供应商辅助核算]
 * </pre>
 *
 * <p>零税订单 (tax_amount = 0/null) → 退化两行 (向后兼容, 字节与历史一致):
 * <pre>
 * 借: 1405 库存商品       = 未税净额
 * 贷: 2202 应付账款       = 未税净额   [供应商辅助核算]
 * </pre>
 *
 * <p>口径 (SP11 spec §3.1): {@code total_amount} 未税净额, {@code tax_amount} 进项税额单列,
 * 含税 = 二者相加。<b>应付用加法 (未税 + 税)</b> 保证借贷精确平衡, 进项税不进库存成本 (可抵扣)。
 *
 * <p>科目代码 2221.02 (进项税) 与主科目 (1405/2202) 同为 hardcode 默认值, 客户实际金蝶账套
 * 可通过 VoucherTemplate (template-first 路径) 覆盖。
 *
 * 业务: PurchaseOrder 审批通过 → 财务记 "原材料入库(未税) + 进项税 + 欠供应商款(含税)".
 */
@Component
public class PurchasePaymentVoucherGenerator extends AbstractVoucherGenerator<PurchaseOrder> {

    public static final String BUSINESS_TYPE = "PURCHASE_ORDER";

    /** 应交税费-应交增值税-进项税额 (默认科目, 客户账套可覆盖) */
    static final String INPUT_VAT_CODE = "2221.02";
    static final String INPUT_VAT_NAME = "应交税费-应交增值税-进项税额";

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
        // SP11: total_amount = 未税净额, tax_amount = 进项税额 (单列)
        BigDecimal netAmount = nullToZero(order.getTotalAmount());       // 未税净额
        BigDecimal taxAmount = nullToZero(order.getTaxAmount());         // 进项税额
        // 含税应付 = 未税 + 税 (加法, 借贷精确平衡 — SP11 spec §3.1/§3.4)
        BigDecimal payableAmount = netAmount.add(taxAmount);
        String supplierId = order.getSupplierId();  // Sprint 5 F-2: 供应商辅助核算

        List<VoucherEntry> entries = new ArrayList<>(3);
        // 库存商品: 未税净额 (进项税不进成本)
        entries.add(debitEntry(1, "1405", "库存商品", netAmount, "采购入库 " + order.getOrderNumber()));
        // 应付账款按供应商分账 (R-HJ Round 11 §G.1 供应商应付明细账); 含税
        entries.add(creditEntryWithAuxiliary(2, "2202", "应付账款", payableAmount, "供应商应付",
                supplierId != null ? AuxiliaryType.SUPPLIER : null, supplierId));
        // 进项税额单列 借方 (仅含税订单, 零税退化两行向后兼容)
        if (taxAmount.signum() > 0) {
            entries.add(debitEntry(3, INPUT_VAT_CODE, INPUT_VAT_NAME, taxAmount,
                    "进项税额 " + order.getOrderNumber()));
        }
        return entries;
    }
}

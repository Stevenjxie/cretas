package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 销售收款凭证 generator (SP11 价税分离).
 *
 * <p>含税订单 (tax_amount > 0):
 * <pre>
 * 借: 1122 应收账款                         = 含税总额 (未税 + 销项税)   [客户辅助核算]
 * 贷: 6001 主营业务收入                     = 未税净额
 * 贷: 2221.01 应交税费-应交增值税-销项税额  = 销项税额
 * </pre>
 *
 * <p>零税订单 (tax_amount = 0/null) → 退化两行 (向后兼容, 字节与历史一致):
 * <pre>
 * 借: 1122 应收账款       = 未税净额   [客户辅助核算]
 * 贷: 6001 主营业务收入   = 未税净额
 * </pre>
 *
 * <p>口径 (SP11 spec §3.1): {@code total_amount} 未税净额, {@code tax_amount} 销项税额单列,
 * 含税 = 二者相加。<b>应收用加法 (未税 + 税)</b> 保证借贷精确平衡, 不重算税率, 无舍入裂缝。
 *
 * <p>科目代码 2221.01 (销项税) 与主科目 (1122/6001) 同为 hardcode 默认值, 客户实际金蝶账套
 * 可通过 VoucherTemplate (template-first 路径, 见 AbstractVoucherGenerator.generate) 覆盖。
 *
 * 业务: SalesOrder 审批通过 → 财务系统记录"客户欠款" + "收入入账" + "销项税负债".
 */
@Component
public class SalesReceiptVoucherGenerator extends AbstractVoucherGenerator<SalesOrder> {

    public static final String BUSINESS_TYPE = "SALES_ORDER";

    /** 应交税费-应交增值税-销项税额 (默认科目, 客户账套可覆盖) */
    static final String OUTPUT_VAT_CODE = "2221.01";
    static final String OUTPUT_VAT_NAME = "应交税费-应交增值税-销项税额";

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
        // SP11: total_amount = 未税净额, tax_amount = 销项税额 (单列)
        BigDecimal netAmount = nullToZero(order.getTotalAmount());       // 未税净额
        BigDecimal taxAmount = nullToZero(order.getTaxAmount());         // 销项税额
        // 含税应收 = 未税 + 税 (加法, 借贷精确平衡, 无舍入裂缝 — SP11 spec §3.1/§3.4)
        BigDecimal receivableAmount = netAmount.add(taxAmount);
        String customerId = order.getCustomerId();  // Sprint 5 F-2: 客户辅助核算

        List<VoucherEntry> entries = new ArrayList<>(3);
        // 应收账款按客户分账 (R-HJ Round 11 §G.1 客户应收明细账); 含税
        entries.add(debitEntryWithAuxiliary(1, "1122", "应收账款", receivableAmount, "销售收入挂账",
                customerId != null ? AuxiliaryType.CUSTOMER : null, customerId));
        // 主营业务收入: 未税净额
        entries.add(creditEntry(2, "6001", "主营业务收入", netAmount, "销售订单 " + order.getOrderNumber()));
        // 销项税额单列 (仅含税订单, 零税退化两行向后兼容)
        if (taxAmount.signum() > 0) {
            entries.add(creditEntry(3, OUTPUT_VAT_CODE, OUTPUT_VAT_NAME, taxAmount,
                    "销项税额 " + order.getOrderNumber()));
        }
        return entries;
    }
}

package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 退货凭证 generator.
 *
 * <p>销售退货 (SALES_RETURN):
 * <pre>
 * 借: 6001 主营业务收入 (冲减收入 — 等同红字 SALES_RECEIPT)
 * 贷: 1122 应收账款 (冲减客户欠款) — 辅助核算 CUSTOMER=counterpartyId
 * </pre>
 *
 * <p>采购退货 (PURCHASE_RETURN):
 * <pre>
 * 借: 2202 应付账款 (冲减供应商欠款) — 辅助核算 SUPPLIER=counterpartyId
 * 贷: 1405 库存商品 (库存减少)
 * </pre>
 *
 * <p>业务: ReturnOrder 审批通过 → 财务反向冲销原销售/采购凭证.
 *
 * <p>Sprint 6 W4-A: 加 CUSTOMER/SUPPLIER 辅助核算到对应往来账款 line — 支持 R-HJ Round 11 §G.1
 * 客户应收/供应商应付明细账中正确反映退货冲销.
 *
 * <p><b>SP11 价税分离 — 退货退化两行 (向后兼容):</b> {@link ReturnOrder} <b>无 {@code tax_amount}
 * 列</b> (只有 {@code total_amount}), 故退货凭证按 SP11 spec §5 "无税率数据视为 0 税 → 退化两行"
 * 处理, 与历史字节一致, 借贷恒平。退货冲销的价税分离需待 ReturnOrder 补 tax_amount 列后才能拆
 * (本 spec §6 defer: 历史含税精确回填脚本按需单独做)。现状两行已对齐原销售/采购凭证的总额冲销。
 */
@Component
public class ReturnVoucherGenerator extends AbstractVoucherGenerator<ReturnOrder> {

    public static final String BUSINESS_TYPE = "RETURN_ORDER";

    @Override
    public VoucherType getType() {
        return VoucherType.RETURN;
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
    protected String extractSourceBusinessId(ReturnOrder r) {
        return r.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(ReturnOrder r) {
        return r.getReturnDate();
    }

    @Override
    protected String extractDescription(ReturnOrder r) {
        return "退货单 " + r.getReturnNumber();
    }

    @Override
    public List<VoucherEntry> buildEntries(ReturnOrder r) {
        BigDecimal amount = nullToZero(r.getTotalAmount());
        String counterpartyId = r.getCounterpartyId();  // Sprint 6 W4-A: 客户/供应商辅助核算
        ReturnType returnType = r.getReturnType();

        // SALES_RETURN: AR 在贷方 (冲减客户欠款), CUSTOMER 辅助核算
        // PURCHASE_RETURN: AP 在借方 (冲减供应商欠款), SUPPLIER 辅助核算
        // null returnType → 走 legacy SALES_RETURN 行为 (向后兼容)
        if (returnType == ReturnType.PURCHASE_RETURN) {
            return List.of(
                    // 应付账款按供应商分账 (R-HJ Round 11 §G.1 供应商应付明细账冲销)
                    debitEntryWithAuxiliary(1, "2202", "应付账款", amount, "采购退货冲减应付 " + r.getReturnNumber(),
                            counterpartyId != null ? AuxiliaryType.SUPPLIER : null, counterpartyId),
                    creditEntry(2, "1405", "库存商品", amount, "退货库存减少")
            );
        }
        // SALES_RETURN (默认 / 老数据无 type)
        return List.of(
                debitEntry(1, "6001", "主营业务收入", amount, "退货冲减收入 " + r.getReturnNumber()),
                // 应收账款按客户分账 (R-HJ Round 11 §G.1 客户应收明细账冲销)
                creditEntryWithAuxiliary(2, "1122", "应收账款", amount, "客户应收冲减",
                        counterpartyId != null ? AuxiliaryType.CUSTOMER : null, counterpartyId)
        );
    }
}

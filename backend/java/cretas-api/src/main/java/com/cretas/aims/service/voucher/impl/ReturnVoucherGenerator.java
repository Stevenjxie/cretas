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
 * 贷: 1403 原材料 (原料退回供应商, 库存减少) — 整单皆产成品时退化为 1405 库存商品
 * </pre>
 * (#4 fix: 采购退货退的是原料 → 1403, 与报损/盘点/期初 raw=1403 口径统一; 旧值 1405 已修正。)
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
            // #4 fix: 采购退货退的是【原料】(退回供应商) → 贷 1403 原材料, 与本轮 campaign 报损/盘点/期初
            // 统一的 raw=1403 口径一致 (此前误用 1405 库存商品=产成品科目, 与原料入库/报损口径打架)。
            // 仅当退货整单都是产成品 (罕见: 外购成品转售退货) 才用 1405。honest-null: 无 items / 惰性加载
            // 失败时默认按原料 1403 (采购退货在本 ERP 语义即原料)。
            String[] subject = resolvePurchaseReturnInventorySubject(r);
            return List.of(
                    // 应付账款按供应商分账 (R-HJ Round 11 §G.1 供应商应付明细账冲销)
                    debitEntryWithAuxiliary(1, "2202", "应付账款", amount, "采购退货冲减应付 " + r.getReturnNumber(),
                            counterpartyId != null ? AuxiliaryType.SUPPLIER : null, counterpartyId),
                    creditEntry(2, subject[0], subject[1], amount, "退货库存减少")
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

    /** 原材料科目 (raw, 与报损/盘点/期初一致)。 */
    static final String SUBJECT_RAW_MATERIAL_CODE = "1403";
    static final String SUBJECT_RAW_MATERIAL_NAME = "原材料";
    /** 库存商品科目 (finished goods)。 */
    static final String SUBJECT_FINISHED_GOODS_CODE = "1405";
    static final String SUBJECT_FINISHED_GOODS_NAME = "库存商品";

    /**
     * #4: 采购退货贷方存货科目 = raw (1403) 还是 finished (1405)。
     *
     * <p>采购退货在本 ERP 语义即"原料退回供应商" → 默认 1403。仅当退货整单每一行都是产成品
     * (productTypeId 非空且 materialTypeId 为空, 罕见的外购成品转售退货) 才用 1405。
     * items 惰性加载失败 / 为空 → honest 默认按原料 1403 (不臆断成产成品)。
     *
     * @return [code, name]
     */
    private String[] resolvePurchaseReturnInventorySubject(ReturnOrder r) {
        boolean allFinished = false;
        try {
            java.util.List<com.cretas.aims.entity.inventory.ReturnOrderItem> items = r.getItems();
            if (items != null && !items.isEmpty()) {
                allFinished = items.stream().allMatch(it ->
                        it.getProductTypeId() != null && it.getMaterialTypeId() == null);
            }
        } catch (RuntimeException e) {
            // LazyInitializationException 等 → 默认按原料 (采购退货主流即原料)。
            allFinished = false;
        }
        return allFinished
                ? new String[]{SUBJECT_FINISHED_GOODS_CODE, SUBJECT_FINISHED_GOODS_NAME}
                : new String[]{SUBJECT_RAW_MATERIAL_CODE, SUBJECT_RAW_MATERIAL_NAME};
    }

    /**
     * 🔴🔒 #1207: template 路径把 CUSTOMER/SUPPLIER 辅助核算附到对应往来账款行 (与 buildEntries 一致)。
     * <ul>
     *   <li>PURCHASE_RETURN → 应付账款在借方 (冲减供应商欠款), 前缀 2202, SUPPLIER；</li>
     *   <li>SALES_RETURN (默认/老数据无 type) → 应收账款在贷方 (冲减客户欠款), 前缀 1122, CUSTOMER。</li>
     * </ul>
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, ReturnOrder r) {
        String counterpartyId = r.getCounterpartyId();
        if (counterpartyId == null) {
            return; // honest-null
        }
        if (r.getReturnType() == ReturnType.PURCHASE_RETURN) {
            attachAuxiliary(entries, EntrySide.DEBIT, "2202", AuxiliaryType.SUPPLIER, counterpartyId);
        } else {
            attachAuxiliary(entries, EntrySide.CREDIT, "1122", AuxiliaryType.CUSTOMER, counterpartyId);
        }
    }
}

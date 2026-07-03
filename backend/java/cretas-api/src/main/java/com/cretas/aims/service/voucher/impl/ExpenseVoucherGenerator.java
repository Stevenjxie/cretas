package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.restaurant.WastageRecord;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 报销 / 损耗 凭证 generator. (Phase 1 实现绑定 WastageRecord)
 *
 * <pre>
 * 借: 6602.01 管理费用-损耗
 * 贷: 1405 库存商品 — 辅助核算 INVENTORY=materialBatchId (优先) 或 rawMaterialTypeId (fallback)
 * </pre>
 *
 * <p>业务: WastageRecord 审批 APPROVED → 财务记 "损耗费用 + 库存减少".
 *
 * <p>Sprint 6 W4-A: 库存商品 line 加 INVENTORY 辅助核算 — 支持 R-HJ Round 11 §G.1 库存科目按
 * 批次/SKU 明细账. materialBatchId 不为空时优先 (批次粒度), 否则用 rawMaterialTypeId (SKU 粒度).
 */
@Component
public class ExpenseVoucherGenerator extends AbstractVoucherGenerator<WastageRecord> {

    public static final String BUSINESS_TYPE = "WASTAGE_RECORD";

    @Override
    public VoucherType getType() {
        return VoucherType.EXPENSE;
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
    protected String extractSourceBusinessId(WastageRecord w) {
        return w.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(WastageRecord w) {
        return w.getWastageDate();
    }

    @Override
    protected String extractDescription(WastageRecord w) {
        return "损耗单 " + w.getWastageNumber() + " (" + w.getType() + ")";
    }

    @Override
    public List<VoucherEntry> buildEntries(WastageRecord w) {
        BigDecimal amount = nullToZero(w.getEstimatedCost());
        // Sprint 6 W4-A: 库存维度优先 materialBatchId (批次粒度) → rawMaterialTypeId (SKU 粒度) → null
        String inventoryRef = w.getMaterialBatchId() != null && !w.getMaterialBatchId().isBlank()
                ? w.getMaterialBatchId()
                : w.getRawMaterialTypeId();
        return List.of(
                debitEntry(1, "6602.01", "管理费用-损耗", amount, "损耗 " + w.getWastageNumber()),
                // 库存科目按批次/SKU 分账 (R-HJ Round 11 §G.1 库存明细账)
                creditEntryWithAuxiliary(2, "1405", "库存商品", amount, "库存减少 (" + w.getType() + ")",
                        inventoryRef != null && !inventoryRef.isBlank() ? AuxiliaryType.INVENTORY : null,
                        inventoryRef != null && !inventoryRef.isBlank() ? inventoryRef : null)
        );
    }

    /**
     * 🔴🔒 #1207: template 路径把 INVENTORY 辅助核算附到库存商品贷方行 (与 buildEntries 一致)。
     * 贷方 = 库存减少 (按批次/SKU 分账), 前缀 1405; 管理费用借方不挂存货维度。
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, WastageRecord w) {
        String inventoryRef = w.getMaterialBatchId() != null && !w.getMaterialBatchId().isBlank()
                ? w.getMaterialBatchId()
                : w.getRawMaterialTypeId();
        boolean present = inventoryRef != null && !inventoryRef.isBlank();
        attachAuxiliary(entries, EntrySide.CREDIT, "1405",
                present ? AuxiliaryType.INVENTORY : null, present ? inventoryRef : null);
    }
}

package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 库存调拨凭证 generator.
 *
 * <pre>
 * 借: 1405 库存商品 / cost_center=target_warehouse — 辅助核算 INVENTORY (仅单一品类时)
 * 贷: 1405 库存商品 / cost_center=source_warehouse — 辅助核算 INVENTORY (同上)
 * </pre>
 *
 * <p>同科目调拨: 借贷同 1405, 通过 cost_center 区分仓库.
 * 跨工厂调拨可通过 cost_center 内嵌 factory_id 实现 (此实现先用 warehouse 兼容).
 *
 * <p>Sprint 6 W4-A: 当调拨单 items 全部为同一 materialTypeId / productTypeId 时, 借贷两行
 * 都挂 INVENTORY 辅助核算 (库存科目按 SKU 明细账). 多品类调拨则不挂 (避免误导),
 * cost_center 继续承担仓库维度.
 */
@Component
public class InventoryTransferVoucherGenerator extends AbstractVoucherGenerator<InternalTransfer> {

    public static final String BUSINESS_TYPE = "INTERNAL_TRANSFER";

    @Override
    public VoucherType getType() {
        return VoucherType.INVENTORY_TRANSFER;
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
    protected String extractSourceBusinessId(InternalTransfer t) {
        return t.getId();
    }

    @Override
    protected LocalDate extractVoucherDate(InternalTransfer t) {
        return t.getTransferDate();
    }

    @Override
    protected String extractDescription(InternalTransfer t) {
        return "调拨单 " + t.getTransferNumber();
    }

    @Override
    public List<VoucherEntry> buildEntries(InternalTransfer t) {
        BigDecimal amount = nullToZero(t.getTotalAmount());
        // Sprint 6 W4-A: 单一品类调拨时挂 INVENTORY 辅助核算; 多品类则 null (避免误导)
        String singleItemId = detectSingleItemId(t);

        VoucherEntry debit = debitEntryWithAuxiliary(1, "1405", "库存商品", amount, "调入: " + t.getTransferNumber(),
                singleItemId != null ? AuxiliaryType.INVENTORY : null, singleItemId);
        VoucherEntry credit = creditEntryWithAuxiliary(2, "1405", "库存商品", amount, "调出: " + t.getTransferNumber(),
                singleItemId != null ? AuxiliaryType.INVENTORY : null, singleItemId);
        // cost_center 仍区分调入/调出方 (仓库维度)
        debit.setCostCenter(buildCostCenter(t.getTargetFactoryId(), t.getTargetWarehouseId(), "调入"));
        credit.setCostCenter(buildCostCenter(t.getSourceFactoryId(), t.getSourceWarehouseId(), "调出"));
        return List.of(debit, credit);
    }

    /**
     * 🔴🔒 #1207: template 路径把 INVENTORY 辅助核算附到借贷两条库存商品行 (与 buildEntries 一致),
     * 并补 cost_center 仓库维度 (调入=借方/target, 调出=贷方/source), 让两条同科目 1405 行可区分。
     * 多品类调拨 (singleItemId=null) 时不挂 INVENTORY (避免误导), 但仍补 cost_center。
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, InternalTransfer t) {
        String singleItemId = detectSingleItemId(t);
        AuxiliaryType auxType = singleItemId != null ? AuxiliaryType.INVENTORY : null;
        // 借贷两侧库存商品行都挂 INVENTORY (同一 SKU 调入调出)
        attachAuxiliary(entries, EntrySide.DEBIT, "1405", auxType, singleItemId);
        attachAuxiliary(entries, EntrySide.CREDIT, "1405", auxType, singleItemId);
        // cost_center 仓库维度 (与 buildEntries 一致): 借方=调入 target, 贷方=调出 source
        for (VoucherEntry e : entries) {
            boolean isDebit = e.getDebit() != null && e.getDebit().signum() > 0;
            boolean isCredit = e.getCredit() != null && e.getCredit().signum() > 0;
            if (isDebit && e.getCostCenter() == null) {
                e.setCostCenter(buildCostCenter(t.getTargetFactoryId(), t.getTargetWarehouseId(), "调入"));
            } else if (isCredit && e.getCostCenter() == null) {
                e.setCostCenter(buildCostCenter(t.getSourceFactoryId(), t.getSourceWarehouseId(), "调出"));
            }
        }
    }

    /**
     * Sprint 6 W4-A: 若调拨单 items 全部为单一品类 (同一 materialTypeId 或同一 productTypeId),
     * 返该 ID; 否则 null. 老调拨单 items=空 也返 null (向后兼容).
     */
    private String detectSingleItemId(InternalTransfer t) {
        List<InternalTransferItem> items = t.getItems();
        if (items == null || items.isEmpty()) {
            return null;
        }
        // 优先 materialTypeId, 再 productTypeId
        String firstMat = items.get(0).getMaterialTypeId();
        String firstProd = items.get(0).getProductTypeId();
        if (firstMat != null && !firstMat.isBlank()) {
            for (InternalTransferItem item : items) {
                if (!firstMat.equals(item.getMaterialTypeId())) {
                    return null;
                }
            }
            return firstMat;
        }
        if (firstProd != null && !firstProd.isBlank()) {
            for (InternalTransferItem item : items) {
                if (!firstProd.equals(item.getProductTypeId())) {
                    return null;
                }
            }
            return firstProd;
        }
        return null;
    }

    private String buildCostCenter(String factoryId, String warehouseId, String label) {
        return label + ":" + (factoryId != null ? factoryId : "?") +
                (warehouseId != null ? "/" + warehouseId : "");
    }
}

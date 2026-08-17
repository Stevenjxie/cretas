package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.unit.UnitDisplayNames;

import java.util.List;
import java.util.stream.Collectors;

public class ProductionStockShortageException extends BusinessException {

    public static final String MESSAGE = "当前只能保存草稿，生产库中投料量不足";

    private final ProductionStockShortageDTO shortage;

    public ProductionStockShortageException(ProductionStockShortageDTO shortage) {
        super(409, buildMessage(shortage));
        this.shortage = shortage;
        withCode("PRODUCTION_STOCK_SHORTAGE");
        withHint(buildMessage(shortage));
        withSeverity("BLOCKING");
        withHintTarget("投料量");
    }

    public ProductionStockShortageDTO getShortage() {
        return shortage;
    }

    private static String buildMessage(ProductionStockShortageDTO shortage) {
        String summary = MESSAGE
                + "。需要 " + shortage.getRequired().stripTrailingZeros().toPlainString() + unitLabel(shortage.getUnit())
                + "，可用 " + shortage.getAvailable().stripTrailingZeros().toPlainString() + unitLabel(shortage.getUnit())
                + "，缺少 " + shortage.getShortage().stripTrailingZeros().toPlainString() + unitLabel(shortage.getUnit())
                + "，请联系仓管补料";
        List<ProductionStockShortageDTO.Item> items = shortage.getItems() == null
                ? List.of()
                : shortage.getItems();
        boolean mixedUnits = "mixed".equals(shortage.getUnit());
        boolean hasNamedItem = items.stream()
                .anyMatch(item -> item.getMaterialName() != null && !item.getMaterialName().isBlank());
        if (items.isEmpty() || (!mixedUnits && !hasNamedItem)) {
            return summary;
        }
        String detail = items.stream()
                .map(item -> displayName(item)
                        + sourceLabel(item.getSourceType())
                        + "：需要 " + decimal(item.getRequired()) + unitLabel(item.getUnit())
                        + "，可用 " + decimal(item.getAvailable()) + unitLabel(item.getUnit())
                        + "，缺少 " + decimal(item.getShortage()) + unitLabel(item.getUnit())
                        + causeLabel(item))
                .collect(Collectors.joining("；"));
        if (mixedUnits) {
            return MESSAGE + "。短缺明细：" + detail + "，请联系仓管补料";
        }
        return summary + "。短缺明细：" + detail;
    }

    /**
     * 成因标注 —— 「工厂里有但没领到生产仓」和「工厂里真没有」要给**不同的下一步动作**。
     *
     * <p>🔴 2026-08-18 实测: 同一天两条短缺, 当时说的是同一句「请联系仓管补料」:
     * <ul>
     *   <li>冻猪蹄: 全厂在手 30kg, 生产仓只有 5kg ⇒ 该做的是**领料**</li>
     *   <li>2030真空袋: 全厂在手 0 ⇒ 该做的是**采购入库**</li>
     * </ul>
     * 一句话覆盖两种处境, 操作工按提示去找仓管, 而仓管手上根本没有货 —— 白跑一趟。
     *
     * <p>⚠️ 成因由 {@code factoryOnHand} 推出来, 不是手填; 没算出在手量时(null)不瞎标。
     */
    private static String causeLabel(ProductionStockShortageDTO.Item item) {
        if (item.getCause() == null) {
            return "";
        }
        return switch (item.getCause()) {
            case NOT_REQUISITIONED -> "（全厂在手 "
                    + decimal(item.getFactoryOnHand()) + unitLabel(item.getUnit())
                    + "，只是还没领到生产仓 → 去「生产管理 → 领料」）";
            case TRULY_OUT_OF_STOCK -> "（全厂在手也是 0 → 需要先采购入库，找仓管补料没用）";
        };
    }

    /**
     * 单位在文案里一律走展示名 —— 库里存的是码({@code box}), 客户读不懂。
     * {@code "mixed"} 是多单位混合时的哨兵值, 不是真单位, 原样保留。
     */
    private static String unitLabel(String unit) {
        return UnitDisplayNames.display(unit);
    }

    private static String decimal(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String displayName(ProductionStockShortageDTO.Item item) {
        return item.getMaterialName() == null || item.getMaterialName().isBlank()
                ? item.getMaterialTypeId()
                : item.getMaterialName();
    }

    private static String sourceLabel(String sourceType) {
        if ("PACKAGING".equals(sourceType)) {
            return "（包材）";
        }
        if ("SEASONING".equals(sourceType)) {
            return "（调料）";
        }
        return "";
    }
}

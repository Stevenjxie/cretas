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
                        + "，缺少 " + decimal(item.getShortage()) + unitLabel(item.getUnit()))
                .collect(Collectors.joining("；"));
        if (mixedUnits) {
            return MESSAGE + "。短缺明细：" + detail + "，请联系仓管补料";
        }
        return summary + "。短缺明细：" + detail;
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

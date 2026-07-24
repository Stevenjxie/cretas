package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.exception.BusinessException;

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
                + "。需要 " + shortage.getRequired().stripTrailingZeros().toPlainString() + shortage.getUnit()
                + "，可用 " + shortage.getAvailable().stripTrailingZeros().toPlainString() + shortage.getUnit()
                + "，缺少 " + shortage.getShortage().stripTrailingZeros().toPlainString() + shortage.getUnit()
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
                        + "：需要 " + decimal(item.getRequired()) + item.getUnit()
                        + "，可用 " + decimal(item.getAvailable()) + item.getUnit()
                        + "，缺少 " + decimal(item.getShortage()) + item.getUnit())
                .collect(Collectors.joining("；"));
        if (mixedUnits) {
            return MESSAGE + "。短缺明细：" + detail + "，请联系仓管补料";
        }
        return summary + "。短缺明细：" + detail;
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

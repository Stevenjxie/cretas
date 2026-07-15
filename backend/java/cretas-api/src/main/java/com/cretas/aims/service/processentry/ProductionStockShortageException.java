package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.exception.BusinessException;

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
        return MESSAGE
                + "。需要 " + shortage.getRequired().stripTrailingZeros().toPlainString() + shortage.getUnit()
                + "，可用 " + shortage.getAvailable().stripTrailingZeros().toPlainString() + shortage.getUnit()
                + "，缺少 " + shortage.getShortage().stripTrailingZeros().toPlainString() + shortage.getUnit()
                + "，请联系仓管补料";
    }
}

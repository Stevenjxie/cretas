package com.cretas.aims.dto.factory;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 半成品盘点差异预览 DTO (镜像 SP7 {@link StocktakeDiffPreviewDTO})。
 */
@Data
public class SemiFinishedStocktakeDiffPreviewDTO {

    private String stocktakeId;
    private String stocktakeNo;
    private String periodMonth;
    private List<DiffLine> diffLines;
    private int surplusCount;
    private int shortageCount;
    private int matchCount;

    @Data
    public static class DiffLine {
        private String itemId;
        private Long semiFinishedId;
        private String intermediateBatchNo;
        private String productTypeId;
        private String unit;
        private BigDecimal systemQty;
        private BigDecimal actualQty;
        private BigDecimal differenceQty;
        private String differenceType; // SURPLUS / SHORTAGE / MATCH
    }
}

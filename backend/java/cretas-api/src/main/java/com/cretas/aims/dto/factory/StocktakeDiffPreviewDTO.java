package com.cretas.aims.dto.factory;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 盘点差异预览 DTO (SP7 §4.1 diff-preview).
 */
@Data
public class StocktakeDiffPreviewDTO {

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
        private String materialBatchId;
        private String batchNumber;
        private String materialName;
        private BigDecimal systemQty;
        private BigDecimal actualQty;
        private BigDecimal differenceQty;
        private String differenceType; // SURPLUS / SHORTAGE / MATCH
    }
}

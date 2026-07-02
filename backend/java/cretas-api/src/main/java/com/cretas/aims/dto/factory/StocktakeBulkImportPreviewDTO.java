package com.cretas.aims.dto.factory;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点批量导入预览结果 DTO。
 *
 * <p>预览阶段（read-only，不落库）返回：匹配成功行的账面/实盘/差异比对，
 * 以及匹配失败行（未知批次 / 批次号空 / 负数 / 重复）的诚实报错——不静默丢弃。
 *
 * <p>确认阶段复用同一结构，额外回填 {@link #stocktakeId} / {@link #stocktakeNo}
 * （此时盘点任务已按现有 {@code FactoryStocktakeService.initiate + updateItems} 创建，
 * 后续走既有 提交→审批→生效 流程写库存 + 留痕，不新增平行过账路径）。
 */
@Data
public class StocktakeBulkImportPreviewDTO {

    /** 确认后才有值；预览阶段为 null。*/
    private String stocktakeId;
    private String stocktakeNo;

    private String warehouseId;
    private String warehouseName;
    private String periodMonth;

    /** Excel 数据总行数（不含表头）。*/
    private int totalRows;
    /** 成功匹配到当前库存批次的行数。*/
    private int matchedCount;
    /** 匹配成功且填了实盘数量 → 盘盈行数（实盘 > 账面）。*/
    private int surplusCount;
    /** 盘亏行数（实盘 < 账面）。*/
    private int shortageCount;
    /** 一致行数（实盘 == 账面）。*/
    private int matchCount;
    /** 匹配成功但实盘数量留空（未盘点）→ 生效时跳过，不校正。*/
    private int skippedCount;
    /** 匹配失败行数（= errors.size()）。*/
    private int errorCount;

    private List<MatchedLine> matchedLines = new ArrayList<>();
    private List<RowError> errors = new ArrayList<>();

    /** 匹配成功的比对行。*/
    @Data
    public static class MatchedLine {
        private String materialBatchId;
        private String batchNumber;
        private String materialName;
        private String unit;
        private BigDecimal systemQty;
        /** 实盘数量；留空表示未盘点。*/
        private BigDecimal actualQty;
        /** = 实盘 - 账面；未盘点时为 null。*/
        private BigDecimal differenceQty;
        /** SURPLUS / SHORTAGE / MATCH / null(未盘点)。*/
        private String differenceType;
    }

    /** 匹配失败行（诚实上报，供用户改表后重传）。*/
    @Data
    public static class RowError {
        /** Excel 行号（含表头，从 2 起）。*/
        private int rowNumber;
        private String batchNumber;
        private String materialName;
        private String reason;

        public RowError() {
        }

        public RowError(int rowNumber, String batchNumber, String materialName, String reason) {
            this.rowNumber = rowNumber;
            this.batchNumber = batchNumber;
            this.materialName = materialName;
            this.reason = reason;
        }
    }
}

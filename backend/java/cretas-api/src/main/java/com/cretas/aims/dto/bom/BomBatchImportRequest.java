package com.cretas.aims.dto.bom;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BOM 批量导入请求 (前端 Excel 解析 → JSON). 后端逐行校验, 任一行失败整批不入库 (原子).
 */
@Data
public class BomBatchImportRequest {

    @NotBlank(message = "productTypeId 不可为空")
    private String productTypeId;

    private List<Row> items = new ArrayList<>();

    @Data
    public static class Row {
        /** 物料名 (materialTypeId 缺时按此模糊匹配原料类型). */
        private String materialName;
        /** 物料类型 id (可选; 缺则按 materialName 匹配). */
        private String materialTypeId;
        /** RAW | AUXILIARY | PACKAGING; 缺省 RAW. */
        private String materialCategory;
        /** 成品含量/标准用量, 必须 > 0. */
        private BigDecimal standardQuantity;
        /** 出成率% (可选, null=待评估). */
        private BigDecimal yieldRate;
        /** 计量单位 (可选; 缺省 RAW/AUXILIARY=g, PACKAGING=pcs). */
        private String unit;
    }
}

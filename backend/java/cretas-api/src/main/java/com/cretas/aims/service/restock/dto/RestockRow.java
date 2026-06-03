package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

/** 备货看板一行 (一个产品)。 */
@Data
@Builder
public class RestockRow {
    private String productTypeId;
    private String productName;
    private String unit;                 // 盒
    private BigDecimal demandQty;        // 需求(盒); 单位不一致时 null
    private BigDecimal fgAvailableQty;   // 成品可用(盒)
    private BigDecimal wipEstimatedQty;  // 在产折成品(盒,估); 无法折时 null
    private BigDecimal scheduledQty;     // 已排产(盒, 仅未开工计划)
    private BigDecimal totalAvailableQty;// 合计可用; 单位不一致时 null
    private BigDecimal shortfallQty;     // max(需求-合计,0); 单位不一致时 null
    private String status;               // SATISFIED | SHORTFALL | UNIT_INCONSISTENT
    private boolean wipIsEstimated;      // 在产列带"估"角标
    private String conversionWarning;    // 未配置规格/出率/单位不一致 等; 无则 null
}

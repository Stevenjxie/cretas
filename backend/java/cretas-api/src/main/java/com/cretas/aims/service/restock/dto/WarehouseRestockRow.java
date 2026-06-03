package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

/**
 * P3 多仓备货看板一行 — 一个产品 × 一个目的仓。
 *
 * <p>库存池 (FG/WIP/已排产) 仍为全厂级共享，只有需求侧按仓拆分。
 * 展示层应明确标注"该仓需求 / 全厂可用"，避免用户误以为库存已按仓预分配。
 *
 * <p>关系: {@link WarehouseRestockBoardDTO} 包含多个此对象, 一个产品可能出现在多行 (不同目的仓)。
 */
@Data
@Builder
public class WarehouseRestockRow {
    private String productTypeId;
    private String productName;
    private String unit;                    // 盒

    /** 目的仓 code; COALESCE null → '未分仓'。 */
    private String destWarehouseCode;
    /** 目的仓全名 (展示用); 旧行可能为 null。 */
    private String destWarehouseName;

    // ---- 需求侧 (该仓) ----

    /** 该仓需求量(盒); 单位不一致时 null。 */
    private BigDecimal warehouseDemandQty;

    // ---- 供给侧 (全厂共享池) ----
    // 注: 以下三项是该产品在全厂的可用数量, 不因目的仓而变化.
    // UI 展示时应加标注 "全厂可用" 以防防呆.

    /** 全厂成品可用 (盒)。 */
    private BigDecimal fgAvailableQty;
    /** 全厂在产折盒 (盒, 估); 无法折算时 null。 */
    private BigDecimal wipEstimatedQty;
    /** 全厂已排产 (盒, 仅未开工计划)。 */
    private BigDecimal scheduledQty;
    /** 全厂合计可用; 单位不一致时 null。 */
    private BigDecimal totalAvailableQty;

    // ---- 缺口 ----

    /** max(该仓需求 - 全厂合计可用, 0); 单位不一致时 null。 */
    private BigDecimal shortfallQty;
    /** SATISFIED | SHORTFALL | UNIT_INCONSISTENT */
    private String status;

    private boolean wipIsEstimated;
    private String conversionWarning;
}

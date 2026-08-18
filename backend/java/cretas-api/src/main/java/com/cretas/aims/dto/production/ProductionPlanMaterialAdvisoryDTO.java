package com.cretas.aims.dto.production;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Production plan material advisory")
public class ProductionPlanMaterialAdvisoryDTO {

    private String planId;
    private String planNumber;
    private boolean hasWarning;
    private String message;
    private List<Item> warnings;

    /**
     * 预警类型 —— 三种处境的<b>下一步动作完全不同</b>, 不能共用一句话。
     *
     * <p>🔴 2026-08-18 实测: 原来只有 {@link #FACTORY_SHORTAGE} 一种, 于是
     * 「货在原料仓、生产仓一粒都没有」这种处境**落不进任何一类**, 计划列表报
     * 「暂无缺料预警」, 而逐道录入页四行全是 0。
     */
    public enum Kind {
        /** 全厂就是不够 → 去采购。 */
        FACTORY_SHORTAGE,
        /** 全厂够, 但<b>生产仓</b>不够 → 去领料 / 调拨, 不是采购。 */
        NOT_IN_WORKSHOP,
        /** BOM 单位与库存单位换不了 → 去补包装规格配置。 */
        UNIT_UNCONVERTIBLE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Material shortage advisory item")
    public static class Item {
        private String materialTypeId;
        private String materialName;
        private BigDecimal requiredQuantity;
        /** 全厂可用量 (不限仓库)。 */
        private BigDecimal availableQuantity;
        private BigDecimal shortageQuantity;
        private String unit;
        private String message;
        /** 见 {@link Kind}。老数据/老调用方为 null 时按 FACTORY_SHORTAGE 理解。 */
        private Kind kind;
        /** 生产仓可用量 —— 与 availableQuantity 同口径, 只多一个仓库限定。 */
        private BigDecimal workshopAvailableQuantity;
    }
}

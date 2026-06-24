package com.cretas.aims.dto.bom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * PUT /{recipeId}/seasoning — 全量替换 BOM 调料配方请求.
 *
 * <p>锅序三参数 + 调料明细列表 (注射段+熟制段).
 * 段内 section 只允许 INJECTION|COOKING (Service 层 validate + BusinessException 400).
 *
 * @since 2026-06-24
 */
@Data
public class BomSeasoningSaveRequest {

    /** 熟制每锅基准原料 (kg), 如 160. */
    private BigDecimal cookingPotBaseKg;

    /** 第二锅起比例, 如 0.3333. */
    private BigDecimal subsequentPotRatio;

    /** 注射率, 如 0.20. */
    private BigDecimal injectionRate;

    /** 调料明细列表 (全量替换). null/empty → 清空所有调料. */
    @Valid
    private List<SeasoningItemDTO> seasoningItems = new ArrayList<>();

    /**
     * 单条调料 DTO (注射段/熟制段原料行).
     */
    @Data
    public static class SeasoningItemDTO {

        /** INJECTION | COOKING. */
        @NotBlank(message = "section 不可为空")
        private String section;

        /** 段内排序序号 (从 0 起). */
        private Integer seq;

        /** 调料名称. */
        @NotBlank(message = "name 不可为空")
        private String name;

        /** 每 kg 原料用量 (g), 不可为 null. */
        @NotNull(message = "dosagePerKgG 不可为空")
        private BigDecimal dosagePerKgG;

        /** 价格来源 1 (可选). */
        private BigDecimal priceSource1;

        /** 价格来源 2 (可选). */
        private BigDecimal priceSource2;

        /**
         * 是否计入调料成本; 默认 true.
         * 老汤/高汤 = false (熟制不计入成本).
         */
        private Boolean countInSeasoning;

        /** 备注 (可选). */
        private String remark;
    }
}

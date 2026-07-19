package com.cretas.aims.dto.bom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    /** 调料明细列表 (全量替换). null/empty → 清空所有调料. */
    @Valid
    private List<SeasoningItemDTO> seasoningItems = new ArrayList<>();

    /** 每道注射工序的绝对注射量（全量替换）。 */
    @Valid
    private List<ProcessInjectionConfigDTO> injectionConfigs = new ArrayList<>();

    /**
     * 单条调料 DTO (注射段/熟制段原料行).
     */
    @Data
    public static class SeasoningItemDTO {

        /** INJECTION | COOKING. */
        @NotBlank(message = "section 不可为空")
        private String section;

        /** 调料配方按工序 (2026-07-13): 该明细归属工序 (work_processes.id)。per-工序 UI 下必填; 兼容期可空。 */
        private String workProcessId;

        /** 关联原辅料档案 ID；名称与价格由后端以物料档案为准。 */
        private String materialTypeId;

        /** 段内排序序号 (从 0 起). */
        private Integer seq;

        /** 调料名称. */
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

        /** 熟制绑定自己的续锅比例；null 表示整段只按总投入应用一次。 */
        @DecimalMin(value = "0", message = "subsequentPotRatio 不能小于 0")
        @DecimalMax(value = "1", message = "subsequentPotRatio 不能大于 1")
        private BigDecimal subsequentPotRatio;
    }
}

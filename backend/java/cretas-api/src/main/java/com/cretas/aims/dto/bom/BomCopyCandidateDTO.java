package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 同源且共享工序的成品 BOM 可复制规则。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomCopyCandidateDTO {

    private String sourceProductTypeId;
    private String sourceProductName;
    private String sourceRecipeId;
    private String sourceRecipeCode;
    private Integer sourceRecipeVersion;
    private String rawRootMaterialTypeId;
    @Builder.Default
    private List<SharedProcessDTO> sharedProcesses = new ArrayList<>();
    @Builder.Default
    private List<BomItemRuleDTO> bomItems = new ArrayList<>();
    @Builder.Default
    private List<SeasoningRuleDTO> seasoningItems = new ArrayList<>();
    @Builder.Default
    private List<ProcessSeasoningRuleDTO> processSeasoningParams = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SharedProcessDTO {
        private String workProcessId;
        private String processName;
        private Integer targetOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BomItemRuleDTO {
        private Long id;
        private String materialTypeId;
        private String materialName;
        private BigDecimal standardQuantity;
        private String unit;
        private String materialCategory;
        private Integer sortOrder;
        private Boolean optional;
        private String substituteGroup;
        private String remark;
        private Boolean perPortion;
        private String semiFinishedRefCode;
        private String subProductTypeId;
        private String primaryCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeasoningRuleDTO {
        private Long id;
        private String materialTypeId;
        private String name;
        private String section;
        private BigDecimal dosagePerKgG;
        private Integer seq;
        private String workProcessId;
        private String workProcessName;
        private Boolean countInSeasoning;
        private String remark;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessSeasoningRuleDTO {
        private Long id;
        private String workProcessId;
        private String workProcessName;
        private BigDecimal subsequentPotRatio;
        private BigDecimal injectionAmountKg;
        private String notes;
    }
}

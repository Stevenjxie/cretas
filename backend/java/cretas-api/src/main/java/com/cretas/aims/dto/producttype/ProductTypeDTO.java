package com.cretas.aims.dto.producttype;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品类型数据传输对象
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-01-09
 * @updated 2025-12-30 Phase 5: Added SKU configuration fields
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductTypeDTO {
    private String id;
    private String factoryId;
    private String code;
    @NotBlank(message = "产品名称不能为空")
    private String name;
    private String category;
    @NotBlank(message = "单位不能为空")
    private String unit;
    private BigDecimal unitPrice;
    private Integer productionTimeMinutes;
    private Integer shelfLifeDays;
    private String packageSpec;

    @Schema(description = "产品大类: FINISHED_PRODUCT, RAW_MATERIAL, PACKAGING, SEASONING, CUSTOMER_MATERIAL")
    private String productCategory;

    @Schema(description = "规格（如 310g*42袋/箱）")
    private String specification;

    @Schema(description = "关联客户名称")
    private String relatedCustomer;

    @Schema(description = "产品图片URL")
    private String imageUrl;

    @Schema(description = "温区: 常温, 冷藏, 冷冻")
    private String temperatureZone;

    @Schema(description = "P0-2 标准克重: 1 份/盒 = X 克 (报工末道折算/成品入库换算用; null=无标准)")
    private BigDecimal gramsPerUnit;

    @Schema(description = "T133 半成品→成品出成率 (0~1 乘数; null=备货看板按 1.0 即偏乐观; 如 0.55 表示 55% 出成率)")
    private BigDecimal wipToFgYield;

    private Boolean isActive;
    private String notes;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ==================== Sprint 2 S2-1: Form Template Association ====================

    /**
     * Associated form template ID for this product type
     * Allows different product types to have specialized input forms
     */
    @Schema(description = "关联的表单模板ID")
    private String formTemplateId;

    /**
     * Form template name (for display purposes)
     */
    @Schema(description = "表单模板名称")
    private String formTemplateName;

    // ==================== Sprint 2 S2-5: SOP Configuration Association ====================

    /**
     * Default SOP configuration ID for this product type
     * Links to SopConfig entity for standardized operating procedures
     */
    @Schema(description = "默认SOP配置ID")
    private String defaultSopConfigId;

    /**
     * SOP configuration name (for display purposes)
     */
    @Schema(description = "SOP配置名称")
    private String defaultSopConfigName;

    // ==================== Phase 5: SKU Configuration Fields ====================

    /**
     * Standard work hours to produce one unit
     */
    @Schema(description = "Standard work hours per unit")
    private BigDecimal workHours;

    /**
     * Processing steps with stage type, order, and skill requirements
     */
    @Schema(description = "Processing steps configuration")
    private List<ProcessingStepDTO> processingSteps;

    /**
     * Skill requirements for production
     */
    @Schema(description = "Skill requirements")
    private SkillRequirementDTO skillRequirements;

    /**
     * Required equipment IDs
     */
    @Schema(description = "Required equipment IDs")
    private List<String> equipmentIds;

    /**
     * Associated quality check item IDs
     */
    @Schema(description = "Quality check item IDs")
    private List<String> qualityCheckIds;

    /**
     * Production complexity score (1-5)
     */
    @Schema(description = "Complexity score 1-5 for LinUCB")
    private Integer complexityScore;

    // ==================== End Phase 5 Fields ====================

    // ==================== Custom Form Schema Configuration ====================

    /**
     * Custom schema overrides for various entity types associated with this product type.
     * JSON format allows defining specialized input forms for MATERIAL_BATCH, QUALITY_CHECK, etc.
     *
     * Example:
     * {
     *   "MATERIAL_BATCH": { "additionalFields": [...], "requiredFields": [...] },
     *   "QUALITY_CHECK": { "checkItems": [...] }
     * }
     */
    @Schema(description = "Custom schema overrides for different entity types (JSON)")
    private String customSchemaOverrides;

    // ==================== End Custom Form Schema Configuration ====================

    // 关联信息
    private String factoryName;
    private String createdByName;

    // 统计信息
    private Integer totalProductionPlans;
    private Integer activePlans;
    private BigDecimal totalProducedQuantity;

    // ==================== 前端字段别名 ====================

    /**
     * productCode 别名（兼容前端）
     * 前端使用 productCode，后端使用 code
     */
    @JsonProperty("productCode")
    @Schema(description = "产品编码(前端别名)")
    public String getProductCode() {
        return code;
    }

    // ==================== Inner DTOs ====================

    /**
     * Processing step configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingStepDTO {
        /**
         * Processing stage type (e.g., SLICING, PACKAGING)
         */
        @Schema(description = "Processing stage type from ProcessingStageType enum")
        private String stageType;

        /**
         * Order index (1-based)
         */
        @Schema(description = "Step order, 1-based")
        private Integer orderIndex;

        /**
         * Required skill level (1-5)
         */
        @Schema(description = "Required skill level 1-5")
        private Integer requiredSkillLevel;

        /**
         * Estimated time in minutes
         */
        @Schema(description = "Estimated time in minutes")
        private Integer estimatedMinutes;

        /**
         * Optional notes for this step
         */
        @Schema(description = "Optional notes")
        private String notes;
    }

    /**
     * Skill requirements configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillRequirementDTO {
        /**
         * Minimum skill level required (1-5)
         */
        @Schema(description = "Minimum skill level 1-5")
        private Integer minLevel;

        /**
         * Preferred skill level (1-5)
         */
        @Schema(description = "Preferred skill level 1-5")
        private Integer preferredLevel;

        /**
         * Special skills required (e.g., knife_handling, quality_inspection)
         */
        @Schema(description = "List of special skill tags")
        private List<String> specialSkills;
    }
}

package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomItemSubstitute;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Read model for a structured BOM substitute relation. */
@Data
@Builder
public class BomItemSubstituteDTO {
    private String id;
    private String factoryId;
    private String recipeId;
    private BomItemSubstitute.ParentKind parentKind;
    private Long parentRecipeItemId;
    private Long parentSeasoningItemId;
    private String parentMaterialTypeId;
    private String parentMaterialName;
    private String materialCategory;
    private String workProcessId;
    private String workflowProcessNodeId;
    private String packagingSpecId;
    private String packagingRole;
    private String substituteMaterialTypeId;
    private String substituteMaterialCode;
    private String substituteMaterialName;
    private String parentUnit;
    private String substituteUnit;
    private BigDecimal conversionFactor;
    private boolean conversionExplicit;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

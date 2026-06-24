package com.cretas.aims.dto.bom;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * U3 迁移报告: product_recipes → BOM (bom_seasoning_items + 锅序列).
 *
 * <p>BOM 统管配方+锅序合并 (2026-06-24). 支持 dryRun (只读对比, 不写) + 实际迁移.
 * 幂等: 已迁移 SKU (BOM 已有调料) 跳过; 无 BOM 的 SKU 不自动建, 记 SKIPPED_NO_BOM 警告.
 */
@Data
@Builder
public class BomRecipeMigrationReport {

    /** ACTION 取值. */
    public static final String MIGRATED = "MIGRATED";
    public static final String SKIPPED_ALREADY = "SKIPPED_ALREADY_MIGRATED";
    public static final String SKIPPED_NO_BOM = "SKIPPED_NO_BOM";

    private boolean dryRun;
    private String factoryId;
    private int skusProcessed;
    private int migrated;
    private int skippedAlready;
    private int skippedNoBom;

    @Builder.Default
    private List<SkuResult> results = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Data
    @Builder
    public static class SkuResult {
        private String productTypeId;
        private String productName;
        private String productRecipeId;
        /** null = 该 SKU 无现有 BOM (SKIPPED_NO_BOM). */
        private String bomRecipeId;
        private String action;
        private int seasoningItemCount;
        private BigDecimal cookingPotBaseKg;
        private BigDecimal subsequentPotRatio;
        private BigDecimal injectionRate;
    }
}

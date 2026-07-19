package com.cretas.aims.dto.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /{recipeId}/seasoning — BOM 调料配方响应.
 *
 * <p>锅序三参数 + 段明细列表 + BOM 基础信息供前端标题展示.
 *
 * @since 2026-06-24
 */
@Data
public class BomSeasoningResponse {

    private String bomRecipeId;
    private String productTypeId;
    private String productName;

    /** BOM 当前状态 (前端据此控制编辑可见性: DRAFT 才可改). */
    private BomRecipe.Status status;

    /** 调料明细, 按 seq ASC. (每条含 workProcessId, 前端按工序分组) */
    private List<BomSeasoningItem> seasoningItems;

    /** 每道注射工序的绝对注射量；熟制锅序只在 seasoningItems 绑定上表达。 */
    private List<ProcessInjectionConfigDTO> injectionConfigs;
}

package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.bom.BomSeasoningSaveRequest;
import com.cretas.aims.dto.bom.BomFamilyOutputCostingResponse;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.UpdateBomFamilyOutputCostingRequest;
import com.cretas.aims.dto.bom.UpdateBomRecipeRequest;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * BomRecipeService (Track D1 / M-BOM-1).
 *
 * <p>Encapsulates BOM 配方主子表的业务操作: CRUD + 状态机 (DRAFT→ACTIVE→ARCHIVED)
 * + 克隆 + 成本计算.
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
public interface BomRecipeService {

    /** Reuse the SKU draft, clone current ACTIVE to a draft, or create an empty v1 draft. */
    BomRecipe ensureDraft(String factoryId, String productTypeId);

    /** 创建草稿 (status=DRAFT). 自动生成 recipeCode (BOM-YYYYMMDD-NNN). */
    BomRecipe createRecipe(String factoryId, CreateBomRecipeRequest request);

    /** 更新草稿. 仅 DRAFT 状态可改, 其他状态抛 IllegalStateException. */
    BomRecipe updateRecipe(String factoryId, String recipeId, UpdateBomRecipeRequest request);

    /** 激活草稿: DRAFT → ACTIVE, 同产品其他 is_current=TRUE 的版本设为 FALSE. */
    BomRecipe activateRecipe(String factoryId, String recipeId, Long operatorId);

    /** 克隆为新版本草稿: source ACTIVE/ARCHIVED → 新 DRAFT recipe, version+1. */
    BomRecipe cloneRecipe(String factoryId, String recipeId);

    /** Explicitly migrate a complete draft family to the current compatible Workflow DRAFT. */
    BomRecipe upgradeWorkflowRevision(String factoryId, String recipeId);

    /** Create/reuse, migrate, and activate the BOM family for the exact Workflow revision. */
    BomRecipe synchronizeActiveBomToWorkflowRevision(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowRevision targetRevision,
            Long operatorId);

    /** Read the business-facing output allocation and by-product valuation for a BOM Family. */
    BomFamilyOutputCostingResponse getFamilyOutputCosting(String factoryId, String recipeId);

    /** Update by-product NRV on a complete DRAFT family; Workflow-owned roles/ratios remain immutable. */
    BomFamilyOutputCostingResponse updateFamilyOutputCosting(
            String factoryId,
            String recipeId,
            UpdateBomFamilyOutputCostingRequest request);

    /** 归档: DRAFT/ACTIVE → ARCHIVED, is_current=FALSE. */
    BomRecipe archiveRecipe(String factoryId, String recipeId);

    /** 软删除 (仅 DRAFT). 通过 BaseEntity.softDelete() 设置 deletedAt. */
    void deleteRecipe(String factoryId, String recipeId);

    /** 取详情 (含 items). */
    BomRecipe getRecipe(String factoryId, String recipeId);

    /** 取产品当前生效 BOM (status=ACTIVE + is_current=TRUE). */
    Optional<BomRecipe> getCurrentRecipe(String factoryId, String productTypeId);

    /** 取产品所有版本 (含历史). */
    List<BomRecipe> getRecipeVersions(String factoryId, String productTypeId);

    /** 分页查询 (可按 status 过滤). */
    Page<BomRecipe> listRecipes(String factoryId, BomRecipe.Status status, Pageable pageable);

    /** 重算成本 (运行时, 返回更新后的 recipe, 写回主表 total_*_cost 字段). */
    BomRecipe calculateCost(String factoryId, String recipeId);

    /** 添加配方项. */
    BomRecipeItem addItem(String factoryId, String recipeId,
                          CreateBomRecipeRequest.BomRecipeItemDTO dto);

    /** 更新配方项. */
    BomRecipeItem updateItem(String factoryId, Long itemId,
                             CreateBomRecipeRequest.BomRecipeItemDTO dto);

    /** 删除配方项 (软删). */
    void deleteItem(String factoryId, Long itemId);

    // ========== U5: 调料配方 CRUD (BOM 统管配方+锅序, 2026-06-24) ==========

    /**
     * 取 BOM 调料配方 (锅序参数 + 段明细).
     *
     * @throws com.cretas.aims.exception.EntityNotFoundException BOM 不存在
     * @throws IllegalArgumentException                          BOM 不属于该工厂 (跨租户防护)
     */
    BomSeasoningResponse getSeasoning(String factoryId, String recipeId);

    /**
     * 按产品取当前 BOM 的调料配方; 无当前 BOM 时返回 {@link Optional#empty()}.
     * Controller 将 empty → 404 "产品未建 BOM 配方".
     */
    Optional<BomSeasoningResponse> getSeasoningByProduct(String factoryId, String productTypeId);

    /**
     * 全量替换 BOM 调料配方 (仅 DRAFT).
     *
     * <p>锅序三参数写进 bom_recipes; 调料明细软删旧行 + 插入新行.
     * section 必须为 INJECTION|COOKING, 违反 → {@link com.cretas.aims.exception.BusinessException} 400.
     *
     * @throws IllegalStateException BOM 非 DRAFT 状态
     */
    BomSeasoningResponse saveSeasoning(String factoryId, String recipeId, BomSeasoningSaveRequest req);
}

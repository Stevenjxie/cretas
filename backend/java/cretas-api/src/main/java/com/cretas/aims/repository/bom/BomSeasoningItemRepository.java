package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * BOM 调料明细 repo. BOM 统管配方+锅序 (2026-06-24).
 *
 * <p>{@code @Where(deleted_at IS NULL)} 在实体上 → 所有查询自动过滤软删除.
 */
@Repository
public interface BomSeasoningItemRepository extends JpaRepository<BomSeasoningItem, Long> {

    /** 取某 BOM 配方的全部调料明细, 按 seq 升序。 */
    List<BomSeasoningItem> findByRecipeIdOrderBySeqAsc(String recipeId);

    /** 取某 BOM 配方在指定工序下的调料明细, 按 seq 升序 (调料配方按工序, 2026-07-13). */
    List<BomSeasoningItem> findByRecipeIdAndWorkProcessIdOrderBySeqAsc(String recipeId, String workProcessId);

    Optional<BomSeasoningItem> findByIdAndRecipeId(Long id, String recipeId);

    Optional<BomSeasoningItem> findByRecipeIdAndWorkProcessIdAndMaterialTypeId(
            String recipeId, String workProcessId, String materialTypeId);

    List<BomSeasoningItem> findByRecipeIdAndWorkflowProcessNodeIdOrderBySeqAsc(
            String recipeId, String workflowProcessNodeId);

    Optional<BomSeasoningItem> findByRecipeIdAndWorkflowProcessNodeIdAndMaterialTypeId(
            String recipeId, String workflowProcessNodeId, String materialTypeId);

    /** 整 SKU 路径只取未绑定工序的明细，按工序明细由工序路径独占核算。 */
    List<BomSeasoningItem> findByRecipeIdAndWorkProcessIdIsNullOrderBySeqAsc(String recipeId);

    /** 孤儿守卫 (2026-07-13): 是否有任何调料明细挂在该工序上 (删工序前检查)。 */
    boolean existsByWorkProcessId(String workProcessId);

}

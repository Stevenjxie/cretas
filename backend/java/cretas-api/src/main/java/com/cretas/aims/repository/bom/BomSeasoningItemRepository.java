package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * BOM 调料明细 repo. BOM 统管配方+锅序 (2026-06-24).
 *
 * <p>{@code @Where(deleted_at IS NULL)} 在实体上 → 所有查询自动过滤软删除.
 */
@Repository
public interface BomSeasoningItemRepository extends JpaRepository<BomSeasoningItem, Long> {

    /** 取某 BOM 配方的全部调料明细, 按 seq 升序 (镜像 ingredientRepo.findByRecipeIdOrderBySeqAsc). */
    List<BomSeasoningItem> findByRecipeIdOrderBySeqAsc(String recipeId);

    /** 取某 BOM 配方在指定工序下的调料明细, 按 seq 升序 (调料配方按工序, 2026-07-13). */
    List<BomSeasoningItem> findByRecipeIdAndWorkProcessIdOrderBySeqAsc(String recipeId, String workProcessId);

    /**
     * 调料配方按工序 (2026-07-13): 整-SKU 回退路径只取"未迁移"(work_process_id 为 NULL) 的明细,
     * 已按工序迁移的明细归 per-工序 路径独占核算 → 避免部分迁移期 double-count (audit Finding 2)。
     */
    List<BomSeasoningItem> findByRecipeIdAndWorkProcessIdIsNullOrderBySeqAsc(String recipeId);

    /** 孤儿守卫 (2026-07-13): 是否有任何调料明细挂在该工序上 (删工序前检查)。 */
    boolean existsByWorkProcessId(String workProcessId);

    /** 幂等迁移用: 判断该 BOM 是否已有调料 (已迁移则跳过). */
    boolean existsByRecipeId(String recipeId);

    /**
     * 幂等迁移用 — 含软删除行计数. 原生查询绕过 {@code @Where(deleted_at IS NULL)},
     * 实现 "曾经迁移过即跳过" 语义: 防止 saveSeasoning 清空(软删)后再次运行迁移
     * 把 product_recipes 原始数据重新灌回, 覆盖用户编辑 (audit Issue 3).
     */
    @Query(value = "SELECT COUNT(*) FROM bom_seasoning_items WHERE recipe_id = :recipeId", nativeQuery = true)
    long countByRecipeIdIncludingDeleted(@Param("recipeId") String recipeId);
}

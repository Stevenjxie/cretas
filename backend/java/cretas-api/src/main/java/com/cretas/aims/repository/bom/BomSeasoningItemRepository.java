package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomSeasoningItem;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** 幂等迁移用: 判断该 BOM 是否已有调料 (已迁移则跳过). */
    boolean existsByRecipeId(String recipeId);
}

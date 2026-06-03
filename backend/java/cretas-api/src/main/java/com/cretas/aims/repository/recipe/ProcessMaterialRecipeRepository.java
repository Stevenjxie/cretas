package com.cretas.aims.repository.recipe;

import com.cretas.aims.entity.recipe.ProcessMaterialRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工序材料配方 Repository.
 *
 * <p>主要查询: 按 factory_id + work_process_id 找 active 配方 (供成本计算)。
 */
@Repository
public interface ProcessMaterialRecipeRepository extends JpaRepository<ProcessMaterialRecipe, Long> {

    /**
     * 找某工厂某道工序的所有 active 配方.
     *
     * <p>通常结果列表长度 ≤ 2 (SEASONING + PACKAGING 各一条); 多条时取第一条 (最新按 id desc 排序)。
     * deleted_at IS NULL 由 {@code @Where(clause = "deleted_at IS NULL")} 自动过滤。
     */
    @Query("SELECT r FROM ProcessMaterialRecipe r " +
           "WHERE r.factoryId = :factoryId AND r.workProcessId = :workProcessId " +
           "AND r.isActive = true " +
           "ORDER BY r.id DESC")
    List<ProcessMaterialRecipe> findActiveByFactoryIdAndWorkProcessId(
            @Param("factoryId") String factoryId,
            @Param("workProcessId") String workProcessId);
}

package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomProcessSeasoning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * per-(recipe × 工序) 调料参数 repo.
 *
 * <p>{@code @Where(deleted_at IS NULL)} 在实体上 → 所有查询自动过滤软删除;
 * 仍显式加 {@code AndDeletedAtIsNull} 后缀以对齐 bom 包内其它 config repo 惯例
 * (如 {@code ProductCostVarianceConfigRepository})。
 */
@Repository
public interface BomProcessSeasoningRepository extends JpaRepository<BomProcessSeasoning, Long> {

    /** 取某 BOM 配方下全部工序级调料参数. */
    List<BomProcessSeasoning> findByRecipeIdAndDeletedAtIsNull(String recipeId);

    /** 取某 BOM 配方在指定工序上的调料参数 (recipe_id + work_process_id 唯一). */
    Optional<BomProcessSeasoning> findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(
            String recipeId, String workProcessId);
}

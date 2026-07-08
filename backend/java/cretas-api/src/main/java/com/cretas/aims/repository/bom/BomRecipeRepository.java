package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipe.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * BomRecipe Repository (Track D1 / M-BOM-1).
 *
 * <p>注意 PostgreSQL parameter-side IS NULL 用 {@code CAST(:param AS string)} hint
 * (per .claude/rules/database-entity-sync.md). 本 repo 暂无 nullable param,
 * 后续若加 filter 需要遵循此规则.
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Repository
public interface BomRecipeRepository extends JpaRepository<BomRecipe, String> {

    /** 取产品的当前生效 BOM (status=ACTIVE + is_current=TRUE). */
    Optional<BomRecipe> findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
            String factoryId, String productTypeId, Status status);

    /** 取产品当前 BOM (is_current=TRUE, 不论 DRAFT/ACTIVE) — picker 软过滤用, 定义即生效无需激活仪式. */
    Optional<BomRecipe> findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
            String factoryId, String productTypeId);

    /** 取产品所有版本 (含 DRAFT/ARCHIVED), 按 version DESC. */
    List<BomRecipe> findByFactoryIdAndProductTypeIdOrderByVersionDesc(
            String factoryId, String productTypeId);

    /** 按状态分页 (工厂级). */
    Page<BomRecipe> findByFactoryIdAndStatus(String factoryId, Status status, Pageable pageable);

    /** 工厂级所有 BOM (含 archived), 默认按 product_type_id 排序. */
    Page<BomRecipe> findByFactoryId(String factoryId, Pageable pageable);

    /** 找同产品其他 ACTIVE 版本 (激活新版本时把他们置为 is_current=false). */
    @Query("SELECT br FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId " +
           "AND br.productTypeId = :productTypeId " +
           "AND br.isCurrent = true " +
           "AND br.id <> :excludeId")
    List<BomRecipe> findCurrentVersionsExcluding(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("excludeId") String excludeId);

    /** 取产品最大 version (clone 时 +1 生成新版本). */
    @Query("SELECT COALESCE(MAX(br.version), 0) FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId AND br.productTypeId = :productTypeId")
    Integer findMaxVersion(@Param("factoryId") String factoryId,
                           @Param("productTypeId") String productTypeId);

    /** 当日 recipeCode 序号 (用于 BOM-YYYYMMDD-NNN 自动生成). */
    @Query("SELECT COUNT(br) FROM BomRecipe br " +
           "WHERE br.factoryId = :factoryId " +
           "AND br.recipeCode LIKE :prefix")
    long countByRecipeCodePrefix(@Param("factoryId") String factoryId,
                                  @Param("prefix") String prefix);

    boolean existsByFactoryIdAndRecipeCode(String factoryId, String recipeCode);
}

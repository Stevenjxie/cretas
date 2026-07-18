package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomRecipe.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * BomRecipeItem Repository (Track D1 / M-BOM-1).
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Repository
public interface BomRecipeItemRepository extends JpaRepository<BomRecipeItem, Long> {

    /** 取配方项列表 (按 sort_order 升序). */
    List<BomRecipeItem> findByRecipeIdOrderBySortOrderAsc(String recipeId);

    /** 三价对比/物料追溯: 找某原料在所有 recipe 中的引用. */
    List<BomRecipeItem> findByFactoryIdAndMaterialTypeId(String factoryId, String materialTypeId);

    /**
     * 生产唯一真值：取产品当前 ACTIVE/current 配方的全部明细。
     * JOIN FETCH 头表，避免调用方为 productTypeId/productName 再走一次旧表或触发懒加载。
     */
    default List<BomRecipeItem> findCurrentByProduct(String factoryId, String productTypeId) {
        return findCurrentByProductAndStatus(factoryId, productTypeId, Status.ACTIVE);
    }

    @Query("SELECT i FROM BomRecipeItem i JOIN FETCH i.recipe r " +
           "WHERE r.factoryId = :factoryId AND r.productTypeId = :productTypeId " +
           "AND r.isCurrent = true AND r.status = :status " +
           "AND i.deletedAt IS NULL ORDER BY i.sortOrder ASC")
    List<BomRecipeItem> findCurrentByProductAndStatus(@Param("factoryId") String factoryId,
                                                      @Param("productTypeId") String productTypeId,
                                                      @Param("status") Status status);

    /** 取工厂所有当前 ACTIVE/current 配方明细。 */
    default List<BomRecipeItem> findAllCurrentByFactory(String factoryId) {
        return findAllCurrentByFactoryAndStatus(factoryId, Status.ACTIVE);
    }

    @Query("SELECT i FROM BomRecipeItem i JOIN FETCH i.recipe r " +
           "WHERE r.factoryId = :factoryId AND r.isCurrent = true " +
           "AND r.status = :status " +
           "AND i.deletedAt IS NULL ORDER BY r.productTypeId ASC, i.sortOrder ASC")
    List<BomRecipeItem> findAllCurrentByFactoryAndStatus(@Param("factoryId") String factoryId,
                                                         @Param("status") Status status);

    /** 当前 ACTIVE/current 配方中引用指定物料的明细。 */
    default List<BomRecipeItem> findCurrentByMaterial(String factoryId, String materialTypeId) {
        return findCurrentByMaterialAndStatus(factoryId, materialTypeId, Status.ACTIVE);
    }

    @Query("SELECT i FROM BomRecipeItem i JOIN FETCH i.recipe r " +
           "WHERE r.factoryId = :factoryId AND r.isCurrent = true " +
           "AND r.status = :status " +
           "AND i.materialTypeId = :materialTypeId AND i.deletedAt IS NULL")
    List<BomRecipeItem> findCurrentByMaterialAndStatus(@Param("factoryId") String factoryId,
                                                       @Param("materialTypeId") String materialTypeId,
                                                       @Param("status") Status status);

    /** 工厂内已有当前生效配方的产品 ID。 */
    default List<String> findDistinctCurrentProductTypeIds(String factoryId) {
        return findDistinctCurrentProductTypeIdsByStatus(factoryId, Status.ACTIVE);
    }

    @Query("SELECT DISTINCT r.productTypeId FROM BomRecipe r " +
           "WHERE r.factoryId = :factoryId AND r.isCurrent = true " +
           "AND r.status = :status " +
           "AND EXISTS (SELECT i.id FROM BomRecipeItem i WHERE i.recipeId = r.id AND i.deletedAt IS NULL)")
    List<String> findDistinctCurrentProductTypeIdsByStatus(@Param("factoryId") String factoryId,
                                                           @Param("status") Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM BomRecipeItem i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<BomRecipeItem> findByIdForUpdate(@Param("id") Long id);

    /** 删除某 recipe 的所有 items (clone 前清空, 或 admin tooling). */
    void deleteByRecipeId(String recipeId);

    /** 计数. */
    long countByRecipeId(String recipeId);
}

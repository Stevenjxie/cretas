package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipeItem;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM BomRecipeItem i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<BomRecipeItem> findByIdForUpdate(@Param("id") Long id);

    /** 删除某 recipe 的所有 items (clone 前清空, 或 admin tooling). */
    void deleteByRecipeId(String recipeId);

    /** 计数. */
    long countByRecipeId(String recipeId);
}

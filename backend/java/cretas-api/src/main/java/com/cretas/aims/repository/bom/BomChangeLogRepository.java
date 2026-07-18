package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * P1-9 BOM 变更痕迹 Repository.
 */
@Repository
public interface BomChangeLogRepository extends JpaRepository<BomChangeLog, String> {

    List<BomChangeLog> findByFactoryIdAndBomRecipeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String bomRecipeId);

    List<BomChangeLog> findByFactoryIdAndBomRecipeItemIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, Long bomRecipeItemId);

    @Query("SELECT COUNT(l) FROM BomChangeLog l WHERE l.factoryId = :factoryId AND l.bomRecipeId = :recipeId AND l.deletedAt IS NULL")
    long countByFactoryIdAndBomRecipeId(@Param("factoryId") String factoryId, @Param("recipeId") String recipeId);
}

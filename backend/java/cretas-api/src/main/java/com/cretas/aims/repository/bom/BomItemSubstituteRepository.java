package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomItemSubstitute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomItemSubstituteRepository extends JpaRepository<BomItemSubstitute, String> {

    List<BomItemSubstitute> findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(
            String factoryId, String recipeId);

    List<BomItemSubstitute> findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
            String factoryId,
            String recipeId,
            BomItemSubstitute.ParentKind parentKind,
            Long parentRecipeItemId);

    List<BomItemSubstitute> findByFactoryIdAndRecipeIdAndParentKindAndParentSeasoningItemIdOrderByCreatedAtAsc(
            String factoryId,
            String recipeId,
            BomItemSubstitute.ParentKind parentKind,
            Long parentSeasoningItemId);
}

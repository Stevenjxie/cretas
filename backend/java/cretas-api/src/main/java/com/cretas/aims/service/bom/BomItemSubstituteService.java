package com.cretas.aims.service.bom;

import com.cretas.aims.dto.bom.BomItemSubstituteDTO;
import com.cretas.aims.dto.bom.BomSubstituteInput;

import java.util.List;
import java.util.Map;

/** Structured BOM substitute relation contract used by BOM write and clone paths. */
public interface BomItemSubstituteService {

    List<BomItemSubstituteDTO> listByRecipe(String factoryId, String recipeId);

    List<BomItemSubstituteDTO> listForRecipeItem(
            String factoryId, String recipeId, Long parentRecipeItemId);

    List<BomItemSubstituteDTO> listForSeasoningItem(
            String factoryId, String recipeId, Long parentSeasoningItemId);

    List<BomItemSubstituteDTO> replaceForRecipeItem(
            String factoryId,
            String recipeId,
            Long parentRecipeItemId,
            List<BomSubstituteInput> substitutes);

    List<BomItemSubstituteDTO> replaceForSeasoningItem(
            String factoryId,
            String recipeId,
            Long parentSeasoningItemId,
            List<BomSubstituteInput> substitutes);

    /**
     * Clone relation snapshots after the caller has cloned both parent row collections.
     * Replaying the same exact mapping is a no-op; a different existing target set is rejected.
     */
    List<BomItemSubstituteDTO> cloneRelations(
            String factoryId,
            String sourceRecipeId,
            String targetRecipeId,
            Map<Long, Long> recipeItemIdMap,
            Map<Long, Long> seasoningItemIdMap);
}

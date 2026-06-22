package com.cretas.aims.repository.recipe;

import com.cretas.aims.entity.recipe.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, String> {

    List<RecipeIngredient> findByRecipeIdOrderBySeqAsc(String recipeId);

    void deleteByRecipeId(String recipeId);
}

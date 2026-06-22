package com.cretas.aims.repository.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRecipeRepository extends JpaRepository<ProductRecipe, String> {

    List<ProductRecipe> findByFactoryId(String factoryId);

    Optional<ProductRecipe> findByFactoryIdAndId(String factoryId, String id);

    /** 同 SKU 的 ACTIVE 配方(唯一性校验). */
    Optional<ProductRecipe> findByFactoryIdAndProductTypeIdAndStatus(
            String factoryId, String productTypeId, String status);
}

package com.cretas.aims.service.recipe;

import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import java.util.List;

public interface ProductRecipeService {
    List<ProductRecipeDTO> list(String factoryId);
    ProductRecipeDTO get(String factoryId, String id);
    ProductRecipeDTO create(String factoryId, SaveRecipeRequest request);
    ProductRecipeDTO update(String factoryId, String id, SaveRecipeRequest request);
    void delete(String factoryId, String id);
}

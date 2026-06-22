package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.service.recipe.ProductRecipeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/product-recipes")
@RequiredArgsConstructor
public class ProductRecipeController {

    private final ProductRecipeService service;

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping
    public ApiResponse<List<ProductRecipeDTO>> list(@PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(service.list(factoryId));
    }

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping("/{id}")
    public ApiResponse<ProductRecipeDTO> get(@PathVariable @NotBlank String factoryId,
                                             @PathVariable @NotBlank String id) {
        return ApiResponse.success(service.get(factoryId, id));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping
    public ApiResponse<ProductRecipeDTO> create(@PathVariable @NotBlank String factoryId,
                                                @Valid @RequestBody SaveRecipeRequest request) {
        return ApiResponse.success("配方创建成功", service.create(factoryId, request));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}")
    public ApiResponse<ProductRecipeDTO> update(@PathVariable @NotBlank String factoryId,
                                                @PathVariable @NotBlank String id,
                                                @Valid @RequestBody SaveRecipeRequest request) {
        return ApiResponse.success("配方更新成功", service.update(factoryId, id, request));
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotBlank String factoryId,
                                    @PathVariable @NotBlank String id) {
        service.delete(factoryId, id);
        return ApiResponse.success("配方已停用", null);
    }
}

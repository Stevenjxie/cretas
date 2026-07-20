package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductConfigurationCompletenessReport;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/product-configuration-readiness")
@Tag(name = "产品配置完整性", description = "Workflow-first BOM/Workflow/生产准入唯一真值")
@RequireModule("production_plan")
@RequiredArgsConstructor
public class ProductConfigurationReadinessController {

    private final ProductConfigurationReadinessService readinessService;

    @GetMapping("/{productTypeId}")
    @Operation(summary = "读取 SKU 的 Workflow/BOM 分阶段完整性与缺失项")
    public ApiResponse<ProductConfigurationCompletenessReport> get(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestParam(required = false) String recipeId) {
        return ApiResponse.success(readinessService.evaluate(factoryId, productTypeId, recipeId));
    }
}

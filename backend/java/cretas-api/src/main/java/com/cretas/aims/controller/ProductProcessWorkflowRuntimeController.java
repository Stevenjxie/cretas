package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production-batches")
@Tag(name = "Production Workflow Runtime")
@RequireModule("production_plan")
@RequiredArgsConstructor
public class ProductProcessWorkflowRuntimeController {

    private final ProductProcessWorkflowRuntimeService runtimeService;

    @GetMapping("/{batchId}/workflow-runtime")
    @Operation(summary = "Read the immutable Workflow runtime for a factory batch")
    public ApiResponse<ProductionWorkflowRuntimeDTO> getRuntime(
            @PathVariable String factoryId,
            @PathVariable Long batchId) {
        return ApiResponse.success(runtimeService.getRuntime(factoryId, batchId));
    }
}

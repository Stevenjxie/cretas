package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionDocumentTraceResponse;
import com.cretas.aims.service.production.ProductionDocumentTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans")
@RequiredArgsConstructor
@Tag(name = "生产单据追踪", description = "按生产计划追踪真实关联业务单据")
public class ProductionDocumentTraceController {

    private final ProductionDocumentTraceService traceService;

    @GetMapping("/{planId}/document-trace")
    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @Operation(summary = "生产计划关联单据追踪")
    public ApiResponse<ProductionDocumentTraceResponse> trace(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId) {
        return ApiResponse.success("查询成功", traceService.trace(factoryId, planId));
    }
}

package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 文员逐道工序录入端点 (SP-B1)。
 * 文员在 PC 端集中录入一条生产链 (多半成品批 + 成品批) 的逐道投入/产出/人工/混锅来源,
 * 后端物化成成本引擎可读的 MaterialConsumption + MaterialBatch 图。Spec §4.
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans/{planId}/process-entry")
@RequiredArgsConstructor
public class ClerkProcessEntryController {

    private final ClerkProcessEntryService service;

    @RequirePermission({"production:read_write"})
    @PostMapping
    public ApiResponse<ProcessChainEntryResult> record(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ProcessChainEntryRequest request) {
        return ApiResponse.success("逐道录入成功", service.recordChain(factoryId, planId, request, userId));
    }
}

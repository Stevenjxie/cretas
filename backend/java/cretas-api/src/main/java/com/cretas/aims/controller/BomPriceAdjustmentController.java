package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.BomPriceAdjustmentApproveRequest;
import com.cretas.aims.dto.bom.BomPriceAdjustmentCheckRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/price-adjustments")
@RequireModule("bom")
@RequiredArgsConstructor
public class BomPriceAdjustmentController {

    private final BomPriceAdjustmentService service;

    @GetMapping
    public ApiResponse<Page<BomPriceAdjustmentProposal>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) BomPriceAdjustmentProposal.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(service.list(factoryId, status, pageable));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/check")
    public ApiResponse<List<BomPriceAdjustmentProposal>> check(
            @PathVariable String factoryId,
            @RequestBody BomPriceAdjustmentCheckRequest request) {
        return ApiResponse.success(service.generateForMaterial(
                factoryId,
                request.getMaterialTypeId(),
                request.getMaterialName(),
                request.getLatestPreTaxPrice(),
                BomPriceAdjustmentProposal.SourceType.MANUAL_CHECK,
                null,
                null));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/{proposalId}/approve")
    public ApiResponse<BomPriceAdjustmentProposal> approve(
            @PathVariable String factoryId,
            @PathVariable Long proposalId,
            @RequestBody BomPriceAdjustmentApproveRequest request) {
        return ApiResponse.success(service.approve(factoryId, proposalId, request.getApproverId(), request.getComment()));
    }
}

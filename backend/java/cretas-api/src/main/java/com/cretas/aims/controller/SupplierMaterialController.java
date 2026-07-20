package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.supplier.*;
import com.cretas.aims.service.supplier.SupplierMaterialPurchaseSpecService;
import com.cretas.aims.service.supplier.SupplierMaterialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}")
@RequiredArgsConstructor
public class SupplierMaterialController {
    private final SupplierMaterialService service;
    private final SupplierMaterialPurchaseSpecService purchaseSpecService;

    @GetMapping("/suppliers/{supplierId}/materials")
    public ApiResponse<List<SupplierMaterialDTO>> listBySupplier(@PathVariable String factoryId, @PathVariable String supplierId) {
        return ApiResponse.success(service.listBySupplier(factoryId, supplierId));
    }
    @GetMapping("/materials/{materialTypeId}/suppliers")
    public ApiResponse<List<SupplierMaterialDTO>> listByMaterial(@PathVariable String factoryId, @PathVariable String materialTypeId) {
        return ApiResponse.success(service.listByMaterial(factoryId, materialTypeId));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/suppliers/{supplierId}/materials")
    public ApiResponse<SupplierMaterialDTO> create(@PathVariable String factoryId, @PathVariable String supplierId,
                                                   @Valid @RequestBody SupplierMaterialRequest request) {
        return ApiResponse.success("供应关系已创建", service.create(factoryId, supplierId, request));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/suppliers/{supplierId}/materials/{relationId}")
    public ApiResponse<SupplierMaterialDTO> update(@PathVariable String factoryId, @PathVariable String supplierId,
                                                   @PathVariable String relationId,
                                                   @Valid @RequestBody SupplierMaterialRequest request) {
        return ApiResponse.success("供应关系已更新", service.update(factoryId, supplierId, relationId, request));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/suppliers/{supplierId}/materials/{relationId}")
    public ApiResponse<SupplierMaterialDTO> deactivate(@PathVariable String factoryId, @PathVariable String supplierId,
                                                       @PathVariable String relationId,
                                                       @RequestParam(required = false) Long version) {
        return ApiResponse.success("供应关系已停用", service.deactivate(factoryId, supplierId, relationId, version));
    }

    @GetMapping("/suppliers/{supplierId}/materials/{relationId}/purchase-specs")
    public ApiResponse<List<SupplierMaterialPurchaseSpecDTO>> listPurchaseSpecs(
            @PathVariable String factoryId, @PathVariable String supplierId, @PathVariable String relationId) {
        return ApiResponse.success(purchaseSpecService.list(factoryId, supplierId, relationId));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PostMapping("/suppliers/{supplierId}/materials/{relationId}/purchase-specs")
    public ApiResponse<SupplierMaterialPurchaseSpecDTO> createPurchaseSpec(
            @PathVariable String factoryId, @PathVariable String supplierId, @PathVariable String relationId,
            @Valid @RequestBody SupplierMaterialPurchaseSpecRequest request) {
        return ApiResponse.success("采购包装规格已创建", purchaseSpecService.create(factoryId, supplierId, relationId, request));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @PutMapping("/suppliers/{supplierId}/materials/{relationId}/purchase-specs/{specId}")
    public ApiResponse<SupplierMaterialPurchaseSpecDTO> updatePurchaseSpec(
            @PathVariable String factoryId, @PathVariable String supplierId, @PathVariable String relationId,
            @PathVariable String specId, @Valid @RequestBody SupplierMaterialPurchaseSpecRequest request) {
        return ApiResponse.success("采购包装规格已更新", purchaseSpecService.update(factoryId, supplierId, relationId, specId, request));
    }
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    @DeleteMapping("/suppliers/{supplierId}/materials/{relationId}/purchase-specs/{specId}")
    public ApiResponse<SupplierMaterialPurchaseSpecDTO> deactivatePurchaseSpec(
            @PathVariable String factoryId, @PathVariable String supplierId, @PathVariable String relationId,
            @PathVariable String specId, @RequestParam(required = false) Long version) {
        return ApiResponse.success("采购包装规格已停用", purchaseSpecService.deactivate(factoryId, supplierId, relationId, specId, version));
    }
}

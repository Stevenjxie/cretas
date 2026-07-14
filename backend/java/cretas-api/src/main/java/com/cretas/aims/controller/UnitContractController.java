package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.unit.ProductUnitConversionDTO;
import com.cretas.aims.dto.unit.UnitCatalogItemDTO;
import com.cretas.aims.dto.unit.UnitConversionRequest;
import com.cretas.aims.service.unit.ProductUnitConversionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}")
@RequiredArgsConstructor
public class UnitContractController {

    private final UnitContractService unitContractService;
    private final ProductUnitConversionService productUnitConversionService;

    @GetMapping("/units/catalog")
    public ResponseEntity<ApiResponse<List<UnitCatalogItemDTO>>> catalog(@PathVariable String factoryId) {
        List<UnitCatalogItemDTO> result = unitContractService.catalog(factoryId).stream()
                .map(UnitCatalogItemDTO::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/units/convert")
    public ResponseEntity<ApiResponse<UnitConversionResult>> convert(
            @PathVariable String factoryId,
            @Valid @RequestBody UnitConversionRequest request) {
        UnitConversionContext context = new UnitConversionContext(
                factoryId,
                request.productTypeId(),
                request.fromUnit(),
                request.toUnit(),
                request.at() == null ? LocalDateTime.now() : request.at(),
                request.scene(),
                request.scale(),
                request.roundingMode() == null ? RoundingMode.HALF_UP : request.roundingMode());
        return ResponseEntity.ok(ApiResponse.success(unitContractService.convert(request.quantity(), context)));
    }

    @GetMapping("/product-types/{productTypeId}/unit-conversions")
    public ResponseEntity<ApiResponse<List<ProductUnitConversionDTO>>> list(
            @PathVariable String factoryId,
            @PathVariable String productTypeId) {
        return ResponseEntity.ok(ApiResponse.success(
                productUnitConversionService.list(factoryId, productTypeId)));
    }

    @PostMapping("/product-types/{productTypeId}/unit-conversions")
    @RequirePermission("system:read_write")
    public ResponseEntity<ApiResponse<ProductUnitConversionDTO>> create(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @Valid @RequestBody ProductUnitConversionDTO request) {
        return ResponseEntity.ok(ApiResponse.success(
                productUnitConversionService.create(factoryId, productTypeId, request)));
    }

    @PutMapping("/product-types/{productTypeId}/unit-conversions/{id}")
    @RequirePermission("system:read_write")
    public ResponseEntity<ApiResponse<ProductUnitConversionDTO>> update(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @PathVariable String id,
            @Valid @RequestBody ProductUnitConversionDTO request) {
        return ResponseEntity.ok(ApiResponse.success(
                productUnitConversionService.update(factoryId, productTypeId, id, request)));
    }

    @DeleteMapping("/product-types/{productTypeId}/unit-conversions/{id}")
    @RequirePermission("system:read_write")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @PathVariable String id,
            @RequestParam Long version) {
        productUnitConversionService.delete(factoryId, productTypeId, id, version);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

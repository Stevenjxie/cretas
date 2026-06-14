package com.cretas.aims.controller.factory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.production.ProductionMaterialReturn;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production-material-returns")
@RequiredArgsConstructor
@RequireModule("production_plan")
public class ProductionMaterialReturnController {

    private final ProductionMaterialReturnRepository repository;

    @GetMapping
    @RequirePermission({"warehouse:read_write", "production:read_write"})
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String requisitionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ProductionMaterialReturn> result = requisitionId == null || requisitionId.isBlank()
                ? repository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable)
                : repository.findByFactoryIdAndRequisitionIdAndDeletedAtIsNull(factoryId, requisitionId, pageable);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }
}

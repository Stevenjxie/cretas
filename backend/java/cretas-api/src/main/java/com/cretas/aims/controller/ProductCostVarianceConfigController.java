package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.CreateProductCostVarianceConfigRequest;
import com.cretas.aims.dto.bom.UpdateProductCostVarianceConfigRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.ProductCostVarianceConfig;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.bom.ProductCostVarianceConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SP3: 产品成本超支阈值配置 CRUD.
 *
 * <p>Base path: {@code /api/mobile/{factoryId}/bom/variance-configs}
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/bom/variance-configs")
@Tag(name = "SP3-成本超支配置", description = "产品成本超支预警阈值管理")
public class ProductCostVarianceConfigController {

    private final ProductCostVarianceConfigRepository configRepo;

    @GetMapping
    @Operation(summary = "列出工厂全部超支阈值配置")
    public ApiResponse<List<ProductCostVarianceConfig>> listConfigs(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        return ApiResponse.success(configRepo.findByFactoryIdAndDeletedAtIsNullOrderByProductTypeIdAsc(factoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查单条超支阈值配置")
    public ApiResponse<ProductCostVarianceConfig> getConfig(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        var config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("超支配置不存在: " + id));
        return ApiResponse.success(config);
    }

    @PostMapping
    @Transactional
    @Operation(summary = "创建超支阈值配置")
    public ApiResponse<ProductCostVarianceConfig> createConfig(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateProductCostVarianceConfigRequest req) {
        var config = ProductCostVarianceConfig.builder()
                .factoryId(factoryId)
                .productTypeId(req.getProductTypeId())
                .varianceThreshold(req.getVarianceThreshold())
                .isActive(req.getIsActive() != null ? req.getIsActive() : Boolean.TRUE)
                .remark(req.getRemark())
                .build();
        var saved = configRepo.save(config);
        log.info("[SP3] 创建超支配置: factoryId={}, productTypeId={}, threshold={}",
                factoryId, req.getProductTypeId(), req.getVarianceThreshold());
        return ApiResponse.success(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    @Operation(summary = "更新超支阈值配置")
    public ApiResponse<ProductCostVarianceConfig> updateConfig(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductCostVarianceConfigRequest req) {
        var config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("超支配置不存在: " + id));
        if (req.getVarianceThreshold() != null) {
            config.setVarianceThreshold(req.getVarianceThreshold());
        }
        if (req.getIsActive() != null) {
            config.setIsActive(req.getIsActive());
        }
        if (req.getRemark() != null) {
            config.setRemark(req.getRemark());
        }
        var saved = configRepo.save(config);
        log.info("[SP3] 更新超支配置: id={}, factoryId={}", id, factoryId);
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    @Operation(summary = "软删除超支阈值配置")
    public ApiResponse<Void> deleteConfig(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        var config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("超支配置不存在: " + id));
        configRepo.delete(config); // BaseEntity @SQLDelete → sets deleted_at
        log.info("[SP3] 软删除超支配置: id={}, factoryId={}", id, factoryId);
        return ApiResponse.success(null);
    }
}

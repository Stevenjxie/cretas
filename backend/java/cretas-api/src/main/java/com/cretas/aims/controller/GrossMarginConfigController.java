package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.pricing.CreateGrossMarginConfigRequest;
import com.cretas.aims.dto.pricing.UpdateGrossMarginConfigRequest;
import com.cretas.aims.entity.pricing.FactoryGrossMarginConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.pricing.FactoryGrossMarginConfigRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * SP5 W6: 工厂毛利红线配置 CRUD.
 *
 * <p>Base path: {@code /api/mobile/{factoryId}/pricing/gross-margin-configs}
 *
 * <p>毛利红线 (target gross margin) 由 {@code GrossMarginRedlineService} 读取，用于
 * 报价/下单前判断报价是否低于最低可接受价 (#693)。本控制器提供管理界面所需的 CRUD —
 * 此前红线只能 SQL 直写，客户/财务无法配置。
 *
 * <p><strong>权限 (🔒 价格敏感)</strong>: 读写均要求 {@code finance:read_write}
 * (财务经理 / 工厂超管)。{@code targetGrossMargin} 是 {@code @PriceSensitive} 字段，
 * 仅 {@code procurement:price:view} 权限角色 (含 finance_manager / factory_super_admin)
 * 能看到真实值 —— 与本闸门一致，无成本结构泄漏。
 *
 * <p>查找优先级 (服务层): product-level > factory-global default。
 * 唯一约束 {@code (factory_id, product_type_id)} 保证每个范围只有一条配置。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/pricing/gross-margin-configs")
@Tag(name = "SP5-毛利红线配置", description = "工厂毛利红线 (目标毛利率) 管理 — 财务/超管可配")
public class GrossMarginConfigController {

    private final FactoryGrossMarginConfigRepository configRepo;
    private final MobileService mobileService;

    // ==================== 查询 (finance:read_write) ====================

    @GetMapping
    @Operation(summary = "列出工厂全部毛利红线配置 (含已禁用)")
    @RequirePermission("finance:read_write")
    public ApiResponse<List<FactoryGrossMarginConfig>> listConfigs(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        return ApiResponse.success(configRepo.findAllByFactory(factoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查单条毛利红线配置")
    @RequirePermission("finance:read_write")
    public ApiResponse<FactoryGrossMarginConfig> getConfig(
            @PathVariable String factoryId,
            @PathVariable String id) {
        FactoryGrossMarginConfig config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("毛利红线配置不存在: " + id));
        return ApiResponse.success(config);
    }

    // ==================== 创建 (finance:read_write) ====================

    @PostMapping
    @Transactional
    @Operation(summary = "创建毛利红线配置")
    @RequirePermission("finance:read_write")
    public ApiResponse<FactoryGrossMarginConfig> createConfig(
            @PathVariable String factoryId,
            @Valid @RequestBody CreateGrossMarginConfigRequest req,
            @RequestHeader("Authorization") String authorization) {
        String productTypeId = normalizeProductTypeId(req.getProductTypeId());

        // fool-proof Rule 4 (幂等防重复): 唯一约束 (factory_id, product_type_id) —
        // 创建前查重 (含已禁用)，避免 DataIntegrityViolation 生成不可读的 409。
        Optional<FactoryGrossMarginConfig> existing = (productTypeId == null)
                ? configRepo.findFactoryDefaultIncludingInactive(factoryId)
                : configRepo.findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        if (existing.isPresent()) {
            String scope = (productTypeId == null) ? "工厂全局默认" : ("产品 " + productTypeId);
            throw new BusinessException(409,
                    scope + " 的毛利红线已存在 (id=" + existing.get().getId() + ")，请直接编辑")
                    .withHint("同一范围只能有一条红线配置，请编辑现有记录")
                    .withSeverity("warning");
        }

        Long userId = extractUserId(authorization);
        FactoryGrossMarginConfig config = FactoryGrossMarginConfig.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .targetGrossMargin(req.getTargetGrossMargin())
                .description(req.getDescription())
                .isActive(req.getIsActive() != null ? req.getIsActive() : Boolean.TRUE)
                .createdBy(userId)
                .build();
        FactoryGrossMarginConfig saved = configRepo.save(config);
        log.info("[SP5-W6] 创建毛利红线: factoryId={}, productTypeId={}, targetGrossMargin={}, by={}",
                factoryId, productTypeId, req.getTargetGrossMargin(), userId);
        return ApiResponse.success("毛利红线已创建", saved);
    }

    // ==================== 更新 (finance:read_write) ====================

    @PutMapping("/{id}")
    @Transactional
    @Operation(summary = "更新毛利红线配置 (null = 不修改)")
    @RequirePermission("finance:read_write")
    public ApiResponse<FactoryGrossMarginConfig> updateConfig(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody UpdateGrossMarginConfigRequest req) {
        FactoryGrossMarginConfig config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("毛利红线配置不存在: " + id));

        if (req.getTargetGrossMargin() != null) {
            config.setTargetGrossMargin(req.getTargetGrossMargin());
        }
        if (req.getIsActive() != null) {
            config.setIsActive(req.getIsActive());
        }
        if (req.getDescription() != null) {
            config.setDescription(req.getDescription());
        }
        FactoryGrossMarginConfig saved = configRepo.save(config);
        // 阈值改动即时生效: GrossMarginRedlineService 每次 checkMargin 都实时读 repo，无缓存。
        log.info("[SP5-W6] 更新毛利红线: id={}, factoryId={}, targetGrossMargin={}, isActive={}",
                id, factoryId, saved.getTargetGrossMargin(), saved.getIsActive());
        return ApiResponse.success("毛利红线已更新", saved);
    }

    // ==================== 删除 (软删, finance:read_write) ====================

    @DeleteMapping("/{id}")
    @Transactional
    @Operation(summary = "软删除毛利红线配置 (删除后该范围跳过红线检查)")
    @RequirePermission("finance:read_write")
    public ApiResponse<Void> deleteConfig(
            @PathVariable String factoryId,
            @PathVariable String id) {
        FactoryGrossMarginConfig config = configRepo.findById(id)
                .filter(c -> factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("毛利红线配置不存在: " + id));
        configRepo.delete(config); // BaseEntity @SQLDelete → 设置 deleted_at
        log.info("[SP5-W6] 软删除毛利红线: id={}, factoryId={}", id, factoryId);
        return ApiResponse.success("毛利红线已删除", null);
    }

    // ==================== 内部方法 ====================

    /** 空白 productTypeId 归一化为 null (工厂全局默认)。 */
    private String normalizeProductTypeId(String productTypeId) {
        return (productTypeId == null || productTypeId.isBlank()) ? null : productTypeId.trim();
    }

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }
}

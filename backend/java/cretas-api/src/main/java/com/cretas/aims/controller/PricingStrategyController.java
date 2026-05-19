package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.pricing.PricingApplicationLog;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.pricing.PricingApplicationLogRepository;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import com.cretas.aims.service.pricing.PricingEngine;
import com.cretas.aims.service.pricing.PricingResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 价格策略 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p>CRUD + toggle 已实现 (使用 Repository 直接读写). {@link #simulate} 调用
 * {@link PricingEngine} 接口, 当前 Skeleton 抛 UnsupportedOperationException —
 * sister chat 填 PricingEngineImpl.
 *
 * <p>权限: factory_super_admin / permission_admin 可写; sales_manager / finance_manager 可读.
 *
 * @see com.cretas.aims.service.pricing.PricingEngine
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/pricing")
@Tag(name = "Canvas-Pricing 价格策略", description = "5 种策略类型: TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE")
@RequiredArgsConstructor
public class PricingStrategyController {

    private final PricingStrategyRepository strategyRepo;
    private final PricingApplicationLogRepository logRepo;
    private final PricingEngine pricingEngine;

    // ==================== Request DTOs ====================

    @Data
    public static class StrategyRequest {
        @NotBlank(message = "策略代码不能为空")
        private String strategyCode;
        private String strategyName;
        @NotBlank(message = "策略类型不能为空")
        private String strategyType;
        private Map<String, Object> scopeFilterJson;
        private Map<String, Object> rulesJson;
        private Integer priority;
        private Boolean enabled;
        private LocalDate validFrom;
        private LocalDate validTo;
    }

    @Data
    public static class SimulateRequest {
        @NotBlank(message = "商品ID不能为空")
        private String productId;
        private Integer quantity;
        private BigDecimal unitPriceList;
        private Long customerId;
        /** Post-review I7: 必须传, 否则 MEMBER 策略 + scope_filter customerGroups 不生效. */
        private String customerGroup;
        /** Post-review I7: 必须传, 否则 scope_filter productCategories 不生效. */
        private String productCategory;
        /**
         * Post-review I7: optional, 可选传 cost estimate 让 engine 触发"below cost"防呆 warning.
         * NULL = caller 不提供 cost 上下文, 跳过 cost-check 防呆 (per spec §4 + PricingRequest.costEstimate).
         */
        private BigDecimal costEstimate;
        /** Optional region filter (per PricingRequest.region). */
        private String region;
    }

    // ==================== Strategies CRUD ====================

    @GetMapping("/strategies")
    @Operation(summary = "列出工厂的所有价格策略 (含已禁用)")
    @RequireRole({"factory_super_admin", "permission_admin", "sales_manager", "finance_manager"})
    public ApiResponse<List<PricingStrategy>> listStrategies(@PathVariable String factoryId) {
        List<PricingStrategy> strategies =
                strategyRepo.findByFactoryIdOrderByPriorityAscCreatedAtDesc(factoryId);
        return ApiResponse.success(strategies);
    }

    @PostMapping("/strategies")
    @Operation(summary = "创建价格策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<PricingStrategy> createStrategy(
            @PathVariable String factoryId,
            @Valid @RequestBody StrategyRequest req) {
        Optional<PricingStrategy> existing =
                strategyRepo.findByFactoryIdAndStrategyCode(factoryId, req.getStrategyCode());
        if (existing.isPresent()) {
            throw new BusinessException(409, "策略代码已存在: " + req.getStrategyCode())
                    .withHint("请使用其他代码")
                    .withSeverity("warning")
                    .withHintTarget("strategyCode");
        }
        PricingStrategy s = new PricingStrategy();
        s.setFactoryId(factoryId);
        s.setStrategyCode(req.getStrategyCode());
        s.setStrategyName(req.getStrategyName());
        s.setStrategyType(parseType(req.getStrategyType()));
        s.setScopeFilterJson(req.getScopeFilterJson());
        s.setRulesJson(req.getRulesJson());
        s.setPriority(req.getPriority() != null ? req.getPriority() : 100);
        s.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        s.setValidFrom(req.getValidFrom());
        s.setValidTo(req.getValidTo());
        PricingStrategy saved = strategyRepo.save(s);
        log.info("创建价格策略: factoryId={}, code={}, type={}, priority={}",
                factoryId, saved.getStrategyCode(), saved.getStrategyType(), saved.getPriority());
        return ApiResponse.success("策略创建成功", saved);
    }

    @PutMapping("/strategies/{id}")
    @Operation(summary = "更新价格策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<PricingStrategy> updateStrategy(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody StrategyRequest req) {
        PricingStrategy s = strategyRepo.findById(id)
                .orElseThrow(() -> new BusinessException(404, "策略不存在: " + id));
        if (!factoryId.equals(s.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的策略");
        }
        // strategy_code rename — 唯一性 check
        if (!s.getStrategyCode().equals(req.getStrategyCode())) {
            Optional<PricingStrategy> dup =
                    strategyRepo.findByFactoryIdAndStrategyCode(factoryId, req.getStrategyCode());
            if (dup.isPresent() && !dup.get().getId().equals(id)) {
                throw new BusinessException(409, "策略代码已存在: " + req.getStrategyCode())
                        .withHint("请使用其他代码")
                        .withSeverity("warning")
                        .withHintTarget("strategyCode");
            }
        }
        s.setStrategyCode(req.getStrategyCode());
        s.setStrategyName(req.getStrategyName());
        s.setStrategyType(parseType(req.getStrategyType()));
        s.setScopeFilterJson(req.getScopeFilterJson());
        s.setRulesJson(req.getRulesJson());
        if (req.getPriority() != null) s.setPriority(req.getPriority());
        if (req.getEnabled() != null) s.setEnabled(req.getEnabled());
        s.setValidFrom(req.getValidFrom());
        s.setValidTo(req.getValidTo());
        PricingStrategy saved = strategyRepo.save(s);
        log.info("更新价格策略: id={}, code={}, enabled={}", id, saved.getStrategyCode(), saved.getEnabled());
        return ApiResponse.success("策略更新成功", saved);
    }

    @PostMapping("/strategies/{id}/toggle")
    @Operation(summary = "启用/禁用 价格策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<PricingStrategy> toggleStrategy(
            @PathVariable String factoryId,
            @PathVariable String id) {
        PricingStrategy s = strategyRepo.findById(id)
                .orElseThrow(() -> new BusinessException(404, "策略不存在: " + id));
        if (!factoryId.equals(s.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的策略");
        }
        s.setEnabled(!Boolean.TRUE.equals(s.getEnabled()));
        PricingStrategy saved = strategyRepo.save(s);
        log.info("toggle 价格策略: id={}, enabled={}", id, saved.getEnabled());
        return ApiResponse.success(saved.getEnabled() ? "策略已启用" : "策略已禁用", saved);
    }

    @DeleteMapping("/strategies/{id}")
    @Operation(summary = "删除价格策略 (软删)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> deleteStrategy(
            @PathVariable String factoryId,
            @PathVariable String id) {
        PricingStrategy s = strategyRepo.findById(id)
                .orElseThrow(() -> new BusinessException(404, "策略不存在: " + id));
        if (!factoryId.equals(s.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的策略");
        }
        s.softDelete();
        strategyRepo.save(s);
        log.info("删除价格策略: id={}, code={}", id, s.getStrategyCode());
        return ApiResponse.success("策略已删除", null);
    }

    // ==================== Simulate (Skeleton — PricingEngineImpl throws) ====================

    @PostMapping("/strategies/simulate")
    @Operation(summary = "模拟计算最终单价 (preview, NOT 写日志)")
    @RequireRole({"factory_super_admin", "permission_admin", "sales_manager"})
    public ApiResponse<PricingResult> simulate(
            @PathVariable String factoryId,
            @Valid @RequestBody SimulateRequest req) {
        // Post-review I7: build full PricingRequest so customerGroup / productCategory /
        // costEstimate / region 都参与 scope_filter + 防呆 cost-check. 旧 5-arg overload 会静默 drop.
        com.cretas.aims.service.pricing.PricingRequest engineReq =
                com.cretas.aims.service.pricing.PricingRequest.builder()
                        .factoryId(factoryId)
                        .productId(req.getProductId())
                        .quantity(req.getQuantity() != null ? req.getQuantity() : 1)
                        .unitPriceList(req.getUnitPriceList() != null ? req.getUnitPriceList() : BigDecimal.ZERO)
                        .customerId(req.getCustomerId())
                        .customerGroup(req.getCustomerGroup())
                        .productCategory(req.getProductCategory())
                        .region(req.getRegion())
                        .costEstimate(req.getCostEstimate())
                        .build();
        PricingResult result = pricingEngine.simulate(engineReq);
        return ApiResponse.success(result);
    }

    // ==================== Application logs ====================

    @GetMapping("/logs")
    @Operation(summary = "查询价格策略应用日志 (审计用)")
    @RequireRole({"factory_super_admin", "permission_admin", "finance_manager"})
    public ApiResponse<Page<PricingApplicationLog>> listLogs(
            @PathVariable String factoryId,
            @RequestParam(required = false) String businessEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PricingApplicationLog> result;
        if (businessEntityId != null && !businessEntityId.isBlank()) {
            result = logRepo.findByFactoryIdAndBusinessEntityIdOrderByAppliedAtDesc(
                    factoryId, businessEntityId, pageable);
        } else {
            result = logRepo.findByFactoryIdOrderByAppliedAtDesc(factoryId, pageable);
        }
        return ApiResponse.success(result);
    }

    // ==================== Helpers ====================

    private PricingStrategyType parseType(String typeStr) {
        try {
            return PricingStrategyType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的策略类型: " + typeStr +
                    " (有效值: TIERED, PROMOTION, MEMBER, BUNDLE, CYCLE)")
                    .withHint("请选择 5 种类型之一")
                    .withSeverity("warning")
                    .withHintTarget("strategyType");
        }
    }
}

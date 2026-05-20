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
import com.fasterxml.jackson.annotation.JsonFormat;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
        /**
         * B-P1 fix: pin format to {@code yyyy-MM-dd} so Jackson rejects full-ISO datetime
         * strings (e.g. {@code "2026-05-01T00:00:00"}) with a date-specific parse error
         * that {@link com.cretas.aims.exception.GlobalExceptionHandler#handleHttpMessageNotReadableException}
         * routes to "日期格式不正确（值: ...），请重新选择日期" hint instead of the generic
         * "请求格式不正确" fallback.
         */
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate validFrom;
        @JsonFormat(pattern = "yyyy-MM-dd")
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
        // QA Round (2026-05-19) B-P2/3/4/5: boundary validators run BEFORE persistence.
        PricingStrategyType type = parseType(req.getStrategyType());
        validateDateRange(req);
        validateRulesByType(type, req.getRulesJson());

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
        s.setStrategyType(type);
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
        // QA Round (2026-05-19) B-P2/3/4/5: boundary validators run BEFORE persistence.
        PricingStrategyType type = parseType(req.getStrategyType());
        validateDateRange(req);
        validateRulesByType(type, req.getRulesJson());

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
        s.setStrategyType(type);
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

    // ==================== Boundary validators (QA 2026-05-19 B-P2/3/4/5) ====================

    /**
     * B-P5: reject {@code validTo < validFrom}. Null on either side = open-ended interval (allowed).
     *
     * <p>Per fool-proof Rule 1 (预先显示边界, 不要事后报错): surface the specific dates so the user
     * can spot the swap immediately.
     */
    private void validateDateRange(StrategyRequest req) {
        LocalDate from = req.getValidFrom();
        LocalDate to = req.getValidTo();
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(400,
                    "validTo 不可早于 validFrom (from=" + from + ", to=" + to + ")")
                    .withHint("请检查生效区间: from=" + from + " to=" + to)
                    .withSeverity("warning")
                    .withHintTarget("validTo");
        }
    }

    /**
     * Dispatch rule-shape validation by strategy type. Currently only TIERED has structural
     * boundary checks (overlap / negative / &gt;100% on each tier discountPct). Other types
     * (PROMOTION / MEMBER / BUNDLE / CYCLE) are validated at engine time by
     * {@link com.cretas.aims.service.pricing.impl.PricingEngineImpl}.
     */
    private void validateRulesByType(PricingStrategyType type, Map<String, Object> rulesJson) {
        if (type == PricingStrategyType.TIERED) {
            validateTiers(rulesJson);
        }
    }

    /**
     * B-P2/3/4 + AUD-2/AUD-3: TIERED tier boundary validators.
     *
     * <ul>
     *   <li>AUD-3: each {@code discountPct} scale &lt;= 2 (28-digit precision rounds to
     *       boundary on Jackson Double; reject ambiguous high-scale inputs)</li>
     *   <li>B-P3: each {@code discountPct} must be &gt;= 0 (negative = 涨价, not a discount)</li>
     *   <li>B-P4: each {@code discountPct} must be &lt;= 100 (over 100% = 倒贴)</li>
     *   <li>AUD-2: {@code minQty} and {@code maxQty} must be &gt;= 0 (Long.MIN_VALUE
     *       previously persisted; relation-only check left negative hole)</li>
     *   <li>B-P2: tier intervals {@code [minQty, maxQty]} must not overlap</li>
     * </ul>
     *
     * <p>{@code rulesJson} is {@code Map<String, Object>} so Bean Validation can't reach inside —
     * tier validation must be explicit at controller boundary. Null/missing {@code tiers} is
     * tolerated for forward-compat (engine returns ZERO discount, no harm).
     */
    @SuppressWarnings("unchecked")
    private void validateTiers(Map<String, Object> rulesJson) {
        if (rulesJson == null) return;
        Object tiersObj = rulesJson.get("tiers");
        if (!(tiersObj instanceof List<?>)) return;
        List<?> tiersRaw = (List<?>) tiersObj;
        if (tiersRaw.isEmpty()) return;

        List<TierBounds> bounds = new ArrayList<>(tiersRaw.size());
        for (int i = 0; i < tiersRaw.size(); i++) {
            Object t = tiersRaw.get(i);
            if (!(t instanceof Map)) continue;
            Map<String, Object> tier = (Map<String, Object>) t;

            BigDecimal discountPct = toDecimal(tier.get("discountPct"));
            if (discountPct != null) {
                // AUD-3 fix (edge audit 2026-05-20): reject precision overflow that could mask
                // round-trip boundary attacks. discountPct accepts at most 2 decimal places —
                // any input with scale > 2 whose value differs from its 2-decimal HALF_UP
                // rounding is ambiguous (could mean "just below 100" but rounded to "exactly
                // 100" = free). Fire BEFORE the < 0 / > 100 checks so the actionable hint
                // surfaces the precision issue, not a downstream symptom.
                if (discountPct.scale() > 2
                        && discountPct.compareTo(discountPct.setScale(2, RoundingMode.HALF_UP)) != 0) {
                    throw new BusinessException(400,
                            "折扣率精度过高 (tier[" + i + "].discountPct=" + discountPct.toPlainString()
                                    + "), 最多保留 2 位小数")
                            .withHint("折扣率请使用 2 位小数, 例: 10.50")
                            .withSeverity("warning")
                            .withHintTarget("tiers[" + i + "].discountPct");
                }
                // B-P3: negative discount = 涨价
                if (discountPct.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException(400,
                            "折扣率不可为负 (tier[" + i + "].discountPct=" + discountPct
                                    + ", 负数 = 涨价)")
                            .withHint("请将折扣率设为 0-100 之间的数值")
                            .withSeverity("warning")
                            .withHintTarget("tiers[" + i + "].discountPct");
                }
                // B-P4: discount > 100% = 倒贴
                if (discountPct.compareTo(new BigDecimal("100")) > 0) {
                    throw new BusinessException(400,
                            "折扣率不可超过 100% (tier[" + i + "].discountPct=" + discountPct
                                    + ", 超过 100% = 倒贴)")
                            .withHint("请将折扣率设为 0-100 之间的数值")
                            .withSeverity("warning")
                            .withHintTarget("tiers[" + i + "].discountPct");
                }
            }

            BigDecimal minQty = toDecimal(tier.get("minQty"));
            BigDecimal maxQty = toDecimal(tier.get("maxQty"));
            // AUD-2 fix (edge audit 2026-05-20): reject negative quantities. Pre-fix, a
            // Long.MIN_VALUE minQty (e.g. crafted curl payload) was persisted because the
            // existing `maxQty < minQty` check left a hole: -9223372036854775808 < anything
            // valid means the relation alone never trips, and overlap detection on negative
            // intervals is meaningless. Surface both indices so the user can spot which
            // tier carries the offending value.
            if (minQty != null && minQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(400,
                        "阶梯数量不可为负 (tier[" + i + "].minQty=" + minQty.toPlainString() + ")")
                        .withHint("请使用非负整数, 最小数量从 0 开始")
                        .withSeverity("warning")
                        .withHintTarget("tiers[" + i + "].minQty");
            }
            if (maxQty != null && maxQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(400,
                        "阶梯数量不可为负 (tier[" + i + "].maxQty=" + maxQty.toPlainString() + ")")
                        .withHint("请使用非负整数, 最大数量从 0 开始")
                        .withSeverity("warning")
                        .withHintTarget("tiers[" + i + "].maxQty");
            }
            if (minQty != null && maxQty != null) {
                if (maxQty.compareTo(minQty) < 0) {
                    throw new BusinessException(400,
                            "阶梯区间无效: tier[" + i + "] maxQty=" + maxQty
                                    + " < minQty=" + minQty)
                            .withHint("请确保每个阶梯的 maxQty >= minQty")
                            .withSeverity("warning")
                            .withHintTarget("tiers[" + i + "].maxQty");
                }
                bounds.add(new TierBounds(i, minQty, maxQty));
            }
        }

        // B-P2: overlap detection.
        // Sort by minQty asc, then assert each tier's minQty > previous tier's maxQty.
        // 相邻区间允许端点相接但不可重叠 (i.e. prev.maxQty < curr.minQty must hold strictly).
        bounds.sort(Comparator.comparing(b -> b.minQty));
        for (int i = 1; i < bounds.size(); i++) {
            TierBounds prev = bounds.get(i - 1);
            TierBounds curr = bounds.get(i);
            if (curr.minQty.compareTo(prev.maxQty) <= 0) {
                throw new BusinessException(400,
                        "阶梯区间重叠: tier[" + prev.index + "] ["
                                + prev.minQty.toPlainString() + "-" + prev.maxQty.toPlainString()
                                + "] 与 tier[" + curr.index + "] ["
                                + curr.minQty.toPlainString() + "-" + curr.maxQty.toPlainString() + "]")
                        .withHint("请调整阶梯, 确保相邻区间不重叠 (prev.maxQty < curr.minQty)")
                        .withSeverity("warning")
                        .withHintTarget("tiers[" + curr.index + "].minQty");
            }
        }
    }

    /**
     * Lenient numeric coercion for JSON-decoded {@code Map<String, Object>} values.
     * Returns null on absent / unparseable input — caller decides whether that's a hard error.
     */
    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sorted-pair holder for tier overlap detection. */
    private static final class TierBounds {
        final int index;
        final BigDecimal minQty;
        final BigDecimal maxQty;
        TierBounds(int index, BigDecimal minQty, BigDecimal maxQty) {
            this.index = index;
            this.minQty = minQty;
            this.maxQty = maxQty;
        }
    }
}

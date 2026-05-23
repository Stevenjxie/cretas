package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.repository.CommissionRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canvas 销售目标中心 REST 接口 — Phase B (2026-05-22).
 *
 * <p>包装 Sprint 7 T5 {@link CommissionRule} 实体, 增加:
 * <ol>
 *   <li>Tier ladder (阶梯提成): >10万 / 10-50万 / 50万+</li>
 *   <li>Period config: MONTHLY / QUARTERLY</li>
 *   <li>Leaderboard formula hint</li>
 * </ol>
 *
 * <p><b>Path</b>: {@code /api/mobile/{factoryId}/canvas-sales-target}.
 *
 * <p><b>RBAC</b>: 查询 + 写入 factory_super_admin / permission_admin.
 *
 * <p><b>AUD-4</b>: PUT 通过 JPA {@code @Version} 并发控制.
 *
 * @since 2026-05-22 (Canvas Phase B)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-sales-target")
@Tag(name = "Canvas-销售目标中心", description = "Phase B — 提成阶梯 / 周期 / 排行榜")
@RequiredArgsConstructor
public class CanvasSalesTargetController {

    private final CommissionRuleRepository commissionRuleRepo;

    private static final List<String> ALLOWED_PERIODS = List.of("MONTHLY", "QUARTERLY");
    private static final int FORMULA_MAX_LENGTH = 500;

    // ============================================================
    // 1. Overview (hub landing)
    // ============================================================

    @GetMapping("/overview")
    @Operation(summary = "中心总览", description = "汇总活跃规则数 / 含阶梯规则数 / 周期分布")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> overview(@PathVariable String factoryId) {
        List<CommissionRule> rules = commissionRuleRepo
                .findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        long activeCount = rules.stream().filter(r -> Boolean.TRUE.equals(r.getActive())).count();
        long tieredCount = rules.stream()
                .filter(r -> r.getTierConfig() != null && !r.getTierConfig().isEmpty())
                .count();
        long monthlyCount = rules.stream()
                .filter(r -> "MONTHLY".equals(r.getPeriodType())).count();
        long quarterlyCount = rules.stream()
                .filter(r -> "QUARTERLY".equals(r.getPeriodType())).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRules", rules.size());
        data.put("activeRules", activeCount);
        data.put("tieredRules", tieredCount);
        data.put("monthlyRules", monthlyCount);
        data.put("quarterlyRules", quarterlyCount);
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 2. List rules
    // ============================================================

    @GetMapping("/rules")
    @Operation(summary = "列出提成规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<Map<String, Object>>> listRules(@PathVariable String factoryId) {
        List<CommissionRule> rows = commissionRuleRepo
                .findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (CommissionRule r : rows) {
            data.add(serializeRule(r));
        }
        return ApiResponse.success("操作成功", data);
    }

    @GetMapping("/rules/{id}")
    @Operation(summary = "规则详情")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> getRule(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<CommissionRule> opt = commissionRuleRepo
                .findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
        if (opt.isEmpty()) {
            return ApiResponse.errorWithHint(404, "规则不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        return ApiResponse.success("操作成功", serializeRule(opt.get()));
    }

    // ============================================================
    // 3. Create rule
    // ============================================================

    @PostMapping("/rules")
    @Operation(summary = "创建提成规则", description = "支持 flat percentage 或 tier ladder (二选一)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createRule(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        log.info("POST /canvas-sales-target/rules factoryId={} keys={}",
                factoryId, body.keySet());

        BigDecimal percentage = bigDecimalField(body, "percentage", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tierConfig = body.get("tierConfig") instanceof List
                ? (List<Map<String, Object>>) body.get("tierConfig") : null;

        if (percentage == null && (tierConfig == null || tierConfig.isEmpty())) {
            return ApiResponse.errorWithHint(400,
                    "必须提供 percentage 或 tierConfig 之一",
                    "请填写 flat 提成率, 或配置阶梯", null, null);
        }
        if (percentage != null
                && (percentage.compareTo(BigDecimal.ZERO) < 0
                    || percentage.compareTo(new BigDecimal("100")) > 0)) {
            return ApiResponse.errorWithHint(400,
                    "percentage 必须 0-100",
                    "请输入合法的提成百分比 (0-100)", null, null);
        }
        if (tierConfig != null && !tierConfig.isEmpty()) {
            String tierError = validateTierConfig(tierConfig);
            if (tierError != null) {
                return ApiResponse.errorWithHint(400, "tierConfig 不合法", tierError, null, null);
            }
        }

        String effectiveFromStr = stringField(body, "effectiveFrom");
        LocalDate effectiveFrom;
        try {
            effectiveFrom = effectiveFromStr == null || effectiveFromStr.isBlank()
                    ? LocalDate.now()
                    : LocalDate.parse(effectiveFromStr);
        } catch (Exception ex) {
            return ApiResponse.errorWithHint(400, "effectiveFrom 格式错误",
                    "请使用 YYYY-MM-DD", null, null);
        }
        LocalDate effectiveTo = null;
        if (body.containsKey("effectiveTo") && body.get("effectiveTo") != null) {
            String to = stringField(body, "effectiveTo");
            if (to != null && !to.isBlank()) {
                try {
                    effectiveTo = LocalDate.parse(to);
                } catch (Exception ex) {
                    return ApiResponse.errorWithHint(400, "effectiveTo 格式错误",
                            "请使用 YYYY-MM-DD", null, null);
                }
            }
        }
        String periodType = stringField(body, "periodType");
        if (periodType == null || periodType.isBlank()) {
            periodType = "MONTHLY";
        } else if (!ALLOWED_PERIODS.contains(periodType.toUpperCase())) {
            return ApiResponse.errorWithHint(400, "periodType 不合法",
                    "允许值: " + String.join(" / ", ALLOWED_PERIODS), null, null);
        } else {
            periodType = periodType.toUpperCase();
        }

        String leaderboardFormula = stringField(body, "leaderboardFormula");
        if (leaderboardFormula != null && leaderboardFormula.length() > FORMULA_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "leaderboardFormula 过长 (最长 " + FORMULA_MAX_LENGTH + " 字符)",
                    "请精简公式表达式", "warning");
        }

        Long createdBy = longField(body, "createdBy");
        if (createdBy == null) createdBy = 0L; // fallback for tests

        CommissionRule rule = CommissionRule.builder()
                .factoryId(factoryId)
                .salesId(longField(body, "salesId"))
                .customerType(stringField(body, "customerType"))
                .percentage(percentage != null ? percentage : BigDecimal.ZERO)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .active(booleanField(body, "active", true))
                .createdBy(createdBy)
                .tierConfig(tierConfig)
                .periodType(periodType)
                .leaderboardFormula(leaderboardFormula)
                .build();

        CommissionRule saved = commissionRuleRepo.saveAndFlush(rule);
        return ApiResponse.success("创建成功", serializeRule(saved));
    }

    // ============================================================
    // 4. Update rule
    // ============================================================

    @PutMapping("/rules/{id}")
    @Operation(summary = "更新提成规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateRule(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<CommissionRule> opt = commissionRuleRepo
                .findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
        if (opt.isEmpty()) {
            return ApiResponse.errorWithHint(404, "规则不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        CommissionRule rule = opt.get();

        Long submittedVersion = longField(body, "version");
        if (submittedVersion == null) {
            return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                    "缺少 version 字段", "请刷新页面后再提交", "warning");
        }
        if (!submittedVersion.equals(rule.getVersion())) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "规则已被他人修改 (当前 v" + rule.getVersion()
                            + ", 提交 v" + submittedVersion + ")",
                    "请刷新页面后再保存", "warning");
        }

        if (body.containsKey("percentage")) {
            BigDecimal pct = bigDecimalField(body, "percentage", null);
            if (pct == null
                    || pct.compareTo(BigDecimal.ZERO) < 0
                    || pct.compareTo(new BigDecimal("100")) > 0) {
                return ApiResponse.errorWithHint(400,
                        "percentage 必须 0-100",
                        "请输入合法的提成百分比", null, null);
            }
            rule.setPercentage(pct);
        }
        if (body.containsKey("tierConfig")) {
            Object tc = body.get("tierConfig");
            if (tc == null) {
                rule.setTierConfig(null);
            } else if (tc instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tiers = (List<Map<String, Object>>) tc;
                if (!tiers.isEmpty()) {
                    String tierError = validateTierConfig(tiers);
                    if (tierError != null) {
                        return ApiResponse.errorWithHint(400, "tierConfig 不合法",
                                tierError, null, null);
                    }
                }
                rule.setTierConfig(tiers);
            }
        }
        if (body.containsKey("salesId")) rule.setSalesId(longField(body, "salesId"));
        if (body.containsKey("customerType")) rule.setCustomerType(stringField(body, "customerType"));
        if (body.containsKey("effectiveFrom")) {
            String d = stringField(body, "effectiveFrom");
            try {
                rule.setEffectiveFrom(LocalDate.parse(d));
            } catch (Exception ex) {
                return ApiResponse.errorWithHint(400, "effectiveFrom 格式错误",
                        "请使用 YYYY-MM-DD", null, null);
            }
        }
        if (body.containsKey("effectiveTo")) {
            String d = stringField(body, "effectiveTo");
            if (d == null || d.isBlank()) {
                rule.setEffectiveTo(null);
            } else {
                try {
                    rule.setEffectiveTo(LocalDate.parse(d));
                } catch (Exception ex) {
                    return ApiResponse.errorWithHint(400, "effectiveTo 格式错误",
                            "请使用 YYYY-MM-DD", null, null);
                }
            }
        }
        if (body.containsKey("active") && body.get("active") instanceof Boolean b) {
            rule.setActive(b);
        }
        if (body.containsKey("periodType")) {
            String pt = stringField(body, "periodType");
            if (pt == null || !ALLOWED_PERIODS.contains(pt.toUpperCase())) {
                return ApiResponse.errorWithHint(400, "periodType 不合法",
                        "允许值: " + String.join(" / ", ALLOWED_PERIODS), null, null);
            }
            rule.setPeriodType(pt.toUpperCase());
        }
        if (body.containsKey("leaderboardFormula")) {
            String f = stringField(body, "leaderboardFormula");
            if (f != null && f.length() > FORMULA_MAX_LENGTH) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "leaderboardFormula 过长", "请精简公式", "warning");
            }
            rule.setLeaderboardFormula(f);
        }

        try {
            CommissionRule saved = commissionRuleRepo.saveAndFlush(rule);
            return ApiResponse.success("更新成功", serializeRule(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "软删除提成规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> deleteRule(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<CommissionRule> opt = commissionRuleRepo
                .findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
        if (opt.isEmpty()) {
            return ApiResponse.errorWithHint(404, "规则不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        // SQLDelete annotation on entity = soft delete on standard delete()
        commissionRuleRepo.delete(opt.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("deleted", true);
        return ApiResponse.success("规则已删除", data);
    }

    // ============================================================
    // 5. Preview commission (calculator dry-run)
    // ============================================================

    @PostMapping("/rules/{id}/preview")
    @Operation(summary = "试算提成金额", description = "给定订单金额, 返回应得提成 (基于 flat 或 tier)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> previewCommission(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<CommissionRule> opt = commissionRuleRepo
                .findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
        if (opt.isEmpty()) {
            return ApiResponse.errorWithHint(404, "规则不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        CommissionRule rule = opt.get();
        BigDecimal orderAmount = bigDecimalField(body, "orderAmount", null);
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) < 0) {
            return ApiResponse.errorWithHint(400, "orderAmount 必须 ≥ 0",
                    "请提供有效订单金额", null, null);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderAmount", orderAmount);
        data.put("ruleId", id);

        if (rule.getTierConfig() != null && !rule.getTierConfig().isEmpty()) {
            // Tier mode: find matching tier
            Map<String, Object> matchedTier = null;
            for (Map<String, Object> tier : rule.getTierConfig()) {
                BigDecimal min = toBigDecimal(tier.get("minAmount"));
                BigDecimal max = toBigDecimal(tier.get("maxAmount"));
                if ((min == null || orderAmount.compareTo(min) >= 0)
                        && (max == null || orderAmount.compareTo(max) < 0)) {
                    matchedTier = tier;
                    break;
                }
            }
            if (matchedTier == null) {
                data.put("commission", BigDecimal.ZERO);
                data.put("matchedTier", null);
                data.put("note", "无匹配阶梯");
            } else {
                BigDecimal rate = toBigDecimal(matchedTier.get("rate"));
                if (rate == null) rate = BigDecimal.ZERO;
                BigDecimal commission = orderAmount.multiply(rate).divide(new BigDecimal("100"),
                        2, java.math.RoundingMode.HALF_UP);
                data.put("commission", commission);
                data.put("matchedTier", matchedTier);
                data.put("rate", rate);
                data.put("mode", "TIER");
            }
        } else {
            // Flat mode
            BigDecimal commission = orderAmount.multiply(rule.getPercentage())
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            data.put("commission", commission);
            data.put("rate", rule.getPercentage());
            data.put("mode", "FLAT");
        }
        return ApiResponse.success("试算成功", data);
    }

    // ============================================================
    // Validation helpers
    // ============================================================

    /**
     * Validate tier configuration list.
     *
     * @return null if valid; otherwise error message
     */
    private String validateTierConfig(List<Map<String, Object>> tiers) {
        if (tiers.size() > 20) {
            return "最多支持 20 个阶梯, 当前 " + tiers.size();
        }
        BigDecimal prevMax = null;
        for (int i = 0; i < tiers.size(); i++) {
            Map<String, Object> t = tiers.get(i);
            BigDecimal min = toBigDecimal(t.get("minAmount"));
            BigDecimal max = toBigDecimal(t.get("maxAmount"));
            BigDecimal rate = toBigDecimal(t.get("rate"));
            if (min == null) {
                return "第 " + (i + 1) + " 个阶梯缺少 minAmount";
            }
            if (rate == null
                    || rate.compareTo(BigDecimal.ZERO) < 0
                    || rate.compareTo(new BigDecimal("100")) > 0) {
                return "第 " + (i + 1) + " 个阶梯 rate 必须 0-100";
            }
            if (max != null && max.compareTo(min) <= 0) {
                return "第 " + (i + 1) + " 个阶梯 maxAmount 必须 > minAmount";
            }
            if (prevMax != null && min.compareTo(prevMax) < 0) {
                return "第 " + (i + 1) + " 个阶梯 minAmount 必须 >= 前一阶梯 maxAmount (区间不重叠)";
            }
            prevMax = max;
        }
        return null;
    }

    // ============================================================
    // Serialization
    // ============================================================

    private Map<String, Object> serializeRule(CommissionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("factoryId", r.getFactoryId());
        m.put("salesId", r.getSalesId());
        m.put("customerType", r.getCustomerType());
        m.put("percentage", r.getPercentage());
        m.put("effectiveFrom", r.getEffectiveFrom());
        m.put("effectiveTo", r.getEffectiveTo());
        m.put("active", r.getActive());
        m.put("createdBy", r.getCreatedBy());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        m.put("version", r.getVersion());
        m.put("tierConfig", r.getTierConfig());
        m.put("periodType", r.getPeriodType() == null ? "MONTHLY" : r.getPeriodType());
        m.put("leaderboardFormula", r.getLeaderboardFormula());
        return m;
    }

    // ============================================================
    // Field-parse helpers
    // ============================================================

    private static String stringField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        return Objects.toString(v).trim();
    }

    private static Long longField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean booleanField(Map<String, Object> body, String key, boolean defaultValue) {
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static BigDecimal bigDecimalField(Map<String, Object> body, String key,
                                              String defaultValueOrNull) {
        Object v = body.get(key);
        if (v == null) {
            return defaultValueOrNull == null ? null : new BigDecimal(defaultValueOrNull);
        }
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString());
        } catch (Exception ex) {
            return defaultValueOrNull == null ? null : new BigDecimal(defaultValueOrNull);
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }
}

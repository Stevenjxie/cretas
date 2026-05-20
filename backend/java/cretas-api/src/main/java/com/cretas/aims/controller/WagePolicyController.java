package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.scheduler.WageMonthlyScheduler;
import com.cretas.aims.service.WageCalculationService;
import com.cretas.aims.service.WagePolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Sprint 6 Track W4-B: WagePolicy + HourlyRateRule admin REST API.
 *
 * <p>提供 admin UI 配置 wage 模式 + 时薪规则 + 手动 trigger 月度计算 的 API 端点.
 *
 * <p>权限: {@code hr:read_write} (write op) / {@code hr:read} (list op).
 *
 * @since 2026-05-19
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/wage-policy")
@RequiredArgsConstructor
@Tag(name = "工资策略与时薪管理", description = "Sprint 6 W4-B — wage mode policy + hourly rate rule admin")
@RequirePermission({"hr:read_write", "hr:read"})
public class WagePolicyController {

    private final WagePolicyService wagePolicyService;
    private final WageCalculationService wageCalculationService;
    private final WageCalculationRepository wageCalculationRepository;
    private final WageMonthlyScheduler wageMonthlyScheduler;

    // ==================== WagePolicy CRUD ====================

    /**
     * 列工厂所有 WagePolicy (admin UI table).
     */
    @GetMapping("/policies")
    @Operation(summary = "列工厂所有 wage policy")
    public ApiResponse<List<WagePolicy>> listPolicies(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("[WagePolicyController] listPolicies factoryId={}", factoryId);
        return ApiResponse.success(wagePolicyService.listPolicies(factoryId));
    }

    /**
     * 查员工 effective 的 wage mode.
     */
    @GetMapping("/policies/resolve")
    @Operation(summary = "查员工 effective wage mode")
    public ApiResponse<Map<String, Object>> resolveMode(
            @PathVariable String factoryId,
            @RequestParam Long employeeId) {
        WageMode mode = wagePolicyService.resolveModeForEmployee(factoryId, employeeId);
        return ApiResponse.success(Map.of(
                "factoryId", factoryId,
                "employeeId", employeeId,
                "resolvedMode", mode
        ));
    }

    /**
     * 创建或更新 WagePolicy.
     */
    @PostMapping("/policies")
    @Operation(summary = "创建或更新 WagePolicy")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<WagePolicy> savePolicy(
            @PathVariable String factoryId,
            @Valid @RequestBody WagePolicy policy) {
        log.info("[WagePolicyController] savePolicy factoryId={}, policyId={}, mode={}",
                factoryId, policy.getId(), policy.getMode());
        return ApiResponse.success(wagePolicyService.savePolicy(factoryId, policy));
    }

    /**
     * 删除 WagePolicy (soft delete).
     */
    @DeleteMapping("/policies/{id}")
    @Operation(summary = "删除 WagePolicy (soft delete)")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<Void> deletePolicy(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        log.info("[WagePolicyController] deletePolicy factoryId={}, id={}", factoryId, id);
        wagePolicyService.deletePolicy(id);
        return ApiResponse.success(null);
    }

    // ==================== HourlyRateRule CRUD ====================

    /**
     * 列工厂所有 HourlyRateRule.
     */
    @GetMapping("/hourly-rules")
    @Operation(summary = "列工厂所有 HourlyRateRule")
    public ApiResponse<List<HourlyRateRule>> listHourlyRateRules(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long employeeId) {
        log.info("[WagePolicyController] listHourlyRateRules factoryId={}, employeeId={}",
                factoryId, employeeId);
        if (employeeId != null) {
            return ApiResponse.success(
                    wagePolicyService.listEmployeeHourlyRateRules(factoryId, employeeId));
        }
        return ApiResponse.success(wagePolicyService.listHourlyRateRules(factoryId));
    }

    /**
     * 创建或更新 HourlyRateRule (server-side fool-proof guards in service).
     */
    @PostMapping("/hourly-rules")
    @Operation(summary = "创建或更新 HourlyRateRule (Rule 1 server-side ¥500 guard)")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<HourlyRateRule> saveHourlyRateRule(
            @PathVariable String factoryId,
            @Valid @RequestBody HourlyRateRule rule) {
        log.info("[WagePolicyController] saveHourlyRateRule factoryId={}, ruleId={}, baseHourlyRate={}",
                factoryId, rule.getId(), rule.getBaseHourlyRate());
        try {
            HourlyRateRule saved = wagePolicyService.saveHourlyRateRule(factoryId, rule);
            return ApiResponse.success(saved);
        } catch (IllegalArgumentException ex) {
            // 防呆 server-side guard 触发 → 400 + 含 actionHint message
            log.warn("[WagePolicyController] HourlyRateRule guard rejected: {}", ex.getMessage());
            return ApiResponse.error(400, ex.getMessage());
        }
    }

    /**
     * 删除 HourlyRateRule.
     */
    @DeleteMapping("/hourly-rules/{id}")
    @Operation(summary = "删除 HourlyRateRule (soft delete)")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<Void> deleteHourlyRateRule(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        log.info("[WagePolicyController] deleteHourlyRateRule factoryId={}, id={}", factoryId, id);
        wagePolicyService.deleteHourlyRateRule(id);
        return ApiResponse.success(null);
    }

    /**
     * 查员工指定日期 effective 的 HourlyRateRule (admin UI 验证).
     */
    @GetMapping("/hourly-rules/effective")
    @Operation(summary = "查员工指定日期 effective HourlyRateRule")
    public ApiResponse<HourlyRateRule> findEffectiveHourlyRule(
            @PathVariable String factoryId,
            @RequestParam Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return wagePolicyService.findEffectiveHourlyRate(factoryId, employeeId, date)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    // ==================== 月度计算 trigger + history ====================

    /**
     * 手动 trigger 单员工月度计算 (admin UI 试算).
     */
    @PostMapping("/calculate-monthly")
    @Operation(summary = "手动 trigger 单员工月度计算")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<WageCalculation> calculateMonthly(
            @PathVariable String factoryId,
            @RequestParam Long employeeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        log.info("[WagePolicyController] calculateMonthly factoryId={}, employeeId={}, month={}",
                factoryId, employeeId, month);
        WageCalculation calc = wageCalculationService.calculateMonthly(factoryId, month, employeeId);
        return ApiResponse.success(calc);
    }

    /**
     * 手动 trigger 工厂级月度批量计算 (admin UI 强制重跑).
     */
    @PostMapping("/calculate-monthly-batch")
    @Operation(summary = "工厂级月度批量计算 trigger")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<Map<String, Object>> calculateMonthlyBatch(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        log.info("[WagePolicyController] calculateMonthlyBatch factoryId={}, month={}", factoryId, month);
        Map<String, Object> result = wageCalculationService.calculateMonthlyForFactory(factoryId, month);
        return ApiResponse.success(result);
    }

    /**
     * 列工厂指定月份所有 WageCalculation (admin 报表用).
     */
    @GetMapping("/calculations")
    @Operation(summary = "列工厂指定月份 WageCalculation")
    public ApiResponse<List<WageCalculation>> listCalculations(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        return ApiResponse.success(
                wageCalculationRepository.findByFactoryIdAndPeriodMonth(factoryId, monthStart));
    }
}

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
     *
     * <p>Bug #4 fix (2026-05-20, prod QA): 显式拒绝空 {} POST + 必填字段缺失.
     * 直接 entity binding (@Valid @RequestBody WagePolicy) 是 Rule 17.1 反模式 —
     * Jackson 把空 body 反序列化为 entity 字段默认值 ({@code mode=PIECE_RATE} 来自
     * 字段初始化器, {@code isActive=true} 同), service 兜底再补一次, 结果空 {} POST
     * 静默创建一条 factory-default policy → "成功 200" 假象误导客户.
     *
     * <p><b>Mitigation</b>: 改 {@code @RequestBody Map<String, Object>} 拿到原始 JSON,
     * 先检查 raw key 存在性 (区分 "客户没传 mode" vs "传了但 entity 默认 PIECE_RATE"),
     * 再手动转换到 entity. 这样 POST {} (size==0) 和 POST {"isActive":true} (无 mode key)
     * 都被拦截.
     *
     * <p>TODO (Sprint 7+): 迁移到 dedicated {@code WagePolicyRequest} DTO + Jakarta
     * {@code @NotNull} bean validation, 不再直接绑定 entity (Rule 17.1 根治).
     */
    @PostMapping("/policies")
    @Operation(summary = "创建或更新 WagePolicy")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<WagePolicy> savePolicy(
            @PathVariable String factoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        // Bug #4: 拒绝 null body / 空 {} POST.
        if (body == null || body.isEmpty()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "请求体不能为空",
                    "请填写 wage policy 必填字段 (mode 工资模式)", "warning");
        }
        // 必填: mode (PIECE_RATE/HOURLY/MIXED). 检查 raw JSON 是否含 mode key.
        if (!body.containsKey("mode") || body.get("mode") == null
                || (body.get("mode") instanceof String && ((String) body.get("mode")).isBlank())) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "工资模式 (mode) 不能为空",
                    "请显式指定 mode: PIECE_RATE / HOURLY / MIXED", "warning");
        }
        // Parse mode (Jackson 可能给 String 也可能给 WageMode enum, 都接受).
        WageMode mode;
        try {
            Object rawMode = body.get("mode");
            mode = (rawMode instanceof WageMode)
                    ? (WageMode) rawMode
                    : WageMode.valueOf(rawMode.toString());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "工资模式 (mode) 非法: " + body.get("mode"),
                    "合法值: PIECE_RATE / HOURLY / MIXED", "warning");
        }
        // MIXED 模式必须填公式说明 (审计可追溯).
        String mixedFormulaHint = body.get("mixedFormulaHint") == null
                ? null : body.get("mixedFormulaHint").toString();
        if (mode == WageMode.MIXED && (mixedFormulaHint == null || mixedFormulaHint.isBlank())) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "MIXED 模式必须填写计算公式说明 (mixedFormulaHint)",
                    "示例: \"基础工时 + 80% 计件提成\"", "warning");
        }
        // 转换 raw map → entity (向后兼容 service signature).
        WagePolicy policy = WagePolicy.builder()
                .id(body.get("id") == null ? null : Long.valueOf(body.get("id").toString()))
                .factoryId(factoryId)
                .employeeId(body.get("employeeId") == null
                        ? null : Long.valueOf(body.get("employeeId").toString()))
                .mode(mode)
                .mixedFormulaHint(mixedFormulaHint)
                .isActive(body.get("isActive") == null
                        ? Boolean.TRUE : Boolean.valueOf(body.get("isActive").toString()))
                .notes(body.get("notes") == null ? null : body.get("notes").toString())
                .build();
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
     *
     * <p>Bug #4 fix (2026-05-20, prod QA, sister to savePolicy): 用 Map raw JSON 检测
     * 字段存在性 (Rule 17.1 反模式 mitigation). 历史上靠 Service 内 baseHourlyRate&gt;0
     * 抛 IllegalArgumentException 兜底, 但 employeeId/effectiveFrom 缺失会先引发 SQL
     * constraint violation (NOT NULL) → 500 而非 400.
     */
    @PostMapping("/hourly-rules")
    @Operation(summary = "创建或更新 HourlyRateRule (Rule 1 server-side ¥500 guard)")
    @RequirePermission({"hr:read_write"})
    public ApiResponse<HourlyRateRule> saveHourlyRateRule(
            @PathVariable String factoryId,
            @RequestBody(required = false) Map<String, Object> body) {
        // Bug #4: 拒绝 null body / 空 {} POST.
        if (body == null || body.isEmpty()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "请求体不能为空",
                    "请填写时薪规则必填字段 (employeeId, baseHourlyRate, effectiveFrom)", "warning");
        }
        // 必填字段 (per entity @Column nullable=false): employeeId, baseHourlyRate, effectiveFrom.
        if (!body.containsKey("employeeId") || body.get("employeeId") == null) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "员工 (employeeId) 不能为空",
                    "请选择适用员工", "warning");
        }
        if (!body.containsKey("effectiveFrom") || body.get("effectiveFrom") == null
                || (body.get("effectiveFrom") instanceof String
                        && ((String) body.get("effectiveFrom")).isBlank())) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "生效日期 (effectiveFrom) 不能为空",
                    "请填写生效日期 (yyyy-MM-dd)", "warning");
        }
        if (!body.containsKey("baseHourlyRate") || body.get("baseHourlyRate") == null) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "基础时薪 (baseHourlyRate) 不能为空",
                    "请填写基础时薪 (元/小时, 范围 ¥10-¥500)", "warning");
        }
        // Parse + build entity. 类型转换 exception (e.g. "abc" 不是日期) → 400 而非 500.
        HourlyRateRule rule;
        try {
            rule = HourlyRateRule.builder()
                    .id(body.get("id") == null ? null : Long.valueOf(body.get("id").toString()))
                    .factoryId(factoryId)
                    .employeeId(Long.valueOf(body.get("employeeId").toString()))
                    .baseHourlyRate(new java.math.BigDecimal(body.get("baseHourlyRate").toString()))
                    .overtimeMultiplier(body.get("overtimeMultiplier") == null
                            ? new java.math.BigDecimal("1.5")
                            : new java.math.BigDecimal(body.get("overtimeMultiplier").toString()))
                    .effectiveFrom(LocalDate.parse(body.get("effectiveFrom").toString()))
                    .effectiveTo(body.get("effectiveTo") == null
                            || (body.get("effectiveTo") instanceof String
                                    && ((String) body.get("effectiveTo")).isBlank())
                            ? null
                            : LocalDate.parse(body.get("effectiveTo").toString()))
                    .isActive(body.get("isActive") == null
                            ? Boolean.TRUE : Boolean.valueOf(body.get("isActive").toString()))
                    .notes(body.get("notes") == null ? null : body.get("notes").toString())
                    .build();
        } catch (NumberFormatException | java.time.format.DateTimeParseException ex) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "字段格式错误: " + ex.getMessage(),
                    "请检查 employeeId(数字) / baseHourlyRate(数字) / effectiveFrom(yyyy-MM-dd)",
                    "warning");
        }
        log.info("[WagePolicyController] saveHourlyRateRule factoryId={}, ruleId={}, baseHourlyRate={}",
                factoryId, rule.getId(), rule.getBaseHourlyRate());
        try {
            HourlyRateRule saved = wagePolicyService.saveHourlyRateRule(factoryId, rule);
            return ApiResponse.success(saved);
        } catch (IllegalArgumentException ex) {
            // 防呆 server-side guard 触发 (e.g. baseHourlyRate > ¥500) → 400 + 含 actionHint message
            log.warn("[WagePolicyController] HourlyRateRule guard rejected: {}", ex.getMessage());
            return ApiResponse.errorWithCode(400, "RULE_VIOLATION",
                    ex.getMessage(), "请核对输入后重试", "warning");
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

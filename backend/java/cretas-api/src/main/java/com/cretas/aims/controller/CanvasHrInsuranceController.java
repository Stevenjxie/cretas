package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.hr.HrInsuranceConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.hr.HrInsuranceConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Canvas-HrInsurance 五险一金费率配置 Canvas controller (Phase BCP3 — 2026-05-22).
 *
 * <p>Wrap 已存的 HrInsuranceConfig entity (#833 follow-up).
 *
 * <p>业务规则:
 *   - factory 最多 1 条 ACTIVE 配置
 *   - 8 个 rate 字段范围 [0, 0.30]
 *   - effectiveFrom 通常 YYYY-MM-01
 *
 * <p>4-in-1 防呆 UX: 所有错误响应携带 actionHint + severity + hintTarget.
 *
 * @since Canvas Phase BCP3 (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-hr-insurance")
@Tag(name = "Canvas-HrInsurance 五险一金费率",
     description = "工厂级 社保 / 公积金 费率配置 (8 个 rate + 缴费基数上下限)")
@RequiredArgsConstructor
public class CanvasHrInsuranceController {

    private static final BigDecimal RATE_MIN = BigDecimal.ZERO;
    private static final BigDecimal RATE_MAX = new BigDecimal("0.30");
    private static final int REMARK_MAX = 500;

    private final HrInsuranceConfigRepository repository;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的所有费率配置 (含 ACTIVE + ARCHIVED, 按 effectiveFrom 倒序)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<HrInsuranceConfig>> list(@PathVariable String factoryId) {
        return ApiResponse.success("查询成功",
                repository.findByFactoryIdOrderByEffectiveFromDesc(factoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个费率配置")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<HrInsuranceConfig> getById(
            @PathVariable String factoryId,
            @PathVariable String id) {
        HrInsuranceConfig c = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "费率配置不存在: " + id)
                        .withHint("请确认 id 拼写")
                        .withSeverity("warning")
                        .withHintTarget("id"));
        return ApiResponse.success("查询成功", c);
    }

    @GetMapping("/active")
    @Operation(summary = "获取当前生效的费率配置 (status=ACTIVE, factory 最多 1 条)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<HrInsuranceConfig> getActive(@PathVariable String factoryId) {
        return repository.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(factoryId, "ACTIVE")
                .map(c -> ApiResponse.success("查询成功", c))
                .orElseThrow(() -> new BusinessException(404,
                        "尚未配置费率, 请先创建 1 条 ACTIVE 配置")
                        .withHint("点击 '新建配置' 进行初始化")
                        .withSeverity("info"));
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "新建费率配置 (会把旧 ACTIVE 自动 ARCHIVE)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<HrInsuranceConfig> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        LocalDate effectiveFrom = parseLocalDate(body, "effectiveFrom", true);
        BigDecimal empPension = parseRate(body, "employeePensionRate", true);
        BigDecimal empPensionR = parseRate(body, "employerPensionRate", true);
        BigDecimal empMedical = parseRate(body, "employeeMedicalRate", true);
        BigDecimal empMedicalR = parseRate(body, "employerMedicalRate", true);
        BigDecimal empUnemp = parseRate(body, "employeeUnemploymentRate", true);
        BigDecimal empUnempR = parseRate(body, "employerUnemploymentRate", true);
        BigDecimal empPf = parseRate(body, "employeeProvidentFundRate", true);
        BigDecimal empPfR = parseRate(body, "employerProvidentFundRate", true);
        BigDecimal lowerBound = parseAmount(body, "baseSalaryLowerBound", false);
        BigDecimal upperBound = parseAmount(body, "baseSalaryUpperBound", false);
        String remark = asString(body.get("remark"));
        validateRemark(remark);

        if (lowerBound != null && upperBound != null && lowerBound.compareTo(upperBound) > 0) {
            throw new BusinessException(400,
                    "缴费基数下限 (" + lowerBound + ") 不能高于上限 (" + upperBound + ")")
                    .withHint("请调整基数范围")
                    .withSeverity("warning")
                    .withHintTarget("baseSalaryLowerBound");
        }

        // 旧 ACTIVE 自动改 ARCHIVED (R4 防呆: factory 最多 1 条 ACTIVE)
        repository.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(factoryId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    repository.save(old);
                });

        HrInsuranceConfig c = HrInsuranceConfig.builder()
                .factoryId(factoryId)
                .employeePensionRate(empPension)
                .employerPensionRate(empPensionR)
                .employeeMedicalRate(empMedical)
                .employerMedicalRate(empMedicalR)
                .employeeUnemploymentRate(empUnemp)
                .employerUnemploymentRate(empUnempR)
                .employeeProvidentFundRate(empPf)
                .employerProvidentFundRate(empPfR)
                .baseSalaryLowerBound(lowerBound)
                .baseSalaryUpperBound(upperBound)
                .effectiveFrom(effectiveFrom)
                .status("ACTIVE")
                .remark(remark)
                .build();
        HrInsuranceConfig saved = repository.save(c);
        log.info("create hr-insurance config: factoryId={}, effectiveFrom={}, id={}",
                factoryId, effectiveFrom, saved.getId());
        return ApiResponse.success("费率配置已创建", saved);
    }

    // ==================== Update (PATCH semantics) ====================

    @PutMapping("/{id}")
    @Operation(summary = "修改费率配置 (PATCH, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<HrInsuranceConfig> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        HrInsuranceConfig c = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "费率配置不存在: " + id));

        // AUD-4 P1: explicit optimistic-lock check (body key "version" → optLockVersion)
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(c.getOptLockVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + c.getOptLockVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        applyRateIfPresent(body, "employeePensionRate", c::setEmployeePensionRate);
        applyRateIfPresent(body, "employerPensionRate", c::setEmployerPensionRate);
        applyRateIfPresent(body, "employeeMedicalRate", c::setEmployeeMedicalRate);
        applyRateIfPresent(body, "employerMedicalRate", c::setEmployerMedicalRate);
        applyRateIfPresent(body, "employeeUnemploymentRate", c::setEmployeeUnemploymentRate);
        applyRateIfPresent(body, "employerUnemploymentRate", c::setEmployerUnemploymentRate);
        applyRateIfPresent(body, "employeeProvidentFundRate", c::setEmployeeProvidentFundRate);
        applyRateIfPresent(body, "employerProvidentFundRate", c::setEmployerProvidentFundRate);

        if (body.containsKey("baseSalaryLowerBound")) {
            c.setBaseSalaryLowerBound(parseAmount(body, "baseSalaryLowerBound", false));
        }
        if (body.containsKey("baseSalaryUpperBound")) {
            c.setBaseSalaryUpperBound(parseAmount(body, "baseSalaryUpperBound", false));
        }
        if (c.getBaseSalaryLowerBound() != null && c.getBaseSalaryUpperBound() != null
                && c.getBaseSalaryLowerBound().compareTo(c.getBaseSalaryUpperBound()) > 0) {
            throw new BusinessException(400,
                    "缴费基数下限 (" + c.getBaseSalaryLowerBound()
                            + ") 不能高于上限 (" + c.getBaseSalaryUpperBound() + ")")
                    .withHint("请调整基数范围")
                    .withSeverity("warning")
                    .withHintTarget("baseSalaryLowerBound");
        }
        if (body.containsKey("effectiveFrom")) {
            c.setEffectiveFrom(parseLocalDate(body, "effectiveFrom", true));
        }
        if (body.containsKey("remark")) {
            String remark = asString(body.get("remark"));
            validateRemark(remark);
            c.setRemark(remark);
        }
        if (body.containsKey("status")) {
            String s = asString(body.get("status"));
            if (s != null && !List.of("ACTIVE", "ARCHIVED").contains(s)) {
                throw new BusinessException(400,
                        "status 仅支持 ACTIVE / ARCHIVED (当前 " + s + ")")
                        .withSeverity("warning").withHintTarget("status");
            }
            c.setStatus(s);
        }

        HrInsuranceConfig saved = repository.saveAndFlush(c);
        log.info("update hr-insurance config: factoryId={}, id={}", factoryId, id);
        return ApiResponse.success("费率配置已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除费率配置 (不可删除 ACTIVE)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        HrInsuranceConfig c = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "费率配置不存在: " + id));
        if ("ACTIVE".equals(c.getStatus())) {
            throw new BusinessException(400,
                    "不能删除当前生效的配置 (status=ACTIVE)")
                    .withHint("请先新建另一条 ACTIVE 配置, 旧的会自动 ARCHIVED")
                    .withSeverity("warning");
        }
        c.softDelete();
        repository.save(c);
        log.info("delete hr-insurance config: factoryId={}, id={}", factoryId, id);
        return ApiResponse.success("费率配置已删除", null);
    }

    // ==================== Helpers ====================

    private static void applyRateIfPresent(Map<String, Object> body, String key,
                                           java.util.function.Consumer<BigDecimal> setter) {
        if (body.containsKey(key)) {
            setter.accept(parseRate(body, key, true));
        }
    }

    private static BigDecimal parseRate(Map<String, Object> body, String key, boolean required) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            if (required) {
                throw new BusinessException(400, "费率不能为空: " + key)
                        .withSeverity("warning").withHintTarget(key);
            }
            return null;
        }
        BigDecimal r;
        try {
            r = new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    key + " 不是有效数字: " + v)
                    .withHint("请输入小数 (e.g. 0.08 表示 8%)")
                    .withSeverity("warning").withHintTarget(key);
        }
        if (r.compareTo(RATE_MIN) < 0 || r.compareTo(RATE_MAX) > 0) {
            throw new BusinessException(400,
                    key + " 必须在 [0, 0.30] 之间 (当前 " + r + ")")
                    .withHint("rate 以小数表示, e.g. 0.08 = 8%")
                    .withSeverity("warning").withHintTarget(key);
        }
        return r;
    }

    private static BigDecimal parseAmount(Map<String, Object> body, String key, boolean required) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            if (required) {
                throw new BusinessException(400, "金额不能为空: " + key)
                        .withSeverity("warning").withHintTarget(key);
            }
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(v.toString());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(400, key + " 不能为负数 (当前 " + amount + ")")
                        .withSeverity("warning").withHintTarget(key);
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, key + " 不是有效金额: " + v)
                    .withSeverity("warning").withHintTarget(key);
        }
    }

    private static LocalDate parseLocalDate(Map<String, Object> body, String key, boolean required) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            if (required) {
                throw new BusinessException(400, "日期不能为空: " + key)
                        .withSeverity("warning").withHintTarget(key);
            }
            return null;
        }
        try {
            return LocalDate.parse(v.toString());
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(400,
                    key + " 日期格式错误 (期望 YYYY-MM-DD): " + v)
                    .withHint("e.g. 2026-05-01")
                    .withSeverity("warning").withHintTarget(key);
        }
    }

    private static void validateRemark(String remark) {
        if (remark != null && remark.length() > REMARK_MAX) {
            throw new BusinessException(400,
                    "remark 最长 " + REMARK_MAX + " 字符 (当前 " + remark.length() + ")")
                    .withHint("请精简备注")
                    .withSeverity("warning").withHintTarget("remark");
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v, Long defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

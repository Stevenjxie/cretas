package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.FactoryTempWorker;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.entity.hr.HrInsuranceConfig;
import com.cretas.aims.repository.EncodingRuleRepository;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FactoryTempWorkerRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import com.cretas.aims.repository.hr.HrInsuranceConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas 工厂配置中心 REST 接口 — Phase B (2026-05-22).
 *
 * <p>聚合 6 个 factory-scoped config 实体:
 * <ol>
 *   <li>{@link FactorySchedulingConfig} — 排班权重 / 临时工因子 / 自适应学习</li>
 *   <li>{@link FactoryTempWorker} — 临时工记录</li>
 *   <li>{@link HrInsuranceConfig} — 五险一金费率 (历史版本)</li>
 *   <li>{@link WagePolicy} — 工资模式 (PIECE_RATE/HOURLY/MIXED)</li>
 *   <li>{@link EncodingRule} — 业务单据编号规则</li>
 *   <li>{@link FactorySettings} — 工厂总设置 (AI/通知/工时/...)</li>
 * </ol>
 *
 * <p><b>Path</b>: {@code /api/mobile/{factoryId}/canvas-factory-config}.
 *
 * <p><b>RBAC</b>: 查询 factory_super_admin / permission_admin, 写入仅前两者.
 *
 * <p><b>AUD-4 Optimistic Locking</b>: 所有 PUT/POST 通过 JPA {@code @Version} + {@code saveAndFlush}
 * 防并发覆盖. 客户端 PUT 必须提交完整 body 包含 version 字段.
 *
 * <p><b>4位一体 UX</b>: 错误响应统一含 actionHint + severity, 前端 toast sticky duration 0.
 *
 * @since 2026-05-22 (Canvas Phase B)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-factory-config")
@Tag(name = "Canvas-工厂配置中心", description = "Phase B — 排班/临时工/五险一金/工资/编码/总设置 聚合")
@RequiredArgsConstructor
public class CanvasFactoryConfigController {

    private final FactorySchedulingConfigRepository schedulingRepo;
    private final FactoryTempWorkerRepository tempWorkerRepo;
    private final HrInsuranceConfigRepository insuranceRepo;
    private final WagePolicyRepository wagePolicyRepo;
    private final EncodingRuleRepository encodingRuleRepo;
    private final FactorySettingsRepository factorySettingsRepo;

    private static final List<String> ALLOWED_RESET_CYCLES =
            List.of("DAILY", "MONTHLY", "YEARLY", "NEVER");
    private static final List<String> ALLOWED_WAGE_MODES =
            List.of("PIECE_RATE", "HOURLY", "MIXED");
    private static final List<String> ALLOWED_INSURANCE_STATUS =
            List.of("ACTIVE", "ARCHIVED");

    // ============================================================
    // 0. Overview (aggregate stats for hub landing)
    // ============================================================

    @GetMapping("/overview")
    @Operation(summary = "中心总览", description = "返回 6 个子模块的统计数 (用于 hub 着陆页)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> overview(@PathVariable String factoryId) {
        log.debug("GET /canvas-factory-config/overview factoryId={}", factoryId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schedulingConfigured", schedulingRepo.existsByFactoryId(factoryId));
        data.put("tempWorkerCount", tempWorkerRepo.countActiveTempWorkers(factoryId));
        data.put("insuranceActive", insuranceRepo
                .findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(factoryId, "ACTIVE")
                .isPresent());
        data.put("wagePolicyCount", wagePolicyRepo
                .findByFactoryIdOrderByEmployeeIdAscIdDesc(factoryId).size());
        data.put("encodingRuleCount", encodingRuleRepo.countByFactoryIdAndEnabledTrue(factoryId));
        data.put("factorySettingsConfigured", factorySettingsRepo.existsByFactoryId(factoryId));
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 1. Scheduling Config (排班)
    // ============================================================

    @GetMapping("/scheduling")
    @Operation(summary = "查询排班配置")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> getScheduling(@PathVariable String factoryId) {
        Optional<FactorySchedulingConfig> opt = schedulingRepo.findByFactoryId(factoryId);
        if (opt.isEmpty()) {
            // 返回 default (未保存到 DB)
            FactorySchedulingConfig defaults = FactorySchedulingConfig.createDefault(factoryId);
            return ApiResponse.success("尚未配置, 返回默认值", serializeScheduling(defaults));
        }
        return ApiResponse.success("操作成功", serializeScheduling(opt.get()));
    }

    @PutMapping("/scheduling")
    @Operation(summary = "保存排班配置 (upsert)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateScheduling(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        log.info("PUT /canvas-factory-config/scheduling factoryId={} keys={}",
                factoryId, body.keySet());

        FactorySchedulingConfig config = schedulingRepo.findByFactoryId(factoryId)
                .orElseGet(() -> FactorySchedulingConfig.createDefault(factoryId));

        // AUD-4: version check (only for existing rows)
        if (config.getId() != null) {
            Long submittedVersion = longField(body, "version");
            if (submittedVersion == null) {
                return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                        "缺少 version 字段 (并发控制)",
                        "请刷新页面重新提交", "warning");
            }
            if (!submittedVersion.equals(config.getVersion())) {
                return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                        "配置已被他人修改 (当前 v" + config.getVersion()
                                + ", 提交 v" + submittedVersion + ")",
                        "请刷新页面查看最新值后再保存", "warning");
            }
        }

        applyDoubleField(body, "linucbWeight", config::setLinucbWeight);
        applyDoubleField(body, "fairnessWeight", config::setFairnessWeight);
        applyDoubleField(body, "skillMaintenanceWeight", config::setSkillMaintenanceWeight);
        applyDoubleField(body, "repetitionWeight", config::setRepetitionWeight);
        applyIntegerField(body, "skillDecayDays", config::setSkillDecayDays);
        applyIntegerField(body, "fairnessPeriodDays", config::setFairnessPeriodDays);
        applyIntegerField(body, "repetitionDays", config::setRepetitionDays);
        applyIntegerField(body, "maxConsecutiveDays", config::setMaxConsecutiveDays);
        applyDoubleField(body, "tempWorkerLinucbFactor", config::setTempWorkerLinucbFactor);
        applyDoubleField(body, "tempWorkerFairnessFactor", config::setTempWorkerFairnessFactor);
        applyIntegerField(body, "tempWorkerSkillDecayDays", config::setTempWorkerSkillDecayDays);
        applyIntegerField(body, "tempWorkerThresholdDays", config::setTempWorkerThresholdDays);
        applyIntegerField(body, "tempWorkerMinAssignments", config::setTempWorkerMinAssignments);
        applyBooleanField(body, "enabled", config::setEnabled);
        applyBooleanField(body, "diversityEnabled", config::setDiversityEnabled);
        applyBooleanField(body, "adaptiveLearningEnabled", config::setAdaptiveLearningEnabled);
        applyDoubleField(body, "learningRate", config::setLearningRate);

        try {
            FactorySchedulingConfig saved = schedulingRepo.saveAndFlush(config);
            return ApiResponse.success("保存成功", serializeScheduling(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            log.warn("Optimistic lock failure on scheduling save factoryId={}", factoryId);
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    // ============================================================
    // 2. Temp Workers (临时工)
    // ============================================================

    @GetMapping("/temp-workers")
    @Operation(summary = "列出临时工")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<Map<String, Object>>> listTempWorkers(@PathVariable String factoryId) {
        List<FactoryTempWorker> workers = tempWorkerRepo.findByFactoryIdAndIsTempWorkerTrue(factoryId);
        List<Map<String, Object>> data = new ArrayList<>(workers.size());
        for (FactoryTempWorker w : workers) {
            data.add(serializeTempWorker(w));
        }
        return ApiResponse.success("操作成功", data);
    }

    @PostMapping("/temp-workers")
    @Operation(summary = "新增临时工")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createTempWorker(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        Long workerId = longField(body, "workerId");
        if (workerId == null) {
            return ApiResponse.errorWithHint(400, "workerId 必填",
                    "请提供员工 ID", null, null);
        }
        Optional<FactoryTempWorker> existing =
                tempWorkerRepo.findByFactoryIdAndWorkerId(factoryId, workerId);
        if (existing.isPresent()) {
            return ApiResponse.errorWithHint(409, "员工已是临时工 (workerId=" + workerId + ")",
                    "请前往详情页编辑现有记录", null, null);
        }

        FactoryTempWorker w = new FactoryTempWorker();
        w.setFactoryId(factoryId);
        w.setWorkerId(workerId);
        w.setIsTempWorker(true);
        String hireDateStr = stringField(body, "hireDate");
        try {
            w.setHireDate(hireDateStr != null ? LocalDate.parse(hireDateStr) : LocalDate.now());
        } catch (Exception ex) {
            return ApiResponse.errorWithHint(400, "hireDate 格式错误",
                    "请使用 YYYY-MM-DD", null, null);
        }
        String expectedEnd = stringField(body, "expectedEndDate");
        if (expectedEnd != null && !expectedEnd.isBlank()) {
            try {
                w.setExpectedEndDate(LocalDate.parse(expectedEnd));
            } catch (Exception ex) {
                return ApiResponse.errorWithHint(400, "expectedEndDate 格式错误",
                        "请使用 YYYY-MM-DD", null, null);
            }
        }
        applyIntegerField(body, "initialSkillLevel", w::setInitialSkillLevel);

        FactoryTempWorker saved = tempWorkerRepo.saveAndFlush(w);
        return ApiResponse.success("创建成功", serializeTempWorker(saved));
    }

    @PutMapping("/temp-workers/{id}")
    @Operation(summary = "更新临时工记录")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateTempWorker(
            @PathVariable String factoryId, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Optional<FactoryTempWorker> opt = tempWorkerRepo.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "记录不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        FactoryTempWorker w = opt.get();

        Long submittedVersion = longField(body, "version");
        if (submittedVersion == null) {
            return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                    "缺少 version 字段", "请刷新页面后再提交", "warning");
        }
        if (!submittedVersion.equals(w.getVersion())) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "记录已被他人修改", "请刷新后再提交", "warning");
        }

        applyBooleanField(body, "convertedToPermanent", w::setConvertedToPermanent);
        if (body.containsKey("conversionDate")) {
            String d = stringField(body, "conversionDate");
            if (d != null && !d.isBlank()) {
                try {
                    w.setConversionDate(LocalDate.parse(d));
                } catch (Exception ex) {
                    return ApiResponse.errorWithHint(400, "conversionDate 格式错误",
                            "请使用 YYYY-MM-DD", null, null);
                }
            }
        }
        applyIntegerField(body, "currentSkillLevel", w::setCurrentSkillLevel);
        applyDoubleField(body, "skillGrowthRate", w::setSkillGrowthRate);
        applyDoubleField(body, "avgEfficiency", w::setAvgEfficiency);
        applyDoubleField(body, "reliabilityScore", w::setReliabilityScore);

        try {
            FactoryTempWorker saved = tempWorkerRepo.saveAndFlush(w);
            return ApiResponse.success("更新成功", serializeTempWorker(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    // ============================================================
    // 3. HR Insurance Config (五险一金)
    // ============================================================

    @GetMapping("/insurance")
    @Operation(summary = "查询五险一金配置 (历史 + 当前)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> listInsurance(@PathVariable String factoryId) {
        List<HrInsuranceConfig> rows = insuranceRepo.findByFactoryIdOrderByEffectiveFromDesc(factoryId);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (HrInsuranceConfig c : rows) {
            data.add(serializeInsurance(c));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("history", data);
        insuranceRepo.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(factoryId, "ACTIVE")
                .ifPresent(active -> response.put("active", serializeInsurance(active)));
        return ApiResponse.success("操作成功", response);
    }

    @PostMapping("/insurance")
    @Operation(summary = "新增费率版本 (老 ACTIVE 自动归档)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createInsurance(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        String effectiveFromStr = stringField(body, "effectiveFrom");
        if (effectiveFromStr == null || effectiveFromStr.isBlank()) {
            return ApiResponse.errorWithHint(400, "effectiveFrom 必填",
                    "请提供生效起始月 (YYYY-MM-DD)", null, null);
        }
        LocalDate effectiveFrom;
        try {
            effectiveFrom = LocalDate.parse(effectiveFromStr);
        } catch (Exception ex) {
            return ApiResponse.errorWithHint(400, "effectiveFrom 格式错误",
                    "请使用 YYYY-MM-DD", null, null);
        }

        // 老 ACTIVE → ARCHIVED
        insuranceRepo.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc(factoryId, "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("ARCHIVED");
                    insuranceRepo.saveAndFlush(old);
                });

        HrInsuranceConfig c = HrInsuranceConfig.builder()
                .factoryId(factoryId)
                .effectiveFrom(effectiveFrom)
                .status("ACTIVE")
                .employeePensionRate(bigDecimalField(body, "employeePensionRate", "0.0800"))
                .employerPensionRate(bigDecimalField(body, "employerPensionRate", "0.1600"))
                .employeeMedicalRate(bigDecimalField(body, "employeeMedicalRate", "0.0200"))
                .employerMedicalRate(bigDecimalField(body, "employerMedicalRate", "0.0800"))
                .employeeUnemploymentRate(bigDecimalField(body, "employeeUnemploymentRate", "0.0050"))
                .employerUnemploymentRate(bigDecimalField(body, "employerUnemploymentRate", "0.0050"))
                .employeeProvidentFundRate(bigDecimalField(body, "employeeProvidentFundRate", "0.0800"))
                .employerProvidentFundRate(bigDecimalField(body, "employerProvidentFundRate", "0.0800"))
                .remark(stringField(body, "remark"))
                .build();

        Object lower = body.get("baseSalaryLowerBound");
        if (lower instanceof Number n) {
            c.setBaseSalaryLowerBound(new BigDecimal(n.toString()));
        }
        Object upper = body.get("baseSalaryUpperBound");
        if (upper instanceof Number n) {
            c.setBaseSalaryUpperBound(new BigDecimal(n.toString()));
        }

        HrInsuranceConfig saved = insuranceRepo.saveAndFlush(c);
        return ApiResponse.success("已新增 ACTIVE 费率, 老配置归档", serializeInsurance(saved));
    }

    // ============================================================
    // 4. Wage Policies (工资模式)
    // ============================================================

    @GetMapping("/wage-policies")
    @Operation(summary = "列出工资策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<Map<String, Object>>> listWagePolicies(@PathVariable String factoryId) {
        List<WagePolicy> rows = wagePolicyRepo.findByFactoryIdOrderByEmployeeIdAscIdDesc(factoryId);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (WagePolicy p : rows) {
            data.add(serializeWagePolicy(p));
        }
        return ApiResponse.success("操作成功", data);
    }

    @PostMapping("/wage-policies")
    @Operation(summary = "新增工资策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createWagePolicy(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        String modeStr = stringField(body, "mode");
        if (modeStr == null || !ALLOWED_WAGE_MODES.contains(modeStr.toUpperCase())) {
            return ApiResponse.errorWithHint(400, "mode 不合法",
                    "允许值: " + String.join(" / ", ALLOWED_WAGE_MODES), null, null);
        }
        WagePolicy p = WagePolicy.builder()
                .factoryId(factoryId)
                .employeeId(longField(body, "employeeId"))
                .mode(WageMode.valueOf(modeStr.toUpperCase()))
                .mixedFormulaHint(stringField(body, "mixedFormulaHint"))
                .isActive(true)
                .notes(stringField(body, "notes"))
                .build();
        WagePolicy saved = wagePolicyRepo.saveAndFlush(p);
        return ApiResponse.success("创建成功", serializeWagePolicy(saved));
    }

    @PutMapping("/wage-policies/{id}")
    @Operation(summary = "更新工资策略")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateWagePolicy(
            @PathVariable String factoryId, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Optional<WagePolicy> opt = wagePolicyRepo.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "策略不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        WagePolicy p = opt.get();
        Long submittedVersion = longField(body, "version");
        if (submittedVersion == null) {
            return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                    "缺少 version 字段", "请刷新页面后再提交", "warning");
        }
        if (!submittedVersion.equals(p.getVersion())) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "策略已被他人修改", "请刷新后再提交", "warning");
        }

        if (body.containsKey("mode")) {
            String modeStr = stringField(body, "mode");
            if (modeStr == null || !ALLOWED_WAGE_MODES.contains(modeStr.toUpperCase())) {
                return ApiResponse.errorWithHint(400, "mode 不合法",
                        "允许值: " + String.join(" / ", ALLOWED_WAGE_MODES), null, null);
            }
            p.setMode(WageMode.valueOf(modeStr.toUpperCase()));
        }
        if (body.containsKey("mixedFormulaHint")) {
            p.setMixedFormulaHint(stringField(body, "mixedFormulaHint"));
        }
        applyBooleanField(body, "isActive", p::setIsActive);
        if (body.containsKey("notes")) {
            p.setNotes(stringField(body, "notes"));
        }

        try {
            WagePolicy saved = wagePolicyRepo.saveAndFlush(p);
            return ApiResponse.success("更新成功", serializeWagePolicy(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    // ============================================================
    // 5. Encoding Rules (编码规则)
    // ============================================================

    @GetMapping("/encoding-rules")
    @Operation(summary = "列出编码规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<Map<String, Object>>> listEncodingRules(@PathVariable String factoryId) {
        List<EncodingRule> rows = encodingRuleRepo.findByFactoryIdAndEnabledTrue(factoryId);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (EncodingRule r : rows) {
            data.add(serializeEncodingRule(r));
        }
        return ApiResponse.success("操作成功", data);
    }

    @PostMapping("/encoding-rules")
    @Operation(summary = "新增编码规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createEncodingRule(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        String entityType = stringField(body, "entityType");
        String ruleName = stringField(body, "ruleName");
        String pattern = stringField(body, "encodingPattern");
        if (entityType == null || entityType.isBlank()
                || ruleName == null || ruleName.isBlank()
                || pattern == null || pattern.isBlank()) {
            return ApiResponse.errorWithHint(400, "entityType / ruleName / encodingPattern 均必填",
                    "请补全必填字段", null, null);
        }
        if (encodingRuleRepo.existsByFactoryIdAndEntityType(factoryId, entityType)) {
            return ApiResponse.errorWithHint(409,
                    "该 entityType 已存在编码规则: " + entityType,
                    "请前往编辑现有规则", null, null);
        }
        String resetCycle = stringField(body, "resetCycle");
        if (resetCycle != null && !ALLOWED_RESET_CYCLES.contains(resetCycle.toUpperCase())) {
            return ApiResponse.errorWithHint(400, "resetCycle 不合法",
                    "允许值: " + String.join(" / ", ALLOWED_RESET_CYCLES), null, null);
        }

        EncodingRule r = EncodingRule.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .entityType(entityType)
                .ruleName(ruleName)
                .ruleDescription(stringField(body, "ruleDescription"))
                .encodingPattern(pattern)
                .prefix(stringField(body, "prefix"))
                .dateFormat(stringField(body, "dateFormat"))
                .resetCycle(resetCycle != null ? resetCycle.toUpperCase() : "DAILY")
                .separator(Objects.requireNonNullElse(stringField(body, "separator"), "-"))
                .includeFactoryCode(booleanField(body, "includeFactoryCode", true))
                .enabled(true)
                .build();
        Integer seqLen = intField(body, "sequenceLength");
        if (seqLen != null) r.setSequenceLength(seqLen);

        EncodingRule saved = encodingRuleRepo.saveAndFlush(r);
        return ApiResponse.success("创建成功", serializeEncodingRule(saved));
    }

    @PutMapping("/encoding-rules/{id}")
    @Operation(summary = "更新编码规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateEncodingRule(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<EncodingRule> opt = encodingRuleRepo.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "编码规则不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        EncodingRule r = opt.get();

        Long submittedLockVersion = longField(body, "lockVersion");
        if (submittedLockVersion == null) {
            return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                    "缺少 lockVersion 字段", "请刷新页面后再提交", "warning");
        }
        if (!submittedLockVersion.equals(r.getOptLockVersion())) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "规则已被他人修改", "请刷新后再提交", "warning");
        }

        if (body.containsKey("ruleName")) r.setRuleName(stringField(body, "ruleName"));
        if (body.containsKey("ruleDescription")) {
            r.setRuleDescription(stringField(body, "ruleDescription"));
        }
        if (body.containsKey("encodingPattern")) {
            r.setEncodingPattern(stringField(body, "encodingPattern"));
        }
        if (body.containsKey("prefix")) r.setPrefix(stringField(body, "prefix"));
        if (body.containsKey("dateFormat")) r.setDateFormat(stringField(body, "dateFormat"));
        if (body.containsKey("resetCycle")) {
            String rc = stringField(body, "resetCycle");
            if (rc == null || !ALLOWED_RESET_CYCLES.contains(rc.toUpperCase())) {
                return ApiResponse.errorWithHint(400, "resetCycle 不合法",
                        "允许值: " + String.join(" / ", ALLOWED_RESET_CYCLES), null, null);
            }
            r.setResetCycle(rc.toUpperCase());
        }
        applyIntegerField(body, "sequenceLength", r::setSequenceLength);
        if (body.containsKey("separator")) r.setSeparator(stringField(body, "separator"));
        applyBooleanField(body, "includeFactoryCode", r::setIncludeFactoryCode);
        applyBooleanField(body, "enabled", r::setEnabled);

        // 业务版本递增
        r.incrementVersion();

        try {
            EncodingRule saved = encodingRuleRepo.saveAndFlush(r);
            return ApiResponse.success("更新成功", serializeEncodingRule(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    // ============================================================
    // 6. Factory Settings (工厂总设置)
    // ============================================================

    @GetMapping("/settings")
    @Operation(summary = "查询工厂总设置")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> getSettings(@PathVariable String factoryId) {
        Optional<FactorySettings> opt = factorySettingsRepo.findByFactoryId(factoryId);
        if (opt.isEmpty()) {
            return ApiResponse.success("尚未配置, 返回默认 stub",
                    Map.of("factoryId", factoryId,
                            "language", "zh-CN",
                            "timezone", "Asia/Shanghai",
                            "currency", "CNY",
                            "skipProcessReportingDefault", false,
                            "version", 0));
        }
        return ApiResponse.success("操作成功", serializeFactorySettings(opt.get()));
    }

    @PutMapping("/settings")
    @Operation(summary = "更新工厂总设置 (upsert)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateSettings(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        FactorySettings settings = factorySettingsRepo.findByFactoryId(factoryId)
                .orElseGet(() -> {
                    FactorySettings s = new FactorySettings();
                    s.setFactoryId(factoryId);
                    return s;
                });

        if (settings.getId() != null) {
            Long submittedVersion = longField(body, "version");
            if (submittedVersion == null) {
                return ApiResponse.errorWithCode(400, "VERSION_MISSING",
                        "缺少 version 字段", "请刷新页面后再提交", "warning");
            }
            if (!submittedVersion.equals(settings.getVersion())) {
                return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                        "设置已被他人修改", "请刷新后再提交", "warning");
            }
        }

        if (body.containsKey("factoryName")) settings.setFactoryName(stringField(body, "factoryName"));
        if (body.containsKey("factoryAddress")) settings.setFactoryAddress(stringField(body, "factoryAddress"));
        if (body.containsKey("contactPhone")) settings.setContactPhone(stringField(body, "contactPhone"));
        if (body.containsKey("contactEmail")) settings.setContactEmail(stringField(body, "contactEmail"));
        if (body.containsKey("workingHours") && body.get("workingHours") instanceof Number n) {
            settings.setWorkingHours(n.intValue());
        }
        if (body.containsKey("language")) settings.setLanguage(stringField(body, "language"));
        if (body.containsKey("timezone")) settings.setTimezone(stringField(body, "timezone"));
        if (body.containsKey("currency")) settings.setCurrency(stringField(body, "currency"));
        if (body.containsKey("dateFormat")) settings.setDateFormat(stringField(body, "dateFormat"));
        if (body.containsKey("aiSettings")) settings.setAiSettings(stringField(body, "aiSettings"));
        if (body.containsKey("notificationSettings")) {
            settings.setNotificationSettings(stringField(body, "notificationSettings"));
        }
        applyBooleanField(body, "enableQrCode", settings::setEnableQrCode);
        applyBooleanField(body, "enableBatchManagement", settings::setEnableBatchManagement);
        applyBooleanField(body, "enableQualityCheck", settings::setEnableQualityCheck);
        applyBooleanField(body, "enableCostCalculation", settings::setEnableCostCalculation);
        applyBooleanField(body, "enableEquipmentManagement", settings::setEnableEquipmentManagement);
        applyBooleanField(body, "enableAttendance", settings::setEnableAttendance);
        applyBooleanField(body, "allowSelfRegistration", settings::setAllowSelfRegistration);
        applyBooleanField(body, "requireAdminApproval", settings::setRequireAdminApproval);
        if (body.containsKey("skipProcessReportingDefault")) {
            settings.setSkipProcessReportingDefault(booleanField(body, "skipProcessReportingDefault", false));
        }
        if (body.containsKey("defaultUserRole")) {
            settings.setDefaultUserRole(stringField(body, "defaultUserRole"));
        }

        settings.setLastModifiedAt(LocalDateTime.now());

        try {
            FactorySettings saved = factorySettingsRepo.saveAndFlush(settings);
            return ApiResponse.success("保存成功", serializeFactorySettings(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            return ApiResponse.errorWithCode(409, "VERSION_CONFLICT",
                    "并发保存冲突", "请刷新后再试", "warning");
        }
    }

    // ============================================================
    // Serialization helpers
    // ============================================================

    private Map<String, Object> serializeScheduling(FactorySchedulingConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("factoryId", c.getFactoryId());
        m.put("enabled", c.getEnabled());
        m.put("diversityEnabled", c.getDiversityEnabled());
        m.put("linucbWeight", c.getLinucbWeight());
        m.put("fairnessWeight", c.getFairnessWeight());
        m.put("skillMaintenanceWeight", c.getSkillMaintenanceWeight());
        m.put("repetitionWeight", c.getRepetitionWeight());
        m.put("skillDecayDays", c.getSkillDecayDays());
        m.put("fairnessPeriodDays", c.getFairnessPeriodDays());
        m.put("repetitionDays", c.getRepetitionDays());
        m.put("maxConsecutiveDays", c.getMaxConsecutiveDays());
        m.put("tempWorkerLinucbFactor", c.getTempWorkerLinucbFactor());
        m.put("tempWorkerFairnessFactor", c.getTempWorkerFairnessFactor());
        m.put("tempWorkerSkillDecayDays", c.getTempWorkerSkillDecayDays());
        m.put("tempWorkerThresholdDays", c.getTempWorkerThresholdDays());
        m.put("tempWorkerMinAssignments", c.getTempWorkerMinAssignments());
        m.put("adaptiveLearningEnabled", c.getAdaptiveLearningEnabled());
        m.put("learningRate", c.getLearningRate());
        m.put("efficiencyTarget", c.getEfficiencyTarget());
        m.put("diversityTarget", c.getDiversityTarget());
        m.put("strategyWeights", c.getStrategyWeightsMap());
        m.put("version", c.getVersion());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private Map<String, Object> serializeTempWorker(FactoryTempWorker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("factoryId", w.getFactoryId());
        m.put("workerId", w.getWorkerId());
        m.put("isTempWorker", w.getIsTempWorker());
        m.put("hireDate", w.getHireDate());
        m.put("expectedEndDate", w.getExpectedEndDate());
        m.put("convertedToPermanent", w.getConvertedToPermanent());
        m.put("conversionDate", w.getConversionDate());
        m.put("initialSkillLevel", w.getInitialSkillLevel());
        m.put("currentSkillLevel", w.getCurrentSkillLevel());
        m.put("skillGrowthRate", w.getSkillGrowthRate());
        m.put("totalAssignments", w.getTotalAssignments());
        m.put("avgEfficiency", w.getAvgEfficiency());
        m.put("reliabilityScore", w.getReliabilityScore());
        m.put("version", w.getVersion());
        m.put("daysEmployed", w.getDaysEmployed());
        return m;
    }

    private Map<String, Object> serializeInsurance(HrInsuranceConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("factoryId", c.getFactoryId());
        m.put("status", c.getStatus());
        m.put("effectiveFrom", c.getEffectiveFrom());
        m.put("employeePensionRate", c.getEmployeePensionRate());
        m.put("employerPensionRate", c.getEmployerPensionRate());
        m.put("employeeMedicalRate", c.getEmployeeMedicalRate());
        m.put("employerMedicalRate", c.getEmployerMedicalRate());
        m.put("employeeUnemploymentRate", c.getEmployeeUnemploymentRate());
        m.put("employerUnemploymentRate", c.getEmployerUnemploymentRate());
        m.put("employeeProvidentFundRate", c.getEmployeeProvidentFundRate());
        m.put("employerProvidentFundRate", c.getEmployerProvidentFundRate());
        m.put("baseSalaryLowerBound", c.getBaseSalaryLowerBound());
        m.put("baseSalaryUpperBound", c.getBaseSalaryUpperBound());
        m.put("remark", c.getRemark());
        // HrInsuranceConfig uses optLockVersion (per P3-batch1 PR #196 schema) — JSON key
        // stays "version" for backwards compat with existing test/clients.
        m.put("version", c.getOptLockVersion());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private Map<String, Object> serializeWagePolicy(WagePolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("factoryId", p.getFactoryId());
        m.put("employeeId", p.getEmployeeId());
        m.put("mode", p.getMode() == null ? null : p.getMode().name());
        m.put("mixedFormulaHint", p.getMixedFormulaHint());
        m.put("isActive", p.getIsActive());
        m.put("notes", p.getNotes());
        m.put("version", p.getVersion());
        return m;
    }

    private Map<String, Object> serializeEncodingRule(EncodingRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("factoryId", r.getFactoryId());
        m.put("entityType", r.getEntityType());
        m.put("ruleName", r.getRuleName());
        m.put("ruleDescription", r.getRuleDescription());
        m.put("encodingPattern", r.getEncodingPattern());
        m.put("prefix", r.getPrefix());
        m.put("dateFormat", r.getDateFormat());
        m.put("sequenceLength", r.getSequenceLength());
        m.put("resetCycle", r.getResetCycle());
        m.put("currentSequence", r.getCurrentSequence());
        m.put("lastResetDate", r.getLastResetDate());
        m.put("separator", r.getSeparator());
        m.put("includeFactoryCode", r.getIncludeFactoryCode());
        m.put("enabled", r.getEnabled());
        m.put("version", r.getVersion());        // business version
        m.put("lockVersion", r.getOptLockVersion()); // AUD-4 optimistic lock (entity field: optLockVersion, JSON contract: lockVersion)
        return m;
    }

    private Map<String, Object> serializeFactorySettings(FactorySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("factoryId", s.getFactoryId());
        m.put("factoryName", s.getFactoryName());
        m.put("factoryAddress", s.getFactoryAddress());
        m.put("contactPhone", s.getContactPhone());
        m.put("contactEmail", s.getContactEmail());
        m.put("workingHours", s.getWorkingHours());
        m.put("language", s.getLanguage());
        m.put("timezone", s.getTimezone());
        m.put("currency", s.getCurrency());
        m.put("dateFormat", s.getDateFormat());
        m.put("aiSettings", s.getAiSettings());
        m.put("aiWeeklyQuota", s.getAiWeeklyQuota());
        m.put("notificationSettings", s.getNotificationSettings());
        m.put("enableQrCode", s.getEnableQrCode());
        m.put("enableBatchManagement", s.getEnableBatchManagement());
        m.put("enableQualityCheck", s.getEnableQualityCheck());
        m.put("enableCostCalculation", s.getEnableCostCalculation());
        m.put("enableEquipmentManagement", s.getEnableEquipmentManagement());
        m.put("enableAttendance", s.getEnableAttendance());
        m.put("allowSelfRegistration", s.getAllowSelfRegistration());
        m.put("requireAdminApproval", s.getRequireAdminApproval());
        m.put("skipProcessReportingDefault", s.getSkipProcessReportingDefault());
        m.put("defaultUserRole", s.getDefaultUserRole());
        m.put("lastModifiedAt", s.getLastModifiedAt());
        m.put("version", s.getVersion());
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

    private static Integer intField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
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

    private static BigDecimal bigDecimalField(Map<String, Object> body, String key, String defaultValue) {
        Object v = body.get(key);
        if (v == null) return new BigDecimal(defaultValue);
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString());
        } catch (Exception ex) {
            return new BigDecimal(defaultValue);
        }
    }

    private static void applyDoubleField(Map<String, Object> body, String key,
                                         java.util.function.Consumer<Double> setter) {
        if (!body.containsKey(key)) return;
        Object v = body.get(key);
        if (v instanceof Number n) setter.accept(n.doubleValue());
    }

    private static void applyIntegerField(Map<String, Object> body, String key,
                                          java.util.function.Consumer<Integer> setter) {
        if (!body.containsKey(key)) return;
        Object v = body.get(key);
        if (v instanceof Number n) setter.accept(n.intValue());
    }

    private static void applyBooleanField(Map<String, Object> body, String key,
                                          java.util.function.Consumer<Boolean> setter) {
        if (!body.containsKey(key)) return;
        Object v = body.get(key);
        if (v instanceof Boolean b) {
            setter.accept(b);
        } else if (v != null) {
            setter.accept(Boolean.parseBoolean(v.toString()));
        }
    }
}

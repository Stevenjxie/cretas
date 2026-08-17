package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canvas-FoodSafety REST API (Phase A Tab 2 — Food Safety Hub).
 *
 * <p>Wraps 5 already-shipped Sprint 8 P3 Phase A entities into a unified
 * configuration UI accessed via Canvas Editor:
 * <ul>
 *   <li>{@link HaccpCheckpoint} — HACCP 关键控制点 (full CRUD)</li>
 *   <li>{@link HaccpMonitoringRecord} — HACCP 监控记录 (list + create)</li>
 *   <li>{@link AdditiveLimit} — GB 2760 添加剂限量库 (read-only)</li>
 *   <li>{@link RecallEvent} — 食品召回事件 (full CRUD with status workflow)</li>
 *   <li>{@link RecallAction} — 召回行动 (list + create per event)</li>
 * </ul>
 *
 * <p>3 additional sub-tabs (留样追踪 / 营养标签 / 供应商资质 / 冷链 / SSOP)
 * are placeholders — their entities live in Sprint 9 branches (P2.A/B/C/D/E)
 * not yet merged to main. UI shows "敬请期待" with link to indicator-center
 * (fool-proof Rule 5: next-action).
 *
 * <p>Role gate: factory_super_admin / permission_admin (config-tier change).
 *
 * <p>AUD-4: HaccpCheckpoint + RecallEvent have @Version (Flyway V20260823_02);
 * PUT endpoints support optimistic locking via {@code body.version} field.
 *
 * <p>Spec: Canvas Phase A Tab 2 brief — Food Safety Hub.
 *
 * @author Cretas Team (Canvas Phase A subagent #2)
 * @version 1.0.0
 * @since 2026-05-21
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-food-safety")
@RequiredArgsConstructor
@Tag(name = "Canvas-FoodSafety", description = "Canvas 食品安全中心 (Phase A Tab 2)")
public class CanvasFoodSafetyController {

    private final HaccpCheckpointRepository haccpCheckpointRepo;
    private final HaccpMonitoringRecordRepository haccpMonitoringRepo;
    private final AdditiveLimitRepository additiveLimitRepo;
    private final RecallEventRepository recallEventRepo;
    private final RecallActionRepository recallActionRepo;

    /** Max length for VARCHAR columns — AUD-5 length pre-check. */
    private static final int SHORT_CODE_MAX = 50;
    private static final int NAME_MAX = 100;
    private static final int EVENT_CODE_MAX = 100;
    private static final int LONG_TEXT_MAX = 4000;

    /** 召回已结案 —— 2026-08-02 起不允许从该状态回退, 见 updateRecall。 */
    private static final String RECALL_STATUS_COMPLETED = "COMPLETED";

    private static final List<String> RECALL_STATUSES =
            List.of("INVESTIGATING", "NOTIFYING", "FROZEN", "REPORTED", "COMPLETED");
    private static final List<String> HAZARD_TYPES =
            List.of("BIOLOGICAL", "CHEMICAL", "PHYSICAL");

    // ============================================================
    // 0. Summary (dashboard counts for all sub-tabs)
    // ============================================================

    /**
     * Aggregate counts across all 5 wrapped entities — Food Safety Hub landing page.
     * Used by Vue {@code SubTabBadge} to render counts next to each sub-tab label.
     */
    @Operation(summary = "食品安全中心 — 各子页计数概览",
               description = "返回 5 个 wrapped entity 的当前条数, 用于 Canvas Tab 徽章")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable String factoryId) {
        log.debug("GET /canvas-food-safety/summary factoryId={}", factoryId);

        Map<String, Object> data = new LinkedHashMap<>();
        // Lightweight count queries — repositories have factory-scoped indexes already.
        long activeCheckpoints = haccpCheckpointRepo.findByFactoryIdAndActiveTrue(factoryId).size();
        long deviationRecords = haccpMonitoringRepo.findByFactoryIdAndIsDeviationTrue(factoryId).size();
        long openRecalls = recallEventRepo.findByFactoryIdOrderByTriggerTimeDesc(
                factoryId, org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(r -> !"COMPLETED".equals(r.getStatus())).count();
        long totalAdditives = additiveLimitRepo.count();   // system-wide (no factory_id)

        data.put("haccpCheckpointsActive", activeCheckpoints);
        data.put("haccpDeviations", deviationRecords);
        data.put("recallsOpen", openRecalls);
        data.put("additiveLimitsTotal", totalAdditives);
        // Phase placeholders — sister Sprint 9 branches not yet merged
        data.put("foodSamplePending", 0);
        data.put("nutritionLabelPending", 0);
        data.put("supplierQualPending", 0);
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 1. HACCP Checkpoints — CRUD
    // ============================================================

    @Operation(summary = "列出 HACCP 关键控制点")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/haccp/checkpoints")
    public ApiResponse<List<Map<String, Object>>> listCheckpoints(
            @PathVariable String factoryId,
            @RequestParam(required = false) Boolean activeOnly) {
        log.debug("GET /haccp/checkpoints factoryId={} activeOnly={}", factoryId, activeOnly);
        List<HaccpCheckpoint> rows = Boolean.TRUE.equals(activeOnly)
                ? haccpCheckpointRepo.findByFactoryIdAndActiveTrue(factoryId)
                : haccpCheckpointRepo.findAll().stream()
                        .filter(c -> factoryId.equals(c.getFactoryId()))
                        .toList();
        List<Map<String, Object>> data = new ArrayList<>();
        for (HaccpCheckpoint c : rows) data.add(serializeCheckpoint(c));
        return ApiResponse.success("操作成功", data);
    }

    @Operation(summary = "新建 HACCP 关键控制点")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/haccp/checkpoints")
    @Transactional
    public ApiResponse<Map<String, Object>> createCheckpoint(@PathVariable String factoryId,
                                                              @RequestBody Map<String, Object> body) {
        log.info("POST /haccp/checkpoints factoryId={} keys={}", factoryId, body.keySet());

        // 1. Required-field + length validation (4位一体 specific error + actionHint)
        String checkpointCode = stringField(body, "checkpointCode");
        ApiResponse<Map<String, Object>> err = requireCode(checkpointCode, "checkpointCode",
                "请填写 CCP 编码 (e.g. CCP-01, factory 内唯一)", SHORT_CODE_MAX);
        if (err != null) return err;

        String name = stringField(body, "name");
        err = requireString(name, "name", "请填写 CCP 名称 (e.g. 中心温度 / 冷却时间)", NAME_MAX);
        if (err != null) return err;

        String hazardType = stringField(body, "hazardType");
        if (hazardType == null || !HAZARD_TYPES.contains(hazardType)) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "hazardType 不合法: " + hazardType,
                    "允许值: " + String.join(" / ", HAZARD_TYPES), "warning");
        }

        BigDecimal min = decimalField(body, "criticalLimitMin");
        BigDecimal max = decimalField(body, "criticalLimitMax");
        if (min == null || max == null) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "criticalLimitMin / criticalLimitMax 必填",
                    "请填写临界限值上下限 (数值, 单位由 unit 字段说明)", "warning");
        }
        if (min.compareTo(max) > 0) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "临界限值下限 (" + min + ") 不能大于上限 (" + max + ")",
                    "请确认上下限顺序", "warning");
        }

        String unit = stringField(body, "unit");
        err = requireString(unit, "unit", "请填写单位 (e.g. ℃ / min / mg/kg)", 20);
        if (err != null) return err;

        // 2. fool-proof Rule 4: idempotent duplicate check
        Optional<HaccpCheckpoint> dup =
                haccpCheckpointRepo.findByFactoryIdAndCheckpointCode(factoryId, checkpointCode);
        if (dup.isPresent()) {
            HaccpCheckpoint existing = dup.get();
            return ApiResponse.errorWithCode(409, "DUPLICATE",
                    "CCP 编码已存在: " + checkpointCode + " (id=" + existing.getId() + ")",
                    "请使用其他编码, 或前往编辑该 CCP", "warning");
        }

        HaccpCheckpoint cp = HaccpCheckpoint.builder()
                .factoryId(factoryId)
                .checkpointCode(checkpointCode)
                .name(name)
                .hazardType(hazardType)
                .description(textField(body, "description"))
                .criticalLimitMin(min)
                .criticalLimitMax(max)
                .unit(unit)
                .monitoringProcedure(textField(body, "monitoringProcedure"))
                .correctiveAction(textField(body, "correctiveAction"))
                .verificationProcedure(textField(body, "verificationProcedure"))
                .recordKeeping(textField(body, "recordKeeping"))
                .active(booleanField(body, "active", true))
                .build();
        HaccpCheckpoint saved = haccpCheckpointRepo.saveAndFlush(cp);
        log.info("Created HaccpCheckpoint id={} factoryId={} code={}",
                saved.getId(), factoryId, checkpointCode);
        return ApiResponse.success("HACCP CCP 创建成功", serializeCheckpoint(saved));
    }

    @Operation(summary = "更新 HACCP 关键控制点 (PATCH 语义)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PutMapping("/haccp/checkpoints/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateCheckpoint(@PathVariable String factoryId,
                                                              @PathVariable Long id,
                                                              @RequestBody Map<String, Object> body) {
        log.info("PUT /haccp/checkpoints/{} factoryId={} keys={}", id, factoryId, body.keySet());

        HaccpCheckpoint cp = loadCheckpoint(factoryId, id);

        // AUD-4 explicit version check (mirrors CanvasRuleController pattern)
        ApiResponse<Map<String, Object>> verErr = checkVersion(body.get("version"), cp.getVersion());
        if (verErr != null) return verErr;

        if (body.containsKey("checkpointCode")) {
            String newCode = stringField(body, "checkpointCode");
            if (newCode != null && !newCode.isBlank() && !newCode.equals(cp.getCheckpointCode())) {
                if (newCode.length() > SHORT_CODE_MAX) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "checkpointCode 最长 " + SHORT_CODE_MAX + " 字符 (当前 " + newCode.length() + ")",
                            "请使用更短的编码", "warning");
                }
                Optional<HaccpCheckpoint> dup =
                        haccpCheckpointRepo.findByFactoryIdAndCheckpointCode(factoryId, newCode);
                if (dup.isPresent() && !dup.get().getId().equals(id)) {
                    return ApiResponse.errorWithCode(409, "DUPLICATE",
                            "CCP 编码已存在: " + newCode, "请使用其他唯一编码", "warning");
                }
                cp.setCheckpointCode(newCode);
            }
        }
        if (body.containsKey("name")) {
            String newName = stringField(body, "name");
            if (newName != null) {
                if (newName.length() > NAME_MAX) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "name 最长 " + NAME_MAX + " 字符 (当前 " + newName.length() + ")",
                            "请使用更短的名称", "warning");
                }
                cp.setName(newName);
            }
        }
        if (body.containsKey("hazardType")) {
            String newType = stringField(body, "hazardType");
            if (newType != null && !HAZARD_TYPES.contains(newType)) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "hazardType 不合法: " + newType,
                        "允许值: " + String.join(" / ", HAZARD_TYPES), "warning");
            }
            if (newType != null) cp.setHazardType(newType);
        }
        if (body.containsKey("description")) cp.setDescription(textField(body, "description"));
        if (body.containsKey("criticalLimitMin")) {
            BigDecimal v = decimalField(body, "criticalLimitMin");
            if (v != null) cp.setCriticalLimitMin(v);
        }
        if (body.containsKey("criticalLimitMax")) {
            BigDecimal v = decimalField(body, "criticalLimitMax");
            if (v != null) cp.setCriticalLimitMax(v);
        }
        // Re-validate min<=max after either change
        if (cp.getCriticalLimitMin() != null && cp.getCriticalLimitMax() != null
                && cp.getCriticalLimitMin().compareTo(cp.getCriticalLimitMax()) > 0) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "临界限值下限 (" + cp.getCriticalLimitMin() + ") 不能大于上限 ("
                            + cp.getCriticalLimitMax() + ")",
                    "请确认上下限顺序", "warning");
        }
        if (body.containsKey("unit")) {
            String newUnit = stringField(body, "unit");
            if (newUnit != null) cp.setUnit(newUnit);
        }
        if (body.containsKey("monitoringProcedure")) cp.setMonitoringProcedure(textField(body, "monitoringProcedure"));
        if (body.containsKey("correctiveAction")) cp.setCorrectiveAction(textField(body, "correctiveAction"));
        if (body.containsKey("verificationProcedure")) cp.setVerificationProcedure(textField(body, "verificationProcedure"));
        if (body.containsKey("recordKeeping")) cp.setRecordKeeping(textField(body, "recordKeeping"));
        if (body.containsKey("active")) cp.setActive(booleanField(body, "active", cp.isActive()));

        try {
            HaccpCheckpoint saved = haccpCheckpointRepo.saveAndFlush(cp);
            return ApiResponse.success("HACCP CCP 更新成功", serializeCheckpoint(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                 | jakarta.persistence.OptimisticLockException ex) {
            log.warn("PUT /haccp/checkpoints/{} optimistic lock conflict: {}", id, ex.getMessage());
            return ApiResponse.errorWithCode(409, "CONFLICT",
                    "HACCP CCP 已被其他用户修改, 无法继续",
                    "请刷新页面查看最新数据后再编辑", "warning");
        }
    }

    @Operation(summary = "删除 HACCP 关键控制点 (软删除, 历史监控记录保留)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @DeleteMapping("/haccp/checkpoints/{id}")
    @Transactional
    public ApiResponse<Void> deleteCheckpoint(@PathVariable String factoryId, @PathVariable Long id) {
        log.info("DELETE /haccp/checkpoints/{} factoryId={}", id, factoryId);
        HaccpCheckpoint cp = loadCheckpoint(factoryId, id);
        haccpCheckpointRepo.delete(cp);  // @SQLDelete soft-deletes via deleted_at = NOW()
        return ApiResponse.successMessage("HACCP CCP 已停用 (历史监控记录保留)");
    }

    // ============================================================
    // 2. HACCP Monitoring Records — list / create
    // ============================================================

    @Operation(summary = "查询 HACCP 监控记录 (按批次或偏离过滤)",
               description = "可按 batchNumber 查特定批次, 或 deviationsOnly=true 看偏离记录")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/haccp/monitoring")
    public ApiResponse<List<Map<String, Object>>> listMonitoring(
            @PathVariable String factoryId,
            @RequestParam(required = false) String batchNumber,
            @RequestParam(required = false) Boolean deviationsOnly) {
        log.debug("GET /haccp/monitoring factoryId={} batchNumber={} deviationsOnly={}",
                factoryId, batchNumber, deviationsOnly);
        List<HaccpMonitoringRecord> rows;
        if (batchNumber != null && !batchNumber.isBlank()) {
            rows = haccpMonitoringRepo
                    .findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(factoryId, batchNumber);
        } else if (Boolean.TRUE.equals(deviationsOnly)) {
            rows = haccpMonitoringRepo.findByFactoryIdAndIsDeviationTrue(factoryId);
        } else {
            // No specific filter — return latest 100 deviations (food safety triage view)
            rows = haccpMonitoringRepo.findByFactoryIdAndIsDeviationTrue(factoryId);
            if (rows.size() > 100) rows = rows.subList(0, 100);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (HaccpMonitoringRecord r : rows) data.add(serializeMonitoring(r));
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 3. Additive Limits (GB 2760) — read-only
    // ============================================================

    @Operation(summary = "查询食品添加剂限量 (GB 2760-2014, 只读)",
               description = "按 food_category 过滤. 国标数据由系统种子, factory 不可修改.")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/additive-limits")
    public ApiResponse<List<Map<String, Object>>> listAdditiveLimits(
            @PathVariable String factoryId,
            @RequestParam(required = false) String foodCategory) {
        log.debug("GET /additive-limits factoryId={} foodCategory={}", factoryId, foodCategory);
        List<AdditiveLimit> rows;
        if (foodCategory != null && !foodCategory.isBlank()) {
            rows = additiveLimitRepo.findByFoodCategoryAndActiveTrue(foodCategory);
        } else {
            rows = additiveLimitRepo.findAll();
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (AdditiveLimit a : rows) data.add(serializeAdditive(a));
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 4. Recall Events — CRUD + status workflow
    // ============================================================

    @Operation(summary = "列出召回事件 (倒序按 trigger_time)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/recalls")
    public ApiResponse<List<Map<String, Object>>> listRecalls(@PathVariable String factoryId) {
        log.debug("GET /recalls factoryId={}", factoryId);
        // No Pageable — list view typically shows all recent events (food safety teams need full visibility)
        List<RecallEvent> rows = recallEventRepo.findByFactoryIdOrderByTriggerTimeDesc(
                factoryId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        List<Map<String, Object>> data = new ArrayList<>();
        for (RecallEvent r : rows) data.add(serializeRecall(r));
        return ApiResponse.success("操作成功", data);
    }

    @Operation(summary = "新建召回事件",
               description = "初始 status=INVESTIGATING, event_code 全局唯一")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PostMapping("/recalls")
    @Transactional
    public ApiResponse<Map<String, Object>> createRecall(@PathVariable String factoryId,
                                                          @RequestBody Map<String, Object> body) {
        log.info("POST /recalls factoryId={} keys={}", factoryId, body.keySet());

        String eventCode = stringField(body, "eventCode");
        ApiResponse<Map<String, Object>> err = requireCode(eventCode, "eventCode",
                "请填写召回事件编号 (e.g. RECALL-20260801-001, 全局唯一)", EVENT_CODE_MAX);
        if (err != null) return err;

        String triggerReason = stringField(body, "triggerReason");
        err = requireString(triggerReason, "triggerReason",
                "请填写触发原因 (e.g. 客户投诉 / 内部发现 / 监管通知)", LONG_TEXT_MAX);
        if (err != null) return err;

        String affectedCategory = stringField(body, "affectedProductCategory");
        err = requireString(affectedCategory, "affectedProductCategory",
                "请填写涉事产品类别 (e.g. 卤猪蹄 / 酱牛肉)", NAME_MAX);
        if (err != null) return err;

        Long triggeredBy = longField(body, "triggeredByUserId");
        if (triggeredBy == null) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "triggeredByUserId 必填",
                    "请提供触发召回的用户 ID", "warning");
        }

        // fool-proof Rule 4: idempotent duplicate check on event_code
        Optional<RecallEvent> dup = recallEventRepo.findByEventCode(eventCode);
        if (dup.isPresent()) {
            RecallEvent existing = dup.get();
            return ApiResponse.errorWithCode(409, "DUPLICATE",
                    "召回事件编号已存在: " + eventCode + " (id=" + existing.getId() + ")",
                    "请使用其他编号, 或前往编辑该召回事件", "warning");
        }

        LocalDateTime triggerTime = parseDateTime(body.get("triggerTime"));
        if (triggerTime == null) triggerTime = LocalDateTime.now();

        RecallEvent ev = RecallEvent.builder()
                .factoryId(factoryId)
                .eventCode(eventCode)
                .triggerReason(triggerReason)
                .affectedProductCategory(affectedCategory)
                .triggerTime(triggerTime)
                .triggeredByUserId(triggeredBy)
                .status("INVESTIGATING")
                .estimatedLoss(decimalField(body, "estimatedLoss"))
                .build();
        RecallEvent saved = recallEventRepo.saveAndFlush(ev);
        log.info("Created RecallEvent id={} factoryId={} code={}",
                saved.getId(), factoryId, eventCode);
        return ApiResponse.success("召回事件创建成功", serializeRecall(saved));
    }

    @Operation(summary = "更新召回事件 (PATCH — 主要 status 流转)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @PutMapping("/recalls/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateRecall(@PathVariable String factoryId,
                                                          @PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        log.info("PUT /recalls/{} factoryId={} keys={}", id, factoryId, body.keySet());

        RecallEvent ev = loadRecall(factoryId, id);

        // AUD-4 explicit version check
        ApiResponse<Map<String, Object>> verErr = checkVersion(body.get("version"), ev.getVersion());
        if (verErr != null) return verErr;

        if (body.containsKey("status")) {
            String newStatus = stringField(body, "status");
            if (newStatus != null && !RECALL_STATUSES.contains(newStatus)) {
                return ApiResponse.errorWithCode(400, "VALIDATION",
                        "status 不合法: " + newStatus,
                        "允许值: " + String.join(" / ", RECALL_STATUSES), "warning");
            }
            // 🔴 2026-08-02 owner 拍板: 已结案(COMPLETED)的召回不允许改回别的状态。
            //    召回是合规留痕场景, 改回去会让台账与监管上报对不上, 且没有审计流水能还原
            //    它曾经结过案。
            //    ⚠️ 【只禁这一条, 不是完整流转矩阵】—— 其余方向(如 REPORTED→FROZEN)仍放行,
            //       补全矩阵会开始拒绝现在能做的其它人工操作, 是另一个决定。
            if (RECALL_STATUS_COMPLETED.equals(ev.getStatus())
                    && newStatus != null
                    && !RECALL_STATUS_COMPLETED.equals(newStatus)) {
                return ApiResponse.errorWithCode(409, "CONFLICT",
                        "召回事件已结案 (COMPLETED), 不可改回 " + newStatus,
                        "已结案的召回不允许回退状态。如确需重开, 请新建召回事件并在原因里引用本次编号",
                        "warning");
            }
            if (newStatus != null) {
                // Sanity: COMPLETED requires completedAt (auto-fill if missing)
                if ("COMPLETED".equals(newStatus) && ev.getCompletedAt() == null
                        && !body.containsKey("completedAt")) {
                    ev.setCompletedAt(LocalDateTime.now());
                }
                ev.setStatus(newStatus);
            }
        }
        if (body.containsKey("completedAt")) {
            ev.setCompletedAt(parseDateTime(body.get("completedAt")));
        }
        if (body.containsKey("triggerReason")) {
            String newReason = stringField(body, "triggerReason");
            if (newReason != null) {
                if (newReason.length() > LONG_TEXT_MAX) {
                    return ApiResponse.errorWithCode(400, "VALIDATION",
                            "triggerReason 最长 " + LONG_TEXT_MAX + " 字符 (当前 " + newReason.length() + ")",
                            "请精简触发原因描述", "warning");
                }
                ev.setTriggerReason(newReason);
            }
        }
        if (body.containsKey("affectedProductCategory")) {
            String newCat = stringField(body, "affectedProductCategory");
            if (newCat != null) ev.setAffectedProductCategory(newCat);
        }
        if (body.containsKey("estimatedLoss")) {
            ev.setEstimatedLoss(decimalField(body, "estimatedLoss"));
        }

        try {
            RecallEvent saved = recallEventRepo.saveAndFlush(ev);
            return ApiResponse.success("召回事件更新成功", serializeRecall(saved));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                 | jakarta.persistence.OptimisticLockException ex) {
            log.warn("PUT /recalls/{} optimistic lock conflict: {}", id, ex.getMessage());
            return ApiResponse.errorWithCode(409, "CONFLICT",
                    "召回事件已被其他用户修改, 无法继续",
                    "请刷新页面查看最新状态后再编辑", "warning");
        }
    }

    // ============================================================
    // 5. Recall Actions — list / create per event
    // ============================================================

    @Operation(summary = "查询某召回事件的全部行动 (timeline)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @GetMapping("/recalls/{eventId}/actions")
    public ApiResponse<List<Map<String, Object>>> listRecallActions(
            @PathVariable String factoryId,
            @PathVariable Long eventId) {
        log.debug("GET /recalls/{}/actions factoryId={}", eventId, factoryId);
        // factory guard
        loadRecall(factoryId, eventId);
        List<RecallAction> rows = recallActionRepo.findByRecallEventIdOrderByCreatedAtAsc(eventId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (RecallAction a : rows) data.add(serializeAction(a));
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // helpers
    // ============================================================

    private HaccpCheckpoint loadCheckpoint(String factoryId, Long id) {
        HaccpCheckpoint cp = haccpCheckpointRepo.findById(id)
                .orElseThrow(() -> new BusinessException(404, "HACCP CCP 不存在: id=" + id));
        if (!factoryId.equals(cp.getFactoryId())) {
            throw new BusinessException(403,
                    "无权访问其他工厂的 HACCP CCP (entity.factoryId=" + cp.getFactoryId() + ")")
                    .withSeverity("warning");
        }
        return cp;
    }

    private RecallEvent loadRecall(String factoryId, Long id) {
        RecallEvent ev = recallEventRepo.findById(id)
                .orElseThrow(() -> new BusinessException(404, "召回事件不存在: id=" + id));
        if (!factoryId.equals(ev.getFactoryId())) {
            throw new BusinessException(403,
                    "无权访问其他工厂的召回事件 (entity.factoryId=" + ev.getFactoryId() + ")")
                    .withSeverity("warning");
        }
        return ev;
    }

    /**
     * AUD-4 version check — mirrors CanvasRuleController.checkVersion.
     * @return null when versions match OR client opted out (null/malformed); 409 when stale.
     */
    private ApiResponse<Map<String, Object>> checkVersion(Object requestVersionRaw, Long currentVersion) {
        if (requestVersionRaw == null) return null;  // lenient: legacy clients
        Long requestVersion;
        if (requestVersionRaw instanceof Number) {
            requestVersion = ((Number) requestVersionRaw).longValue();
        } else {
            try {
                requestVersion = Long.parseLong(requestVersionRaw.toString());
            } catch (NumberFormatException ex) {
                return null;  // malformed → lenient skip
            }
        }
        if (!requestVersion.equals(currentVersion)) {
            return ApiResponse.errorWithCode(409, "CONFLICT",
                    "数据已被其他用户修改 (服务端 v=" + currentVersion
                            + ", 客户端 v=" + requestVersion + ")",
                    "请刷新页面查看最新数据后再编辑", "warning");
        }
        return null;
    }

    private ApiResponse<Map<String, Object>> requireCode(String value, String fieldName,
                                                          String actionHint, int maxLen) {
        if (value == null || value.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    fieldName + " 必填", actionHint, "warning");
        }
        if (value.length() > maxLen) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    fieldName + " 最长 " + maxLen + " 字符 (当前 " + value.length() + ")",
                    "请使用更短的编码", "warning");
        }
        return null;
    }

    private ApiResponse<Map<String, Object>> requireString(String value, String fieldName,
                                                            String actionHint, int maxLen) {
        if (value == null || value.isBlank()) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    fieldName + " 必填", actionHint, "warning");
        }
        if (value.length() > maxLen) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    fieldName + " 最长 " + maxLen + " 字符 (当前 " + value.length() + ")",
                    "请精简文本", "warning");
        }
        return null;
    }

    // ==================== serializers ====================

    private Map<String, Object> serializeCheckpoint(HaccpCheckpoint c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("factoryId", c.getFactoryId());
        m.put("checkpointCode", c.getCheckpointCode());
        m.put("name", c.getName());
        m.put("hazardType", c.getHazardType());
        m.put("description", c.getDescription());
        m.put("criticalLimitMin", c.getCriticalLimitMin());
        m.put("criticalLimitMax", c.getCriticalLimitMax());
        m.put("unit", c.getUnit());
        m.put("monitoringProcedure", c.getMonitoringProcedure());
        m.put("correctiveAction", c.getCorrectiveAction());
        m.put("verificationProcedure", c.getVerificationProcedure());
        m.put("recordKeeping", c.getRecordKeeping());
        m.put("active", c.isActive());
        m.put("version", c.getVersion());  // AUD-4 client snapshot
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeMonitoring(HaccpMonitoringRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("factoryId", r.getFactoryId());
        m.put("checkpointId", r.getCheckpointId());
        m.put("batchNumber", r.getBatchNumber());
        m.put("monitoringTime", r.getMonitoringTime() != null ? r.getMonitoringTime().toString() : null);
        m.put("measuredValue", r.getMeasuredValue());
        m.put("operatorUserId", r.getOperatorUserId());
        m.put("isDeviation", r.isDeviation());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeAdditive(AdditiveLimit a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("additiveName", a.getAdditiveName());
        m.put("additiveCode", a.getAdditiveCode());
        m.put("foodCategory", a.getFoodCategory());
        m.put("maxLimit", a.getMaxLimit());
        m.put("unit", a.getUnit());
        m.put("regulationRef", a.getRegulationRef());
        m.put("active", a.isActive());
        return m;
    }

    private Map<String, Object> serializeRecall(RecallEvent r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("factoryId", r.getFactoryId());
        m.put("eventCode", r.getEventCode());
        m.put("triggerReason", r.getTriggerReason());
        m.put("affectedProductCategory", r.getAffectedProductCategory());
        m.put("triggerTime", r.getTriggerTime() != null ? r.getTriggerTime().toString() : null);
        m.put("triggeredByUserId", r.getTriggeredByUserId());
        m.put("status", r.getStatus());
        m.put("completedAt", r.getCompletedAt() != null ? r.getCompletedAt().toString() : null);
        m.put("estimatedLoss", r.getEstimatedLoss());
        m.put("version", r.getVersion());  // AUD-4 client snapshot
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeAction(RecallAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("recallEventId", a.getRecallEventId());
        m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        return m;
    }

    // ==================== body-field helpers ====================

    private String stringField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    /** TEXT-column field — same as stringField but skipping the length cap (caller validates). */
    private String textField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private Long longField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal decimalField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean booleanField(Map<String, Object> body, String key, boolean defaultValue) {
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    private LocalDateTime parseDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime) return (LocalDateTime) v;
        try {
            return LocalDateTime.parse(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }
}

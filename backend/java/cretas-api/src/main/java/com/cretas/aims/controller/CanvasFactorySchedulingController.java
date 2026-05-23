package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canvas-FactoryScheduling 工厂排班配置 (Phase B/C/P3 半-Canvas-ed UI wrap).
 *
 * <p>包装已存的 {@link FactorySchedulingConfig} entity (调度算法 LinUCB / 临时工策略 /
 * APS 排产策略权重 等 30+ 字段). Per-factory 单一配置 (UNIQUE factory_id + deleted_at IS NULL).
 *
 * <p>典型用例: factory_admin 调整 LinUCB 权重 / 临时工策略 / SKU 复杂度阈值 / 自适应学习参数
 * 而不需要改代码.
 *
 * <p>4-in-1 UX (per fool-proof Rule + qa-prompt v2.4): all error responses include
 * actionHint + severity + hintTarget when applicable.
 *
 * @since Canvas Phase B/C/P3 batch 2 (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-factory-scheduling")
@Tag(name = "Canvas-工厂排班配置",
     description = "工厂调度配置 — LinUCB / 临时工策略 / SKU 复杂度 / APS 排产策略权重")
@RequiredArgsConstructor
public class CanvasFactorySchedulingController {

    private final FactorySchedulingConfigRepository repository;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂调度配置 (per-factory 唯一)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<FactorySchedulingConfig>> list(@PathVariable String factoryId) {
        // Per-factory 仅一条配置, 但用 list 接口保持 Canvas 一致 (list/get/create/update/delete).
        Optional<FactorySchedulingConfig> opt = repository.findByFactoryId(factoryId);
        return ApiResponse.success("查询成功", opt.map(List::of).orElse(List.of()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "按 id 查询排班配置")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<FactorySchedulingConfig> get(@PathVariable String factoryId,
                                                    @PathVariable Long id) {
        FactorySchedulingConfig cfg = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "排班配置不存在: id=" + id)
                        .withHint("请先创建该工厂的排班配置")
                        .withSeverity("warning"));
        if (!factoryId.equals(cfg.getFactoryId())) {
            throw new BusinessException(403, "无权访问其他工厂的排班配置")
                    .withSeverity("warning");
        }
        return ApiResponse.success("查询成功", cfg);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建工厂排班配置 (per-factory 唯一)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactorySchedulingConfig> create(@PathVariable String factoryId,
                                                        @RequestBody Map<String, Object> body) {
        // 防呆 Rule 4 (幂等): per-factory 唯一, 重复创建返 409 + 现有 id 提示
        Optional<FactorySchedulingConfig> dup = repository.findByFactoryId(factoryId);
        if (dup.isPresent()) {
            throw new BusinessException(409,
                    "工厂排班配置已存在 (id=" + dup.get().getId() + ")")
                    .withHint("请使用 PUT 更新现有配置, 或先 DELETE 后再 POST")
                    .withSeverity("warning");
        }

        FactorySchedulingConfig cfg = FactorySchedulingConfig.createDefault(factoryId);
        applyPatch(cfg, body);

        FactorySchedulingConfig saved = repository.save(cfg);
        log.info("create scheduling config: factoryId={}, id={}", factoryId, saved.getId());
        return ApiResponse.success("排班配置已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "更新排班配置 (PATCH semantics, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactorySchedulingConfig> update(@PathVariable String factoryId,
                                                        @PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        FactorySchedulingConfig cfg = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "排班配置不存在: id=" + id)
                        .withSeverity("warning"));
        if (!factoryId.equals(cfg.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的排班配置")
                    .withSeverity("warning");
        }

        // AUD-4 P1: explicit optimistic-lock check fires BEFORE save so JPA can observe
        // stale snapshots. Null = lenient (legacy clients keep working).
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(cfg.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + cfg.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        applyPatch(cfg, body);
        FactorySchedulingConfig saved = repository.saveAndFlush(cfg);
        log.info("update scheduling config: factoryId={}, id={}, version={}",
                factoryId, id, saved.getVersion());
        return ApiResponse.success("排班配置已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除排班配置")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(@PathVariable String factoryId, @PathVariable Long id) {
        FactorySchedulingConfig cfg = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "排班配置不存在: id=" + id)
                        .withSeverity("warning"));
        if (!factoryId.equals(cfg.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的排班配置")
                    .withSeverity("warning");
        }
        cfg.setDeletedAt(LocalDateTime.now());
        repository.save(cfg);
        log.info("delete scheduling config: factoryId={}, id={}", factoryId, id);
        return ApiResponse.success("排班配置已删除", null);
    }

    // ==================== Helpers ====================

    /**
     * 应用 PATCH 语义: body 中包含的字段才更新.
     */
    private static void applyPatch(FactorySchedulingConfig cfg, Map<String, Object> body) {
        // 基础配置
        if (body.containsKey("enabled")) cfg.setEnabled(asBoolean(body.get("enabled"), true));
        if (body.containsKey("diversityEnabled")) cfg.setDiversityEnabled(asBoolean(body.get("diversityEnabled"), true));

        // 权重参数 (LinUCB / 公平性 / 技能维护 / 重复)
        if (body.containsKey("linucbWeight")) cfg.setLinucbWeight(asDouble(body.get("linucbWeight"), cfg.getLinucbWeight()));
        if (body.containsKey("fairnessWeight")) cfg.setFairnessWeight(asDouble(body.get("fairnessWeight"), cfg.getFairnessWeight()));
        if (body.containsKey("skillMaintenanceWeight")) cfg.setSkillMaintenanceWeight(asDouble(body.get("skillMaintenanceWeight"), cfg.getSkillMaintenanceWeight()));
        if (body.containsKey("repetitionWeight")) cfg.setRepetitionWeight(asDouble(body.get("repetitionWeight"), cfg.getRepetitionWeight()));

        // 时间参数
        if (body.containsKey("skillDecayDays")) cfg.setSkillDecayDays(asInteger(body.get("skillDecayDays"), cfg.getSkillDecayDays()));
        if (body.containsKey("fairnessPeriodDays")) cfg.setFairnessPeriodDays(asInteger(body.get("fairnessPeriodDays"), cfg.getFairnessPeriodDays()));
        if (body.containsKey("repetitionDays")) cfg.setRepetitionDays(asInteger(body.get("repetitionDays"), cfg.getRepetitionDays()));
        if (body.containsKey("maxConsecutiveDays")) cfg.setMaxConsecutiveDays(asInteger(body.get("maxConsecutiveDays"), cfg.getMaxConsecutiveDays()));

        // 临时工配置
        if (body.containsKey("tempWorkerLinucbFactor")) cfg.setTempWorkerLinucbFactor(asDouble(body.get("tempWorkerLinucbFactor"), cfg.getTempWorkerLinucbFactor()));
        if (body.containsKey("tempWorkerFairnessFactor")) cfg.setTempWorkerFairnessFactor(asDouble(body.get("tempWorkerFairnessFactor"), cfg.getTempWorkerFairnessFactor()));
        if (body.containsKey("tempWorkerSkillDecayDays")) cfg.setTempWorkerSkillDecayDays(asInteger(body.get("tempWorkerSkillDecayDays"), cfg.getTempWorkerSkillDecayDays()));
        if (body.containsKey("tempWorkerThresholdDays")) cfg.setTempWorkerThresholdDays(asInteger(body.get("tempWorkerThresholdDays"), cfg.getTempWorkerThresholdDays()));
        if (body.containsKey("tempWorkerMinAssignments")) cfg.setTempWorkerMinAssignments(asInteger(body.get("tempWorkerMinAssignments"), cfg.getTempWorkerMinAssignments()));

        // SKU 复杂度
        if (body.containsKey("skuComplexityWeight")) cfg.setSkuComplexityWeight(asDouble(body.get("skuComplexityWeight"), cfg.getSkuComplexityWeight()));
        if (body.containsKey("highComplexitySkillThreshold")) cfg.setHighComplexitySkillThreshold(asInteger(body.get("highComplexitySkillThreshold"), cfg.getHighComplexitySkillThreshold()));
        if (body.containsKey("lowComplexityForTraining")) cfg.setLowComplexityForTraining(asBoolean(body.get("lowComplexityForTraining"), true));

        // 自适应学习
        if (body.containsKey("adaptiveLearningEnabled")) cfg.setAdaptiveLearningEnabled(asBoolean(body.get("adaptiveLearningEnabled"), true));
        if (body.containsKey("learningRate")) cfg.setLearningRate(asDouble(body.get("learningRate"), cfg.getLearningRate()));
        if (body.containsKey("minSamplesForAdaptation")) cfg.setMinSamplesForAdaptation(asInteger(body.get("minSamplesForAdaptation"), cfg.getMinSamplesForAdaptation()));
        if (body.containsKey("efficiencyTarget")) cfg.setEfficiencyTarget(asDouble(body.get("efficiencyTarget"), cfg.getEfficiencyTarget()));
        if (body.containsKey("diversityTarget")) cfg.setDiversityTarget(asDouble(body.get("diversityTarget"), cfg.getDiversityTarget()));

        // 异常检测
        if (body.containsKey("anomalyDetectionEnabled")) cfg.setAnomalyDetectionEnabled(asBoolean(body.get("anomalyDetectionEnabled"), true));
        if (body.containsKey("efficiencyAnomalyThreshold")) cfg.setEfficiencyAnomalyThreshold(asDouble(body.get("efficiencyAnomalyThreshold"), cfg.getEfficiencyAnomalyThreshold()));
        if (body.containsKey("anomalyCountForCalibration")) cfg.setAnomalyCountForCalibration(asInteger(body.get("anomalyCountForCalibration"), cfg.getAnomalyCountForCalibration()));

        // APS 排产策略权重
        if (body.containsKey("earliestDeadlineWeight")) cfg.setEarliestDeadlineWeight(asDouble(body.get("earliestDeadlineWeight"), cfg.getEarliestDeadlineWeight()));
        if (body.containsKey("minChangeoverWeight")) cfg.setMinChangeoverWeight(asDouble(body.get("minChangeoverWeight"), cfg.getMinChangeoverWeight()));
        if (body.containsKey("capacityMatchWeight")) cfg.setCapacityMatchWeight(asDouble(body.get("capacityMatchWeight"), cfg.getCapacityMatchWeight()));
        if (body.containsKey("shortestProcessWeight")) cfg.setShortestProcessWeight(asDouble(body.get("shortestProcessWeight"), cfg.getShortestProcessWeight()));
        if (body.containsKey("materialReadyWeight")) cfg.setMaterialReadyWeight(asDouble(body.get("materialReadyWeight"), cfg.getMaterialReadyWeight()));
        if (body.containsKey("urgencyFirstWeight")) cfg.setUrgencyFirstWeight(asDouble(body.get("urgencyFirstWeight"), cfg.getUrgencyFirstWeight()));

        // 校验权重范围 (0.0-1.0 防呆)
        validateWeights(cfg);
    }

    /**
     * 防呆 (Rule 1): 校验所有权重在 [0.0, 1.0] 区间.
     */
    private static void validateWeights(FactorySchedulingConfig cfg) {
        checkWeight("linucbWeight", cfg.getLinucbWeight());
        checkWeight("fairnessWeight", cfg.getFairnessWeight());
        checkWeight("skillMaintenanceWeight", cfg.getSkillMaintenanceWeight());
        checkWeight("repetitionWeight", cfg.getRepetitionWeight());
        checkWeight("skuComplexityWeight", cfg.getSkuComplexityWeight());
        checkWeight("learningRate", cfg.getLearningRate());
        checkWeight("efficiencyTarget", cfg.getEfficiencyTarget());
        checkWeight("diversityTarget", cfg.getDiversityTarget());
        checkWeight("efficiencyAnomalyThreshold", cfg.getEfficiencyAnomalyThreshold());
        checkWeight("earliestDeadlineWeight", cfg.getEarliestDeadlineWeight());
        checkWeight("minChangeoverWeight", cfg.getMinChangeoverWeight());
        checkWeight("capacityMatchWeight", cfg.getCapacityMatchWeight());
        checkWeight("shortestProcessWeight", cfg.getShortestProcessWeight());
        checkWeight("materialReadyWeight", cfg.getMaterialReadyWeight());
        checkWeight("urgencyFirstWeight", cfg.getUrgencyFirstWeight());
    }

    private static void checkWeight(String field, Double value) {
        if (value == null) return; // null 允许 (使用 default)
        if (value < 0.0 || value > 1.0) {
            throw new BusinessException(400,
                    field + " 必须在 [0.0, 1.0] 区间 (当前 " + value + ")")
                    .withHint("请输入 0 到 1 之间的小数")
                    .withSeverity("warning")
                    .withHintTarget(field);
        }
    }

    private static Boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Integer asInteger(Object v, Integer defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Double asDouble(Object v, Double defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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

package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas-Thresholds 阈值参数中心 (Phase A P0-1).
 *
 * <p>Per-factory threshold configuration overlaying hard-coded constants in 6 service classes
 * (Inventory / IoT / Credit / BOM / Shortage / AI). All endpoints scoped to {@code factoryId};
 * global default rows (factoryId = {@code "*"}) are managed via a dedicated admin path
 * {@code /api/mobile/&#42;/canvas-thresholds} — JwtAuthInterceptor treats {@code "*"} as a
 * factory-id placeholder same as any other string.
 *
 * <p>4-in-1 UX (per fool-proof Rule + qa-prompt v2.4): all error responses include
 * actionHint + severity + hintTarget when applicable.
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-thresholds")
@Tag(name = "Canvas-Thresholds 阈值参数中心",
     description = "工厂级阈值配置 — 库存 / IoT / 信用 / BOM / 缺料 / AI 路由 6 大类 ~19 阈值")
@RequiredArgsConstructor
public class CanvasThresholdsController {

    private static final int KEY_MAX_LENGTH = 100;
    private static final int VALUE_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int UNIT_MAX_LENGTH = 20;

    private final FactoryThresholdRepository repository;
    private final ThresholdResolverService resolver;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的所有阈值 (可按 category 过滤)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<FactoryThreshold>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String category) {
        List<FactoryThreshold> list;
        if (category != null && !category.isBlank()) {
            ThresholdCategory cat = parseCategory(category);
            list = repository.findByFactoryIdAndCategoryOrderByThresholdKey(factoryId, cat);
        } else {
            list = repository.findByFactoryIdOrderByCategoryAscThresholdKeyAsc(factoryId);
        }
        return ApiResponse.success("查询成功", list);
    }

    @GetMapping("/{key}")
    @Operation(summary = "查询单个阈值 (按 thresholdKey)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<FactoryThreshold> getByKey(
            @PathVariable String factoryId,
            @PathVariable String key) {
        FactoryThreshold t = repository.findByFactoryIdAndThresholdKey(factoryId, key)
                .orElseThrow(() -> new BusinessException(404, "阈值不存在: " + key)
                        .withHint("请先创建该阈值, 或确认 key 拼写正确")
                        .withSeverity("warning")
                        .withHintTarget("thresholdKey"));
        return ApiResponse.success("查询成功", t);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建阈值 (新增 per-factory 配置)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactoryThreshold> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String thresholdKey = required(body, "thresholdKey");
        String category = required(body, "category");
        String valueType = required(body, "valueType");
        String thresholdValue = required(body, "thresholdValue");
        String defaultValue = required(body, "defaultValue");

        validateLengths(thresholdKey, thresholdValue, body.get("description"), body.get("unit"));

        // 防呆 (Rule 4): 唯一性 check 避免重复
        Optional<FactoryThreshold> dup = repository.findByFactoryIdAndThresholdKey(factoryId, thresholdKey);
        if (dup.isPresent()) {
            throw new BusinessException(409, "阈值已存在: " + thresholdKey
                    + " (id=" + dup.get().getId() + ")")
                    .withHint("请使用 PUT 更新现有阈值, 或修改 thresholdKey")
                    .withSeverity("warning")
                    .withHintTarget("thresholdKey");
        }

        ThresholdCategory cat = parseCategory(category);
        ThresholdValueType type = parseValueType(valueType);
        validateValueAgainstType(thresholdValue, type, "thresholdValue");
        validateValueAgainstType(defaultValue, type, "defaultValue");

        FactoryThreshold t = FactoryThreshold.builder()
                .factoryId(factoryId)
                .thresholdKey(thresholdKey)
                .category(cat)
                .valueType(type)
                .thresholdValue(thresholdValue)
                .defaultValue(defaultValue)
                .description(asString(body.get("description")))
                .unit(asString(body.get("unit")))
                .minValue(asString(body.get("minValue")))
                .maxValue(asString(body.get("maxValue")))
                .enabled(asBoolean(body.get("enabled"), true))
                .build();
        FactoryThreshold saved = repository.save(t);
        resolver.invalidate(factoryId, thresholdKey);
        log.info("create threshold: factoryId={}, key={}, value={}", factoryId, thresholdKey, thresholdValue);
        return ApiResponse.success("阈值已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "修改阈值 (partial PATCH, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactoryThreshold> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        FactoryThreshold t = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "阈值不存在: " + id));
        if (!factoryId.equals(t.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的阈值")
                    .withSeverity("warning");
        }

        // AUD-4 P1 (mirror ScheduledTaskController.checkVersion):
        // explicit optimistic-lock check fires BEFORE save so JPA can observe stale snapshots.
        // Null = lenient (legacy clients keep working).
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(t.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + t.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        // PATCH semantics: only apply fields present in body.
        if (body.containsKey("thresholdValue")) {
            String newValue = required(body, "thresholdValue");
            if (newValue.length() > VALUE_MAX_LENGTH) {
                throw new BusinessException(400,
                        "阈值最长 " + VALUE_MAX_LENGTH + " 字符 (当前 " + newValue.length() + ")")
                        .withHint("请使用更短的值")
                        .withSeverity("warning")
                        .withHintTarget("thresholdValue");
            }
            validateValueAgainstType(newValue, t.getValueType(), "thresholdValue");
            t.setThresholdValue(newValue);
        }
        if (body.containsKey("defaultValue")) {
            String newDefault = required(body, "defaultValue");
            validateValueAgainstType(newDefault, t.getValueType(), "defaultValue");
            t.setDefaultValue(newDefault);
        }
        if (body.containsKey("description")) {
            String desc = asString(body.get("description"));
            if (desc != null && desc.length() > DESCRIPTION_MAX_LENGTH) {
                throw new BusinessException(400,
                        "描述最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 " + desc.length() + ")")
                        .withHint("请精简描述")
                        .withSeverity("warning")
                        .withHintTarget("description");
            }
            t.setDescription(desc);
        }
        if (body.containsKey("unit")) {
            String unit = asString(body.get("unit"));
            if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
                throw new BusinessException(400,
                        "单位最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.length() + ")")
                        .withHint("请简化单位")
                        .withSeverity("warning")
                        .withHintTarget("unit");
            }
            t.setUnit(unit);
        }
        if (body.containsKey("minValue")) {
            t.setMinValue(asString(body.get("minValue")));
        }
        if (body.containsKey("maxValue")) {
            t.setMaxValue(asString(body.get("maxValue")));
        }
        if (body.containsKey("enabled")) {
            t.setEnabled(asBoolean(body.get("enabled"), true));
        }
        FactoryThreshold saved = repository.saveAndFlush(t);
        resolver.invalidate(factoryId, t.getThresholdKey());
        log.info("update threshold: factoryId={}, id={}, key={}, newValue={}",
                factoryId, id, t.getThresholdKey(), t.getThresholdValue());
        return ApiResponse.success("阈值已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除阈值")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        FactoryThreshold t = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "阈值不存在: " + id));
        if (!factoryId.equals(t.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的阈值")
                    .withSeverity("warning");
        }
        t.softDelete();
        repository.save(t);
        resolver.invalidate(factoryId, t.getThresholdKey());
        log.info("delete threshold: factoryId={}, id={}, key={}", factoryId, id, t.getThresholdKey());
        return ApiResponse.success("阈值已删除", null);
    }

    // ==================== Reset-to-default ====================

    @PostMapping("/reset-to-default")
    @Operation(summary = "按类别批量重置阈值到默认值")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Integer> resetCategoryToDefault(
            @PathVariable String factoryId,
            @RequestParam String category) {
        ThresholdCategory cat = parseCategory(category);
        List<FactoryThreshold> rows = repository
                .findByFactoryIdAndCategoryOrderByThresholdKey(factoryId, cat);
        int updated = 0;
        for (FactoryThreshold t : rows) {
            if (!Objects.equals(t.getThresholdValue(), t.getDefaultValue())) {
                t.setThresholdValue(t.getDefaultValue());
                repository.save(t);
                resolver.invalidate(factoryId, t.getThresholdKey());
                updated++;
            }
        }
        log.info("reset-to-default: factoryId={}, category={}, updated={}", factoryId, category, updated);
        return ApiResponse.success(
                "已重置 " + updated + " 个阈值到默认值 (category=" + category + ")", updated);
    }

    // ==================== Helpers ====================

    private static String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new BusinessException(400, "字段不能为空: " + key)
                    .withSeverity("warning")
                    .withHintTarget(key);
        }
        return v.toString().trim();
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
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

    private static ThresholdCategory parseCategory(String s) {
        try {
            return ThresholdCategory.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的 category: " + s
                            + " (有效值: INVENTORY, IOT, CREDIT, BOM, SHORTAGE, AI)")
                    .withHint("请选择 6 种类型之一")
                    .withSeverity("warning")
                    .withHintTarget("category");
        }
    }

    private static ThresholdValueType parseValueType(String s) {
        try {
            return ThresholdValueType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的 valueType: " + s
                            + " (有效值: INTEGER, DECIMAL, DOUBLE, STRING)")
                    .withHint("请选择 4 种类型之一")
                    .withSeverity("warning")
                    .withHintTarget("valueType");
        }
    }

    /**
     * 字段长度预校验 (AUD-5 B-A3 sister sweep): 在请求触底 PG VARCHAR 截断之前抛 400.
     * 未传入值的字段 (null) 跳过 — PATCH 语义。
     */
    private static void validateLengths(String key, String value, Object description, Object unit) {
        if (key != null && key.length() > KEY_MAX_LENGTH) {
            throw new BusinessException(400,
                    "thresholdKey 最长 " + KEY_MAX_LENGTH + " 字符 (当前 " + key.length() + ")")
                    .withHint("请使用更短的 key")
                    .withSeverity("warning")
                    .withHintTarget("thresholdKey");
        }
        if (value != null && value.length() > VALUE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "thresholdValue 最长 " + VALUE_MAX_LENGTH + " 字符 (当前 " + value.length() + ")")
                    .withHint("请使用更短的值")
                    .withSeverity("warning")
                    .withHintTarget("thresholdValue");
        }
        if (description != null && description.toString().length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(400,
                    "description 最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 "
                            + description.toString().length() + ")")
                    .withHint("请精简描述")
                    .withSeverity("warning")
                    .withHintTarget("description");
        }
        if (unit != null && unit.toString().length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(400,
                    "unit 最长 " + UNIT_MAX_LENGTH + " 字符 (当前 " + unit.toString().length() + ")")
                    .withHint("请简化单位")
                    .withSeverity("warning")
                    .withHintTarget("unit");
        }
    }

    /**
     * 值类型预校验: thresholdValue 必须可被 valueType 解析, 否则抛 400.
     * 防止存入"NaN" / "abc" 这类 service 层 parse 时崩溃的值。
     */
    private static void validateValueAgainstType(String value, ThresholdValueType type, String fieldName) {
        if (value == null) return;
        try {
            switch (type) {
                case INTEGER -> Integer.parseInt(value);
                case DECIMAL -> new BigDecimal(value);
                case DOUBLE -> Double.parseDouble(value);
                case STRING -> { /* 任意字符串都合法 */ }
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    fieldName + " (" + value + ") 不能解析为 " + type)
                    .withHint("请检查值的格式")
                    .withSeverity("warning")
                    .withHintTarget(fieldName);
        }
    }
}

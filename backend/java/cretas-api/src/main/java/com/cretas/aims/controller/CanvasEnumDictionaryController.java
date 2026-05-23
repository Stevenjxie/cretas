package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.canvas.EnumDictionary;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.EnumDictionaryRepository;
import com.cretas.aims.service.canvas.EnumDictionaryResolverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas-Phase C 枚举字典 (Enum Dictionary).
 *
 * <p>统一存储防呆 Rule 3 dropdown 值: CANCEL_REASON / RETURN_REASON / APPROVAL_OPINION /
 * DEFECT_SEVERITY / NONCONFORM_TYPE / WASTAGE_REASON / RECALL_LEVEL / URGENCY_LEVEL.
 *
 * <p>4位一体 UX (per fool-proof Rule + qa-prompt v2.4): 所有错误响应附 actionHint + severity
 * + hintTarget; 重复 code → 409; 跨工厂 → 403; AUD-4 P1 乐观锁 stale → 409.
 *
 * @since Canvas Phase C (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-enum-dictionary")
@Tag(name = "Canvas-Phase C 枚举字典",
     description = "防呆 Rule 3 dropdown — 取消原因/退货原因/审批意见/缺陷严重度等 8 大类")
@RequiredArgsConstructor
public class CanvasEnumDictionaryController {

    private static final int CATEGORY_MAX_LENGTH = 50;
    private static final int CODE_MAX_LENGTH = 50;
    private static final int LABEL_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int LOCALE_MAX_LENGTH = 10;

    private final EnumDictionaryRepository repository;
    private final EnumDictionaryResolverService resolver;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的所有枚举值 (可按 category 过滤)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<EnumDictionary>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) String category) {
        List<EnumDictionary> list;
        if (category != null && !category.isBlank()) {
            list = repository.findByFactoryIdAndCategoryOrderByDisplayOrderAscCodeAsc(
                    factoryId, category.toUpperCase());
        } else {
            list = repository.findByFactoryIdOrderByCategoryAscDisplayOrderAscCodeAsc(factoryId);
        }
        return ApiResponse.success("查询成功", list);
    }

    @GetMapping("/categories")
    @Operation(summary = "列出工厂下所有 distinct category (用于 UI 顶层 tab 过滤)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<String>> categories(@PathVariable String factoryId) {
        List<String> cats = repository.findByFactoryId(factoryId).stream()
                .map(EnumDictionary::getCategory)
                .distinct()
                .sorted()
                .toList();
        return ApiResponse.success("查询成功", cats);
    }

    @GetMapping("/resolve")
    @Operation(summary = "Resolver 入口 — 取 enabled dropdown 值 (per-factory fallback global)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin",
                  "department_admin", "operator", "viewer"})
    public ApiResponse<List<EnumDictionary>> resolve(
            @PathVariable String factoryId,
            @RequestParam String category) {
        if (category == null || category.isBlank()) {
            throw new BusinessException(400, "category 不能为空")
                    .withHint("请传入枚举类别 (e.g. CANCEL_REASON)")
                    .withSeverity("warning")
                    .withHintTarget("category");
        }
        List<EnumDictionary> values = resolver.getEnumValues(factoryId, category.toUpperCase());
        return ApiResponse.success("查询成功", values);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建枚举值 (新增 per-factory 配置)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<EnumDictionary> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String category = required(body, "category").toUpperCase();
        String code = required(body, "code").toUpperCase();
        String label = required(body, "label");

        validateLengths(category, code, label, body.get("description"), body.get("locale"));

        // 防呆 (Rule 4): 唯一性 check
        Optional<EnumDictionary> dup =
                repository.findByFactoryIdAndCategoryAndCode(factoryId, category, code);
        if (dup.isPresent()) {
            throw new BusinessException(409,
                    "枚举值已存在: category=" + category + ", code=" + code
                            + " (id=" + dup.get().getId() + ")")
                    .withHint("请使用 PUT 更新现有枚举值, 或修改 code")
                    .withSeverity("warning")
                    .withHintTarget("code");
        }

        EnumDictionary e = EnumDictionary.builder()
                .factoryId(factoryId)
                .category(category)
                .code(code)
                .label(label)
                .displayOrder(asInteger(body.get("displayOrder"), 0))
                .enabled(asBoolean(body.get("enabled"), true))
                .parentCode(asString(body.get("parentCode")))
                .description(asString(body.get("description")))
                .locale(asStringOrDefault(body.get("locale"), EnumDictionary.DEFAULT_LOCALE))
                .build();
        EnumDictionary saved = repository.save(e);
        resolver.invalidate(factoryId, category);
        log.info("create enum: factoryId={}, category={}, code={}, label={}",
                factoryId, category, code, label);
        return ApiResponse.success("枚举值已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "修改枚举值 (partial PATCH, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<EnumDictionary> update(
            @PathVariable String factoryId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        EnumDictionary e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "枚举值不存在: " + id)
                        .withHint("请确认 id 正确, 或先创建该枚举值")
                        .withSeverity("warning"));
        if (!factoryId.equals(e.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的枚举值")
                    .withSeverity("warning");
        }

        // AUD-4 P1 (mirror ScheduledTaskController.checkVersion): explicit optimistic-lock
        // check fires BEFORE save so JPA can observe stale snapshots.
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(e.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + e.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        // PATCH semantics
        if (body.containsKey("label")) {
            String newLabel = required(body, "label");
            if (newLabel.length() > LABEL_MAX_LENGTH) {
                throw new BusinessException(400,
                        "label 最长 " + LABEL_MAX_LENGTH + " 字符 (当前 " + newLabel.length() + ")")
                        .withHint("请精简显示文本")
                        .withSeverity("warning")
                        .withHintTarget("label");
            }
            e.setLabel(newLabel);
        }
        if (body.containsKey("displayOrder")) {
            e.setDisplayOrder(asInteger(body.get("displayOrder"), 0));
        }
        if (body.containsKey("enabled")) {
            e.setEnabled(asBoolean(body.get("enabled"), true));
        }
        if (body.containsKey("parentCode")) {
            e.setParentCode(asString(body.get("parentCode")));
        }
        if (body.containsKey("description")) {
            String desc = asString(body.get("description"));
            if (desc != null && desc.length() > DESCRIPTION_MAX_LENGTH) {
                throw new BusinessException(400,
                        "description 最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 "
                                + desc.length() + ")")
                        .withHint("请精简描述")
                        .withSeverity("warning")
                        .withHintTarget("description");
            }
            e.setDescription(desc);
        }
        if (body.containsKey("locale")) {
            String loc = asString(body.get("locale"));
            if (loc != null && loc.length() > LOCALE_MAX_LENGTH) {
                throw new BusinessException(400,
                        "locale 最长 " + LOCALE_MAX_LENGTH + " 字符 (当前 " + loc.length() + ")")
                        .withHint("请使用标准 locale e.g. zh-CN")
                        .withSeverity("warning")
                        .withHintTarget("locale");
            }
            e.setLocale(loc == null || loc.isBlank() ? EnumDictionary.DEFAULT_LOCALE : loc);
        }
        EnumDictionary saved = repository.saveAndFlush(e);
        resolver.invalidate(factoryId, e.getCategory());
        log.info("update enum: factoryId={}, id={}, category={}, code={}",
                factoryId, id, e.getCategory(), e.getCode());
        return ApiResponse.success("枚举值已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除枚举值")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable UUID id) {
        EnumDictionary e = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "枚举值不存在: " + id));
        if (!factoryId.equals(e.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的枚举值")
                    .withSeverity("warning");
        }
        e.softDelete();
        repository.save(e);
        resolver.invalidate(factoryId, e.getCategory());
        log.info("delete enum: factoryId={}, id={}, category={}, code={}",
                factoryId, id, e.getCategory(), e.getCode());
        return ApiResponse.success("枚举值已删除", null);
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

    private static String asStringOrDefault(Object v, String defaultValue) {
        String s = asString(v);
        return (s == null || s.isBlank()) ? defaultValue : s;
    }

    private static Boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Integer asInteger(Object v, int defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
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

    /**
     * 字段长度预校验 — 在请求触底 PG VARCHAR 截断之前抛 400.
     */
    private static void validateLengths(String category, String code, String label,
                                        Object description, Object locale) {
        if (category != null && category.length() > CATEGORY_MAX_LENGTH) {
            throw new BusinessException(400,
                    "category 最长 " + CATEGORY_MAX_LENGTH + " 字符 (当前 " + category.length() + ")")
                    .withHint("请使用更短的 category")
                    .withSeverity("warning")
                    .withHintTarget("category");
        }
        if (code != null && code.length() > CODE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "code 最长 " + CODE_MAX_LENGTH + " 字符 (当前 " + code.length() + ")")
                    .withHint("请使用更短的 code")
                    .withSeverity("warning")
                    .withHintTarget("code");
        }
        if (label != null && label.length() > LABEL_MAX_LENGTH) {
            throw new BusinessException(400,
                    "label 最长 " + LABEL_MAX_LENGTH + " 字符 (当前 " + label.length() + ")")
                    .withHint("请精简显示文本")
                    .withSeverity("warning")
                    .withHintTarget("label");
        }
        if (description != null && description.toString().length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(400,
                    "description 最长 " + DESCRIPTION_MAX_LENGTH + " 字符 (当前 "
                            + description.toString().length() + ")")
                    .withHint("请精简描述")
                    .withSeverity("warning")
                    .withHintTarget("description");
        }
        if (locale != null && locale.toString().length() > LOCALE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "locale 最长 " + LOCALE_MAX_LENGTH + " 字符 (当前 "
                            + locale.toString().length() + ")")
                    .withHint("请使用标准 locale, e.g. zh-CN")
                    .withSeverity("warning")
                    .withHintTarget("locale");
        }
    }
}

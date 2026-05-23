package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EncodingRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas-EncodingRule 编码规则 Canvas controller (Phase BCP3 — 2026-05-22).
 *
 * <p>Wrap 已存的 EncodingRule entity, 提供 Canvas-style CRUD UI 入口.
 * Legacy controller (EncodingRuleController) 保留供历史调用方使用.
 *
 * <p>4-in-1 防呆 UX: 所有错误响应携带 actionHint + severity + hintTarget.
 *
 * @since Canvas Phase BCP3 (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-encoding-rule")
@Tag(name = "Canvas-EncodingRule 编码规则",
     description = "工厂级业务单据编码规则配置 (MB-/PB-/SH-/QI- 前缀 + 日期 + 序列号)")
@RequiredArgsConstructor
public class CanvasEncodingRuleController {

    private static final int ENTITY_TYPE_MAX = 50;
    private static final int RULE_NAME_MAX = 100;
    private static final int RULE_DESC_MAX = 500;
    private static final int PATTERN_MAX = 200;

    private final EncodingRuleRepository repository;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂所有编码规则")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<EncodingRule>> list(@PathVariable String factoryId) {
        // 查询工厂级规则 + 系统级 fallback
        List<EncodingRule> factoryLevel = repository.findByFactoryIdAndEnabledTrue(factoryId);
        List<EncodingRule> systemLevel = repository.findByFactoryIdIsNullAndEnabledTrue();
        // 合并: 工厂级在前
        List<EncodingRule> merged = new java.util.ArrayList<>(factoryLevel);
        merged.addAll(systemLevel);
        return ApiResponse.success("查询成功", merged);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个编码规则")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<EncodingRule> getById(
            @PathVariable String factoryId,
            @PathVariable String id) {
        EncodingRule r = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "编码规则不存在: " + id)
                        .withHint("请确认 id 拼写或先创建该规则")
                        .withSeverity("warning")
                        .withHintTarget("id"));
        if (!Objects.equals(factoryId, r.getFactoryId()) && r.getFactoryId() != null) {
            throw new BusinessException(403, "无权查看其他工厂的编码规则")
                    .withSeverity("warning");
        }
        return ApiResponse.success("查询成功", r);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "新建编码规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<EncodingRule> create(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body) {
        String entityType = required(body, "entityType");
        String ruleName = required(body, "ruleName");
        String encodingPattern = required(body, "encodingPattern");

        validateLengths(entityType, ruleName, body.get("ruleDescription"), encodingPattern);

        // 防呆 Rule 4 (幂等): 同 (factoryId, entityType) 唯一性 check
        if (repository.existsByFactoryIdAndEntityType(factoryId, entityType)) {
            throw new BusinessException(409,
                    "编码规则已存在: factoryId=" + factoryId + ", entityType=" + entityType)
                    .withHint("请使用 PUT 更新现有规则, 或修改 entityType")
                    .withSeverity("warning")
                    .withHintTarget("entityType");
        }

        EncodingRule r = EncodingRule.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .entityType(entityType)
                .ruleName(ruleName)
                .ruleDescription(asString(body.get("ruleDescription")))
                .encodingPattern(encodingPattern)
                .prefix(asString(body.get("prefix")))
                .dateFormat(asString(body.get("dateFormat")))
                .sequenceLength(asInteger(body.get("sequenceLength"), 4))
                .resetCycle(asString(body.get("resetCycle"), "DAILY"))
                .currentSequence(0L)
                .separator(asString(body.get("separator"), "-"))
                .includeFactoryCode(asBoolean(body.get("includeFactoryCode"), true))
                .enabled(asBoolean(body.get("enabled"), true))
                .build();

        EncodingRule saved = repository.save(r);
        log.info("create encoding rule: factoryId={}, entityType={}, id={}",
                factoryId, entityType, saved.getId());
        return ApiResponse.success("编码规则已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "修改编码规则 (PATCH, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<EncodingRule> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        EncodingRule r = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "编码规则不存在: " + id));
        if (r.getFactoryId() != null && !factoryId.equals(r.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的编码规则")
                    .withSeverity("warning");
        }

        // AUD-4 P1: explicit optimistic-lock check (note: 字段名 optLockVersion, body key "version")
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(r.getOptLockVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + r.getOptLockVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        if (body.containsKey("ruleName")) {
            String s = required(body, "ruleName");
            if (s.length() > RULE_NAME_MAX) {
                throw new BusinessException(400,
                        "ruleName 最长 " + RULE_NAME_MAX + " 字符 (当前 " + s.length() + ")")
                        .withHint("请精简名称").withSeverity("warning").withHintTarget("ruleName");
            }
            r.setRuleName(s);
        }
        if (body.containsKey("ruleDescription")) {
            String s = asString(body.get("ruleDescription"));
            if (s != null && s.length() > RULE_DESC_MAX) {
                throw new BusinessException(400,
                        "ruleDescription 最长 " + RULE_DESC_MAX + " 字符 (当前 " + s.length() + ")")
                        .withHint("请精简描述").withSeverity("warning").withHintTarget("ruleDescription");
            }
            r.setRuleDescription(s);
        }
        if (body.containsKey("encodingPattern")) {
            String s = required(body, "encodingPattern");
            if (s.length() > PATTERN_MAX) {
                throw new BusinessException(400,
                        "encodingPattern 最长 " + PATTERN_MAX + " 字符 (当前 " + s.length() + ")")
                        .withHint("请精简模板").withSeverity("warning").withHintTarget("encodingPattern");
            }
            r.setEncodingPattern(s);
        }
        if (body.containsKey("prefix")) {
            r.setPrefix(asString(body.get("prefix")));
        }
        if (body.containsKey("dateFormat")) {
            r.setDateFormat(asString(body.get("dateFormat")));
        }
        if (body.containsKey("sequenceLength")) {
            Integer sl = asInteger(body.get("sequenceLength"), null);
            if (sl != null && (sl < 1 || sl > 20)) {
                throw new BusinessException(400,
                        "sequenceLength 必须在 1-20 之间 (当前 " + sl + ")")
                        .withHint("常用值 4-6").withSeverity("warning").withHintTarget("sequenceLength");
            }
            r.setSequenceLength(sl);
        }
        if (body.containsKey("resetCycle")) {
            String rc = asString(body.get("resetCycle"));
            if (rc != null && !List.of("DAILY", "MONTHLY", "YEARLY", "NEVER").contains(rc)) {
                throw new BusinessException(400,
                        "resetCycle 仅支持 DAILY/MONTHLY/YEARLY/NEVER (当前 " + rc + ")")
                        .withHint("请选择 4 种重置周期之一")
                        .withSeverity("warning")
                        .withHintTarget("resetCycle");
            }
            r.setResetCycle(rc);
        }
        if (body.containsKey("separator")) {
            r.setSeparator(asString(body.get("separator")));
        }
        if (body.containsKey("includeFactoryCode")) {
            r.setIncludeFactoryCode(asBoolean(body.get("includeFactoryCode"), true));
        }
        if (body.containsKey("enabled")) {
            r.setEnabled(asBoolean(body.get("enabled"), true));
        }

        EncodingRule saved = repository.saveAndFlush(r);
        log.info("update encoding rule: factoryId={}, id={}, entityType={}",
                factoryId, id, r.getEntityType());
        return ApiResponse.success("编码规则已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除编码规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        EncodingRule r = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "编码规则不存在: " + id));
        if (r.getFactoryId() != null && !factoryId.equals(r.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的编码规则")
                    .withSeverity("warning");
        }
        r.softDelete();
        repository.save(r);
        log.info("delete encoding rule: factoryId={}, id={}, entityType={}",
                factoryId, id, r.getEntityType());
        return ApiResponse.success("编码规则已删除", null);
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

    private static String asString(Object v, String defaultValue) {
        return v == null ? defaultValue : v.toString();
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

    private static Integer asInteger(Object v, Integer defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void validateLengths(String entityType, String ruleName,
                                        Object ruleDescription, String pattern) {
        if (entityType != null && entityType.length() > ENTITY_TYPE_MAX) {
            throw new BusinessException(400,
                    "entityType 最长 " + ENTITY_TYPE_MAX + " 字符 (当前 " + entityType.length() + ")")
                    .withHint("常用值: MATERIAL_BATCH / PROCESSING_BATCH / SHIPMENT")
                    .withSeverity("warning")
                    .withHintTarget("entityType");
        }
        if (ruleName != null && ruleName.length() > RULE_NAME_MAX) {
            throw new BusinessException(400,
                    "ruleName 最长 " + RULE_NAME_MAX + " 字符 (当前 " + ruleName.length() + ")")
                    .withHint("请精简名称").withSeverity("warning").withHintTarget("ruleName");
        }
        if (ruleDescription != null && ruleDescription.toString().length() > RULE_DESC_MAX) {
            throw new BusinessException(400,
                    "ruleDescription 最长 " + RULE_DESC_MAX + " 字符 (当前 "
                            + ruleDescription.toString().length() + ")")
                    .withHint("请精简描述").withSeverity("warning").withHintTarget("ruleDescription");
        }
        if (pattern != null && pattern.length() > PATTERN_MAX) {
            throw new BusinessException(400,
                    "encodingPattern 最长 " + PATTERN_MAX + " 字符 (当前 " + pattern.length() + ")")
                    .withHint("请精简模板").withSeverity("warning").withHintTarget("encodingPattern");
        }
    }
}

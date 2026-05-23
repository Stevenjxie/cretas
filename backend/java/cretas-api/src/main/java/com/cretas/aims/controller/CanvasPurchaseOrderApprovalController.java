package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.inventory.PurchaseOrderApprovalRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderApprovalRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canvas-PurchaseOrderApproval 采购订单审批规则 (Phase B/C/P3 半-Canvas-ed UI wrap).
 *
 * <p>包装已存的 {@link PurchaseOrderApprovalRule} entity (per-factory 价格偏差阈值 +
 * 总金额阈值). 替代 PurchaseServiceImpl 中硬编码的 PRICE_ALERT_THRESHOLD = 10.
 *
 * <p>典型用例: factory_admin / 财务主管 调整采购单自动转财务审核的阈值, 不需要发版.
 * approveOrder 评估时: 任一行 priceAlert = true (偏差超过 priceVarianceThreshold) 或
 * 订单 totalAmount 超过 amountThreshold → 触发 PENDING_FINANCE_REVIEW.
 *
 * <p>4-in-1 UX (per fool-proof Rule + qa-prompt v2.4): all error responses include
 * actionHint + severity + hintTarget when applicable.
 *
 * @since Canvas Phase B/C/P3 batch 2 (2026-05-22)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-purchase-order-approval")
@Tag(name = "Canvas-采购审批规则",
     description = "采购订单财务审核规则 — 价格偏差阈值 / 总金额阈值")
@RequiredArgsConstructor
public class CanvasPurchaseOrderApprovalController {

    private static final int RULE_NAME_MAX_LENGTH = 100;

    private final PurchaseOrderApprovalRuleRepository repository;

    // ==================== Read ====================

    @GetMapping
    @Operation(summary = "列出工厂的采购审批规则 (含 disabled)")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<List<PurchaseOrderApprovalRule>> list(@PathVariable String factoryId) {
        // 走 repository 的 enabled-only query 看启用规则; 完整列表 (含 disabled) 需要另查
        // 因为现有 Repository 只暴露 enabled=true 的查询. 为简化, 用 findAll + factoryId filter.
        // 由于 @Where 已 baked deleted_at IS NULL, 这里只需 stream filter factory_id.
        List<PurchaseOrderApprovalRule> all = repository.findAll().stream()
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .toList();
        return ApiResponse.success("查询成功", all);
    }

    @GetMapping("/{id}")
    @Operation(summary = "按 id 查询采购审批规则")
    @RequireRole({"factory_super_admin", "permission_admin", "factory_admin"})
    public ApiResponse<PurchaseOrderApprovalRule> get(@PathVariable String factoryId,
                                                       @PathVariable String id) {
        PurchaseOrderApprovalRule rule = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "采购审批规则不存在: id=" + id)
                        .withHint("请检查 id 拼写或先创建规则")
                        .withSeverity("warning"));
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权访问其他工厂的采购审批规则")
                    .withSeverity("warning");
        }
        return ApiResponse.success("查询成功", rule);
    }

    // ==================== Create ====================

    @PostMapping
    @Operation(summary = "创建采购审批规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<PurchaseOrderApprovalRule> create(@PathVariable String factoryId,
                                                          @RequestBody Map<String, Object> body) {
        String ruleName = required(body, "ruleName");
        validateRuleNameLength(ruleName);

        // 防呆 Rule 4 (幂等): 唯一性 check
        Optional<PurchaseOrderApprovalRule> dup = repository.findByFactoryIdAndRuleName(factoryId, ruleName);
        if (dup.isPresent()) {
            throw new BusinessException(409,
                    "规则名称已存在: " + ruleName + " (id=" + dup.get().getId() + ")")
                    .withHint("请使用 PUT 更新现有规则, 或换一个唯一的规则名称")
                    .withSeverity("warning")
                    .withHintTarget("ruleName");
        }

        PurchaseOrderApprovalRule rule = new PurchaseOrderApprovalRule();
        rule.setFactoryId(factoryId);
        rule.setRuleName(ruleName);
        rule.setPriceVarianceThreshold(parseThreshold(
                body.get("priceVarianceThreshold"), new BigDecimal("10.00"), "priceVarianceThreshold"));
        rule.setAmountThreshold(body.containsKey("amountThreshold")
                ? parseAmount(body.get("amountThreshold"))
                : null);
        rule.setEnabled(asBoolean(body.get("enabled"), true));

        PurchaseOrderApprovalRule saved = repository.save(rule);
        log.info("create approval rule: factoryId={}, ruleName={}, id={}",
                factoryId, ruleName, saved.getId());
        return ApiResponse.success("采购审批规则已创建", saved);
    }

    // ==================== Update (PATCH semantics — Map body) ====================

    @PutMapping("/{id}")
    @Operation(summary = "更新采购审批规则 (PATCH semantics, Map body)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<PurchaseOrderApprovalRule> update(@PathVariable String factoryId,
                                                          @PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        PurchaseOrderApprovalRule rule = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "采购审批规则不存在: id=" + id)
                        .withSeverity("warning"));
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的采购审批规则")
                    .withSeverity("warning");
        }

        // AUD-4 P1: explicit optimistic-lock check fires BEFORE save.
        Object versionObj = body.get("version");
        if (versionObj != null) {
            Long requested = asLong(versionObj, null);
            if (requested != null && !requested.equals(rule.getVersion())) {
                throw new BusinessException(409,
                        "数据已被其他用户修改 (服务端 v=" + rule.getVersion()
                                + ", 客户端 v=" + requested + ")")
                        .withHint("请刷新页面查看最新数据后再编辑")
                        .withSeverity("warning");
            }
        }

        // PATCH semantics: only apply fields present in body.
        if (body.containsKey("ruleName")) {
            String newName = required(body, "ruleName");
            validateRuleNameLength(newName);
            // 重名 check (排除自己)
            Optional<PurchaseOrderApprovalRule> dup =
                    repository.findByFactoryIdAndRuleName(factoryId, newName);
            if (dup.isPresent() && !dup.get().getId().equals(id)) {
                throw new BusinessException(409,
                        "规则名称已被占用: " + newName + " (id=" + dup.get().getId() + ")")
                        .withHint("请换一个唯一的规则名称")
                        .withSeverity("warning")
                        .withHintTarget("ruleName");
            }
            rule.setRuleName(newName);
        }
        if (body.containsKey("priceVarianceThreshold")) {
            rule.setPriceVarianceThreshold(parseThreshold(
                    body.get("priceVarianceThreshold"),
                    rule.getPriceVarianceThreshold(),
                    "priceVarianceThreshold"));
        }
        if (body.containsKey("amountThreshold")) {
            rule.setAmountThreshold(parseAmount(body.get("amountThreshold")));
        }
        if (body.containsKey("enabled")) {
            rule.setEnabled(asBoolean(body.get("enabled"), true));
        }

        PurchaseOrderApprovalRule saved = repository.saveAndFlush(rule);
        log.info("update approval rule: factoryId={}, id={}, version={}",
                factoryId, id, saved.getVersion());
        return ApiResponse.success("采购审批规则已更新", saved);
    }

    // ==================== Delete (soft) ====================

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除采购审批规则")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<Void> delete(@PathVariable String factoryId, @PathVariable String id) {
        PurchaseOrderApprovalRule rule = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "采购审批规则不存在: id=" + id)
                        .withSeverity("warning"));
        if (!factoryId.equals(rule.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的采购审批规则")
                    .withSeverity("warning");
        }
        // BaseEntity.softDelete() 设置 deletedAt = NOW(). @SQLDelete 注解会触发 UPDATE.
        // 这里显式 delete 让 Hibernate 走 @SQLDelete 路径.
        repository.delete(rule);
        log.info("delete approval rule: factoryId={}, id={}, ruleName={}",
                factoryId, id, rule.getRuleName());
        return ApiResponse.success("采购审批规则已删除", null);
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

    private static void validateRuleNameLength(String name) {
        if (name != null && name.length() > RULE_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "ruleName 最长 " + RULE_NAME_MAX_LENGTH + " 字符 (当前 " + name.length() + ")")
                    .withHint("请使用更短的规则名称")
                    .withSeverity("warning")
                    .withHintTarget("ruleName");
        }
    }

    /**
     * 解析价格偏差阈值百分比. 必须在 [0, 100] 区间.
     */
    private static BigDecimal parseThreshold(Object v, BigDecimal defaultValue, String fieldName) {
        if (v == null) return defaultValue;
        try {
            BigDecimal val = new BigDecimal(v.toString());
            if (val.compareTo(BigDecimal.ZERO) < 0 || val.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(400,
                        fieldName + " 必须在 [0, 100] 区间 (当前 " + val + ")")
                        .withHint("请输入 0 到 100 之间的百分比")
                        .withSeverity("warning")
                        .withHintTarget(fieldName);
            }
            return val;
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    fieldName + " 不能解析为数值: " + v)
                    .withHint("请输入有效的数值")
                    .withSeverity("warning")
                    .withHintTarget(fieldName);
        }
    }

    /**
     * 解析总金额阈值 (允许 null = 不参与判断). 必须 >= 0.
     */
    private static BigDecimal parseAmount(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        try {
            BigDecimal val = new BigDecimal(v.toString());
            if (val.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(400,
                        "amountThreshold 不能为负 (当前 " + val + ")")
                        .withHint("请输入非负金额, 或留空表示不参与判断")
                        .withSeverity("warning")
                        .withHintTarget("amountThreshold");
            }
            return val;
        } catch (NumberFormatException e) {
            throw new BusinessException(400,
                    "amountThreshold 不能解析为金额: " + v)
                    .withHint("请输入有效的金额")
                    .withSeverity("warning")
                    .withHintTarget("amountThreshold");
        }
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
}

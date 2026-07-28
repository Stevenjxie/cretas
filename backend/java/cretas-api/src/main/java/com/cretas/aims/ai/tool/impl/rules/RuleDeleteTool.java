package com.cretas.aims.ai.tool.impl.rules;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.rules.BusinessRule;
import com.cretas.aims.repository.rules.BusinessRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: soft-delete a Canvas-Rules BusinessRule.
 *
 * Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §5
 *
 * <p>Uses BaseEntity soft delete — BusinessRule is annotated with
 * {@code @SQLDelete(sql = "UPDATE business_rules SET deleted_at = NOW() WHERE id = ?")}
 * and {@code @Where(clause = "deleted_at IS NULL")} so the rule disappears from
 * RuleEngine evaluation and can be restored via DB ops.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-18
 */
@Slf4j
@Component
public class RuleDeleteTool extends AbstractBusinessTool {

    @Autowired
    private BusinessRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "rule_delete";
    }

    @Override
    public String getDescription() {
        return "删除一条业务规则 (软删除)。例: '删除 vip_discount 规则'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleCode", Map.of("type", "string", "description", "要删除的规则代码"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("ruleCode"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("ruleCode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String ruleCode = getString(params, "ruleCode");
        BusinessRule rule = ruleRepository.findByFactoryIdAndRuleCode(factoryId, ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "规则 " + ruleCode + " 不存在 (factoryId=" + factoryId + ")"));

        // Bug #4 fix (2026-05-20, sister of CanvasRuleController.deleteRule): use manual
        // softDelete()+save() instead of repository.delete(). With @Version added to
        // BusinessRule (V20260626_02), the hard-coded @SQLDelete UPDATE does NOT increment
        // version → Hibernate's EntityDeleteAction reports a DataIntegrityViolationException
        // that surfaces as the generic "数据处理异常" 409. The standard UPDATE path
        // (via save) bumps version correctly and translates conflicts to a specific
        // ObjectOptimisticLockingFailureException.
        //
        // Idempotency (fool-proof Rule 4): re-running rule_delete on an already-deleted
        // rule returns success with alreadyDeleted=true rather than failing.
        if (rule.getDeletedAt() != null) {
            log.info("rule_delete - factory={}, ruleCode={} already deleted at {}, idempotent no-op",
                    factoryId, ruleCode, rule.getDeletedAt());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", String.valueOf(rule.getId()));
            data.put("ruleCode", ruleCode);
            data.put("ruleName", rule.getRuleName());
            data.put("deletedAt", rule.getDeletedAt().toString());
            data.put("alreadyDeleted", true);
            return buildSimpleResult("规则 " + ruleCode + " 已是删除状态", data);
        }

        rule.softDelete();
        BusinessRule saved = ruleRepository.save(rule);
        log.info("rule_delete - factory={}, ruleCode={}, id={}, deletedAt={}",
                factoryId, ruleCode, saved.getId(), saved.getDeletedAt());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(saved.getId()));
        data.put("ruleCode", ruleCode);
        data.put("ruleName", saved.getRuleName());
        data.put("deletedAt", saved.getDeletedAt() != null
                ? saved.getDeletedAt().toString() : null);
        return buildSimpleResult("规则 " + ruleCode + " 已删除 (软删除, 可恢复)", data);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}

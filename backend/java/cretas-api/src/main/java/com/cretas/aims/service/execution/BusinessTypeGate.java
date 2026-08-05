package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sprint 13 #305 业态门控 (business-type gate) — shared across all execution paths.
 *
 * <p>Cretas serves 2 customer types: RESTAURANT (餐厅, e.g. RES_3101_009) and FACTORY
 * (制造厂, e.g. F006 六膳门卤味). A DOMAIN-EXCLUSIVE intent (business_type=RESTAURANT or
 * FACTORY) triggered on a mismatched {@code factory.type} has no data to operate on →
 * previously executed and returned a misleading half-broken "数据不可用". This gate returns
 * an honest "本厂非该业态" empty-state + a domain-appropriate next-action instead, per
 * {@code .claude/rules/fool-proof-design.md} Rule 5 (dead-end → next action).
 *
 * <p>Only DOMAIN-EXCLUSIVE intents gate; {@code COMMON}/null business_type are universal and
 * always pass (anti-goal: 不挡通用 intent). Fail-soft: any domain-lookup error → pass.
 *
 * <p>Centralized here so every execution entry point gates consistently
 * ({@link IntentExecutionOrchestrator} main + explicit-intent flows, {@link SseStreamingService}).
 */
@Slf4j
@Component
public class BusinessTypeGate {

    private final IntentConfigManagementService configService;

    @Autowired
    public BusinessTypeGate(@Lazy IntentConfigManagementService configService) {
        this.configService = configService;
    }

    /**
     * Task-1 (2026-08-05, restaurant-tenant-consolidation): expose the fail-soft domain
     * lookup this gate already wraps, so other execution-path callers (e.g.
     * {@link SseStreamingService}, which has no config-service dependency of its own) can
     * do domain-aware restaurant-tenant checks without wiring a second dependency for the
     * same lookup.
     *
     * @return "RESTAURANT" / "FACTORY" / whatever {@link IntentConfigManagementService}
     *         resolves, or {@code null} if resolution throws.
     */
    public String resolveDomain(String factoryId) {
        try {
            return configService.resolveBusinessDomain(factoryId);
        } catch (Exception e) {
            log.warn("[业态门控] resolveBusinessDomain failed for factoryId={}: {}", factoryId, e.getMessage());
            return null;
        }
    }

    /**
     * @return an honest empty-state {@link IntentExecuteResponse} when the intent's business
     *         type is exclusive to a different domain than the factory; {@link Optional#empty()}
     *         to proceed with normal execution.
     */
    public Optional<IntentExecuteResponse> check(String factoryId, AIIntentConfig intent) {
        if (intent == null) {
            return Optional.empty();
        }
        String intentBiz = intent.getBusinessType();
        if (intentBiz == null || intentBiz.isBlank() || "COMMON".equalsIgnoreCase(intentBiz)) {
            return Optional.empty(); // universal intent — never gated
        }
        String factoryDomain;
        try {
            factoryDomain = configService.resolveBusinessDomain(factoryId);
        } catch (Exception e) {
            log.warn("[业态门控] resolveBusinessDomain failed for factoryId={} — skipping gate: {}",
                    factoryId, e.getMessage());
            return Optional.empty(); // fail-soft: never block execution on a lookup hiccup
        }
        if (factoryDomain == null || intentBiz.equalsIgnoreCase(factoryDomain)) {
            return Optional.empty(); // domain matches (or unknown) — proceed
        }
        // Mismatch: a domain-exclusive intent on the wrong factory type → honest gate.
        String name = (intent.getIntentName() != null && !intent.getIntentName().isBlank())
                ? intent.getIntentName() : intent.getIntentCode();
        String msg;
        if ("RESTAURANT".equalsIgnoreCase(intentBiz)) {
            msg = "本厂为制造(工厂)业态，无餐饮经营数据，「" + name + "」餐饮分析不适用。"
                + "工厂经营分析请使用：库存查询 / 采购建议 / 质检报告 / 生产出品率 等功能。";
        } else { // FACTORY-exclusive intent on a RESTAURANT-type factory
            msg = "本店为餐饮业态，「" + name + "」工厂制造分析不适用。"
                + "餐饮经营分析请使用：损益分析 / 客单价 / 翻台率 / 菜品销售排行 等功能。";
        }
        log.info("[业态门控] gated intent={} (business_type={}) on factory={} (domain={}) → honest empty-state",
                intent.getIntentCode(), intentBiz, factoryId, factoryDomain);
        return Optional.of(IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("NOT_APPLICABLE")
                .message(msg)
                .formattedText(msg)
                .executedAt(LocalDateTime.now())
                .build());
    }
}

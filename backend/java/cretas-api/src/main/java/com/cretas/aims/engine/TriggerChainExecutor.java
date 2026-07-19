package com.cretas.aims.engine;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Canvas V2: Configurable trigger chain executor.
 * Listens to ALL ApplicationEvents and routes them through factory_trigger_chains.
 * If no chain is configured, the event falls through to existing @EventListener handlers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1) // Execute BEFORE other AFTER_COMMIT listeners
public class TriggerChainExecutor {

    private final FactoryTriggerChainRepository triggerChainRepository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    // Self-reference for proxy-aware @Transactional dispatch.
    // R7 G3: persistExecutionMetadata needs REQUIRES_NEW tx propagation, which
    // Spring's AOP can only honor through the proxy (not direct this.call).
    // @Lazy breaks the circular dependency at wire time.
    @Autowired @Lazy
    private TriggerChainExecutor self;

    // Round 4 Fix P1-14: added SalesOrderCreatedEvent so trigger chains can react to
    // order creation (e.g. fetch external market price, create parallel workflows).
    // Round 8-α Fix: added InvoiceIssuedEvent + SalesOrderSettledEvent — both were
    // being published by InvoiceServiceImpl / PaymentRecordServiceImpl but silently
    // dropped by this whitelist. Customer-configured "发票开具→同步税务" / "订单结清→自动归档"
    // trigger chains literally never fired. See round8-findings Gap #2.
    // Full list should eventually be data-driven from factory_trigger_chains.event_type
    // but this hardcoded whitelist is the current performance-safe approach.
    private static final Set<String> HANDLED_EVENTS = Set.of(
            "SalesOrderCreatedEvent",
            "SalesOrderConfirmedEvent", "SalesOrderFinanceApprovedEvent",
            "MaterialReceivedEvent", "BatchCompletedEvent",
            "FinishedGoodsCreatedEvent", "PaymentReceivedEvent",
            "InvoiceIssuedEvent", "SalesOrderSettledEvent",
            // Round 9 Fix: SalesDelivery Canvas integration template (R8-α Gap #1)
            "SalesDeliveryCreatedEvent",
            // Round 9-α Fix: 5 more events discovered by subagent audit — all were being
            // published but silently dropped, so customer trigger chains on these events
            // literally could not fire. Most impactful: ProductionAlertEvent ("质检不合格
            // 自动通知采购" / "异常检测触发维护工单").
            "ProductionAlertEvent",
            "SampleApprovedEvent",
            "SkuComplexityChangedEvent",
            "SopUploadedEvent",
            "RescheduleNeededEvent",
            // Round 10 Fix (R8-α Gap #1 template 3rd hook): generic event for ALL material
            // batch sources. MaterialReceivedEvent only fires on the purchase-receive path;
            // this new event covers return/gain/manual batches so trigger chains can react
            // to every material_batch row that lands in the DB.
            "MaterialBatchCreatedEvent",
            // Round 11 T1 — purchase_receipt template event. Fires on DRAFT create via
            // PurchaseServiceImpl.createReceiveRecord; MaterialReceivedEvent still fires
            // on CONFIRMED state via confirmReceive. Both hooks are live.
            "PurchaseReceiveCreatedEvent",
            // Round 11 T2 — sales_return / purchase_return template event. Fires on DRAFT
            // create via ReturnOrderServiceImpl.createReturnOrder. Covers both return
            // types via the shared returnType enum — trigger chains can filter by
            // the event's returnType field if they want one direction only.
            "ReturnOrderCreatedEvent",
            // Round 11 T4 — internal transfer template event. Fires on DRAFT create via
            // TransferServiceImpl.createTransfer for both factory-to-factory and
            // warehouse-to-warehouse transfers. Trigger chains can filter by
            // sourceFactoryId vs targetFactoryId to distinguish directions.
            "TransferCreatedEvent",
            // Round 12 — status-change + create events:
            "ShipmentCreatedEvent",
            "ProductionStartedEvent",
            "ProductionCompletedEvent",
            "BatchMaterialConsumedEvent",
            // Round 13
            "InvoiceRequestedEvent"
    );

    // Round 6 Fix CHECK-5: rate-limited warn log when a factory configures a trigger chain
    // for an event type not in HANDLED_EVENTS. Previously we silently skipped, leaving
    // operators unable to diagnose why their chain never fired. One warning per eventType
    // per JVM run is enough — debouncing prevents log flooding when many events fire.
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> unhandledWarnedOnce =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * doomed-tx 修复 (#929 族 backlog #2, 2026-06-16): 改 AFTER_COMMIT + REQUIRES_NEW。
     *
     * <p>旧 {@code @EventListener} 在发布者 (e.g. {@code PurchaseServiceImpl.createReceiveRecord})
     * 的同一物理事务内同步执行。虽然本方法把每条 chain 的执行异常都 try-catch 吞掉 (见
     * {@link #onApplicationEvent} 的 catch + {@link #executeChain} STOP 分支), 异常不会
     * propagate 回发布者, 但 chain step 里的工具若做了**内层** DB 写并把事务标记 rollback-only,
     * 吞异常无法清除该标记 → 发布者 commit 阶段抛 {@code UnexpectedRollbackException} → doom。
     *
     * <p>AFTER_COMMIT: 等发布者事务真正提交后才跑 (chain 看到已落库的数据, 且不可能再 doom
     * 发布者事务)。REQUIRES_NEW: chain 执行在独立事务里, 任何失败只回滚自身。
     *
     * <p>{@code fallbackExecution = true}: 本监听器处理 {@link #HANDLED_EVENTS} 里的多种事件,
     * 其中部分 (e.g. {@code SalesOrderCreatedEvent} / {@code ProductionAlertEvent}) 可能在
     * **无事务**上下文中发布。默认 {@code @TransactionalEventListener} 在无事务时**静默丢弃**事件,
     * 会导致这些 chain 永不触发。fallbackExecution=true 让无事务时仍同步执行 (此时无 doom 风险,
     * 因为没有外层事务可被回滚)。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApplicationEvent(ApplicationEvent event) {
        String eventType = event.getClass().getSimpleName();
        if (!HANDLED_EVENTS.contains(eventType)) {
            // Round 6 Fix CHECK-5: warn once if a factory has a chain configured for this
            // unhandled event (someone bothered to configure it — they deserve a hint).
            if (unhandledWarnedOnce.putIfAbsent(eventType, Boolean.TRUE) == null) {
                try {
                    long configuredCount = triggerChainRepository.findAll().stream()
                            .filter(c -> eventType.equals(c.getEventType()) && Boolean.TRUE.equals(c.getEnabled()))
                            .count();
                    if (configuredCount > 0) {
                        log.warn("TriggerChain: event '{}' is NOT in HANDLED_EVENTS whitelist but "
                                + "{} enabled chain(s) are configured in factory_trigger_chains — "
                                + "those chains will NEVER fire until HANDLED_EVENTS is updated. "
                                + "(This warning logs once per eventType per JVM run.)",
                                eventType, configuredCount);
                    }
                } catch (Exception ignored) {
                    // guarded — scheduling too early at boot may fail; don't crash event dispatch
                }
            }
            return;
        }

        String factoryId = extractFactoryId(event);
        if (factoryId == null) {
            log.warn("TriggerChain: event {} has no factoryId accessor, skipping", eventType);
            return;
        }

        log.info("TriggerChain: event {} received for factory {}, looking up chains", eventType, factoryId);

        List<FactoryTriggerChain> chains = triggerChainRepository
                .findByFactoryIdAndEventTypeAndEnabledTrue(factoryId, eventType);

        if (chains.isEmpty()) {
            chains = triggerChainRepository.findGlobalByEventType(eventType);
        }

        if (chains.isEmpty()) {
            log.info("TriggerChain: no chain configured for event {} / factory {} (fallthrough to hardcoded handlers)",
                    eventType, factoryId);
            return;
        }

        log.info("TriggerChain: found {} chain(s) for event {} / factory {}",
                chains.size(), eventType, factoryId);

        for (FactoryTriggerChain chain : chains) {
            try {
                executeChain(chain, factoryId, event);
            } catch (Exception e) {
                log.error("Trigger chain {} failed for factory {}: {}",
                        chain.getChainCode(), factoryId, e.getMessage(), e);
            }
        }
    }

    private void executeChain(FactoryTriggerChain chain, String factoryId, ApplicationEvent event) {
        List<Map<String, Object>> steps = chain.getSteps();
        if (steps == null || steps.isEmpty()) return;

        Map<String, Object> chainContext = new HashMap<>();
        chainContext.put("factoryId", factoryId);

        // R7 G3: execution observability — track per-chain outcomes for E2E J2-9
        // and operator diagnostics. Tally step outcomes to produce final status.
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.now();
        int stepFailures = 0;
        int stepAttempts = 0;
        StringBuilder errorSummary = new StringBuilder();

        log.info("Executing trigger chain {} ({} steps) for factory {}",
                chain.getChainCode(), steps.size(), factoryId);

        for (Map<String, Object> step : steps) {
            Boolean stepEnabled = (Boolean) step.getOrDefault("enabled", true);
            if (!stepEnabled) continue;

            String toolName = (String) step.get("tool");
            String condition = (String) step.getOrDefault("condition", "always");
            int order = step.containsKey("order") ? ((Number) step.get("order")).intValue() : 0;

            if (!"always".equals(condition) && !evaluateCondition(condition, chainContext)) {
                log.debug("Chain {} step {} skipped (condition: {})", chain.getChainCode(), toolName, condition);
                continue;
            }

            Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
            if (executor.isEmpty()) {
                log.warn("Tool not found in chain {}: {}", chain.getChainCode(), toolName);
                continue;
            }

            // Round 9 Fix (R8-β tail): respect factory_tool_configs disable flag.
            // R7b fixed ToolDispatchService; R9 fixes SkillExecutor + this trigger chain path.
            if (!toolRegistry.isToolEnabledForFactory(factoryId, toolName)) {
                log.warn("Chain {} step {} skipped — tool disabled for factory {} via Canvas config",
                        chain.getChainCode(), toolName, factoryId);
                continue;
            }

            stepAttempts++;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) step.getOrDefault("params", Map.of());

                // Build proper ToolCall with JSON-serialized arguments
                String argsJson = objectMapper.writeValueAsString(params);
                ToolCall toolCall = ToolCall.of(
                        "chain-" + chain.getChainCode() + "-" + order,
                        toolName, argsJson);

                Map<String, Object> execContext = new HashMap<>(chainContext);

                String result = executor.get().execute(toolCall, execContext);
                chainContext.put("step" + order, Map.of("result", result, "success", true));
                log.info("Chain {} step {} executed OK", chain.getChainCode(), toolName);
            } catch (Exception e) {
                stepFailures++;
                if (errorSummary.length() > 0) errorSummary.append("; ");
                errorSummary.append(toolName).append(": ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
                chainContext.put("step" + order, Map.of("success", false, "error", e.getMessage()));
                log.error("Chain {} step {} failed: {}", chain.getChainCode(), toolName, e.getMessage());

                if ("STOP".equals(chain.getErrorStrategy())) {
                    self.persistExecutionMetadata(chain, startedAt, "FAILED", errorSummary.toString());
                    throw new RuntimeException("Trigger chain stopped at step " + toolName, e);
                }
            }
        }

        // R7 G3: final status — SUCCESS if no failures, PARTIAL if some, FAILED if all failed.
        String finalStatus;
        if (stepFailures == 0) finalStatus = "SUCCESS";
        else if (stepFailures < stepAttempts) finalStatus = "PARTIAL";
        else finalStatus = "FAILED";
        self.persistExecutionMetadata(chain, startedAt,
                finalStatus, stepFailures > 0 ? errorSummary.toString() : null);
    }

    /**
     * R7 G3: best-effort write of execution metadata. Refetch to avoid stale snapshots
     * (listener may hold a copy from before the execute-chain iteration started).
     * Failure to persist metadata must not crash event dispatch.
     *
     * REQUIRES_NEW: @EventListener fires synchronously inside the event-publisher's
     * transaction. If the publisher's transaction rolls back (or hasn't committed
     * when our method is called), our save gets rolled back too — observability
     * metadata would silently disappear. REQUIRES_NEW forces a fresh transaction
     * that commits independently so we can track execution even when the upstream
     * operation fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistExecutionMetadata(FactoryTriggerChain chain,
                                         java.time.LocalDateTime startedAt,
                                         String status,
                                         String errorSummary) {
        try {
            triggerChainRepository.findById(chain.getId()).ifPresent(fresh -> {
                fresh.setLastExecutedAt(startedAt);
                fresh.setLastExecutionStatus(status);
                fresh.setLastExecutionError(errorSummary);
                fresh.setExecutionCount((fresh.getExecutionCount() == null ? 0L : fresh.getExecutionCount()) + 1);
                triggerChainRepository.save(fresh);
            });
        } catch (Exception persistErr) {
            log.warn("Failed to persist chain execution metadata for {}: {}",
                    chain.getChainCode(), persistErr.getMessage());
        }
    }

    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        try {
            if (condition.contains("==")) {
                String[] parts = condition.split("==");
                String path = parts[0].trim();
                String expected = parts[1].trim();
                Object actual = resolvePath(path, context);
                return String.valueOf(actual).equals(expected);
            }
            if (condition.startsWith("!")) {
                String path = condition.substring(1).trim();
                Object val = resolvePath(path, context);
                return !Boolean.TRUE.equals(val) && !"true".equals(String.valueOf(val));
            }
            Object val = resolvePath(condition, context);
            return Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val));
        } catch (Exception e) {
            log.debug("Condition evaluation failed: {} — treating as false", condition);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, Map<String, Object> context) {
        String[] parts = path.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private String extractFactoryId(ApplicationEvent event) {
        try {
            Method method = event.getClass().getMethod("getFactoryId");
            return (String) method.invoke(event);
        } catch (Exception e) {
            return null;
        }
    }
}

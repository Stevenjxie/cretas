package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.service.execution.MultiIntentExecutionService;
import com.cretas.aims.service.execution.SseStreamingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI意图执行服务实现 — Facade
 *
 * 保持 {@link IntentExecutorService} 接口完全不变，
 * 将实际工作委托给 5 个专注子服务：
 * <ul>
 *   <li>{@link IntentExecutionOrchestrator} — 核心 execute() 路由</li>
 *   <li>{@link com.cretas.aims.service.execution.ToolDispatchService} — 直接 Tool 执行</li>
 *   <li>{@link com.cretas.aims.service.execution.DynamicToolSelectionService} — 动态 Tool 选择 + Skill 路由</li>
 *   <li>{@link SseStreamingService} — SSE 流式执行</li>
 *   <li>{@link MultiIntentExecutionService} — 多意图拆分/合并</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-01-02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentExecutorServiceImpl implements IntentExecutorService {

    private final IntentExecutionOrchestrator orchestrator;
    private final SseStreamingService sseStreamingService;
    private final MultiIntentExecutionService multiIntentExecutionService;
    private final ConversationMemoryService conversationMemoryService;

    @Override
    public IntentExecuteResponse execute(String factoryId, IntentExecuteRequest request,
                                         Long userId, String userRole) {
        IntentExecuteResponse response = orchestrator.execute(factoryId, request, userId, userRole);
        populateConversationRound(request, response);
        return response;
    }

    @Override
    public IntentExecuteResponse preview(String factoryId, IntentExecuteRequest request,
                                         Long userId, String userRole) {
        request.setPreviewOnly(true);
        IntentExecuteResponse response = orchestrator.execute(factoryId, request, userId, userRole);
        populateConversationRound(request, response);
        return response;
    }

    /**
     * X1 Part A — populate {@code conversationRound} on the response.
     *
     * <p>The DTO field {@link IntentExecuteResponse#getConversationRound()} existed but was never
     * populated, so every multi-turn response reported a null round. This single facade chokepoint
     * fills it for ALL execution paths (success / clarification / no-match / conversational) because
     * the orchestrator has many early-return sites; populating here avoids touching every one.
     *
     * <p>Round is derived from the persisted conversation message count
     * ({@code (messageCount + 1) / 2}, 1-based, clamped to ≥ 1 once a session exists). After the
     * orchestrator's success path persists the current turn's user+assistant messages, messageCount
     * is even and the formula yields the current round; on early-return paths (no persist yet) it
     * yields the prior round — both are non-null and sane.
     *
     * <p>Fail-open: never crashes the response if the memory store is unavailable; the round simply
     * stays null. An explicit non-null round already set upstream is preserved.
     */
    private void populateConversationRound(IntentExecuteRequest request, IntentExecuteResponse response) {
        if (response == null || request == null) {
            return;
        }
        if (response.getConversationRound() != null) {
            return; // upstream already populated it — don't override
        }
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return; // standalone (sessionless) query — no round to report
        }
        try {
            ConversationContext context = conversationMemoryService.getContext(sessionId);
            if (context == null) {
                return;
            }
            int messageCount = context.getMessageCount();
            int round = Math.max(1, (messageCount + 1) / 2);
            response.setConversationRound(round);
        } catch (Exception e) {
            log.warn("populateConversationRound failed (fail-open, round left null): sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    @Override
    public IntentExecuteResponse confirm(String factoryId, ConfirmationProof confirmationProof,
                                          Long userId, String userRole) {
        return orchestrator.confirm(factoryId, confirmationProof, userId, userRole);
    }

    @Override
    public SseEmitter executeStream(String factoryId, IntentExecuteRequest request,
                                     Long userId, String userRole) {
        return sseStreamingService.executeStream(factoryId, request, userId, userRole);
    }

    @Override
    public IntentExecuteResponse executeMultiIntent(String factoryId, IntentExecuteRequest request,
                                                     Long userId, String userRole) {
        return multiIntentExecutionService.executeMultiIntent(factoryId, request, userId, userRole);
    }
}

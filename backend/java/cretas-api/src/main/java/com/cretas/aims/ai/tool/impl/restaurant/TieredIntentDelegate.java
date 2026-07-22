package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.client.GoldFinanceClient;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared tiered-intent delegate gate for legacy restaurant tools.
 *
 * <p>Sheet 7/22 菜品链事故: {@code RestaurantDishSalesRankingTool} 等旧餐饮工具
 * 直查工厂 ERP 表 (demo 租户为空 → "近 0 天暂无销售记录"), 而 Python 侧的
 * POS 菜品限域/时间窗/多轮上下文答案链已就绪。该组件把
 * {@code GoldBackedRestaurantTool} 的 Phase-2 委派门 (2026-07-07 design)
 * 提炼为可复用 bean, 让非 Gold 基类的旧工具也能先问 Python tiered 路由,
 * 命中即用其答案, 未命中/异常一律 fall through 原有流程 (never throws)。
 *
 * <p>与基类内联实现的差异: 不做 {@code ensureActionableMessage} 包装 (那是
 * Gold 工具族的 owner-action 框架), 委派答案原样返回。
 */
@Slf4j
@Component
public class TieredIntentDelegate {

    @Autowired
    private GoldFinanceClient gold;

    /**
     * Try the Python tiered router first. Returns a ready tool-result map when
     * Python answered ({@code delegate:true}), else {@code null} so the caller
     * runs its own flow. Never throws.
     */
    public Map<String, Object> tryDelegate(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context,
            String toolName) {
        try {
            String userInput = params != null ? asString(params.get("userInput")) : null;
            String sessionId = null;
            Object requestObj = context != null ? context.get("request") : null;
            if (requestObj instanceof IntentExecuteRequest req) {
                if (userInput == null || userInput.isBlank()) {
                    userInput = req.getUserInput();
                }
                sessionId = req.getSessionId();
            }
            if (userInput == null || userInput.isBlank()) {
                return null;
            }
            Map<String, Object> response = (sessionId != null && !sessionId.isBlank())
                    ? gold.fetchTieredIntentAnswer(factoryId, userInput, toolName, sessionId)
                    : gold.fetchTieredIntentAnswer(factoryId, userInput, toolName);
            if (response == null || !Boolean.TRUE.equals(response.get("delegate"))) {
                return null;
            }
            Object answerObj = response.get("answer_text");
            String answerText = answerObj != null ? answerObj.toString() : null;
            if (answerText == null || answerText.isBlank()) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dataAvailable", true);
            result.put("message", answerText);
            result.put("tieredDelegate", true);
            if (!"clarification".equals(response.get("kind"))) {
                result.put("charts", response.getOrDefault("charts", Collections.emptyList()));
                result.put("kpis", response.getOrDefault("kpis", Collections.emptyList()));
                if (response.get("code") != null) {
                    result.put("code", response.get("code"));
                }
                if (response.get("contract_pass") != null) {
                    result.put("contractPass", response.get("contract_pass"));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[{}] tiered delegate failed factory={}: {}", toolName, factoryId, e.getMessage());
            return null;
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}

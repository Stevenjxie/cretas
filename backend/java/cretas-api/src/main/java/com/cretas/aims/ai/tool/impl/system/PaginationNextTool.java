package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.service.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 翻页工具
 *
 * 处理用户请求翻页/下一页的场景。
 * 从对话记忆中读取上次查询意图，自动执行 page+1 翻页。
 *
 * Intent Code: PAGINATION_NEXT / NAVIGATION_NEXT_PAGE
 *
 * factoryId 隔离豁免说明: conversationMemoryService.getContext(sessionId) 是
 * 用户会话级 state (sessionId 来自 JWT userId + 会话), 不涉及跨工厂读取.
 * 翻页的 reused intent params 里已带原始查询的 factoryId.
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class PaginationNextTool extends AbstractBusinessTool {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    /**
     * 可翻页的查询类意图后缀
     */
    private static final Set<String> PAGINABLE_SUFFIXES = Set.of(
            "_LIST", "_QUERY", "_SEARCH", "_STATUS_QUERY",
            "_HISTORY", "_RECORDS", "_LOG"
    );

    @Override
    public String getToolName() {
        return "pagination_next";
    }

    @Override
    public String getDescription() {
        return "处理翻页请求，从对话记忆读取上次查询并执行下一页。" +
                "适用场景：用户说'下一页'、'翻页'、'更多'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pageProp = new HashMap<>();
        pageProp.put("type", "integer");
        pageProp.put("description", "目标页码，默认自动计算为上次页码+1");
        properties.put("targetPage", pageProp);
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("翻页请求 - 工厂ID: {}", factoryId);

        String sessionId = extractSessionId(context);
        Map<String, Object> result = new HashMap<>();

        if (sessionId == null || sessionId.isEmpty()) {
            log.info("无 sessionId，无法翻页");
            result.put("message", "当前没有查询记录，无法翻页。请先执行一次查询，例如：「查看库存」「查看订单」");
            return result;
        }

        // 从对话记忆中读取上下文
        ConversationContext convContext = conversationMemoryService.getContext(sessionId);

        if (convContext == null || convContext.getLastIntentCode() == null) {
            log.info("会话 {} 无上次查询记录", sessionId);
            result.put("message", "当前会话没有查询记录，无法翻页。请先执行一次查询，例如：「查看库存」「查看订单」");
            return result;
        }

        String lastIntentCode = convContext.getLastIntentCode();

        // 检查上次意图是否为可翻页的查询类意图
        if (!isPaginableIntent(lastIntentCode)) {
            log.info("上次意图 {} 不支持翻页", lastIntentCode);
            result.put("message", String.format(
                    "上次操作「%s」不支持翻页。翻页仅适用于列表查询类操作。请先执行一次列表查询，例如：「查看库存」「查看订单」",
                    lastIntentCode));
            return result;
        }

        // 计算目标页码
        Integer targetPage = getInteger(params, "targetPage");
        int currentPage = 1; // 默认当前在第1页

        // 尝试从最近的助手消息中提取当前页码
        List<ConversationMessage> recentMessages = convContext.getRecentMessages();
        if (recentMessages != null) {
            currentPage = extractCurrentPage(recentMessages);
        }

        int nextPage = targetPage != null ? targetPage : currentPage + 1;

        log.info("翻页: sessionId={}, lastIntent={}, currentPage={}, nextPage={}",
                sessionId, lastIntentCode, currentPage, nextPage);

        // 找到上次的用户查询输入
        String lastUserInput = findLastQueryInput(recentMessages);

        result.put("status", "PAGINATION_READY");
        result.put("lastIntentCode", lastIntentCode);
        result.put("sessionId", sessionId);
        result.put("currentPage", currentPage);
        result.put("nextPage", nextPage);
        result.put("reExecuteIntent", lastIntentCode);

        if (lastUserInput != null) {
            result.put("reExecuteInput", lastUserInput);
            result.put("paginationParams", Map.of("page", nextPage, "size", 10));
            result.put("message", String.format(
                    "正在为您加载第 %d 页数据（上次查询：%s）...",
                    nextPage, lastUserInput));
        } else {
            result.put("message", String.format(
                    "正在为您加载「%s」的第 %d 页数据...",
                    lastIntentCode, nextPage));
        }

        return result;
    }

    /**
     * 从工具上下文中提取 sessionId
     */
    private String extractSessionId(Map<String, Object> context) {
        Object request = context.get("request");
        if (request instanceof IntentExecuteRequest) {
            String sessionId = ((IntentExecuteRequest) request).getSessionId();
            if (sessionId != null && !sessionId.isEmpty()) {
                return sessionId;
            }
        }
        Object sessionId = context.get("sessionId");
        return sessionId != null ? sessionId.toString() : null;
    }

    /**
     * 判断意图是否支持翻页
     */
    private boolean isPaginableIntent(String intentCode) {
        if (intentCode == null) return false;
        String upper = intentCode.toUpperCase();
        return PAGINABLE_SUFFIXES.stream().anyMatch(upper::endsWith);
    }

    /**
     * 从最近消息中提取当前页码
     */
    private int extractCurrentPage(List<ConversationMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.getRole() == ConversationMessage.Role.ASSISTANT && msg.getMetadata() != null) {
                Object page = msg.getMetadata().get("currentPage");
                if (page instanceof Number) {
                    return ((Number) page).intValue();
                }
            }
        }
        return 1;
    }

    /**
     * 从最近消息中找到上次的查询输入
     */
    private String findLastQueryInput(List<ConversationMessage> messages) {
        if (messages == null) return null;
        Set<String> paginationPhrases = Set.of(
                "下一页", "翻页", "翻到下一页", "更多", "上一页"
        );
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (msg.getRole() == ConversationMessage.Role.USER) {
                String content = msg.getContent();
                if (content != null && !paginationPhrases.contains(content.trim())) {
                    return content;
                }
            }
        }
        return null;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}

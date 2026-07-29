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
 * 上下文继续工具
 *
 * 处理用户请求继续上一次操作的场景。
 * 从对话记忆中读取上次意图和上下文，返回给用户以便系统可以重新执行。
 *
 * Intent Code: CONTEXT_CONTINUE / CONTINUE_LAST_OPERATION / SYSTEM_RESUME_LAST_ACTION
 *
 * factoryId 隔离豁免说明: conversationMemoryService.getContext(sessionId) 是
 * 用户会话级 state (sessionId 来自当前 JWT userId + 登录会话), 不涉及跨工厂数据读取.
 * 会话所属工厂由调用方 factoryId 隐式限定.
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ContextContinueTool extends AbstractBusinessTool {

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Override
    public String getToolName() {
        return "context_continue";
    }

    @Override
    public String getDescription() {
        return "处理用户请求继续上一次操作的场景，从对话记忆恢复上次查询上下文。" +
                "适用场景：用户说'继续'、'接着'、'然后呢'、'再查一遍'等。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("上下文继续 - 工厂ID: {}", factoryId);

        // 从 context 中提取 sessionId
        String sessionId = extractSessionId(context);
        Map<String, Object> result = new HashMap<>();

        if (sessionId == null || sessionId.isEmpty()) {
            log.info("无 sessionId，无法恢复上下文");
            result.put("message", "当前没有历史对话记录。请告诉我您想查询什么？例如：「查看库存」「查看今天的生产数据」「看一下设备状态」");
            return result;
        }

        // 从对话记忆中读取上下文
        ConversationContext convContext = conversationMemoryService.getContext(sessionId);

        if (convContext == null || convContext.getLastIntentCode() == null) {
            log.info("会话 {} 无上次意图记录", sessionId);
            result.put("message", "当前会话没有上次操作记录。请告诉我您想查询什么？例如：「查看库存」「查看今天的生产数据」「看一下设备状态」");
            return result;
        }

        String lastIntentCode = convContext.getLastIntentCode();
        log.info("恢复上下文: sessionId={}, lastIntentCode={}", sessionId, lastIntentCode);

        // 构建上下文恢复信息
        result.put("lastIntentCode", lastIntentCode);
        result.put("sessionId", sessionId);
        result.put("status", "CONTEXT_RESTORED");

        // 提取最近的用户消息作为参考
        List<ConversationMessage> recentMessages = convContext.getRecentMessages();
        String lastUserInput = null;
        if (recentMessages != null && !recentMessages.isEmpty()) {
            // 从最近消息中找到最后一条用户消息（排除"继续"/"接着"等触发词本身）
            for (int i = recentMessages.size() - 1; i >= 0; i--) {
                ConversationMessage msg = recentMessages.get(i);
                if (msg.getRole() == ConversationMessage.Role.USER) {
                    String content = msg.getContent();
                    if (content != null && !isContextContinuePhrase(content)) {
                        lastUserInput = content;
                        break;
                    }
                }
            }
        }

        if (lastUserInput != null) {
            result.put("lastUserInput", lastUserInput);
            result.put("message", String.format(
                    "正在为您恢复上次操作。上次您查询的是：「%s」（意图: %s）。系统将自动重新执行此查询。",
                    lastUserInput, lastIntentCode));
            // 标记需要重新执行上次意图
            result.put("reExecuteIntent", lastIntentCode);
            result.put("reExecuteInput", lastUserInput);
        } else {
            result.put("message", String.format(
                    "上次执行的操作是: %s。请问您想继续执行同样的操作，还是有新的需求？",
                    lastIntentCode));
        }

        // 添加实体槽位信息（如果有）
        if (convContext.getEntitySlots() != null && !convContext.getEntitySlots().isEmpty()) {
            Map<String, String> slotSummary = new HashMap<>();
            convContext.getEntitySlots().forEach((type, slot) -> {
                String display = slot.getDisplayValue() != null ? slot.getDisplayValue() : slot.getName();
                if (display != null) {
                    slotSummary.put(type.name(), display);
                }
            });
            if (!slotSummary.isEmpty()) {
                result.put("contextEntities", slotSummary);
            }
        }

        return result;
    }

    /**
     * 从工具上下文中提取 sessionId
     */
    private String extractSessionId(Map<String, Object> context) {
        // 优先从 request 对象获取
        Object request = context.get("request");
        if (request instanceof IntentExecuteRequest) {
            String sessionId = ((IntentExecuteRequest) request).getSessionId();
            if (sessionId != null && !sessionId.isEmpty()) {
                return sessionId;
            }
        }
        // 回退: 从 context map 直接获取
        Object sessionId = context.get("sessionId");
        return sessionId != null ? sessionId.toString() : null;
    }

    /**
     * 判断是否为"继续/接着"等触发词（避免递归引用）
     */
    private boolean isContextContinuePhrase(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        return Set.of(
                "继续", "接着", "然后呢", "同上", "详细的呢",
                "跟刚才一样", "还是之前那个", "再查一遍",
                "一样的，再查", "刚才那个继续", "接上条",
                "上一个结果", "对就是这个再查"
        ).contains(trimmed);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}

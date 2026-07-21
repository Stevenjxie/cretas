package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.entity.conversation.ConversationMemory.EntitySlotData;
import com.cretas.aims.entity.conversation.ConversationMemory.MessageData;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.service.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话记忆服务实现
 *
 * 实现对话上下文管理、实体槽位跟踪、指代消解等功能
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Service
public class ConversationMemoryServiceImpl implements ConversationMemoryService {

    private final ConversationMemoryRepository memoryRepository;
    private final DashScopeClient dashScopeClient;

    /**
     * 滑动窗口大小（保留最近 N 轮消息）
     */
    @Value("${cretas.ai.memory.window-size:6}")
    private int windowSize;

    /**
     * 触发摘要更新的消息数阈值
     */
    @Value("${cretas.ai.memory.summary-threshold:10}")
    private int summaryThreshold;

    /**
     * 会话过期时间（分钟）
     */
    @Value("${cretas.ai.memory.expire-minutes:60}")
    private int expireMinutes;

    // ========== 指代模式定义 ==========

    /**
     * 批次指代模式
     */
    private static final Pattern BATCH_REFERENCE_PATTERN = Pattern.compile(
            "(这批|那批|该批次|这个批次|那个批次|此批|本批|上批|前一批)"
    );

    /**
     * 供应商指代模式
     */
    private static final Pattern SUPPLIER_REFERENCE_PATTERN = Pattern.compile(
            "(这家|那家|该供应商|这个供应商|那个供应商|他们|这家公司|那家公司|上家)"
    );

    /**
     * 门店指代模式
     */
    private static final Pattern STORE_REFERENCE_PATTERN = Pattern.compile(
            "(那家店|这家店|该店|那个店|这个店|该门店|那家|这家)"
    );

    /**
     * 菜品指代模式。"它" 仅在 DISH 优先于 STORE/SUPPLIER 解析，防止歧义：
     * resolveReference 按 DISH → STORE → SUPPLIER 顺序执行，
     * resolvePatternReference 只在对应 slotType 存在时替换，天然隔离。
     */
    private static final Pattern DISH_REFERENCE_PATTERN = Pattern.compile(
            "(那道菜|这道菜|该菜品|这个菜|那个菜|这款菜|那款菜|(?:那)?它)"
    );
    private static final Pattern AMBIGUOUS_ENTITY_REFERENCE_PATTERN = Pattern.compile(
            "((?:那)?它)"
    );

    /**
     * 客户指代模式
     */
    private static final Pattern CUSTOMER_REFERENCE_PATTERN = Pattern.compile(
            "(这个客户|那个客户|该客户|对方|这位客户|那位客户|此客户)"
    );

    /**
     * 产品指代模式
     */
    private static final Pattern PRODUCT_REFERENCE_PATTERN = Pattern.compile(
            "(这个产品|那个产品|该产品|这种货|那种货|此产品|本产品|这款|那款)"
    );

    /**
     * 时间范围指代模式
     */
    private static final Pattern TIME_RANGE_REFERENCE_PATTERN = Pattern.compile(
            "(那段时间|同期|这段时间|这个时间段|那个时间段|上次|之前)"
    );

    /**
     * 仓库指代模式
     */
    private static final Pattern WAREHOUSE_REFERENCE_PATTERN = Pattern.compile(
            "(那个仓库|这个仓库|这里|那里|该仓库|此仓库|本仓库)"
    );

    @Autowired
    public ConversationMemoryServiceImpl(
            ConversationMemoryRepository memoryRepository,
            @Autowired(required = false) DashScopeClient dashScopeClient) {
        this.memoryRepository = memoryRepository;
        this.dashScopeClient = dashScopeClient;
    }

    @Override
    @Transactional
    public ConversationContext getOrCreateContext(String factoryId, Long userId, String sessionId) {
        log.debug("获取或创建对话上下文: factoryId={}, userId={}, sessionId={}", factoryId, userId, sessionId);

        ConversationMemory memory;
        boolean isNew = false;

        if (sessionId != null && !sessionId.isEmpty()) {
            // 根据 sessionId 查找
            Optional<ConversationMemory> existingOpt = memoryRepository.findBySessionId(sessionId);
            if (existingOpt.isPresent()) {
                memory = existingOpt.get();
                memory.touch();
            } else {
                // sessionId 不存在，创建新的
                memory = createNewMemory(factoryId, userId, sessionId);
                isNew = true;
            }
        } else {
            // 查找用户最近的活跃会话
            Optional<ConversationMemory> existingOpt = memoryRepository.findLatestActiveByFactoryIdAndUserId(factoryId, userId);
            if (existingOpt.isPresent()) {
                memory = existingOpt.get();
                // 检查是否过期
                if (isSessionExpired(memory)) {
                    // 过期，创建新会话
                    memory.softDelete();
                    memoryRepository.save(memory);
                    memory = createNewMemory(factoryId, userId, null);
                    isNew = true;
                } else {
                    memory.touch();
                }
            } else {
                memory = createNewMemory(factoryId, userId, null);
                isNew = true;
            }
        }

        memoryRepository.save(memory);

        return buildContext(memory, isNew);
    }

    @Override
    @Transactional
    public void updateEntitySlot(String sessionId, EntitySlot.SlotType type, EntitySlot slot) {
        log.debug("更新实体槽位: sessionId={}, type={}, slot={}", sessionId, type, slot);

        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        ConversationMemory memory = memoryOpt.get();
        Map<String, EntitySlotData> slots = memory.getEntitySlots();
        if (slots == null) {
            slots = new HashMap<>();
        }

        // 转换 EntitySlot 到 EntitySlotData
        EntitySlotData slotData = EntitySlotData.builder()
                .type(type.name())
                .id(slot.getId())
                .name(slot.getName())
                .displayValue(slot.getDisplayValue())
                .metadata(slot.getMetadata())
                .mentionedAt(slot.getMentionedAt() != null ? slot.getMentionedAt().toString() : LocalDateTime.now().toString())
                .mentionCount(slot.getMentionCount())
                .build();

        // 如果槽位已存在，增加提及次数
        if (slots.containsKey(type.name())) {
            EntitySlotData existing = slots.get(type.name());
            slotData.setMentionCount(existing.getMentionCount() + 1);
        }

        slots.put(type.name(), slotData);
        memory.setEntitySlots(slots);
        memory.touch();

        memoryRepository.save(memory);
    }

    @Override
    @Transactional(readOnly = true)
    public EntitySlot getEntitySlot(String sessionId, EntitySlot.SlotType type) {
        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return null;
        }

        ConversationMemory memory = memoryOpt.get();
        Map<String, EntitySlotData> slots = memory.getEntitySlots();
        if (slots == null || !slots.containsKey(type.name())) {
            return null;
        }

        EntitySlotData slotData = slots.get(type.name());
        return convertToEntitySlot(slotData);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveReference(String sessionId, String referenceText) {
        log.debug("指代消解: sessionId={}, text={}", sessionId, referenceText);

        if (referenceText == null || referenceText.isEmpty()) {
            return referenceText;
        }

        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return referenceText;
        }

        ConversationMemory memory = memoryOpt.get();
        Map<String, EntitySlotData> slots = memory.getEntitySlots();
        if (slots == null || slots.isEmpty()) {
            return referenceText;
        }

        String result = referenceText;

        // 尝试匹配各种指代模式
        // 注意顺序：DISH 先于 STORE/SUPPLIER，防止"它"被错误匹配到非菜品实体
        result = resolvePatternReference(result, BATCH_REFERENCE_PATTERN, slots, EntitySlot.SlotType.BATCH.name());
        result = resolvePatternReference(result, DISH_REFERENCE_PATTERN,  slots, EntitySlot.SlotType.DISH.name());
        // 若没有可用 DISH 槽，上一步保持原文；此时允许最近的 STORE 槽接管“它/那它”。
        // 若 DISH 已成功替换，原文中已无该代词，本行自然无操作。
        result = resolvePatternReference(result, AMBIGUOUS_ENTITY_REFERENCE_PATTERN, slots, EntitySlot.SlotType.STORE.name());
        result = resolvePatternReference(result, STORE_REFERENCE_PATTERN, slots, EntitySlot.SlotType.STORE.name());
        result = resolvePatternReference(result, SUPPLIER_REFERENCE_PATTERN, slots, EntitySlot.SlotType.SUPPLIER.name());
        result = resolvePatternReference(result, CUSTOMER_REFERENCE_PATTERN, slots, EntitySlot.SlotType.CUSTOMER.name());
        result = resolvePatternReference(result, PRODUCT_REFERENCE_PATTERN, slots, EntitySlot.SlotType.PRODUCT.name());
        result = resolvePatternReference(result, TIME_RANGE_REFERENCE_PATTERN, slots, EntitySlot.SlotType.TIME_RANGE.name());
        result = resolvePatternReference(result, WAREHOUSE_REFERENCE_PATTERN, slots, EntitySlot.SlotType.WAREHOUSE.name());

        log.debug("指代消解结果: {} -> {}", referenceText, result);
        return result;
    }

    /**
     * 根据模式解析指代
     */
    private String resolvePatternReference(String text, Pattern pattern, Map<String, EntitySlotData> slots, String slotType) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find() && slots.containsKey(slotType)) {
            EntitySlotData slot = slots.get(slotType);
            String replacement = slot.getDisplayValue() != null ? slot.getDisplayValue() : slot.getName();
            return matcher.replaceFirst(replacement);
        }
        return text;
    }

    @Override
    @Transactional
    public void addMessage(String sessionId, ConversationMessage message) {
        log.debug("添加消息: sessionId={}, role={}", sessionId, message.getRole());

        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        ConversationMemory memory = memoryOpt.get();

        // 获取现有消息列表
        List<MessageData> messages = memory.getRecentMessages();
        if (messages == null) {
            messages = new ArrayList<>();
        }

        // 添加新消息
        MessageData messageData = MessageData.builder()
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .timestamp(message.getTimestamp() != null ? message.getTimestamp().toString() : LocalDateTime.now().toString())
                .intentCode(message.getIntentCode())
                .metadata(message.getMetadata())
                .build();
        messages.add(messageData);

        // 滑动窗口：保留最近 windowSize * 2 条消息（用户+助手各算一条）
        int maxMessages = windowSize * 2;
        if (messages.size() > maxMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
        }

        memory.setRecentMessages(messages);
        memory.incrementMessageCount();
        memory.touch();

        // 更新最后意图
        if (message.getIntentCode() != null && !message.getIntentCode().isEmpty()) {
            memory.setLastIntentCode(message.getIntentCode());
        }

        memoryRepository.save(memory);

        // 检查是否需要更新摘要
        if (memory.needsSummaryUpdate()) {
            updateSummary(sessionId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationMessage> getRecentMessages(String sessionId, int limit) {
        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return Collections.emptyList();
        }

        ConversationMemory memory = memoryOpt.get();
        List<MessageData> messages = memory.getRecentMessages();
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        // 获取最后 limit 条消息
        int start = Math.max(0, messages.size() - limit);
        return messages.subList(start, messages.size()).stream()
                .map(this::convertToConversationMessage)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateSummary(String sessionId) {
        log.info("更新对话摘要: sessionId={}", sessionId);

        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return;
        }

        ConversationMemory memory = memoryOpt.get();

        // 如果 DashScope 不可用，跳过摘要更新
        if (dashScopeClient == null || !dashScopeClient.isAvailable()) {
            log.debug("DashScope 不可用，跳过摘要更新");
            return;
        }

        try {
            // 构建要摘要的内容
            StringBuilder conversationHistory = new StringBuilder();
            List<MessageData> messages = memory.getRecentMessages();
            if (messages != null) {
                for (MessageData msg : messages) {
                    String role = "user".equals(msg.getRole()) ? "用户" : "助手";
                    conversationHistory.append(role).append(": ").append(msg.getContent()).append("\n");
                }
            }

            // 如果已有摘要，也包含进去
            if (memory.getConversationSummary() != null && !memory.getConversationSummary().isEmpty()) {
                conversationHistory.insert(0, "之前的摘要: " + memory.getConversationSummary() + "\n\n最近对话:\n");
            }

            // 调用 LLM 生成摘要
            String systemPrompt = "你是一个对话摘要助手。请用简洁的语言总结以下对话内容，" +
                    "保留关键信息（如提到的批次号、供应商、客户、时间范围等）。" +
                    "摘要应该在 100 字以内。只输出摘要内容，不要有其他说明。";

            String summary = dashScopeClient.chat(systemPrompt, conversationHistory.toString());

            // 更新摘要
            memory.setConversationSummary(summary.trim());
            memory.setSummaryUpdatedAt(LocalDateTime.now());

            memoryRepository.save(memory);
            log.info("摘要更新完成: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("更新摘要失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String buildContextForLLM(String sessionId) {
        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return "";
        }

        ConversationMemory memory = memoryOpt.get();
        StringBuilder context = new StringBuilder();

        // 1. 添加实体槽位信息
        Map<String, EntitySlotData> slots = memory.getEntitySlots();
        if (slots != null && !slots.isEmpty()) {
            context.append("## 当前上下文实体\n\n");
            for (Map.Entry<String, EntitySlotData> entry : slots.entrySet()) {
                EntitySlotData slot = entry.getValue();
                context.append(String.format("- %s: %s (ID: %s)\n",
                        getSlotTypeDisplayName(entry.getKey()),
                        slot.getDisplayValue() != null ? slot.getDisplayValue() : slot.getName(),
                        slot.getId()));
            }
            context.append("\n");
        }

        // 2. 添加对话摘要
        if (memory.getConversationSummary() != null && !memory.getConversationSummary().isEmpty()) {
            context.append("## 对话历史摘要\n\n");
            context.append(memory.getConversationSummary()).append("\n\n");
        }

        // 3. 添加最近消息
        List<MessageData> messages = memory.getRecentMessages();
        if (messages != null && !messages.isEmpty()) {
            context.append("## 最近对话\n\n");
            // 只取最近 4 轮
            int start = Math.max(0, messages.size() - 8);
            for (int i = start; i < messages.size(); i++) {
                MessageData msg = messages.get(i);
                String role = "user".equals(msg.getRole()) ? "用户" : "助手";
                context.append(role).append(": ").append(msg.getContent()).append("\n\n");
            }
        }

        return context.toString();
    }

    @Override
    @Transactional
    public void clearSession(String sessionId) {
        log.info("清除会话: sessionId={}", sessionId);
        memoryRepository.softDeleteBySessionId(sessionId, LocalDateTime.now());
    }

    @Override
    @Transactional
    public void updateLastIntent(String sessionId, String intentCode) {
        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        if (memoryOpt.isEmpty()) {
            return;
        }

        ConversationMemory memory = memoryOpt.get();
        memory.setLastIntentCode(intentCode);
        memory.touch();
        memoryRepository.save(memory);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationContext getContext(String sessionId) {
        Optional<ConversationMemory> memoryOpt = memoryRepository.findBySessionId(sessionId);
        return memoryOpt.map(memory -> buildContext(memory, false)).orElse(null);
    }

    @Override
    @Transactional
    public int expireOldSessions(int expireMinutes) {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(expireMinutes);
        int count = memoryRepository.expireOldSessions(expireTime, LocalDateTime.now());
        if (count > 0) {
            log.info("已过期 {} 个旧会话", count);
        }
        return count;
    }

    // ========== 私有方法 ==========

    /**
     * 创建新的记忆实体
     */
    private ConversationMemory createNewMemory(String factoryId, Long userId, String sessionId) {
        return ConversationMemory.builder()
                .factoryId(factoryId)
                .userId(userId)
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString())
                .messageCount(0)
                .lastActiveAt(LocalDateTime.now())
                .entitySlots(new HashMap<>())
                .recentMessages(new ArrayList<>())
                .userPreferences(new HashMap<>())
                .build();
    }

    /**
     * 检查会话是否过期
     */
    private boolean isSessionExpired(ConversationMemory memory) {
        if (memory.getLastActiveAt() == null) {
            return false;
        }
        return memory.getLastActiveAt().plusMinutes(expireMinutes).isBefore(LocalDateTime.now());
    }

    /**
     * 构建对话上下文
     */
    private ConversationContext buildContext(ConversationMemory memory, boolean isNew) {
        // 转换实体槽位
        Map<EntitySlot.SlotType, EntitySlot> entitySlots = new HashMap<>();
        if (memory.getEntitySlots() != null) {
            for (Map.Entry<String, EntitySlotData> entry : memory.getEntitySlots().entrySet()) {
                try {
                    EntitySlot.SlotType type = EntitySlot.SlotType.valueOf(entry.getKey());
                    entitySlots.put(type, convertToEntitySlot(entry.getValue()));
                } catch (IllegalArgumentException e) {
                    log.warn("未知的槽位类型: {}", entry.getKey());
                }
            }
        }

        // 转换消息列表
        List<ConversationMessage> messages = new ArrayList<>();
        if (memory.getRecentMessages() != null) {
            messages = memory.getRecentMessages().stream()
                    .map(this::convertToConversationMessage)
                    .collect(Collectors.toList());
        }

        return ConversationContext.builder()
                .sessionId(memory.getSessionId())
                .factoryId(memory.getFactoryId())
                .userId(memory.getUserId())
                .entitySlots(entitySlots)
                .recentMessages(messages)
                .conversationSummary(memory.getConversationSummary())
                .userPreferences(memory.getUserPreferences())
                .lastIntentCode(memory.getLastIntentCode())
                .messageCount(memory.getMessageCount() != null ? memory.getMessageCount() : 0)
                .lastActiveAt(memory.getLastActiveAt())
                .newSession(isNew)
                .build();
    }

    /**
     * 转换 EntitySlotData 到 EntitySlot
     */
    private EntitySlot convertToEntitySlot(EntitySlotData slotData) {
        EntitySlot.SlotType type;
        try {
            type = EntitySlot.SlotType.valueOf(slotData.getType());
        } catch (IllegalArgumentException e) {
            type = EntitySlot.SlotType.BATCH; // 默认值
        }

        LocalDateTime mentionedAt = null;
        if (slotData.getMentionedAt() != null) {
            try {
                mentionedAt = LocalDateTime.parse(slotData.getMentionedAt());
            } catch (Exception e) {
                mentionedAt = LocalDateTime.now();
            }
        }

        return EntitySlot.builder()
                .type(type)
                .id(slotData.getId())
                .name(slotData.getName())
                .displayValue(slotData.getDisplayValue())
                .metadata(slotData.getMetadata())
                .mentionedAt(mentionedAt)
                .mentionCount(slotData.getMentionCount())
                .build();
    }

    /**
     * 转换 MessageData 到 ConversationMessage
     */
    private ConversationMessage convertToConversationMessage(MessageData msgData) {
        ConversationMessage.Role role;
        try {
            role = ConversationMessage.Role.valueOf(msgData.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            role = ConversationMessage.Role.USER;
        }

        LocalDateTime timestamp = null;
        if (msgData.getTimestamp() != null) {
            try {
                timestamp = LocalDateTime.parse(msgData.getTimestamp());
            } catch (Exception e) {
                timestamp = LocalDateTime.now();
            }
        }

        return ConversationMessage.builder()
                .role(role)
                .content(msgData.getContent())
                .timestamp(timestamp)
                .intentCode(msgData.getIntentCode())
                .metadata(msgData.getMetadata())
                .build();
    }

    /**
     * 获取槽位类型的显示名称
     */
    private String getSlotTypeDisplayName(String slotType) {
        switch (slotType) {
            case "BATCH":
                return "批次";
            case "SUPPLIER":
                return "供应商";
            case "STORE":
                return "门店";
            case "CUSTOMER":
                return "客户";
            case "PRODUCT":
                return "产品";
            case "TIME_RANGE":
                return "时间范围";
            case "WAREHOUSE":
                return "仓库";
            case "EQUIPMENT":
                return "设备";
            case "EMPLOYEE":
                return "员工";
            case "ORDER":
                return "订单";
            default:
                return slotType;
        }
    }
}

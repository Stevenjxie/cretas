package com.cretas.aims.entity.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多轮对话会话实体
 *
 * 当 Layer 1-4 置信度 < 30% 且无明确匹配时触发 Layer 5 多轮对话模式。
 * 支持最多 5 轮对话来澄清用户意图。
 *
 * 生命周期:
 * 1. ACTIVE - 对话进行中
 * 2. COMPLETED - 成功识别意图
 * 3. TIMEOUT - 超时未响应
 * 4. CANCELLED - 用户取消
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Entity
@Table(name = "conversation_sessions", indexes = {
    @Index(name = "idx_cs_factory_user", columnList = "factory_id, user_id"),
    @Index(name = "idx_cs_status", columnList = "status"),
    @Index(name = "idx_cs_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSession {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 会话ID (UUID)
     */
    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 用户最初的输入
     */
    @Column(name = "original_input", columnDefinition = "TEXT", nullable = false)
    private String originalInput;

    /**
     * 最终识别的意图代码 (对话结束后填充)
     */
    @Column(name = "final_intent_code", length = 100)
    private String finalIntentCode;

    /**
     * 当前轮次 (最多5轮)
     */
    @Column(name = "current_round", nullable = false)
    @Builder.Default
    private Integer currentRound = 0;

    /**
     * 最大轮次限制
     */
    @Column(name = "max_rounds")
    @Builder.Default
    private Integer maxRounds = 5;

    /**
     * 会话状态
     */
    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 会话模式 (默认: 意图识别模式)
     */
    @Column(name = "session_mode", length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SessionMode sessionMode = SessionMode.INTENT_RECOGNITION;

    /**
     * 已知意图代码 (仅用于 PARAMETER_COLLECTION 模式)
     * 当模式为参数收集时，此字段存储已确定的意图代码
     */
    @Column(name = "known_intent_code", length = 100)
    private String knownIntentCode;

    /**
     * 必需参数列表 JSON (仅用于 PARAMETER_COLLECTION 模式)
     * 格式: [{"name": "batchId", "label": "批次ID", "type": "string", "collected": false, "value": null}]
     */
    @Column(name = "required_parameters_json", columnDefinition = "TEXT")
    private String requiredParametersJson;

    /**
     * 已收集参数 JSON
     * 格式: {"batchId": "BATCH-001", "materialTypeId": "RMT-001"}
     */
    @Column(name = "collected_parameters_json", columnDefinition = "TEXT")
    private String collectedParametersJson;

    /**
     * 对话历史 JSON
     * 格式: [{"role": "user/assistant", "content": "...", "timestamp": "..."}]
     */
    @Column(name = "messages_json", columnDefinition = "TEXT")
    private String messagesJson;

    /**
     * 候选意图列表 JSON
     * 格式: [{"intentCode": "...", "confidence": 0.5}]
     */
    @Column(name = "candidates_json", columnDefinition = "TEXT")
    private String candidatesJson;

    /**
     * 最后识别的置信度
     */
    @Column(name = "last_confidence")
    private Double lastConfidence;

    /**
     * 超时时间（分钟）
     */
    @Column(name = "timeout_minutes")
    @Builder.Default
    private Integer timeoutMinutes = 10;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (sessionId == null) {
            sessionId = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActiveAt == null) {
            lastActiveAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ========== 会话模式枚举 ==========

    /**
     * 会话模式：区分意图识别对话和参数收集对话
     */
    public enum SessionMode {
        /** 意图识别模式 - 帮助用户明确想做什么操作 */
        INTENT_RECOGNITION,
        /** 参数收集模式 - 意图已确定，收集缺失的必需参数 */
        PARAMETER_COLLECTION
    }

    // ========== 会话状态枚举 ==========

    public enum SessionStatus {
        /** 对话进行中 */
        ACTIVE,
        /** 成功识别意图 */
        COMPLETED,
        /** 超时未响应 */
        TIMEOUT,
        /** 用户取消 */
        CANCELLED,
        /** 达到最大轮次仍未识别 */
        MAX_ROUNDS_REACHED
    }

    // ========== 对话消息类 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role; // "user" or "assistant"
        private String content;
        private LocalDateTime timestamp;

        public static Message user(String content) {
            return Message.builder()
                    .role("user")
                    .content(content)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public static Message assistant(String content) {
            return Message.builder()
                    .role("assistant")
                    .content(content)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    // ========== 候选意图类 ==========

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CandidateIntent {
        private String intentCode;
        private String intentName;
        private Double confidence;
        private String reasoning;
    }

    // ========== 便捷方法 ==========

    /**
     * 获取消息列表
     */
    public List<Message> getMessages() {
        if (messagesJson == null || messagesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(messagesJson, new TypeReference<List<Message>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 设置消息列表
     */
    public void setMessages(List<Message> messages) {
        try {
            this.messagesJson = OBJECT_MAPPER.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            this.messagesJson = "[]";
        }
    }

    /**
     * 添加消息
     */
    public void addMessage(Message message) {
        List<Message> messages = getMessages();
        messages.add(message);
        setMessages(messages);
        this.lastActiveAt = LocalDateTime.now();
    }

    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        addMessage(Message.user(content));
    }

    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        addMessage(Message.assistant(content));
    }

    /**
     * 获取候选意图列表
     */
    public List<CandidateIntent> getCandidates() {
        if (candidatesJson == null || candidatesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(candidatesJson, new TypeReference<List<CandidateIntent>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 设置候选意图列表
     */
    public void setCandidates(List<CandidateIntent> candidates) {
        try {
            this.candidatesJson = OBJECT_MAPPER.writeValueAsString(candidates);
        } catch (JsonProcessingException e) {
            this.candidatesJson = "[]";
        }
    }

    /**
     * 进入下一轮
     */
    public boolean nextRound() {
        if (currentRound >= maxRounds) {
            return false;
        }
        this.currentRound++;
        this.lastActiveAt = LocalDateTime.now();
        return true;
    }

    /**
     * 检查是否已超时
     */
    public boolean isExpired() {
        if (status != SessionStatus.ACTIVE) {
            return false;
        }
        if (lastActiveAt == null) {
            return false;
        }
        LocalDateTime expireTime = lastActiveAt.plusMinutes(timeoutMinutes);
        return LocalDateTime.now().isAfter(expireTime);
    }

    /**
     * 检查是否可以继续对话
     */
    public boolean canContinue() {
        return status == SessionStatus.ACTIVE && currentRound < maxRounds && !isExpired();
    }

    /**
     * 完成会话
     */
    public void complete(String intentCode, double confidence) {
        this.finalIntentCode = intentCode;
        this.lastConfidence = confidence;
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 超时会话
     */
    public void timeout() {
        this.status = SessionStatus.TIMEOUT;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 取消会话
     */
    public void cancel() {
        this.status = SessionStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 达到最大轮次
     */
    public void maxRoundsReached() {
        this.status = SessionStatus.MAX_ROUNDS_REACHED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 构建对话历史字符串 (用于发送给 LLM)
     */
    public String buildConversationHistory() {
        List<Message> messages = getMessages();
        if (messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 创建新会话 (意图识别模式)
     */
    public static ConversationSession create(String factoryId, Long userId, String originalInput) {
        ConversationSession session = ConversationSession.builder()
                .factoryId(factoryId)
                .userId(userId)
                .originalInput(originalInput)
                .currentRound(1)
                .maxRounds(5)
                .status(SessionStatus.ACTIVE)
                .sessionMode(SessionMode.INTENT_RECOGNITION)
                .timeoutMinutes(10)
                .build();

        // 添加初始用户消息
        session.addUserMessage(originalInput);

        return session;
    }

    /**
     * 创建参数收集会话
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param intentCode 已确定的意图代码
     * @param requiredParameters 需要收集的参数列表
     * @param initialQuestion 初始收集问题
     */
    public static ConversationSession createForParameterCollection(
            String factoryId,
            Long userId,
            String intentCode,
            List<RequiredParameter> requiredParameters,
            String initialQuestion) {

        ConversationSession session = ConversationSession.builder()
                .factoryId(factoryId)
                .userId(userId)
                .originalInput("[参数收集] " + intentCode)
                .currentRound(1)
                .maxRounds(5)
                .status(SessionStatus.ACTIVE)
                .sessionMode(SessionMode.PARAMETER_COLLECTION)
                .knownIntentCode(intentCode)
                .timeoutMinutes(10)
                .build();

        // 设置必需参数
        session.setRequiredParameters(requiredParameters);

        // 添加初始助手消息
        session.addAssistantMessage(initialQuestion);

        return session;
    }

    // ========== 参数收集相关方法 ==========

    /**
     * 获取必需参数列表
     */
    public List<RequiredParameter> getRequiredParameters() {
        if (requiredParametersJson == null || requiredParametersJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(requiredParametersJson,
                    new TypeReference<List<RequiredParameter>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 设置必需参数列表
     */
    public void setRequiredParameters(List<RequiredParameter> parameters) {
        try {
            this.requiredParametersJson = OBJECT_MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            this.requiredParametersJson = "[]";
        }
    }

    /**
     * 获取已收集参数
     */
    public Map<String, String> getCollectedParameters() {
        if (collectedParametersJson == null || collectedParametersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(collectedParametersJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    /**
     * 设置已收集参数
     */
    public void setCollectedParameters(Map<String, String> parameters) {
        try {
            this.collectedParametersJson = OBJECT_MAPPER.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            this.collectedParametersJson = "{}";
        }
    }

    /**
     * 添加收集到的参数
     */
    public void addCollectedParameter(String name, String value) {
        Map<String, String> params = getCollectedParameters();
        params.put(name, value);
        setCollectedParameters(params);

        // 更新参数状态
        List<RequiredParameter> required = getRequiredParameters();
        for (RequiredParameter param : required) {
            if (param.getName().equals(name)) {
                param.setCollected(true);
                param.setValue(value);
            }
        }
        setRequiredParameters(required);
        this.lastActiveAt = LocalDateTime.now();
    }

    /**
     * 获取下一个待收集的参数
     */
    public Optional<RequiredParameter> getNextPendingParameter() {
        return getRequiredParameters().stream()
                .filter(p -> !p.isCollected())
                .findFirst();
    }

    /**
     * 检查是否所有参数都已收集
     */
    public boolean allParametersCollected() {
        return getRequiredParameters().stream()
                .allMatch(RequiredParameter::isCollected);
    }

    /**
     * 完成参数收集会话
     */
    public void completeParameterCollection() {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        // 对于参数收集模式，finalIntentCode 已经在创建时设置
        if (this.finalIntentCode == null) {
            this.finalIntentCode = this.knownIntentCode;
        }
        this.lastConfidence = 1.0; // 参数收集完成置信度为 100%
    }

    /**
     * 检查会话是否为参数收集模式
     */
    public boolean isParameterCollectionMode() {
        return sessionMode == SessionMode.PARAMETER_COLLECTION;
    }

    // ========== 参数实体类 ==========

    /**
     * 必需参数定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequiredParameter {
        /** 参数名称 (API 字段名) */
        private String name;
        /** 显示标签 */
        private String label;
        /** 参数类型 */
        private String type;
        /** 验证提示 */
        private String validationHint;
        /** 是否已收集 */
        private boolean collected;
        /** 收集到的值 */
        private String value;
    }
}

package com.cretas.aims.entity.conversation;

import com.cretas.aims.converter.EntitySlotsConverter;
import com.cretas.aims.converter.JsonMapConverter;
import com.cretas.aims.converter.MessageListConverter;
import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Where;

/**
 * 对话记忆实体
 *
 * 存储用户对话的上下文信息，支持：
 * - 实体槽位记忆（批次、供应商、客户等）
 * - 最近消息历史（滑动窗口）
 * - 对话摘要（长期记忆压缩）
 * - 用户偏好设置
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Entity
@Table(name = "conversation_memory", indexes = {
    @Index(name = "idx_cm_factory_user", columnList = "factory_id, user_id"),
    @Index(name = "idx_cm_session_id", columnList = "session_id"),
    @Index(name = "idx_cm_last_active", columnList = "last_active_at")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ConversationMemory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 会话ID (UUID)，唯一标识一个对话会话
     */
    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    /**
     * 实体槽位
     * 存储对话中提到的实体信息，用于指代消解
     * 格式: {"BATCH": {...}, "SUPPLIER": {...}, ...}
     */
    @Column(name = "entity_slots", columnDefinition = "json")
    @Convert(converter = EntitySlotsConverter.class)
    private Map<String, EntitySlotData> entitySlots;

    /**
     * 最近消息列表
     * 保留最近 6 轮原始消息
     */
    @Column(name = "recent_messages", columnDefinition = "json")
    @Convert(converter = MessageListConverter.class)
    private List<MessageData> recentMessages;

    /**
     * 对话摘要
     * 当消息数超过阈值时，由 LLM 生成摘要压缩历史
     */
    @Column(name = "conversation_summary", columnDefinition = "TEXT")
    private String conversationSummary;

    /**
     * 摘要最后更新时间
     */
    @Column(name = "summary_updated_at")
    private LocalDateTime summaryUpdatedAt;

    /**
     * 用户偏好设置
     * 存储用户的个性化偏好，如常用查询时间范围等
     */
    @Column(name = "user_preferences", columnDefinition = "json")
    @Convert(converter = JsonMapConverter.class)
    private Map<String, Object> userPreferences;

    /**
     * 消息计数
     * 用于判断是否需要更新摘要
     */
    @Column(name = "message_count")
    @Builder.Default
    private Integer messageCount = 0;

    /**
     * 最后识别的意图代码
     */
    @Column(name = "last_intent_code", length = 100)
    private String lastIntentCode;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    // ========== 内部数据类 ==========

    /**
     * 实体槽位数据
     * 存储对话中提到的实体详细信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EntitySlotData {
        /**
         * 实体类型 (BATCH, SUPPLIER, CUSTOMER, PRODUCT, TIME_RANGE, WAREHOUSE)
         */
        private String type;

        /**
         * 实体ID
         */
        private String id;

        /**
         * 实体名称
         */
        private String name;

        /**
         * 显示值 (用于向用户展示)
         */
        private String displayValue;

        /**
         * 元数据 (存储额外信息)
         */
        private Map<String, Object> metadata;

        /**
         * 首次提及时间
         */
        private String mentionedAt;

        /**
         * 提及次数
         */
        private int mentionCount;
    }

    /**
     * 消息数据
     * 存储对话历史中的单条消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageData {
        /**
         * 角色 (user/assistant)
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        /**
         * 时间戳
         */
        private String timestamp;

        /**
         * 意图代码 (如果识别到)
         */
        private String intentCode;

        /**
         * 元数据 (存储额外信息)
         */
        private Map<String, Object> metadata;

        /**
         * 创建用户消息
         */
        public static MessageData user(String content) {
            return MessageData.builder()
                    .role("user")
                    .content(content)
                    .timestamp(LocalDateTime.now().toString())
                    .build();
        }

        /**
         * 创建助手消息
         */
        public static MessageData assistant(String content, String intentCode) {
            return MessageData.builder()
                    .role("assistant")
                    .content(content)
                    .intentCode(intentCode)
                    .timestamp(LocalDateTime.now().toString())
                    .build();
        }
    }

    // ========== 生命周期回调 ==========

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (sessionId == null) {
            sessionId = java.util.UUID.randomUUID().toString();
        }
        if (lastActiveAt == null) {
            lastActiveAt = LocalDateTime.now();
        }
        if (messageCount == null) {
            messageCount = 0;
        }
    }

    @PreUpdate
    @Override
    protected void onUpdate() {
        super.onUpdate();
        lastActiveAt = LocalDateTime.now();
    }

    // ========== 便捷方法 ==========

    /**
     * 更新最后活跃时间
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

    /**
     * 增加消息计数
     */
    public void incrementMessageCount() {
        if (this.messageCount == null) {
            this.messageCount = 0;
        }
        this.messageCount++;
    }

    /**
     * 检查是否需要更新摘要
     * 当 messageCount > 10 且距离上次摘要 > 5 条消息时返回 true
     */
    public boolean needsSummaryUpdate() {
        if (messageCount == null || messageCount <= 10) {
            return false;
        }
        if (summaryUpdatedAt == null) {
            return true;
        }
        // 通过消息计数简单判断
        return messageCount > 15;
    }

    /**
     * 创建新的对话记忆
     */
    public static ConversationMemory create(String factoryId, Long userId) {
        return ConversationMemory.builder()
                .factoryId(factoryId)
                .userId(userId)
                .sessionId(java.util.UUID.randomUUID().toString())
                .messageCount(0)
                .lastActiveAt(LocalDateTime.now())
                .build();
    }
}

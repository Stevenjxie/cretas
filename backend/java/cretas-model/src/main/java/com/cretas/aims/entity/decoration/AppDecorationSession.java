package com.cretas.aims.entity.decoration;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;

import jakarta.persistence.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Where;

/**
 * AI装饰会话实体
 *
 * 记录用户与AI进行首页布局装饰的对话会话:
 * - 保存用户输入的提示词
 * - 记录AI生成的配置
 * - 跟踪会话状态和对话历史
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Entity
@Table(name = "app_decoration_session",
       uniqueConstraints = @UniqueConstraint(columnNames = {"session_id"}),
       indexes = {
           @Index(name = "idx_decoration_session_factory", columnList = "factory_id"),
           @Index(name = "idx_decoration_session_user", columnList = "user_id"),
           @Index(name = "idx_decoration_session_status", columnList = "status")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AppDecorationSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 会话ID (UUID格式, 唯一)
     */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

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
     * 用户输入的提示词
     */
    @Column(name = "user_prompt", columnDefinition = "TEXT", nullable = false)
    private String userPrompt;

    /**
     * 识别的意图代码
     * 关联 ai_intent_configs.intent_code
     * 如: HOME_LAYOUT_UPDATE, HOME_LAYOUT_GENERATE
     */
    @Column(name = "intent_code", length = 50)
    private String intentCode;

    /**
     * AI生成的配置 JSON
     * 包含模块配置、主题配置等
     */
    @Column(name = "generated_config", columnDefinition = "JSON")
    private String generatedConfig;

    /**
     * 状态: 0处理中 1成功 2失败 3已应用
     */
    @Column(name = "status")
    @Builder.Default
    private Integer status = 0;

    /**
     * 对话历史 JSON
     * 格式:
     * [
     *   { "role": "user", "content": "把今日统计放到第一个" },
     *   { "role": "assistant", "content": "好的，已将今日统计模块移到首位" }
     * ]
     */
    @Column(name = "conversation_history", columnDefinition = "JSON")
    private String conversationHistory;

    /**
     * 是否需要澄清: 0否 1是
     */
    @Column(name = "clarification_needed")
    @Builder.Default
    private Integer clarificationNeeded = 0;

    // ==================== 状态常量 ====================

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_APPLIED = 3;

    // ==================== 辅助方法 ====================

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断是否处理中
     */
    public boolean isProcessing() {
        return Integer.valueOf(STATUS_PROCESSING).equals(status);
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return Integer.valueOf(STATUS_SUCCESS).equals(status);
    }

    /**
     * 判断是否失败
     */
    public boolean isFailed() {
        return Integer.valueOf(STATUS_FAILED).equals(status);
    }

    /**
     * 判断是否已应用
     */
    public boolean isApplied() {
        return Integer.valueOf(STATUS_APPLIED).equals(status);
    }

    /**
     * 判断是否需要澄清
     */
    public boolean needsClarification() {
        return Integer.valueOf(1).equals(clarificationNeeded);
    }

    /**
     * 获取生成配置Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getGeneratedConfigMap() {
        if (generatedConfig == null || generatedConfig.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(generatedConfig, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 设置生成配置Map
     */
    public void setGeneratedConfigMap(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            this.generatedConfig = "{}";
            return;
        }
        try {
            this.generatedConfig = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            this.generatedConfig = "{}";
        }
    }

    /**
     * 获取对话历史列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getConversationHistoryList() {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(conversationHistory,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 设置对话历史列表
     */
    public void setConversationHistoryList(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            this.conversationHistory = "[]";
            return;
        }
        try {
            this.conversationHistory = objectMapper.writeValueAsString(history);
        } catch (Exception e) {
            this.conversationHistory = "[]";
        }
    }

    /**
     * 添加对话消息
     */
    public void addConversationMessage(String role, String content) {
        List<Map<String, String>> history = new java.util.ArrayList<>(getConversationHistoryList());
        Map<String, String> message = new java.util.HashMap<>();
        message.put("role", role);
        message.put("content", content);
        history.add(message);
        setConversationHistoryList(history);
    }

    /**
     * 标记为成功
     */
    public void markAsSuccess(String config) {
        this.status = STATUS_SUCCESS;
        this.generatedConfig = config;
    }

    /**
     * 标记为失败
     */
    public void markAsFailed() {
        this.status = STATUS_FAILED;
    }

    /**
     * 标记为已应用
     */
    public void markAsApplied() {
        this.status = STATUS_APPLIED;
    }

    /**
     * 标记需要澄清
     */
    public void markNeedsClarification() {
        this.clarificationNeeded = 1;
    }
}

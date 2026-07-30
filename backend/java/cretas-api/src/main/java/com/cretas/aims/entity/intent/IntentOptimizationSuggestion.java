package com.cretas.aims.entity.intent;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 意图优化建议实体
 *
 * 自动生成的规则优化建议，用于:
 * - 添加缺失关键词
 * - 调整意图优先级
 * - 添加正则表达式
 * - 合并/拆分意图
 * - **创建新意图 (自学习核心功能)**
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-01-02
 */
@Entity
@Table(name = "intent_optimization_suggestions",
       indexes = {
           @Index(name = "idx_factory_intent", columnList = "factory_id, intent_code"),
           @Index(name = "idx_status", columnList = "status"),
           @Index(name = "idx_impact_score", columnList = "impact_score")
       })
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class IntentOptimizationSuggestion extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 20)
    private String factoryId;

    /**
     * 相关意图代码
     */
    @Column(name = "intent_code", length = 50)
    private String intentCode;

    // ==================== 建议类型 ====================

    /**
     * 建议类型
     */
    @Column(name = "suggestion_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private SuggestionType suggestionType;

    // ==================== 建议内容 ====================

    /**
     * 建议标题
     */
    @Column(name = "suggestion_title", nullable = false, length = 200)
    private String suggestionTitle;

    /**
     * 建议详情
     */
    @Column(name = "suggestion_detail", columnDefinition = "TEXT", nullable = false)
    private String suggestionDetail;

    // ==================== 支持数据 ====================

    /**
     * 支持该建议的用户输入样例 (JSON Array)
     */
    @Column(name = "supporting_examples", columnDefinition = "JSON")
    private String supportingExamples;

    /**
     * 相关问题出现频率
     */
    @Builder.Default
    @Column(name = "frequency", nullable = false)
    private Integer frequency = 0;

    /**
     * 预估影响分数 (0-100)
     */
    @Column(name = "impact_score", precision = 5, scale = 2)
    private BigDecimal impactScore;

    // ==================== 状态管理 ====================

    /**
     * 状态
     */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    /**
     * 应用时间
     */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * 应用人ID
     */
    @Column(name = "applied_by")
    private Long appliedBy;

    /**
     * 拒绝原因
     */
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /**
     * 审批备注 (平台晋升审批时使用)
     */
    @Column(name = "approval_notes", columnDefinition = "TEXT")
    private String approvalNotes;

    /**
     * 过期时间（建议有效期30天）
     */
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    // ==================== CREATE_INTENT 专用字段 ====================

    /**
     * LLM建议的意图代码 (CREATE_INTENT 类型专用)
     */
    @Column(name = "suggested_intent_code", length = 100)
    private String suggestedIntentCode;

    /**
     * LLM建议的意图名称 (CREATE_INTENT 类型专用)
     */
    @Column(name = "suggested_intent_name", length = 200)
    private String suggestedIntentName;

    /**
     * LLM建议的关键词 (JSON数组, CREATE_INTENT 类型专用)
     */
    @Column(name = "suggested_keywords", columnDefinition = "JSON")
    private String suggestedKeywords;

    /**
     * LLM建议的意图分类 (CREATE_INTENT 类型专用)
     * 如: ANALYSIS, DATA_OP, FORM, SCHEDULE, SYSTEM
     */
    @Column(name = "suggested_category", length = 50)
    private String suggestedCategory;

    /**
     * LLM 置信度
     */
    @Column(name = "llm_confidence", precision = 5, scale = 4)
    private BigDecimal llmConfidence;

    /**
     * LLM 推理说明
     */
    @Column(name = "llm_reasoning", columnDefinition = "TEXT")
    private String llmReasoning;

    /**
     * 创建后的意图ID (应用后填写)
     */
    @Column(name = "created_intent_id", length = 36)
    private String createdIntentId;

    // ==================== 枚举类型 ====================

    /**
     * 建议类型枚举
     */
    public enum SuggestionType {
        ADD_KEYWORD,          // 添加关键词
        ADJUST_PRIORITY,      // 调整优先级
        ADD_REGEX,            // 添加正则表达式
        MERGE_INTENT,         // 合并意图
        SPLIT_INTENT,         // 拆分意图
        CREATE_INTENT,        // 创建新意图 (自学习核心功能)
        PROMOTE_TO_PLATFORM   // 晋升为平台级意图 (需平台管理员审批)
    }

    /**
     * 状态枚举
     */
    public enum SuggestionStatus {
        PENDING,  // 待处理
        APPLIED,  // 已应用
        REJECTED, // 已拒绝
        EXPIRED   // 已过期
    }

    // ==================== 便捷方法 ====================

    /**
     * 应用建议
     */
    public void apply(Long userId) {
        this.status = SuggestionStatus.APPLIED;
        this.appliedAt = LocalDateTime.now();
        this.appliedBy = userId;
    }

    /**
     * 拒绝建议
     */
    public void reject(String reason) {
        this.status = SuggestionStatus.REJECTED;
        this.rejectReason = reason;
    }

    /**
     * 检查是否已过期
     */
    public boolean isExpired() {
        if (expiredAt == null) return false;
        return LocalDateTime.now().isAfter(expiredAt);
    }

    /**
     * 设置默认过期时间（30天后）
     */
    @PrePersist
    public void setDefaultExpiredAt() {
        if (expiredAt == null) {
            expiredAt = LocalDateTime.now().plusDays(30);
        }
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建「新建意图」建议 (自学习核心)
     *
     * 当LLM识别到不属于现有意图的新模式时，生成此建议
     *
     * @param factoryId         工厂ID
     * @param userInput         触发的用户输入
     * @param suggestedCode     LLM建议的意图代码
     * @param suggestedName     LLM建议的意图名称
     * @param suggestedKeywords LLM建议的关键词列表 (JSON数组)
     * @param suggestedCategory LLM建议的分类
     * @param confidence        LLM置信度
     * @param reasoning         LLM推理说明
     * @return 新建意图的优化建议
     */
    public static IntentOptimizationSuggestion createNewIntentSuggestion(
            String factoryId,
            String userInput,
            String suggestedCode,
            String suggestedName,
            String suggestedKeywords,
            String suggestedCategory,
            double confidence,
            String reasoning) {

        return IntentOptimizationSuggestion.builder()
                .factoryId(factoryId)
                .intentCode(suggestedCode) // 建议的意图代码
                .suggestionType(SuggestionType.CREATE_INTENT)
                .suggestionTitle("LLM建议创建新意图: " + suggestedName)
                .suggestionDetail(String.format(
                        "LLM识别到新的意图模式，建议创建意图 [%s]。\n触发输入: %s\n推理说明: %s",
                        suggestedCode, userInput, reasoning))
                .supportingExamples("[\"" + userInput.replace("\"", "\\\"") + "\"]")
                .frequency(1)
                .impactScore(BigDecimal.valueOf(confidence * 100))
                .suggestedIntentCode(suggestedCode)
                .suggestedIntentName(suggestedName)
                .suggestedKeywords(suggestedKeywords)
                .suggestedCategory(suggestedCategory)
                .llmConfidence(BigDecimal.valueOf(confidence))
                .llmReasoning(reasoning)
                .status(SuggestionStatus.PENDING)
                .build();
    }

    /**
     * 累加相同意图建议的触发次数
     *
     * @param newUserInput 新的触发输入
     */
    public void incrementFrequency(String newUserInput) {
        this.frequency = (this.frequency == null ? 0 : this.frequency) + 1;

        // 添加到样例列表
        if (this.supportingExamples == null || this.supportingExamples.isEmpty()) {
            this.supportingExamples = "[\"" + escapeJson(newUserInput) + "\"]";
        } else {
            String trimmed = this.supportingExamples.trim();
            if (trimmed.endsWith("]")) {
                this.supportingExamples = trimmed.substring(0, trimmed.length() - 1)
                        + ", \"" + escapeJson(newUserInput) + "\"]";
            }
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 判断是否为创建新意图的建议
     */
    public boolean isCreateIntent() {
        return SuggestionType.CREATE_INTENT.equals(this.suggestionType);
    }

    /**
     * 判断是否为平台晋升的建议
     */
    public boolean isPromoteToPlatform() {
        return SuggestionType.PROMOTE_TO_PLATFORM.equals(this.suggestionType);
    }

    /**
     * 创建平台晋升建议
     *
     * @param factoryId      工厂ID
     * @param intentCode     意图代码
     * @param intentName     意图名称
     * @param reason         晋升原因
     * @param requestedBy    申请人
     * @return 晋升建议
     */
    public static IntentOptimizationSuggestion createPromotionSuggestion(
            String factoryId,
            String intentCode,
            String intentName,
            String reason,
            String requestedBy) {

        return IntentOptimizationSuggestion.builder()
                .factoryId(factoryId)
                .intentCode(intentCode)
                .suggestionType(SuggestionType.PROMOTE_TO_PLATFORM)
                .suggestionTitle("申请晋升为平台级意图: " + intentName)
                .suggestionDetail(String.format(
                        "工厂 [%s] 申请将意图 [%s] 晋升为平台级共享意图。\n" +
                        "申请原因: %s\n申请人: %s",
                        factoryId, intentCode, reason, requestedBy))
                .supportingExamples(null)
                .frequency(1)
                .impactScore(BigDecimal.valueOf(80)) // 晋升请求默认影响分数
                .suggestedIntentCode(intentCode)
                .suggestedIntentName(intentName)
                .status(SuggestionStatus.PENDING)
                .build();
    }
}

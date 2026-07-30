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
 * 意图匹配记录实体
 *
 * 记录每次意图识别的详细信息，用于:
 * - 错误归因分析
 * - 规则优化建议
 * - 用户行为追踪
 * - LLM fallback 效果评估
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Entity
@Table(name = "intent_match_records",
       indexes = {
           @Index(name = "idx_factory_user", columnList = "factory_id, user_id"),
           @Index(name = "idx_session", columnList = "session_id"),
           @Index(name = "idx_intent_code", columnList = "matched_intent_code"),
           @Index(name = "idx_created_at", columnList = "created_at"),
           @Index(name = "idx_execution_status", columnList = "execution_status"),
           @Index(name = "idx_error_attribution", columnList = "error_attribution"),
           @Index(name = "idx_llm_called", columnList = "llm_called")
       })
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class IntentMatchRecord extends BaseEntity {

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
     * 用户ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 会话ID（用于关联多轮对话）
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    // ==================== 用户输入 ====================

    /**
     * 用户原始输入
     */
    @Column(name = "user_input", columnDefinition = "TEXT", nullable = false)
    private String userInput;

    /**
     * 标准化后的输入
     */
    @Column(name = "normalized_input", columnDefinition = "TEXT")
    private String normalizedInput;

    // ==================== 匹配结果 ====================

    /**
     * 匹配的意图代码（可能为空）
     */
    @Column(name = "matched_intent_code", length = 50)
    private String matchedIntentCode;

    /**
     * 匹配的意图名称
     */
    @Column(name = "matched_intent_name", length = 100)
    private String matchedIntentName;

    /**
     * 匹配的意图分类
     */
    @Column(name = "matched_intent_category", length = 50)
    private String matchedIntentCategory;

    // ==================== 置信度信息 ====================

    /**
     * 置信度分数 (0.0000-1.0000)
     */
    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    /**
     * 原始匹配分数
     */
    @Column(name = "match_score")
    private Integer matchScore;

    /**
     * 匹配方法: REGEX/KEYWORD/LLM/NONE
     */
    @Column(name = "match_method", length = 20)
    @Enumerated(EnumType.STRING)
    private MatchMethod matchMethod;

    // ==================== 候选意图 (JSON) ====================

    /**
     * Top-N候选意图列表 [{intentCode, confidence, matchScore}]
     */
    @Column(name = "top_candidates", columnDefinition = "JSON")
    private String topCandidates;

    /**
     * 匹配到的关键词列表
     */
    @Column(name = "matched_keywords", columnDefinition = "JSON")
    private String matchedKeywords;

    // ==================== 信号判断 ====================

    /**
     * 是否为强信号
     * 注意: insertable=false 让 Hibernate 不插入此列，使用数据库默认值 0
     */
    @Builder.Default
    @Column(name = "is_strong_signal", nullable = false, insertable = false)
    private boolean isStrongSignal = false;

    /**
     * 是否需要用户确认
     */
    @Builder.Default
    @Column(name = "requires_confirmation", nullable = false, insertable = false)
    private boolean requiresConfirmation = false;

    /**
     * 澄清问题内容
     */
    @Column(name = "clarification_question", columnDefinition = "TEXT")
    private String clarificationQuestion;

    // ==================== LLM 相关 ====================

    /**
     * 是否调用了LLM fallback
     */
    @Builder.Default
    @Column(name = "llm_called", nullable = false)
    private Boolean llmCalled = false;

    /**
     * LLM返回的原始内容
     */
    @Column(name = "llm_response", columnDefinition = "TEXT")
    private String llmResponse;

    /**
     * LLM判断的意图代码
     */
    @Column(name = "llm_intent_code", length = 50)
    private String llmIntentCode;

    /**
     * LLM返回的置信度
     */
    @Column(name = "llm_confidence", precision = 5, scale = 4)
    private BigDecimal llmConfidence;

    // ==================== 用户反馈 ====================

    /**
     * 用户是否确认（null=未确认，true=确认，false=拒绝）
     */
    @Column(name = "user_confirmed")
    private Boolean userConfirmed;

    /**
     * 用户选择的意图代码（当有多候选时）
     */
    @Column(name = "user_selected_intent", length = 50)
    private String userSelectedIntent;

    /**
     * 用户反馈内容
     */
    @Column(name = "user_feedback", columnDefinition = "TEXT")
    private String userFeedback;

    // ==================== 执行结果 ====================

    /**
     * 执行状态: PENDING/EXECUTED/FAILED/CANCELLED
     */
    @Column(name = "execution_status", length = 20)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus executionStatus;

    /**
     * 执行结果摘要
     */
    @Column(name = "execution_result", columnDefinition = "TEXT")
    private String executionResult;

    /**
     * 错误信息（如果执行失败）
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ==================== 错误归因 ====================

    /**
     * 错误归因: RULE_MISS/AMBIGUOUS/FALSE_POSITIVE/USER_CANCEL/SYSTEM_ERROR
     */
    @Column(name = "error_attribution", length = 50)
    @Enumerated(EnumType.STRING)
    private ErrorAttribution errorAttribution;

    /**
     * 错误归因详情
     */
    @Column(name = "attribution_details", columnDefinition = "TEXT")
    private String attributionDetails;

    // ==================== 额外时间戳 ====================

    /**
     * 用户确认时间
     */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /**
     * 执行时间
     */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    // ==================== 枚举类型 ====================

    /**
     * 匹配方法枚举
     */
    public enum MatchMethod {
        EXACT,          // 精确表达匹配 (hash查表)
        PHRASE_MATCH,   // 短语映射匹配
        REGEX,          // 正则表达式匹配
        KEYWORD,        // 关键词匹配
        SEMANTIC,       // 语义向量匹配
        CLASSIFIER,     // BERT 分类器匹配
        FUSION,         // 融合匹配（语义+关键词）
        SIMILAR,        // 相似表达匹配 (编辑距离)
        LLM,            // LLM fallback 匹配
        DOMAIN_DEFAULT, // 域默认意图匹配 (Layer 3.5)
        REJECTED,       // 输入被拒绝
        NONE            // 未匹配
    }

    /**
     * 执行状态枚举
     */
    public enum ExecutionStatus {
        PENDING,    // 待执行
        EXECUTED,   // 已执行
        FAILED,     // 执行失败
        CANCELLED   // 已取消
    }

    /**
     * 错误归因枚举
     */
    public enum ErrorAttribution {
        RULE_MISS,      // 规则缺失 - 无法匹配有效意图
        AMBIGUOUS,      // 歧义匹配 - 多个候选意图难以区分
        FALSE_POSITIVE, // 误匹配 - 匹配到错误意图
        USER_CANCEL,    // 用户取消 - 用户拒绝执行
        SYSTEM_ERROR    // 系统错误 - 执行过程中发生异常
    }
}

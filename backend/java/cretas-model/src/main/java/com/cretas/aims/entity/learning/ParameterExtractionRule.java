package com.cretas.aims.entity.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 参数提取规则实体
 *
 * 存储从用户输入中提取参数的学习规则。
 * 当规则命中时，可以直接从用户输入中提取参数，无需调用 LLM。
 *
 * 学习流程：
 * 1. 首次调用 → LLM 提取参数 → 返回确认请求
 * 2. 用户确认 → 执行操作 + 学习规则
 * 3. 二次调用 → 规则提取参数 → 直接执行（不调用 LLM）
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-17
 */
@Entity
@Table(name = "ai_parameter_extraction_rules", indexes = {
    @Index(name = "idx_per_factory_intent", columnList = "factory_id, intent_code"),
    @Index(name = "idx_per_intent_param", columnList = "factory_id, intent_code, param_name"),
    @Index(name = "idx_per_pattern", columnList = "pattern_type"),
    @Index(name = "idx_per_is_verified", columnList = "is_verified")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterExtractionRule {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 工厂ID (null = 全局)
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 关联的意图代码
     */
    @Column(name = "intent_code", nullable = false, length = 100)
    private String intentCode;

    /**
     * 参数名称 (如 username, role)
     */
    @Column(name = "param_name", nullable = false, length = 100)
    private String paramName;

    /**
     * 参数显示名称 (如 "用户名", "角色")
     */
    @Column(name = "param_display_name", length = 100)
    private String paramDisplayName;

    // ==================== 提取规则 ====================

    /**
     * 模式类型
     * KEYWORD_AFTER - 关键词后取值，如 "用户名zhangsan" → username=zhangsan
     * KEYWORD_IS - "关键词为/是+值"模式，如 "角色为操作员" → role=操作员
     * REGEX - 正则表达式模式
     * POSITION - 位置相关，如第N个逗号分隔的值
     * NER - 命名实体识别（预留）
     * SEMANTIC - 语义提取（预留）
     */
    @Column(name = "pattern_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PatternType patternType;

    /**
     * 提取模式/关键词/正则表达式
     * - KEYWORD_AFTER: 关键词，如 "用户名"
     * - KEYWORD_IS: 关键词，如 "角色"
     * - REGEX: 正则表达式，如 "用户名(\\w+)"
     * - POSITION: 位置索引，如 "2" 表示第2个逗号分隔值
     */
    @Column(name = "extraction_pattern", length = 500)
    private String extractionPattern;

    /**
     * 附加的提取配置（JSON格式）
     * 可包含：分隔符、正则捕获组索引、值映射等
     */
    @Column(name = "extraction_config", columnDefinition = "TEXT")
    private String extractionConfig;

    // ==================== 示例和验证 ====================

    /**
     * 原始用户输入示例
     */
    @Column(name = "example_input", columnDefinition = "TEXT")
    private String exampleInput;

    /**
     * 提取出的值示例
     */
    @Column(name = "example_value", length = 500)
    private String exampleValue;

    /**
     * 参数值类型 (STRING, NUMBER, BOOLEAN, DATE, ENUM)
     */
    @Column(name = "value_type", length = 20)
    @Builder.Default
    private String valueType = "STRING";

    /**
     * 值验证正则（可选）
     */
    @Column(name = "value_validation_regex", length = 200)
    private String valueValidationRegex;

    // ==================== 置信度和命中统计 ====================

    /**
     * 置信度 (0.0 - 1.0)
     * 用于决定是否使用此规则
     */
    @Column(name = "confidence", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal confidence = new BigDecimal("0.80");

    /**
     * 命中次数
     */
    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;

    /**
     * 成功提取次数（提取后值通过验证）
     */
    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    /**
     * 最后命中时间
     */
    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    // ==================== 来源追踪 ====================

    /**
     * 来源类型
     */
    @Column(name = "source_type", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SourceType sourceType = SourceType.LLM_LEARNED;

    /**
     * 是否已人工确认
     */
    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // ==================== 时间戳 ====================

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
     * 软删除时间
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 记录一次命中
     */
    public void recordHit() {
        this.hitCount = (this.hitCount == null ? 0 : this.hitCount) + 1;
        this.lastHitAt = LocalDateTime.now();
    }

    /**
     * 记录一次成功提取
     */
    public void recordSuccess() {
        recordHit();
        this.successCount = (this.successCount == null ? 0 : this.successCount) + 1;
        // 成功率高时提升置信度
        if (this.hitCount > 0) {
            double successRate = (double) this.successCount / this.hitCount;
            if (successRate > 0.9 && this.confidence.doubleValue() < 0.95) {
                this.confidence = new BigDecimal("0.95");
            }
        }
    }

    /**
     * 记录一次失败提取
     */
    public void recordFailure() {
        recordHit();
        // 成功率低时降低置信度
        if (this.hitCount > 5) {
            double successRate = (double) (this.successCount == null ? 0 : this.successCount) / this.hitCount;
            if (successRate < 0.5) {
                this.confidence = new BigDecimal("0.50");
                // 成功率太低时停用
                if (successRate < 0.3) {
                    this.isActive = false;
                }
            }
        }
    }

    /**
     * 用户确认后提升置信度
     */
    public void confirm() {
        this.isVerified = true;
        this.sourceType = SourceType.USER_CONFIRMED;
        // 用户确认的规则置信度设为高值
        if (this.confidence.doubleValue() < 0.95) {
            this.confidence = new BigDecimal("0.95");
        }
    }

    /**
     * 计算成功率
     */
    public double getSuccessRate() {
        if (hitCount == null || hitCount == 0) {
            return 0.0;
        }
        return (double) (successCount == null ? 0 : successCount) / hitCount;
    }

    /**
     * 模式类型枚举
     */
    public enum PatternType {
        /** 关键词后取值: "用户名zhangsan" → zhangsan */
        KEYWORD_AFTER,
        /** 关键词为/是+值: "角色为操作员" → 操作员 */
        KEYWORD_IS,
        /** 正则表达式匹配 */
        REGEX,
        /** 位置提取 (第N个分隔值) */
        POSITION,
        /** 命名实体识别 (预留) */
        NER,
        /** 语义提取 (预留) */
        SEMANTIC
    }

    /**
     * 来源类型枚举
     */
    public enum SourceType {
        /** LLM 提取后自动学习 */
        LLM_LEARNED,
        /** 用户确认后学习 */
        USER_CONFIRMED,
        /** 手动添加 */
        MANUAL
    }

    /**
     * 创建 KEYWORD_AFTER 类型的规则
     */
    public static ParameterExtractionRule createKeywordAfterRule(
            String factoryId, String intentCode, String paramName,
            String keyword, String exampleInput, String exampleValue) {
        return ParameterExtractionRule.builder()
                .factoryId(factoryId)
                .intentCode(intentCode)
                .paramName(paramName)
                .patternType(PatternType.KEYWORD_AFTER)
                .extractionPattern(keyword)
                .exampleInput(exampleInput)
                .exampleValue(exampleValue)
                .confidence(new BigDecimal("0.80"))
                .sourceType(SourceType.LLM_LEARNED)
                .isVerified(false)
                .isActive(true)
                .build();
    }

    /**
     * 创建 KEYWORD_IS 类型的规则
     */
    public static ParameterExtractionRule createKeywordIsRule(
            String factoryId, String intentCode, String paramName,
            String keyword, String exampleInput, String exampleValue) {
        return ParameterExtractionRule.builder()
                .factoryId(factoryId)
                .intentCode(intentCode)
                .paramName(paramName)
                .patternType(PatternType.KEYWORD_IS)
                .extractionPattern(keyword)
                .exampleInput(exampleInput)
                .exampleValue(exampleValue)
                .confidence(new BigDecimal("0.80"))
                .sourceType(SourceType.LLM_LEARNED)
                .isVerified(false)
                .isActive(true)
                .build();
    }

    /**
     * 创建 REGEX 类型的规则
     */
    public static ParameterExtractionRule createRegexRule(
            String factoryId, String intentCode, String paramName,
            String regex, String exampleInput, String exampleValue) {
        return ParameterExtractionRule.builder()
                .factoryId(factoryId)
                .intentCode(intentCode)
                .paramName(paramName)
                .patternType(PatternType.REGEX)
                .extractionPattern(regex)
                .exampleInput(exampleInput)
                .exampleValue(exampleValue)
                .confidence(new BigDecimal("0.80"))
                .sourceType(SourceType.LLM_LEARNED)
                .isVerified(false)
                .isActive(true)
                .build();
    }
}

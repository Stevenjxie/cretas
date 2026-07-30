package com.cretas.aims.entity.calibration;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 纠错记录实体
 * 记录自我纠错机制的详细过程和结果
 */
@Entity
@Table(name = "correction_records", indexes = {
    @Index(name = "idx_tool_call_id", columnList = "tool_call_id"),
    @Index(name = "idx_error_category", columnList = "error_category"),
    @Index(name = "idx_correction_strategy", columnList = "correction_strategy"),
    @Index(name = "idx_correction_success", columnList = "correction_success")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class CorrectionRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tool_call_id", nullable = false)
    private Long toolCallId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_call_id", insertable = false, updatable = false)
    private ToolCallRecord toolCallRecord;

    @Column(name = "factory_id", length = 64)
    private String factoryId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "error_type", length = 64, nullable = false)
    private String errorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_category", nullable = false)
    @Builder.Default
    private ErrorCategory errorCategory = ErrorCategory.UNKNOWN;

    @Column(name = "original_error_message", columnDefinition = "TEXT")
    private String originalErrorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_strategy", nullable = false)
    private CorrectionStrategy correctionStrategy;

    @Column(name = "injected_prompt", columnDefinition = "TEXT")
    private String injectedPrompt;

    @Column(name = "correction_success")
    @Builder.Default
    private Boolean correctionSuccess = false;

    @Column(name = "correction_rounds")
    @Builder.Default
    private Integer correctionRounds = 1;

    @Column(name = "final_status", length = 64)
    private String finalStatus;

    /**
     * 错误分类枚举
     * 用于确定采用何种纠错策略
     */
    public enum ErrorCategory {
        DATA_INSUFFICIENT,  // 数据不足 - 需重新检索
        ANALYSIS_ERROR,     // 分析错误 - 需从分析点重做
        FORMAT_ERROR,       // 格式错误 - 仅修正格式
        LOGIC_ERROR,        // 逻辑错误 - 注入纠正提示
        UNKNOWN             // 未知错误 - 完全重试
    }

    /**
     * 纠错策略枚举
     */
    public enum CorrectionStrategy {
        RE_RETRIEVE,        // 重新检索数据
        RE_ANALYZE,         // 从分析点重做
        FORMAT_FIX,         // 仅修正格式
        PROMPT_INJECTION,   // 注入纠正提示
        FULL_RETRY          // 完全重试
    }

    /**
     * 根据错误分类获取推荐的纠错策略
     */
    public static CorrectionStrategy getRecommendedStrategy(ErrorCategory category) {
        switch (category) {
            case DATA_INSUFFICIENT:
                return CorrectionStrategy.RE_RETRIEVE;
            case ANALYSIS_ERROR:
                return CorrectionStrategy.RE_ANALYZE;
            case FORMAT_ERROR:
                return CorrectionStrategy.FORMAT_FIX;
            case LOGIC_ERROR:
                return CorrectionStrategy.PROMPT_INJECTION;
            case UNKNOWN:
            default:
                return CorrectionStrategy.FULL_RETRY;
        }
    }

    /**
     * 标记纠错成功
     */
    public void markSuccess(String finalStatus) {
        this.correctionSuccess = true;
        this.finalStatus = finalStatus;
    }

    /**
     * 标记纠错失败
     */
    public void markFailure(String finalStatus) {
        this.correctionSuccess = false;
        this.finalStatus = finalStatus;
    }

    /**
     * 增加纠错轮次
     */
    public void incrementRounds() {
        this.correctionRounds = (this.correctionRounds == null ? 1 : this.correctionRounds) + 1;
    }
}

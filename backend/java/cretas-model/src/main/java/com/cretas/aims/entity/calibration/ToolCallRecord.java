package com.cretas.aims.entity.calibration;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 工具调用记录实体
 * 记录每次AI工具调用的详细信息，用于冗余检测和行为校准指标计算
 *
 * 基于 ET-Agent 论文 (arXiv:2601.06860) 的设计
 */
@Entity
@Table(name = "tool_call_records", indexes = {
    @Index(name = "idx_factory_id", columnList = "factory_id"),
    @Index(name = "idx_session_id", columnList = "session_id"),
    @Index(name = "idx_tool_name", columnList = "tool_name"),
    @Index(name = "idx_execution_status", columnList = "execution_status"),
    @Index(name = "idx_is_redundant", columnList = "is_redundant"),
    @Index(name = "idx_parameters_hash", columnList = "parameters_hash")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ToolCallRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 64)
    private String factoryId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "intent_code", length = 128)
    private String intentCode;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(name = "tool_parameters", columnDefinition = "JSON")
    private String toolParameters;

    @Column(name = "parameters_hash", length = 64)
    private String parametersHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false)
    @Builder.Default
    private ExecutionStatus executionStatus = ExecutionStatus.SUCCESS;

    @Column(name = "error_type", length = 64)
    private String errorType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "is_redundant")
    @Builder.Default
    private Boolean isRedundant = false;

    @Column(name = "redundant_reason", length = 256)
    private String redundantReason;

    @Column(name = "original_call_id")
    private Long originalCallId;

    @Column(name = "execution_time_ms")
    private Integer executionTimeMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "recovery_strategy", length = 64)
    private String recoveryStrategy;

    @Column(name = "recovered")
    @Builder.Default
    private Boolean recovered = false;

    /**
     * 执行状态枚举
     */
    public enum ExecutionStatus {
        SUCCESS,    // 执行成功
        FAILED,     // 执行失败
        SKIPPED,    // 跳过（冗余调用）
        TIMEOUT     // 执行超时
    }

    /**
     * 标记为冗余调用
     */
    public void markAsRedundant(Long originalCallId, String reason) {
        this.isRedundant = true;
        this.originalCallId = originalCallId;
        this.redundantReason = reason;
        this.executionStatus = ExecutionStatus.SKIPPED;
    }

    /**
     * 记录执行失败
     */
    public void recordFailure(String errorType, String errorMessage) {
        this.executionStatus = ExecutionStatus.FAILED;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    /**
     * 记录恢复成功
     */
    public void recordRecovery(String strategy) {
        this.recovered = true;
        this.recoveryStrategy = strategy;
        this.executionStatus = ExecutionStatus.SUCCESS;
    }

    /**
     * 增加重试次数
     */
    public void incrementRetryCount() {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }
}

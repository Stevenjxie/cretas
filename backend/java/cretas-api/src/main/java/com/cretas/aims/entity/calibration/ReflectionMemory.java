package com.cretas.aims.entity.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 反思记忆实体
 *
 * 基于 Reflexion 论文 (NeurIPS 2023) 的 episodic memory 设计：
 * - 存储每次纠错的反思内容
 * - 用于未来类似错误的快速参考
 * - 支持学习和改进纠错策略
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-19
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "reflection_memories", indexes = {
        @Index(name = "idx_reflection_tool_name", columnList = "tool_name"),
        @Index(name = "idx_reflection_session", columnList = "session_id"),
        @Index(name = "idx_reflection_created", columnList = "created_at")
})
public class ReflectionMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话ID
     */
    @Column(name = "session_id")
    private String sessionId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id")
    private String factoryId;

    /**
     * 工具名称
     */
    @Column(name = "tool_name", nullable = false)
    private String toolName;

    /**
     * 原始错误信息
     */
    @Column(name = "original_error", columnDefinition = "TEXT")
    private String originalError;

    /**
     * 反思内容
     * LLM 生成的错误分析和改进建议
     */
    @Column(name = "reflection_content", columnDefinition = "TEXT")
    private String reflectionContent;

    /**
     * 修正后的参数 (JSON)
     */
    @Column(name = "corrected_params", columnDefinition = "TEXT")
    private String correctedParams;

    /**
     * 纠错策略
     * RE_QUERY, EXPAND_RANGE, FIX_FORMAT, CHANGE_CONDITION, ABANDON
     */
    @Column(name = "correction_strategy")
    private String correctionStrategy;

    /**
     * 置信度 (0.0-1.0)
     */
    @Column(name = "confidence")
    private Double confidence;

    /**
     * 纠错是否成功
     */
    @Column(name = "was_successful")
    private boolean wasSuccessful;

    /**
     * 重试次数
     */
    @Column(name = "retry_count")
    private Integer retryCount;

    /**
     * 执行耗时 (毫秒)
     */
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

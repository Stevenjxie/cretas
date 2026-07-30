package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI使用日志实体
 * 用于记录LLM AI API的调用历史
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-02
 */
@Entity
@Table(name = "ai_usage_log", indexes = {
    @Index(name = "idx_factory_week", columnList = "factory_id,week_number"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 用户ID（可选）
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 请求类型
     * 例如: 'analysis', 'suggestion', 'quality_check'
     */
    @Column(name = "request_type", length = 50)
    private String requestType;

    /**
     * 使用的token数量
     */
    @Column(name = "tokens_used")
    private Integer tokensUsed;

    /**
     * 成本（元）
     */
    @Column(name = "cost", precision = 10, scale = 4)
    private BigDecimal cost;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 周次编号（ISO 8601格式）
     * 例如: '2025-W44'
     */
    @Column(name = "week_number", length = 10)
    private String weekNumber;

    /**
     * 外键关联：工厂
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id",
                insertable = false, updatable = false)
    private Factory factory;

    /**
     * 在持久化之前自动设置创建时间和周次
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.weekNumber == null) {
            this.weekNumber = getCurrentWeekNumber();
        }
    }

    /**
     * 获取当前周次编号（ISO 8601格式）
     * 例如: '2025-W44'
     */
    private String getCurrentWeekNumber() {
        LocalDateTime now = LocalDateTime.now();
        // 简化实现：使用当前年份和周数
        int year = now.getYear();
        int weekOfYear = now.getDayOfYear() / 7 + 1;
        return String.format("%d-W%02d", year, weekOfYear);
    }
}

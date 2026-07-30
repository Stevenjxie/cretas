package com.cretas.aims.entity.calibration;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;

/**
 * 工具可靠性统计实体
 * 按工具维度统计成功率和性能指标
 */
@Entity
@Table(name = "tool_reliability_stats",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_tool_date",
        columnNames = {"factory_id", "tool_name", "stat_date"}
    ),
    indexes = {
        @Index(name = "idx_tool_name", columnList = "tool_name"),
        @Index(name = "idx_stat_date", columnList = "stat_date"),
        @Index(name = "idx_success_rate", columnList = "success_rate")
    }
)
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class ToolReliabilityStats extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 64)
    private String factoryId;

    @Column(name = "tool_name", length = 128, nullable = false)
    private String toolName;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "total_calls")
    @Builder.Default
    private Integer totalCalls = 0;

    @Column(name = "successful_calls")
    @Builder.Default
    private Integer successfulCalls = 0;

    @Column(name = "failed_calls")
    @Builder.Default
    private Integer failedCalls = 0;

    @Column(name = "avg_execution_time_ms")
    private Integer avgExecutionTimeMs;

    @Column(name = "success_rate", precision = 5, scale = 2)
    private BigDecimal successRate;

    @Column(name = "common_errors", columnDefinition = "JSON")
    private String commonErrors;

    /**
     * 计算成功率
     */
    public void calculateSuccessRate() {
        if (totalCalls == null || totalCalls == 0) {
            this.successRate = BigDecimal.ZERO;
            return;
        }

        int success = successfulCalls != null ? successfulCalls : 0;
        this.successRate = BigDecimal.valueOf(success)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(totalCalls), 2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 增加调用计数
     */
    public void incrementTotalCalls() {
        this.totalCalls = (this.totalCalls == null ? 0 : this.totalCalls) + 1;
    }

    public void incrementSuccessfulCalls() {
        this.successfulCalls = (this.successfulCalls == null ? 0 : this.successfulCalls) + 1;
    }

    public void incrementFailedCalls() {
        this.failedCalls = (this.failedCalls == null ? 0 : this.failedCalls) + 1;
    }
}

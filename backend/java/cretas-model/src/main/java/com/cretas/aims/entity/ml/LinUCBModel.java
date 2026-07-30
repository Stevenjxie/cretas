package com.cretas.aims.entity.ml;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * LinUCB模型参数实体
 * 存储每个工人的UCB模型参数
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Entity
@Table(name = "linucb_models", uniqueConstraints = {
        @UniqueConstraint(name = "uk_linucb_factory_worker", columnNames = {"factory_id", "worker_id"})
}, indexes = {
        @Index(name = "idx_linucb_factory", columnList = "factory_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class LinUCBModel extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 工人ID
     */
    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * 特征维度
     */
    @Column(name = "feature_dim")
    @Builder.Default
    private Integer featureDim = 12;

    /**
     * A矩阵 (JSON格式的二维数组)
     * 用于计算 theta = A^(-1) * b
     */
    @Column(name = "matrix_a", columnDefinition = "JSON", nullable = false)
    private String matrixA;

    /**
     * A逆矩阵 (JSON格式的二维数组)
     * 预计算的A^(-1)，用于加速UCB计算
     */
    @Column(name = "matrix_a_inverse", columnDefinition = "JSON", nullable = false)
    private String matrixAInverse;

    /**
     * b向量 (JSON格式的一维数组)
     */
    @Column(name = "vector_b", columnDefinition = "JSON", nullable = false)
    private String vectorB;

    /**
     * 模型更新次数
     */
    @Column(name = "update_count")
    @Builder.Default
    private Integer updateCount = 0;

    /**
     * 最近一次奖励值
     */
    @Column(name = "last_reward", precision = 5, scale = 4)
    private BigDecimal lastReward;

    /**
     * 平均奖励值
     */
    @Column(name = "avg_reward", precision = 5, scale = 4)
    private BigDecimal avgReward;

    /**
     * 累计奖励总和
     */
    @Column(name = "total_reward", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal totalReward = BigDecimal.ZERO;

    /**
     * 最后更新时间
     */
    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    // ==================== 辅助方法 ====================

    /**
     * 增加更新计数
     */
    public void incrementUpdateCount() {
        this.updateCount = (this.updateCount == null ? 0 : this.updateCount) + 1;
    }

    /**
     * 更新平均奖励
     */
    public void updateAvgReward(BigDecimal newReward) {
        if (newReward == null) return;

        this.lastReward = newReward;
        this.totalReward = (this.totalReward == null ? BigDecimal.ZERO : this.totalReward).add(newReward);

        int count = this.updateCount == null ? 1 : this.updateCount;
        if (count > 0) {
            this.avgReward = this.totalReward.divide(new BigDecimal(count), 4, BigDecimal.ROUND_HALF_UP);
        }
    }
}

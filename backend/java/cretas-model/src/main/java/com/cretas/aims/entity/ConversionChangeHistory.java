package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ChangeType;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 转换率变更历史记录实体类
 * 用于追踪原料→产品转换率的每一次变更，供AI分析使用
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"conversion", "changedByUser"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "conversion_change_history",
       indexes = {
           @Index(name = "idx_cch_conversion_id", columnList = "conversion_id"),
           @Index(name = "idx_cch_factory_material", columnList = "factory_id, material_type_id"),
           @Index(name = "idx_cch_changed_at", columnList = "changed_at")
       }
)
@Where(clause = "deleted_at IS NULL")
public class ConversionChangeHistory extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /**
     * 关联的转换率配置ID
     */
    @Column(name = "conversion_id", nullable = false, length = 36)
    private String conversionId;

    /**
     * 工厂ID（冗余存储，便于按工厂查询）
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 原料类型ID（冗余存储，便于按原料查询）
     */
    @Column(name = "material_type_id", length = 50)
    private String materialTypeId;

    /**
     * 产品类型ID（冗余存储，便于按产品查询）
     */
    @Column(name = "product_type_id", length = 50)
    private String productTypeId;

    /**
     * 变更类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    // ========== 变更前后的核心值 ==========

    /**
     * 变更前的转换率
     */
    @Column(name = "old_conversion_rate", precision = 10, scale = 4)
    private BigDecimal oldConversionRate;

    /**
     * 变更后的转换率
     */
    @Column(name = "new_conversion_rate", precision = 10, scale = 4)
    private BigDecimal newConversionRate;

    /**
     * 变更前的损耗率
     */
    @Column(name = "old_wastage_rate", precision = 10, scale = 4)
    private BigDecimal oldWastageRate;

    /**
     * 变更后的损耗率
     */
    @Column(name = "new_wastage_rate", precision = 10, scale = 4)
    private BigDecimal newWastageRate;

    // ========== 变更说明 ==========

    /**
     * 变更原因（可选）
     */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========== 操作信息 ==========

    /**
     * 操作人用户ID
     */
    @Column(name = "changed_by")
    private Long changedBy;

    /**
     * 变更时间
     */
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    // ========== 关联关系 ==========

    /**
     * 关联的转换率配置
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversion_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialProductConversion conversion;

    /**
     * 操作人用户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User changedByUser;
}

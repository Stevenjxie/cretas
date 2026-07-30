package com.cretas.aims.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 原材料批次调整记录实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"batch", "adjustedBy"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "material_batch_adjustments",
       indexes = {
           @Index(name = "idx_adjustment_batch", columnList = "material_batch_id"),
           @Index(name = "idx_adjustment_time", columnList = "adjustment_time")
       }
)
@Where(clause = "deleted_at IS NULL")
public class MaterialBatchAdjustment extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;
    @Column(name = "adjustment_type", nullable = false, length = 50)
    private String adjustmentType; // loss, damage, correction, return
    @Column(name = "quantity_before", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityBefore;
    @Column(name = "adjustment_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal adjustmentQuantity;
    @Column(name = "quantity_after", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityAfter;
    @Column(name = "reason", nullable = false)
    private String reason;
    @Column(name = "adjustment_time", nullable = false)
    private LocalDateTime adjustmentTime;
    @Column(name = "adjusted_by", nullable = false)
    private Long adjustedBy;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialBatch batch;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjusted_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User adjustedByUser;
}

package com.cretas.aims.entity.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * T126 Phase 1 — 成品库存调整日志
 *
 * <p>每次 POST /finished-goods/{id}/adjust 成功后写一行，
 * 提供可审计的数量变更历史。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "finished_goods_adjustment_log",
        indexes = {
                @Index(name = "idx_fgal_factory_batch", columnList = "factory_id, batch_id"),
                @Index(name = "idx_fgal_created_at",    columnList = "created_at")
        })
public class FinishedGoodsAdjustmentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "batch_id", nullable = false, length = 191)
    private String batchId;

    @Column(name = "adjustment_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal adjustmentQuantity;

    @Column(name = "before_produced", nullable = false, precision = 15, scale = 4)
    private BigDecimal beforeProduced;

    @Column(name = "after_produced", nullable = false, precision = 15, scale = 4)
    private BigDecimal afterProduced;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** 调整来源类型 enum value stored as string */
    @Column(name = "reference_type", length = 50)
    private String referenceType;

    /** 操作人 userId — captured via request.getAttribute("userId"), NOT SecurityContextHolder */
    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

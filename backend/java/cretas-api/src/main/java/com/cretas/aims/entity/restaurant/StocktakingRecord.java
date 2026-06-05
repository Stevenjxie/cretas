package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 库存盘点记录实体（餐饮版）
 *
 * <p>盘点流程：</p>
 * <ol>
 *   <li>创建盘点单（自动从系统读取 systemQuantity）</li>
 *   <li>盘点人录入 actualQuantity</li>
 *   <li>完成盘点（计算 differenceQuantity 和 differenceType）</li>
 *   <li>差异可触发库存调整（盘盈/盘亏）</li>
 * </ol>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "stocktaking_records",
        indexes = {
                @Index(name = "idx_stk_factory", columnList = "factory_id"),
                @Index(name = "idx_stk_date", columnList = "stocktaking_date"),
                @Index(name = "idx_stk_status", columnList = "status"),
                @Index(name = "idx_stk_material", columnList = "raw_material_type_id"),
                @Index(name = "idx_stk_factory_date", columnList = "factory_id,stocktaking_date,status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class StocktakingRecord extends BaseEntity {

    // ========== 主键 ==========

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    // ========== 归属与单号 ==========

    /**
     * Apr 20 Bug BR-05 fix: 移除 @NotBlank — controller.create 在 @Valid 之后
     * setFactoryId(pathFactoryId). DB 列仍 NOT NULL.
     */
    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    /**
     * 盘点单号（如：STK-20260220-001）
     */
    @Column(name = "stocktaking_number", length = 50)
    private String stocktakingNumber;

    /**
     * 盘点日期
     * Apr 20 Bug BR-05 fix: 移除 @NotNull — controller auto-fill LocalDate.now() 如 null.
     * @Valid 先跑导致"不能为空"误报 (尽管前端发日期, 某些 edge 边界 null 仍会到).
     * DB 列仍 NOT NULL.
     */
    @Column(name = "stocktaking_date", nullable = false)
    private LocalDate stocktakingDate;

    // ========== 状态 ==========

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.IN_PROGRESS;

    // ========== 食材信息 ==========

    /**
     * 食材类型 ID (raw_material_types.id)
     */
    @NotBlank
    @Column(name = "raw_material_type_id", nullable = false, length = 191)
    private String rawMaterialTypeId;

    /**
     * 计量单位
     */
    @Column(name = "unit", length = 20)
    private String unit;

    // ========== 数量信息 ==========

    /**
     * 系统账面库存数量（盘点创建时自动读取）
     */
    @Column(name = "system_quantity", precision = 10, scale = 4)
    private BigDecimal systemQuantity;

    /**
     * 实盘数量（盘点人现场清点）
     */
    @Column(name = "actual_quantity", precision = 10, scale = 4)
    private BigDecimal actualQuantity;

    /**
     * 差异数量 = actualQuantity - systemQuantity
     * <p>正数为盘盈，负数为盘亏</p>
     */
    @Column(name = "difference_quantity", precision = 10, scale = 4)
    private BigDecimal differenceQuantity;

    /**
     * 差异类型（盘盈/盘亏/一致）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "difference_type", length = 32)
    private DifferenceType differenceType;

    /**
     * 差异金额（|differenceQuantity| × 单价）
     */
    @Column(name = "difference_amount", precision = 15, scale = 2)
    private BigDecimal differenceAmount;

    @Column(name = "inventory_posted_at")
    private LocalDateTime inventoryPostedAt;

    @Column(name = "inventory_posted_by")
    private Long inventoryPostedBy;

    @Column(name = "inventory_posting_detail", columnDefinition = "TEXT")
    private String inventoryPostingDetail;

    @Column(name = "inventory_posting_error", columnDefinition = "TEXT")
    private String inventoryPostingError;

    /**
     * 差异原因说明
     */
    @Column(name = "adjustment_reason", columnDefinition = "TEXT")
    private String adjustmentReason;

    // ========== 人员信息 ==========

    /**
     * 盘点人 ID
     */
    @Column(name = "counted_by")
    private Long countedBy;

    /**
     * 复核人 ID
     */
    @Column(name = "verified_by")
    private Long verifiedBy;

    /**
     * 完成时间
     */
    @Column(name = "completed_at")
    private java.time.LocalDateTime completedAt;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========== 枚举 ==========

    /**
     * 盘点状态
     * <ul>
     *   <li>IN_PROGRESS — 盘点中（已创建，等待录入实盘数量）</li>
     *   <li>COMPLETED — 已完成（差异已计算）</li>
     *   <li>CANCELLED — 已取消</li>
     * </ul>
     */
    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        CANCELLED
    }

    /**
     * 差异类型
     * <ul>
     *   <li>SURPLUS — 盘盈（实盘 > 系统）</li>
     *   <li>SHORTAGE — 盘亏（实盘 < 系统）</li>
     *   <li>MATCH — 一致（实盘 = 系统）</li>
     * </ul>
     */
    public enum DifferenceType {
        SURPLUS,
        SHORTAGE,
        MATCH
    }

    // ========== 业务方法 ==========

    /**
     * 计算差异（在完成盘点时调用）
     */
    public void calculateDifference() {
        if (actualQuantity == null || systemQuantity == null) {
            return;
        }
        this.differenceQuantity = actualQuantity.subtract(systemQuantity);
        int cmp = differenceQuantity.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            this.differenceType = DifferenceType.SURPLUS;
        } else if (cmp < 0) {
            this.differenceType = DifferenceType.SHORTAGE;
        } else {
            this.differenceType = DifferenceType.MATCH;
        }
    }
}

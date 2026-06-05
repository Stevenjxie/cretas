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
 * 领料/日消耗记录实体（餐饮版）
 *
 * <p>记录厨房领料申请，支持两种模式：</p>
 * <ul>
 *   <li><b>PRODUCTION</b>：按菜品 BOM 自动计算所需食材用量</li>
 *   <li><b>MANUAL</b>：手动指定食材和数量（临时补领、特殊用途）</li>
 * </ul>
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
@Table(name = "material_requisitions",
        indexes = {
                @Index(name = "idx_req_factory", columnList = "factory_id"),
                @Index(name = "idx_req_date", columnList = "requisition_date"),
                @Index(name = "idx_req_status", columnList = "status"),
                @Index(name = "idx_req_type", columnList = "type"),
                @Index(name = "idx_req_factory_date_status", columnList = "factory_id,requisition_date,status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class MaterialRequisition extends BaseEntity {

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
     * Apr 20 Bug BR-02 fix: 移除 @NotBlank — controller.create 在 @Valid 之后
     * setFactoryId(pathFactoryId). @Valid 先跑导致"不能为空"误报. DB 列仍 NOT NULL.
     */
    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    /**
     * 领料单号（如：REQ-20260220-001）
     */
    @Column(name = "requisition_number", length = 50)
    private String requisitionNumber;

    /**
     * 领料日期
     * Apr 20 Bug BR-02 fix: 移除 @NotNull — controller.createRequisition 在 @Valid 之后会
     * auto-fill LocalDate.now() 如果 null, 但 @Valid 先跑会误判"不能为空". DB 列仍 NOT NULL.
     */
    @Column(name = "requisition_date", nullable = false)
    private LocalDate requisitionDate;

    // ========== 类型与状态 ==========

    /**
     * 领料类型
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private RequisitionType type = RequisitionType.MANUAL;

    /**
     * 审批状态
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.DRAFT;

    // ========== 按菜品领料 (type = PRODUCTION) ==========

    /**
     * 菜品 ID（PRODUCTION 模式下必填）
     */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /**
     * 制作份数
     */
    @Column(name = "dish_quantity")
    private Integer dishQuantity;

    // ========== 食材详情 ==========

    /**
     * 食材类型 ID (raw_material_types.id)
     */
    @Column(name = "raw_material_type_id", length = 191)
    private String rawMaterialTypeId;

    /**
     * 申请数量
     */
    @Column(name = "requested_quantity", precision = 10, scale = 4)
    private BigDecimal requestedQuantity;

    /**
     * 实际领用数量（审批通过后仓库实发量）
     */
    @Column(name = "actual_quantity", precision = 10, scale = 4)
    private BigDecimal actualQuantity;

    /**
     * 从哪个物料批次领料 (material_batches.id)
     */
    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    @Column(name = "inventory_posted_at")
    private LocalDateTime inventoryPostedAt;

    @Column(name = "inventory_posted_by")
    private Long inventoryPostedBy;

    @Column(name = "inventory_posting_detail", columnDefinition = "TEXT")
    private String inventoryPostingDetail;

    @Column(name = "inventory_posting_error", columnDefinition = "TEXT")
    private String inventoryPostingError;

    /**
     * 计量单位
     */
    @Column(name = "unit", length = 20)
    private String unit;

    // ========== 审批信息 ==========

    /**
     * 申请人 ID
     */
    @Column(name = "requested_by")
    private Long requestedBy;

    /**
     * 审批人 ID
     */
    @Column(name = "approved_by")
    private Long approvedBy;

    /**
     * 审批时间
     */
    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    /**
     * 备注 / 驳回原因
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ========== 枚举 ==========

    /**
     * 领料类型
     * <ul>
     *   <li>PRODUCTION — 按菜品 BOM 计算用量</li>
     *   <li>MANUAL — 手动指定食材和数量</li>
     * </ul>
     */
    public enum RequisitionType {
        PRODUCTION,
        MANUAL
    }

    /**
     * 审批状态
     * <ul>
     *   <li>DRAFT — 草稿，可编辑</li>
     *   <li>SUBMITTED — 已提交，待审批</li>
     *   <li>APPROVED — 已审批，可领料</li>
     *   <li>REJECTED — 已驳回</li>
     * </ul>
     */
    public enum Status {
        DRAFT,
        SUBMITTED,
        APPROVED,
        REJECTED
    }
}

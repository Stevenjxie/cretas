package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 销售订单行价格调整记录
 *
 * <p>每次对销售订单行单价的改动都写一条记录，支持:
 * <ul>
 *   <li>历史留痕 (改前/改后价格、操作人、原因)</li>
 *   <li>fool-proof Rule 3: reasonType 用 enum dropdown, OTHER 时才填 reasonDetail</li>
 *   <li>阈值触发审批: 降价 > 10% 或 涨价 > 20% 时 approvalRequired=true</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_price_adjustment_records",
        indexes = {
                @Index(name = "idx_spa_factory_order", columnList = "factory_id, sales_order_id"),
                @Index(name = "idx_spa_line", columnList = "sales_order_line_id")
        })
@Where(clause = "deleted_at IS NULL")
public class SalesPriceAdjustmentRecord extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "sales_order_line_id", nullable = false)
    private Long salesOrderLineId;

    @Column(name = "sales_order_id", nullable = false, length = 191)
    private String salesOrderId;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "old_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal oldUnitPrice;

    @Column(name = "new_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal newUnitPrice;

    /**
     * 改价原因类型 (fool-proof Rule 3 — dropdown enum).
     * 当为 OTHER 时，adjustmentReasonDetail 必填。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_reason_type", nullable = false, length = 64)
    private ReasonType adjustmentReasonType;

    /** 原因明细 (仅当 reasonType=OTHER 时需填) */
    @Column(name = "adjustment_reason_detail", columnDefinition = "TEXT")
    private String adjustmentReasonDetail;

    @Column(name = "adjusted_by", nullable = false)
    private Long adjustedBy;

    @Column(name = "adjusted_by_name", length = 200)
    private String adjustedByName;

    /** 是否需要审批 (降价>10% 或 涨价>20%) */
    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus = ApprovalStatus.NOT_REQUIRED;

    /** 关联审批链 ID (当 approvalRequired=true 时写入) */
    @Column(name = "approval_chain_id", length = 191)
    private String approvalChainId;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
    }

    // ==================== Enums ====================

    /** 改价原因 (fool-proof Rule 3 标准化选项) */
    public enum ReasonType {
        CUSTOMER_REQUEST,   // 客户要求
        MARKET_CHANGE,      // 市场行情变化
        NEGOTIATION,        // 商务谈判
        PROMOTION,          // 促销活动
        ERROR_CORRECTION,   // 录入错误修正
        OTHER               // 其他 (需填 reasonDetail)
    }

    /** 审批状态 */
    public enum ApprovalStatus {
        NOT_REQUIRED,  // 未达到审批阈值，无需审批
        PENDING,       // 等待审批
        APPROVED,      // 审批通过
        REJECTED       // 审批拒绝
    }
}

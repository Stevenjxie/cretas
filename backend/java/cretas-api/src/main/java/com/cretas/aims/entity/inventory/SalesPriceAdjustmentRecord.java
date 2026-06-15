package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 销售订单行价格调整记录 — warn-not-block 模式
 *
 * <p>每次对销售订单行单价的改动都写一条记录，支持:
 * <ul>
 *   <li>历史留痕 (改前/改后价格、操作人、原因)</li>
 *   <li>fool-proof Rule 3: reasonType 用 enum dropdown, OTHER 时才填 reasonDetail</li>
 *   <li>warn-not-block: 改价立即生效; 超阈值 (降价>10%/涨价>20%) 设 flagged=true 供审计</li>
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

    /**
     * 超阈值审计标记: 降价 > 10% 或 涨价 > 20% 时为 true.
     * 改价仍立即生效 (warn-not-block), 此字段仅供审计/复核用途.
     */
    @Column(name = "flagged", nullable = false)
    private boolean flagged = false;

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
}

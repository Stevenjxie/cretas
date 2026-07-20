package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.ProductType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 发货行项目
 * 每行对应一种产品的发货明细
 * 发货确认后关联到扣减的 FinishedGoodsBatch
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"deliveryRecord", "productType", "finishedGoodsBatch"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sales_delivery_items",
        indexes = {
                @Index(name = "idx_sdi_record", columnList = "delivery_record_id"),
                @Index(name = "idx_sdi_product", columnList = "product_type_id"),
                @Index(name = "idx_sdi_batch", columnList = "finished_goods_batch_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SalesDeliveryItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_record_id", nullable = false, length = 191)
    private String deliveryRecordId;

    /** 精确关联销售订单行；多行同 SKU 时不可退化为 productTypeId 猜测。 */
    @Column(name = "sales_order_item_id")
    private Long salesOrderItemId;

    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 产品名称（冗余） */
    @Column(name = "product_name", length = 200)
    private String productName;

    /** 发货数量 */
    @Column(name = "delivered_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal deliveredQuantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** Packaging selection snapshot copied from the originating sales-order line. */
    @Column(name = "packaging_spec_id", length = 36)
    private String packagingSpecId;

    @Column(name = "packaging_spec_name", length = 60)
    private String packagingSpecName;

    @Column(name = "packaging_unit", length = 20)
    private String packagingUnit;

    @Column(name = "packaging_base_unit", length = 20)
    private String packagingBaseUnit;

    @Column(name = "packaging_factor", precision = 20, scale = 8)
    private BigDecimal packagingFactor;

    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 关联扣减的成品批次ID */
    @Column(name = "finished_goods_batch_id", length = 191)
    private String finishedGoodsBatchId;

    /**
     * 来源仓库 code — T4-D5 (issue #553): preserved from SalesOrderItem.sourceWarehouseCode
     * when the delivery is created. Currently stored only (no display path yet on delivery
     * detail — sales order detail.vue:1060 already displays the upstream order field).
     * Reserved for future intelligent batch allocation; existing FinishedGoodsBatch
     * allocation logic does NOT yet filter by this column (follow-up scope, gated by PRD
     * decisions on fallback/split behavior when picked warehouse has insufficient stock).
     *
     * <p>UI maps via {@code utils/warehouse.ts:warehouseDisplayLabel} — WH-LOG → 总仓,
     * WH-WKS → 线边仓. Migration: {@code V20260514_02__add_sales_delivery_item_source_warehouse_code.sql}
     */
    @Column(name = "source_warehouse_code", length = 20)
    private String sourceWarehouseCode;

    @Column(name = "remark", length = 500)
    private String remark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_record_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SalesDeliveryRecord deliveryRecord;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductType productType;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_goods_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private FinishedGoodsBatch finishedGoodsBatch;
}

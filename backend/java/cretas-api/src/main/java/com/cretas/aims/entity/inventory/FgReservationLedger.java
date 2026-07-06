package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 成品预留台账 (fg_reservation_ledger)
 *
 * <p>把 {@link FinishedGoodsBatch#getReservedQuantity()} 从"匿名聚合计数器"升级为
 * per-SO/delivery 可归属、可精确释放的台账。核心不变式:
 * <pre>
 *   finished_goods_batches.reserved_quantity
 *       == Σ(该批次所有 status=ACTIVE 台账行 reserved_qty)
 * </pre>
 *
 * <p>{@link #reservedQty} 与 {@code batch.reserved_quantity} 同单位 (批次原生单位) —— 台账层
 * 不做单位换算, 它记录的就是"写进 batch.reserved 的那个数", 保证不变式逐值相等。
 *
 * @author Cretas Team
 * @since 2026-07-06
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "fg_reservation_ledger",
        indexes = {
                @Index(name = "idx_fgrl_factory_so", columnList = "factory_id, sales_order_id"),
                @Index(name = "idx_fgrl_batch_status", columnList = "finished_goods_batch_id, status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class FgReservationLedger extends BaseEntity {

    /** 台账行状态。 */
    public static final class Status {
        /** 生效中 —— 计入 batch.reserved_quantity。 */
        public static final String ACTIVE = "ACTIVE";
        /** 已释放 —— 取消 / 发货 / 完成后置此, 不再计入 batch.reserved。 */
        public static final String RELEASED = "RELEASED";

        private Status() {}
    }

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 关联销售订单 (预留的归属主体)。 */
    @Column(name = "sales_order_id", nullable = false, length = 191)
    private String salesOrderId;

    /** 关联销售订单行 (可空 —— 归属可细化到行, backfill/FEFO 尽力填)。 */
    @Column(name = "sales_order_item_id", length = 191)
    private String salesOrderItemId;

    /** 关联发货单 (预留被某次发货锁定时填, 可空)。 */
    @Column(name = "delivery_id", length = 191)
    private String deliveryId;

    /** 关联发货行 (可空)。 */
    @Column(name = "delivery_item_id", length = 191)
    private String deliveryItemId;

    /** 预留落在哪个成品批次。 */
    @Column(name = "finished_goods_batch_id", nullable = false, length = 191)
    private String finishedGoodsBatchId;

    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 预留量 (批次原生单位, 与 finished_goods_batches.reserved_quantity 同口径)。 */
    @Column(name = "reserved_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal reservedQty;

    /** 状态: ACTIVE / RELEASED。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = Status.ACTIVE;
}

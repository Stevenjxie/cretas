package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 客户记忆价 — Sprint 4 W2 S-PRICE-1
 *
 * Append-only price history per (customer, productType). 用于销售单 prefill 时
 * 3 层 fallback 第 2 层 (PriceList SELLING_PRICE > customer_price_history > productType default).
 *
 * 写入时机: SalesOrder.status = CONFIRMED (via SalesOrderConfirmedEvent listener).
 *
 * Idempotency (防呆 R4): UNIQUE(factory_id, customer_id, product_type_id, source_sales_order_id) —
 * 同一 SO 行项目重复 confirm (e.g. cancel→re-confirm) 不会重复写 row.
 *
 * Query: findTop1ByFactoryIdAndCustomerIdAndProductTypeIdOrderByOrderDateDescRecordedAtDesc(...)
 * — 最近一次成交 (按订单日期, tie-breaker 录入时间).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "customer_price_history",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_cph_dedup",
                             columnNames = {"factory_id", "customer_id", "product_type_id", "source_sales_order_id"})
       },
       indexes = {
           @Index(name = "idx_cph_lookup",       columnList = "factory_id,customer_id,product_type_id,order_date"),
           @Index(name = "idx_cph_source_order", columnList = "source_sales_order_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class CustomerPriceHistory extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    /** 注意: 用 productTypeId (销售/SO 侧) 而非 materialId (采购/BOM 侧). Per §0 grep finding. */
    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    /** 成交单价 (含税与否由 source SO 决定) */
    @PriceSensitive
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 来源销售订单 ID — 用于 hint "上次成交 ¥X (SO-XXX)" + R4 idempotency */
    @Column(name = "source_sales_order_id", nullable = false, length = 191)
    private String sourceSalesOrderId;

    /** 来源销售订单号 (冗余, 避免 hint 时再 join) */
    @Column(name = "source_order_number", length = 50)
    private String sourceOrderNumber;

    /** 订单日期 (来自 SO.orderDate) — 用于 "上次成交 yyyy-mm-dd" hint */
    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;
}

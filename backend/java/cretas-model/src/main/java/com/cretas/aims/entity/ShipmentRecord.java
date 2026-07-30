package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.Where;
/**
 * 出货记录实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "customer", "recorder"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "shipment_records",
       indexes = {
           @Index(name = "idx_shipment_factory", columnList = "factory_id"),
           @Index(name = "idx_shipment_customer", columnList = "customer_id"),
           @Index(name = "idx_shipment_date", columnList = "shipment_date"),
           @Index(name = "idx_shipment_batch", columnList = "batch_number")
       }
)
@Where(clause = "deleted_at IS NULL")
public class ShipmentRecord extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "shipment_number", nullable = false, unique = true, length = 50)
    private String shipmentNumber;
    @NotBlank(message = "客户ID不能为空")
    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;
    @Column(name = "order_number", length = 100)
    private String orderNumber;
    @Column(name = "batch_number", length = 100)
    private String batchNumber;
    @NotBlank(message = "产品名称不能为空")
    @Column(name = "product_name", nullable = false)
    private String productName;
    @NotNull(message = "数量不能为空")
    @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;
    @NotBlank(message = "单位不能为空")
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;
    @PriceSensitive
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
    @PriceSensitive
    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;
    @NotNull(message = "发货日期不能为空")
    @Column(name = "shipment_date", nullable = false)
    private LocalDate shipmentDate;
    @Column(name = "delivery_address")
    private String deliveryAddress;
    @Column(name = "logistics_company", length = 100)
    private String logisticsCompany;
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;
    @Column(name = "status", nullable = false, length = 20)
    private String status = "pending"; // pending, shipped, delivered, returned
    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    @Column(name = "vehicle_number", length = 50)
    private String vehicleNumber;
    @Column(name = "driver_name", length = 50)
    private String driverName;
    @Column(name = "driver_phone", length = 30)
    private String driverPhone;
    // 关联关系 - 使用JsonIgnore避免懒加载序列化问题
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Customer customer;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User recorder;
}

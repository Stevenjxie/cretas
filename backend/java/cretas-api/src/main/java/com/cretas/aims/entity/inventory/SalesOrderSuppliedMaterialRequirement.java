package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Structured customer-supplied material requirement attached to a sales order.
 *
 * <p>This row is the single warehouse task identity. Receipt mutations may update
 * {@link #receivedQuantity} and {@link #status} in a later warehouse workflow, but creating a
 * requirement never creates inventory or a {@code MaterialBatch}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"salesOrder", "salesOrderItem", "materialType", "targetWarehouse"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_order_supplied_material_requirements",
        indexes = {
                @Index(name = "idx_sosmr_factory_status_arrival",
                        columnList = "factory_id,status,expected_arrival_at"),
                @Index(name = "idx_sosmr_sales_order", columnList = "sales_order_id"),
                @Index(name = "idx_sosmr_sales_order_item", columnList = "sales_order_item_id"),
                @Index(name = "idx_sosmr_material", columnList = "material_type_id"),
                @Index(name = "idx_sosmr_target_warehouse", columnList = "target_warehouse_id")
        })
@Where(clause = "deleted_at IS NULL")
public class SalesOrderSuppliedMaterialRequirement extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUuidAndDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (receivedQuantity == null) {
            receivedQuantity = BigDecimal.ZERO;
        }
        if (status == null) {
            status = SalesOrderSuppliedMaterialRequirementStatus.PENDING;
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @NotBlank
    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    @NotBlank
    @Column(name = "sales_order_id", nullable = false, length = 191)
    private String salesOrderId;

    /** Optional finished-product order-line lineage. */
    @Column(name = "sales_order_item_id")
    private Long salesOrderItemId;

    @NotBlank
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    /** Immutable display snapshot from the factory material master. */
    @NotBlank
    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    @NotNull
    @Positive
    @Digits(integer = 8, fraction = 2)
    @Column(name = "expected_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedQuantity;

    @NotNull
    @Digits(integer = 8, fraction = 2)
    @Column(name = "received_quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    /** Canonical inventory-quantity unit code. */
    @NotBlank
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @NotNull
    @Column(name = "expected_arrival_at", nullable = false)
    private LocalDateTime expectedArrivalAt;

    @NotBlank
    @Column(name = "target_warehouse_id", nullable = false, length = 64)
    private String targetWarehouseId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SalesOrderSuppliedMaterialRequirementStatus status =
            SalesOrderSuppliedMaterialRequirementStatus.PENDING;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private SalesOrder salesOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_item_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private SalesOrderItem salesOrderItem;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private RawMaterialType materialType;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_warehouse_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    private FactoryWarehouse targetWarehouse;

    @Transient
    public BigDecimal getRemainingQuantity() {
        if (expectedQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal received = receivedQuantity != null ? receivedQuantity : BigDecimal.ZERO;
        return expectedQuantity.subtract(received).max(BigDecimal.ZERO);
    }
}

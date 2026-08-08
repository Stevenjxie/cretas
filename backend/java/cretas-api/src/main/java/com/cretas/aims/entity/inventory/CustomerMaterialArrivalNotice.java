package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.CustomerMaterialArrivalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Operations coordination document for customer material that may arrive before any sales order.
 *
 * <p>The notice deliberately has no material or quantity lines: warehouse records the actual
 * material identity and quantity only when the truck arrives. Creating this row never creates or
 * changes inventory.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "customer_material_arrival_notices", indexes = {
        @Index(name = "idx_cman_factory_status_arrival",
                columnList = "factory_id,status,expected_arrival_at"),
        @Index(name = "idx_cman_factory_customer",
                columnList = "factory_id,customer_id")
})
@Where(clause = "deleted_at IS NULL")
public class CustomerMaterialArrivalNotice extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "notice_number", nullable = false, length = 50)
    private String noticeNumber;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    @Formula("(SELECT c.name FROM customers c WHERE c.id = customer_id)")
    private String customerName;

    @Column(name = "expected_arrival_at")
    private LocalDateTime expectedArrivalAt;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "remark", length = 1000)
    private String remark;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CustomerMaterialArrivalStatus status = CustomerMaterialArrivalStatus.OPEN;

    @Column(name = "receipt_count", nullable = false)
    private Integer receiptCount = 0;

    @Column(name = "last_received_at")
    private LocalDateTime lastReceivedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void assignDefaults() {
        if (id == null) id = UUID.randomUUID().toString();
        if (status == null) status = CustomerMaterialArrivalStatus.OPEN;
        if (receiptCount == null) receiptCount = 0;
    }
}

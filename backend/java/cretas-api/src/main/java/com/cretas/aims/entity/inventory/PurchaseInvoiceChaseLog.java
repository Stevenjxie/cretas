package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseLevel;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_invoice_chase_logs",
        indexes = {
                @Index(name = "idx_pi_chase_factory_po_status",
                        columnList = "factory_id, purchase_order_id, status"),
                @Index(name = "idx_pi_chase_window",
                        columnList = "factory_id, purchase_order_id, chase_level, chase_window_start")
        })
@Where(clause = "deleted_at IS NULL")
public class PurchaseInvoiceChaseLog extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "order_number", length = 50)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "chase_level", nullable = false, length = 32)
    private PurchaseInvoiceChaseLevel chaseLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseInvoiceChaseStatus status = PurchaseInvoiceChaseStatus.SENT;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "chase_window_start", nullable = false)
    private LocalDate chaseWindowStart;

    @Column(name = "days_overdue", nullable = false)
    private Integer daysOverdue;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}

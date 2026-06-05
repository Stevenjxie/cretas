package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "lines")
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "restaurant_supplier_monthly_reconciliations",
        indexes = {
                @Index(name = "idx_rsmr_factory_supplier_month",
                        columnList = "factory_id,supplier_id,reconciliation_month"),
                @Index(name = "idx_rsmr_factory_status", columnList = "factory_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class SupplierMonthlyReconciliation extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    /** First day of the reconciliation month. */
    @Column(name = "reconciliation_month", nullable = false)
    private LocalDate reconciliationMonth;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "delivery_note_count", nullable = false)
    private Integer deliveryNoteCount = 0;

    @Column(name = "ap_transaction_count", nullable = false)
    private Integer apTransactionCount = 0;

    @PriceSensitive
    @Column(name = "delivery_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal deliveryTotal = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "ap_invoice_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal apInvoiceTotal = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "ap_payment_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal apPaymentTotal = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "ap_adjustment_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal apAdjustmentTotal = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "difference_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal differenceAmount = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "net_payable_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal netPayableAmount = BigDecimal.ZERO;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @OneToMany(mappedBy = "reconciliation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierMonthlyReconciliationLine> lines = new ArrayList<>();

    public void addLine(SupplierMonthlyReconciliationLine line) {
        line.setReconciliation(this);
        lines.add(line);
    }

    public enum Status {
        DRAFT,
        CONFIRMED
    }
}

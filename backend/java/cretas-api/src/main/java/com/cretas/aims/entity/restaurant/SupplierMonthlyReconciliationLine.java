package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "restaurant_supplier_monthly_reconciliation_lines",
        indexes = {
                @Index(name = "idx_rsmrl_reconciliation", columnList = "reconciliation_id"),
                @Index(name = "idx_rsmrl_delivery_note", columnList = "delivery_note_id"),
                @Index(name = "idx_rsmrl_ap_transaction", columnList = "ap_transaction_id")
        })
public class SupplierMonthlyReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconciliation_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SupplierMonthlyReconciliation reconciliation;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 32)
    private LineType lineType;

    @Column(name = "delivery_note_id", length = 191)
    private String deliveryNoteId;

    @Column(name = "delivery_note_number", length = 100)
    private String deliveryNoteNumber;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @PriceSensitive
    @Column(name = "delivery_amount", precision = 15, scale = 2)
    private BigDecimal deliveryAmount;

    @Column(name = "ap_transaction_id", length = 191)
    private String apTransactionId;

    @Column(name = "ap_transaction_number", length = 50)
    private String apTransactionNumber;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 32)
    private ArApTransactionType transactionType;

    @PriceSensitive
    @Column(name = "ap_amount", precision = 15, scale = 2)
    private BigDecimal apAmount;

    @PriceSensitive
    @Column(name = "difference_amount", precision = 15, scale = 2)
    private BigDecimal differenceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_status", nullable = false, length = 32)
    private LineStatus lineStatus;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum LineType {
        DELIVERY_NOTE,
        AP_TRANSACTION
    }

    public enum LineStatus {
        MATCHED,
        MISSING_AP,
        AMOUNT_MISMATCH,
        UNMATCHED_AP,
        INFO
    }
}

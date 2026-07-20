package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/** Immutable allocation from one AP payment ledger row to one AP invoice open item. */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "ar_ap_payment_allocations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ap_payment_allocation_pair",
                columnNames = {"factory_id", "payment_transaction_id", "payable_transaction_id"}),
        indexes = {
                @Index(name = "idx_ap_alloc_payment", columnList = "factory_id,payment_transaction_id"),
                @Index(name = "idx_ap_alloc_payable", columnList = "factory_id,payable_transaction_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ArApPaymentAllocation extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUuid() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "payment_transaction_id", nullable = false, length = 191)
    private String paymentTransactionId;

    @Column(name = "payable_transaction_id", nullable = false, length = 191)
    private String payableTransactionId;

    @PriceSensitive
    @Column(name = "allocated_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "operated_by", nullable = false)
    private Long operatedBy;
}

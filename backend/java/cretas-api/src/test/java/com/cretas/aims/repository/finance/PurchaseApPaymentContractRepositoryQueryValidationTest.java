package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import com.cretas.aims.entity.finance.ArApTransaction;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.finance")
@DisplayName("AP payment entity and repository real JPA startup gate")
class PurchaseApPaymentContractRepositoryQueryValidationTest {

    @Autowired ArApTransactionRepository transactionRepository;
    @Autowired ArApPaymentAllocationRepository allocationRepository;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("lock and anomaly queries parse; allocated payments are not reported as orphan")
    void mappingsAndQueriesBoot() {
        ArApTransaction payable = transaction("AP-I-1", ArApTransactionType.AP_INVOICE, "100.00");
        payable.setSettledAmount(new BigDecimal("0.00"));
        payable.setOutstandingAmount(new BigDecimal("100.00"));
        payable.setPaymentStatus(PayablePaymentStatus.UNPAID);
        transactionRepository.saveAndFlush(payable);

        ArApTransaction allocatedPayment = transaction("AP-P-1", ArApTransactionType.AP_PAYMENT, "-40.00");
        allocatedPayment.setSourceType("PAYABLE_SETTLEMENT");
        allocatedPayment.setSourceId("idem-jpa-1");
        transactionRepository.saveAndFlush(allocatedPayment);

        ArApPaymentAllocation allocation = new ArApPaymentAllocation();
        allocation.setId("ALLOC-1");
        allocation.setFactoryId("F-JPA");
        allocation.setPaymentTransactionId(allocatedPayment.getId());
        allocation.setPayableTransactionId(payable.getId());
        allocation.setAllocatedAmount(new BigDecimal("40.00"));
        allocation.setCurrencyCode("CNY");
        allocation.setOperatedBy(1L);
        allocationRepository.saveAndFlush(allocation);

        ArApTransaction orphan = transaction("AP-P-OLD", ArApTransactionType.AP_PAYMENT, "-10.00");
        transactionRepository.saveAndFlush(orphan);
        entityManager.clear();

        assertThat(transactionRepository.findPayableForSettlement("F-JPA", "AP-I-1")).isPresent();
        assertThat(transactionRepository.findPayableForSettlement("F-OTHER", "AP-I-1")).isEmpty();
        List<ArApTransaction> anomalies = transactionRepository.findUnallocatedApPayments("F-JPA");
        assertThat(anomalies).extracting(ArApTransaction::getId).containsExactly("AP-P-OLD");
        assertThat(entityManager.getMetamodel().entity(ArApTransaction.class).getAttribute("version")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(ArApPaymentAllocation.class)
                .getAttribute("payableTransactionId")).isNotNull();
    }

    @Test
    @DisplayName("legacy payable persists as unreconciled without invented open balance")
    void legacyPayableStatePersistsWithoutInventedAmounts() {
        ArApTransaction legacy = transaction("AP-I-LEGACY", ArApTransactionType.AP_INVOICE, "220.00");
        legacy.setSettledAmount(null);
        legacy.setOutstandingAmount(null);
        legacy.setPaymentStatus(PayablePaymentStatus.NEEDS_RECONCILIATION);

        transactionRepository.saveAndFlush(legacy);
        entityManager.clear();

        ArApTransaction reloaded = transactionRepository.findById("AP-I-LEGACY").orElseThrow();
        assertThat(reloaded.getPaymentStatus()).isEqualTo(PayablePaymentStatus.NEEDS_RECONCILIATION);
        assertThat(reloaded.getSettledAmount()).isNull();
        assertThat(reloaded.getOutstandingAmount()).isNull();
    }

    private ArApTransaction transaction(String id, ArApTransactionType type, String amount) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        transaction.setFactoryId("F-JPA");
        transaction.setTransactionNumber("NO-" + id);
        transaction.setTransactionType(type);
        transaction.setCounterpartyType(CounterpartyType.SUPPLIER);
        transaction.setCounterpartyId("SUP-JPA");
        transaction.setCounterpartyName("JPA供应商");
        transaction.setPurchaseOrderId("PO-JPA");
        transaction.setAmount(new BigDecimal(amount));
        transaction.setBalanceAfter(new BigDecimal("100.00"));
        transaction.setCurrencyCode("CNY");
        transaction.setTransactionDate(LocalDate.of(2026, 7, 20));
        transaction.setApprovalStatus(ArApApprovalStatus.APPROVED);
        return transaction;
    }
}

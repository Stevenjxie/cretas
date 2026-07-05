package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🔒🔒 finance-dashboard sign-agnostic fix (2026-07) — JPA slice for {@link ArApTransactionRepository}
 * {@code sumReceivables}/{@code sumPayables}/{@code sumByCounterparty}/{@code sumArPaymentsBySalesOrderId}.
 *
 * <p>Bug (prod-data verified, F006): AR/AP payment SIGN is inconsistent across write paths —
 * {@code ArApServiceImpl.recordArPayment}/{@code recordApPayment}/{@code reverseOpeningPayable} store the
 * amount NEGATIVE (amount.negate()), while {@code PaymentRequestServiceImpl.markPaidPurchase}/
 * {@code markPaidSales} store AP_PAYMENT/AR_CREDIT_NOTE POSITIVE. The old read CASE used {@code -t.amount}
 * for the PAYMENT/CREDIT_NOTE branch, which is correct for POSITIVE rows but computes {@code -(-X)=+X} for
 * NEGATIVE rows → payments get ADDED to the balance instead of subtracted → dashboard inflated ~44%
 * (F006: 应收 ¥276,786 shown vs ¥192,581 true).
 *
 * <p>Fix: read-side {@code -ABS(t.amount)} (subtract magnitude) so BOTH existing positive and negative
 * rows compute correctly WITHOUT a data migration (self-healing). The invariant that makes {@code -ABS}
 * safe: PAYMENT/CREDIT_NOTE ALWAYS reduce the balance (customer pays / refund / red-reversal); the ONLY
 * legitimately-ADDITIVE channel is AR_ADJUSTMENT/AP_ADJUSTMENT, which keeps its signed {@code t.amount}.
 *
 * <p>H2 in PostgreSQL compat mode (application-test.properties). JPQL {@code ABS()} is a JPA-spec scalar
 * function; Hibernate emits SQL {@code abs()} which both H2 and PostgreSQL support.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.finance")
@DisplayName("ArApTransactionRepository — payment/credit-note sign-agnostic read (44% inflation fix)")
class ArApTransactionRepositoryPaymentSignTest {

    private static final String F1 = "F-ARSIGN-1";
    private static final String CUST = "CUST-1";
    private static final String SUPP = "SUPP-1";
    private static final String SO = "SO-1";

    @Autowired private ArApTransactionRepository repo;

    // ---------- AR: payment reduces regardless of stored sign ----------

    @Test
    @DisplayName("sumReceivables: NEGATIVE-stored AR_PAYMENT reduces 应收 (not adds) — the actual bug")
    void negativeStoredPayment_reducesReceivable() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        // recordArPayment (live 收款 REST path) stores NEGATIVE.
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("-300.00"), ArApApprovalStatus.APPROVED));
        // Correct: 1000 - 300 = 700. Old buggy code gave -(-300) = +300 → 1300.
        assertEquals(0, new BigDecimal("700.00").compareTo(repo.sumReceivables(F1)),
                "negative-stored payment must SUBTRACT its magnitude");
    }

    @Test
    @DisplayName("sumReceivables: POSITIVE-stored AR_PAYMENT also reduces 应收 (same result)")
    void positiveStoredPayment_reducesReceivable() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("300.00"), ArApApprovalStatus.APPROVED));
        assertEquals(0, new BigDecimal("700.00").compareTo(repo.sumReceivables(F1)),
                "positive-stored payment must ALSO subtract its magnitude (sign-agnostic)");
    }

    @Test
    @DisplayName("sumReceivables: MIXED pos+neg AR_PAYMENT + AR_CREDIT_NOTE → 应收 == invoices − Σ|reductions|")
    void mixedSignPaymentsAndCreditNotes_receivable() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("-200.00"), ArApApprovalStatus.APPROVED)); // recordArPayment
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("150.00"), ArApApprovalStatus.APPROVED));  // hypothetical positive
        repo.saveAndFlush(ar(ArApTransactionType.AR_CREDIT_NOTE, new BigDecimal("100.00"), ArApApprovalStatus.APPROVED)); // markPaidSales (positive)
        // 1000 - 200 - 150 - 100 = 550
        assertEquals(0, new BigDecimal("550.00").compareTo(repo.sumReceivables(F1)),
                "mixed-sign reductions all subtract magnitude");
    }

    // ---------- AP: same story ----------

    @Test
    @DisplayName("sumPayables: mixed-sign AP_PAYMENT (recordApPayment neg + markPaidPurchase pos) + AP_CREDIT_NOTE all reduce")
    void mixedSignPaymentsAndCreditNotes_payable() {
        repo.saveAndFlush(ap(ArApTransactionType.AP_INVOICE, new BigDecimal("800.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ap(ArApTransactionType.AP_PAYMENT, new BigDecimal("-150.00"), ArApApprovalStatus.APPROVED)); // recordApPayment
        repo.saveAndFlush(ap(ArApTransactionType.AP_PAYMENT, new BigDecimal("250.00"), ArApApprovalStatus.APPROVED));  // markPaidPurchase
        repo.saveAndFlush(ap(ArApTransactionType.AP_CREDIT_NOTE, new BigDecimal("-50.00"), ArApApprovalStatus.APPROVED)); // reverseOpeningPayable (neg)
        // 800 - 150 - 250 - 50 = 350
        assertEquals(0, new BigDecimal("350.00").compareTo(repo.sumPayables(F1)),
                "AP mixed-sign reductions all subtract magnitude");
    }

    // ---------- F006-like real scenario ----------

    @Test
    @DisplayName("F006 scenario: invoices 234,684 − payments 42,102.60 = 192,581.40 (NOT the inflated 276,786.60)")
    void f006Scenario_notInflated() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("234684.00"), ArApApprovalStatus.APPROVED));
        // 8 NEGATIVE-stored AR_PAYMENT rows (live recordArPayment path) summing -42,102.60.
        BigDecimal[] pays = {
                new BigDecimal("-5000.00"), new BigDecimal("-6000.00"), new BigDecimal("-4102.60"),
                new BigDecimal("-3000.00"), new BigDecimal("-8000.00"), new BigDecimal("-7000.00"),
                new BigDecimal("-5000.00"), new BigDecimal("-4000.00")
        };
        for (BigDecimal p : pays) {
            repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, p, ArApApprovalStatus.APPROVED));
        }
        BigDecimal result = repo.sumReceivables(F1);
        assertEquals(0, new BigDecimal("192581.40").compareTo(result),
                "應收 must be invoices − Σ|payments| = 192,581.40");
        assertNotEquals(0, new BigDecimal("276786.60").compareTo(result),
                "must NOT be the inflated 276,786.60 (old -(-X)=+X bug)");
    }

    // ---------- #1255 approval filter still composes ----------

    @Test
    @DisplayName("#1255 compose: PENDING/REJECTED adjustment still excluded WHILE payments (any sign) subtract")
    void adjustmentApprovalFilterComposesWithSignFix() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("-200.00"), ArApApprovalStatus.APPROVED));   // reduces → 800
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-450.00"), ArApApprovalStatus.PENDING)); // excluded
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("9999.00"), ArApApprovalStatus.REJECTED));// excluded
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-100.00"), ArApApprovalStatus.APPROVED));// counted (signed)
        // 1000 - 200 (payment) - 100 (approved adj) = 700; PENDING/REJECTED excluded
        assertEquals(0, new BigDecimal("700.00").compareTo(repo.sumReceivables(F1)),
                "sign fix + approval filter compose: only APPROVED adj + payment magnitude");
    }

    // ---------- per-counterparty sweep is sign-agnostic ----------

    @Test
    @DisplayName("sumByCounterparty: mixed-sign payments subtract magnitude; APPROVED adjustment stays signed")
    void sumByCounterparty_signAgnostic() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("-200.00"), ArApApprovalStatus.APPROVED)); // neg
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("50.00"), ArApApprovalStatus.APPROVED));   // pos
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-100.00"), ArApApprovalStatus.APPROVED));
        List<Object[]> rows = repo.sumByCounterparty(F1, CounterpartyType.CUSTOMER);
        assertEquals(1, rows.size(), "one customer");
        // 1000 - 200 - 50 - 100 = 650
        assertEquals(0, new BigDecimal("650.00").compareTo((BigDecimal) rows.get(0)[2]),
                "per-customer sum subtracts payment magnitude regardless of stored sign");
    }

    // ---------- SO paid-amount is sign-agnostic ----------

    @Test
    @DisplayName("sumArPaymentsBySalesOrderId: mixed-sign AR_PAYMENT rows → positive 累计收款 magnitude")
    void sumArPaymentsBySalesOrderId_signAgnostic() {
        repo.saveAndFlush(arSo(new BigDecimal("-300.00"), ArApApprovalStatus.APPROVED)); // recordArPayment neg
        repo.saveAndFlush(arSo(new BigDecimal("200.00"), ArApApprovalStatus.APPROVED));  // hypothetical pos
        BigDecimal paid = repo.sumArPaymentsBySalesOrderId(F1, SO);
        // |−300| + |200| = 500
        assertEquals(0, new BigDecimal("500.00").compareTo(paid),
                "SO paid-amount is positive magnitude regardless of stored sign");
    }

    // ---------- helpers ----------

    private ArApTransaction ar(ArApTransactionType type, BigDecimal amount, ArApApprovalStatus status) {
        return tx(type, CounterpartyType.CUSTOMER, CUST, amount, status, null);
    }

    private ArApTransaction arSo(BigDecimal amount, ArApApprovalStatus status) {
        return tx(ArApTransactionType.AR_PAYMENT, CounterpartyType.CUSTOMER, CUST, amount, status, SO);
    }

    private ArApTransaction ap(ArApTransactionType type, BigDecimal amount, ArApApprovalStatus status) {
        return tx(type, CounterpartyType.SUPPLIER, SUPP, amount, status, null);
    }

    private ArApTransaction tx(ArApTransactionType type, CounterpartyType cpType, String cpId,
                               BigDecimal amount, ArApApprovalStatus status, String salesOrderId) {
        ArApTransaction t = new ArApTransaction();
        t.setFactoryId(F1);
        t.setTransactionNumber("T-" + System.nanoTime());
        t.setTransactionType(type);
        t.setCounterpartyType(cpType);
        t.setCounterpartyId(cpId);
        t.setCounterpartyName(cpId);
        t.setAmount(amount);
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setTransactionDate(LocalDate.now());
        t.setApprovalStatus(status);
        if (salesOrderId != null) {
            t.setSalesOrderId(salesOrderId);
        }
        return t;
    }
}

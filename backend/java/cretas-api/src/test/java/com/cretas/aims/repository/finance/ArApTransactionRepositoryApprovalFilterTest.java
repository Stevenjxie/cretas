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
 * 🔒 4-eyes finance-dashboard fix (2026-07) — JPA slice for {@link ArApTransactionRepository}
 * {@code sumReceivables}/{@code sumPayables}/{@code sumByCounterparty} approval-status filtering.
 *
 * <p>Bug: the dashboard 应收/应付 KPI summed ALL adjustment rows regardless of approval_status,
 * so a PENDING (un-approved) adjustment corrupted the headline number immediately, and a REJECTED
 * adjustment (whose amount is never zeroed) corrupted it permanently — both defeating the dual-control
 * (recordAdjustment does NOT mutate currentBalance until a 2nd user calls approveAdjustment).
 *
 * <p>Oracle: dashboard 应收/应付 must EQUAL the gated ledger — only APPROVED adjustments reflected;
 * PENDING/REJECTED adjustments must NOT affect it; invoice/payment always count.
 *
 * <p>H2 in PostgreSQL compat mode (application-test.properties).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.finance")
@DisplayName("ArApTransactionRepository — sum approval-status filter (4-eyes)")
class ArApTransactionRepositoryApprovalFilterTest {

    private static final String F1 = "F-ARAP-1";
    private static final String CUST = "CUST-1";
    private static final String SUPP = "SUPP-1";

    @Autowired private ArApTransactionRepository repo;

    // ---------- AR (应收) ----------

    @Test
    @DisplayName("sumReceivables: PENDING adjustment NOT counted; APPROVED counted; REJECTED never counted; invoice/payment still participate")
    void sumReceivables_gatesAdjustmentsByApprovalStatus() {
        // Invoice +1000 (auto-posted, APPROVED). Baseline = 1000 (no adjustments yet).
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        assertEquals(0, new BigDecimal("1000.00").compareTo(repo.sumReceivables(F1)),
                "invoice-only baseline");

        // PENDING adjustment -450 must NOT change the dashboard (gated ledger unchanged) — the bug.
        ArApTransaction pending = repo.saveAndFlush(
                ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-450.00"), ArApApprovalStatus.PENDING));
        assertEquals(0, new BigDecimal("1000.00").compareTo(repo.sumReceivables(F1)),
                "PENDING adjustment must NOT be counted");

        // Approve it -> now counted: 1000 - 450 = 550
        pending.setApprovalStatus(ArApApprovalStatus.APPROVED);
        repo.saveAndFlush(pending);
        assertEquals(0, new BigDecimal("550.00").compareTo(repo.sumReceivables(F1)),
                "APPROVED adjustment must be counted");

        // A separate REJECTED adjustment (amount left un-zeroed) must NEVER corrupt the total.
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("9999.00"), ArApApprovalStatus.REJECTED));
        assertEquals(0, new BigDecimal("550.00").compareTo(repo.sumReceivables(F1)),
                "REJECTED adjustment must never be counted");

        // AR_PAYMENT is NOT gated by approval status — it must still participate in the sum.
        // (Exact direction depends on the separately-flagged payment-sign convention; here we
        //  only assert the payment is included, i.e. the approval filter does not drop it.)
        BigDecimal before = repo.sumReceivables(F1);
        repo.saveAndFlush(ar(ArApTransactionType.AR_PAYMENT, new BigDecimal("300.00"), ArApApprovalStatus.APPROVED));
        assertNotEquals(0, before.compareTo(repo.sumReceivables(F1)),
                "AR_PAYMENT must still be counted (not blocked by the adjustment filter)");
    }

    // ---------- AP (应付) ----------

    @Test
    @DisplayName("sumPayables: PENDING/REJECTED adjustments excluded; APPROVED included; invoice/payment still participate")
    void sumPayables_gatesAdjustmentsByApprovalStatus() {
        // Invoice +500 (auto-posted, APPROVED). Baseline = 500.
        repo.saveAndFlush(ap(ArApTransactionType.AP_INVOICE, new BigDecimal("500.00"), ArApApprovalStatus.APPROVED));
        assertEquals(0, new BigDecimal("500.00").compareTo(repo.sumPayables(F1)),
                "invoice-only baseline");

        repo.saveAndFlush(ap(ArApTransactionType.AP_ADJUSTMENT, new BigDecimal("-100.00"), ArApApprovalStatus.PENDING));
        assertEquals(0, new BigDecimal("500.00").compareTo(repo.sumPayables(F1)),
                "PENDING AP adjustment must NOT be counted");

        repo.saveAndFlush(ap(ArApTransactionType.AP_ADJUSTMENT, new BigDecimal("7777.00"), ArApApprovalStatus.REJECTED));
        assertEquals(0, new BigDecimal("500.00").compareTo(repo.sumPayables(F1)),
                "REJECTED AP adjustment must never be counted");

        repo.saveAndFlush(ap(ArApTransactionType.AP_ADJUSTMENT, new BigDecimal("50.00"), ArApApprovalStatus.APPROVED));
        assertEquals(0, new BigDecimal("550.00").compareTo(repo.sumPayables(F1)),
                "APPROVED AP adjustment must be counted");

        // AP_PAYMENT is NOT gated — must still participate in the sum.
        BigDecimal before = repo.sumPayables(F1);
        repo.saveAndFlush(ap(ArApTransactionType.AP_PAYMENT, new BigDecimal("200.00"), ArApApprovalStatus.APPROVED));
        assertNotEquals(0, before.compareTo(repo.sumPayables(F1)),
                "AP_PAYMENT must still be counted (not blocked by the adjustment filter)");
    }

    // ---------- per-counterparty sweep ----------

    @Test
    @DisplayName("sumByCounterparty: excludes PENDING/REJECTED adjustments, keeps invoice/payment + APPROVED adjustment")
    void sumByCounterparty_excludesUnapprovedAdjustments() {
        repo.saveAndFlush(ar(ArApTransactionType.AR_INVOICE, new BigDecimal("1000.00"), ArApApprovalStatus.APPROVED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-450.00"), ArApApprovalStatus.PENDING));
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("8888.00"), ArApApprovalStatus.REJECTED));
        repo.saveAndFlush(ar(ArApTransactionType.AR_ADJUSTMENT, new BigDecimal("-100.00"), ArApApprovalStatus.APPROVED));

        List<Object[]> rows = repo.sumByCounterparty(F1, CounterpartyType.CUSTOMER);
        assertEquals(1, rows.size(), "one customer");
        // 1000 (invoice) - 100 (approved adj) = 900; PENDING -450 + REJECTED 8888 excluded
        assertEquals(0, new BigDecimal("900.00").compareTo((BigDecimal) rows.get(0)[2]),
                "per-customer sum excludes PENDING/REJECTED adjustments");
    }

    // ---------- helpers ----------

    private ArApTransaction ar(ArApTransactionType type, BigDecimal amount, ArApApprovalStatus status) {
        return tx(type, CounterpartyType.CUSTOMER, CUST, amount, status);
    }

    private ArApTransaction ap(ArApTransactionType type, BigDecimal amount, ArApApprovalStatus status) {
        return tx(type, CounterpartyType.SUPPLIER, SUPP, amount, status);
    }

    private ArApTransaction tx(ArApTransactionType type, CounterpartyType cpType, String cpId,
                               BigDecimal amount, ArApApprovalStatus status) {
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
        return t;
    }
}

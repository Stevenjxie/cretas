package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface ArApTransactionRepository extends JpaRepository<ArApTransaction, String> {

    /** Serialize all settlements of one AP invoice and keep factory isolation in the lock query. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ArApTransaction t WHERE t.id = :transactionId " +
            "AND t.factoryId = :factoryId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE " +
            "AND t.deletedAt IS NULL")
    Optional<ArApTransaction> findPayableForSettlement(
            @Param("factoryId") String factoryId,
            @Param("transactionId") String transactionId);

    Page<ArApTransaction> findByFactoryIdOrderByTransactionDateDesc(String factoryId, Pageable pageable);

    Page<ArApTransaction> findByFactoryIdAndCounterpartyTypeOrderByTransactionDateDesc(
            String factoryId, CounterpartyType counterpartyType, Pageable pageable);

    Page<ArApTransaction> findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
            String factoryId, CounterpartyType counterpartyType, String counterpartyId, Pageable pageable);

    /**
     * R28 P2 (R23 P5): list PENDING adjustments for the approval queue.
     * AR_ADJUSTMENT + AP_ADJUSTMENT only; approval_status='PENDING'; not soft-deleted.
     * Sorted newest first (created_at DESC) so urgent items surface to top.
     */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING " +
            "AND (t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "OR t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "AND t.deletedAt IS NULL " +
            "ORDER BY t.createdAt DESC")
    Page<ArApTransaction> findPendingAdjustments(@Param("factoryId") String factoryId, Pageable pageable);

    /**
     * R29 P1 filtered: PENDING adjustments by counterpartyType.
     * (Amount + date range filters applied in service layer to avoid PG
     *  parameter-type-inference issue with `:p IS NULL` on dynamically-null params.)
     */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING " +
            "AND (t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "OR t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "AND t.deletedAt IS NULL " +
            "AND t.counterpartyType = :counterpartyType " +
            "ORDER BY t.createdAt DESC")
    Page<ArApTransaction> findPendingAdjustmentsByType(
            @Param("factoryId") String factoryId,
            @Param("counterpartyType") com.cretas.aims.entity.enums.CounterpartyType counterpartyType,
            Pageable pageable);

    /** 查找某个交易对手的所有交易（用于对账单） */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :type AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionDate BETWEEN :startDate AND :endDate " +
            "ORDER BY t.transactionDate ASC, t.createdAt ASC")
    List<ArApTransaction> findByCounterpartyAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type,
            @Param("counterpartyId") String counterpartyId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 逾期应收（AR_INVOICE，dueDate已过，未完全冲销） */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.transactionType = 'AR_INVOICE' " +
            "AND t.dueDate IS NOT NULL AND t.dueDate < :today " +
            "ORDER BY t.dueDate ASC")
    List<ArApTransaction> findOverdueReceivables(
            @Param("factoryId") String factoryId,
            @Param("today") LocalDate today);

    /** 逾期应付 */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.transactionType = 'AP_INVOICE' " +
            "AND t.outstandingAmount IS NOT NULL AND t.outstandingAmount > 0 " +
            "AND t.dueDate IS NOT NULL AND t.dueDate < :today " +
            "ORDER BY t.dueDate ASC")
    List<ArApTransaction> findOverduePayables(
            @Param("factoryId") String factoryId,
            @Param("today") LocalDate today);

    /**
     * 应收余额 = 挂账增加 - 收款减少 - 退货冲减.
     * R42 BUG-13 fix: 之前 SUM(amount) 把 AR_INVOICE + AR_PAYMENT 全加, 导致 ¥1,457K + ¥620K = ¥2,077K
     * (错误地翻倍). 正确应是 1,457K - 620K = 837K.
     *
     * <p>🔒 4-eyes fix (2026-07): AR_ADJUSTMENT 只在 approval_status='APPROVED' 时计入。此前无审批状态
     * 过滤 → PENDING (未审批) 调整立即污染看板总额, 且 REJECTED (被驳回但金额未清零) 调整永久污染,
     * 双双绕过 dual-control 闸 (recordAdjustment 故意不改 currentBalance, 直到 approveAdjustment 才改)。
     * 非调整类型 (AR_INVOICE / AR_PAYMENT / AR_CREDIT_NOTE) 自动过账, 默认即 APPROVED, 恒计入 (不受此闸阻挡)。
     * 修后 sumReceivables 与 gated ledger (Σ Customer.currentBalance) 口径一致。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): AR_PAYMENT / AR_CREDIT_NOTE 用 {@code -ABS(t.amount)} (减去金额绝对值),
     * 而非 {@code -t.amount}。原因：写路径 SIGN 不一致 —— ArApServiceImpl.recordArPayment 存 NEGATIVE
     * (amount.negate()), 而 PaymentRequestServiceImpl.markPaidSales 的 AR_CREDIT_NOTE 存 POSITIVE。
     * 旧 {@code -t.amount} 对 POSITIVE 行正确 (减), 但对 NEGATIVE 行算成 -(-X)=+X → 把收款 ADD 进应收 →
     * 看板虚高 (F006 实测虚高 44%: 应收 ¥276,786 应为 ¥192,581)。{@code -ABS} 对两种存储 SIGN 都正确减去,
     * 无需数据迁移即自愈现有 mixed-sign 数据。invariant: PAYMENT/CREDIT_NOTE 语义上恒 REDUCE 应收 (客户付款/
     * 退款返利冲减), 唯一合法的 ADDITIVE 通道是 AR_ADJUSTMENT (保留 signed {@code t.amount})。写路径 SIGN
     * 归一化是更大的 🔒🔒 follow-up (23 条 prod 负号行 + 多消费者), 不在本 PR。
     */
    @Query("SELECT COALESCE(SUM(CASE " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_INVOICE THEN t.amount " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "       AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED THEN t.amount " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE 0 END), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = 'CUSTOMER'")
    BigDecimal sumReceivables(@Param("factoryId") String factoryId);

    /**
     * 应付余额 = 挂账增加 - 付款减少 - 退货冲减.
     * R42 BUG-13 fix: 同 sumReceivables, 之前 SUM 全加 ¥247K + ¥197K = ¥444K (错). 正确是 50K.
     *
     * <p>🔒 4-eyes fix (2026-07): AP_ADJUSTMENT 同 sumReceivables —— 只在 APPROVED 时计入, PENDING/REJECTED
     * 不污染看板。非调整类型恒计入。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): AP_PAYMENT / AP_CREDIT_NOTE 用 {@code -ABS(t.amount)} 同 sumReceivables。
     * 写路径 SIGN 不一致 —— ArApServiceImpl.recordApPayment / reverseOpeningPayable 存 NEGATIVE (negate()),
     * PaymentRequestServiceImpl.markPaidPurchase 的 AP_PAYMENT 存 POSITIVE。{@code -ABS} 对两种 SIGN 都正确减去,
     * 自愈现有 mixed-sign 数据, 无需迁移。写路径 SIGN 归一化留作 🔒🔒 follow-up。
     */
    @Query("SELECT COALESCE(SUM(CASE " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE THEN t.amount " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT " +
            "       AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED THEN t.amount " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE 0 END), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = 'SUPPLIER'")
    BigDecimal sumPayables(@Param("factoryId") String factoryId);

    /**
     * 按交易对手汇总余额。
     *
     * <p>🔒 4-eyes fix (2026-07): 与 sumReceivables/sumPayables 同类 —— 排除 PENDING/REJECTED 调整,
     * 避免 per-customer 应收/应付明细把未审批/被驳回的调整算进去。非调整类型全部计入 (approval_status
     * 默认 APPROVED, 此 predicate 对它们恒真)。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): 原 {@code SUM(t.amount)} 直接裸加, 依赖写路径 SIGN 约定
     * (INVOICE 正 / PAYMENT 负)。写路径 SIGN 不一致时 (见 sumReceivables 注释), POSITIVE 存储的付款/冲减
     * 会被 ADD 进 per-counterparty 应收/应付明细 → 虚高。改用与 sumReceivables/sumPayables 一致的 sign-agnostic
     * CASE: PAYMENT/CREDIT_NOTE 恒 {@code -ABS(t.amount)} (减去金额), INVOICE 与已过审 ADJUSTMENT 保留
     * signed {@code t.amount} (ADJUSTMENT 是唯一合法 ADDITIVE 通道)。ORDER BY 同步用 CASE 保持排序正确。
     */
    @Query("SELECT t.counterpartyId, t.counterpartyName, SUM(CASE " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE t.amount END) " +
            "FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = :type " +
            "AND (t.transactionType NOT IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT, " +
            "                                com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "     OR t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED) " +
            "GROUP BY t.counterpartyId, t.counterpartyName " +
            "ORDER BY SUM(CASE " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE t.amount END) DESC")
    List<Object[]> sumByCounterparty(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type);

    /** 查找期初余额快照（startDate之前最后一笔交易的balanceAfter） */
    @Query("SELECT t.balanceAfter FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :type AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionDate < :startDate " +
            "ORDER BY t.transactionDate DESC, t.createdAt DESC")
    List<BigDecimal> findOpeningBalance(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type,
            @Param("counterpartyId") String counterpartyId,
            @Param("startDate") LocalDate startDate);

    /** 检查销售订单是否已挂账 */
    boolean existsByFactoryIdAndSalesOrderIdAndTransactionType(
            String factoryId, String salesOrderId, ArApTransactionType transactionType);

    /** 检查采购订单是否已挂账 */
    boolean existsByFactoryIdAndPurchaseOrderIdAndTransactionType(
            String factoryId, String purchaseOrderId, ArApTransactionType transactionType);

    /**
     * Idempotent lookup for auto-posted PO payables (采购入库自动挂账 across partial receives).
     * Mirrors the predicate of {@link #existsByFactoryIdAndPurchaseOrderIdAndTransactionType}
     * but returns the existing row so callers can skip WITHOUT throwing inside a shared tx.
     */
    Optional<ArApTransaction> findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
            String factoryId, String purchaseOrderId, ArApTransactionType transactionType);

    /** Source-based idempotency for non-SO/PO AP invoices. */
    Optional<ArApTransaction> findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
            String factoryId, String sourceType, String sourceId, ArApTransactionType transactionType);

    /** Source-based AP invoice queries for supplier delivery monthly reconciliation. */
    List<ArApTransaction> findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
            String factoryId, String sourceType, List<String> sourceIds, ArApTransactionType transactionType);

    /**
     * #3(a) 退货资金链: 按来源单据 (sourceType/sourceId) + 审批状态查调整。用于父单据 (退货单)
     * 被驳回时级联撤销其挂起的 AR/AP_ADJUSTMENT。返回该来源下所有匹配状态的调整行 (通常 0 或 1)。
     */
    List<ArApTransaction> findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
            String factoryId, String sourceType, String sourceId,
            com.cretas.aims.entity.enums.ArApApprovalStatus approvalStatus);

    List<ArApTransaction> findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
            String factoryId, CounterpartyType counterpartyType, String counterpartyId,
            ArApTransactionType transactionType, LocalDate startDate, LocalDate endDate);

    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :counterpartyType AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionType IN :transactionTypes " +
            "AND t.transactionDate BETWEEN :startDate AND :endDate " +
            "AND t.deletedAt IS NULL " +
            "ORDER BY t.transactionDate ASC, t.createdAt ASC")
    List<ArApTransaction> findByCounterpartyAndTypesAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("counterpartyType") CounterpartyType counterpartyType,
            @Param("counterpartyId") String counterpartyId,
            @Param("transactionTypes") List<ArApTransactionType> transactionTypes,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 收款幂等检查：同一 paymentReference 不能重复 */
    boolean existsByFactoryIdAndPaymentReference(String factoryId, String paymentReference);

    /** Legacy/unallocated payment detection for a specific PO and supplier. */
    List<ArApTransaction> findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
            String factoryId,
            String purchaseOrderId,
            String counterpartyId,
            ArApTransactionType transactionType);

    /** Historical AP_PAYMENT rows without an explicit payable allocation are read-only anomalies. */
    @Query("SELECT p FROM ArApTransaction p WHERE p.factoryId = :factoryId " +
            "AND p.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT " +
            "AND p.deletedAt IS NULL " +
            "AND NOT EXISTS (SELECT a.id FROM com.cretas.aims.entity.finance.ArApPaymentAllocation a " +
            "WHERE a.factoryId = p.factoryId AND a.paymentTransactionId = p.id AND a.deletedAt IS NULL) " +
            "ORDER BY p.transactionDate DESC, p.createdAt DESC")
    List<ArApTransaction> findUnallocatedApPayments(@Param("factoryId") String factoryId);

    /**
     * Issue #739: AR_PAYMENT idempotency for SO-bound 快速收款 (no paymentReference).
     * Find recent (last N minutes) AR_PAYMENT for same SO with same |amount|. Triggered when
     * user double-clicks "收款" — without this, second click silently creates duplicate 收款记录.
     *
     * NOTE: amount stored NEGATIVE on AR_PAYMENT (冲减), so caller passes negated amount.
     */
    @Query("SELECT t FROM ArApTransaction t "
            + "WHERE t.factoryId = :factoryId AND t.salesOrderId = :salesOrderId "
            + "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT "
            + "AND t.amount = :amount "
            + "AND t.createdAt > :since "
            + "AND t.deletedAt IS NULL "
            + "ORDER BY t.createdAt DESC")
    List<ArApTransaction> findRecentArPaymentsForSO(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId,
            @Param("amount") BigDecimal amount,
            @Param("since") java.time.LocalDateTime since);

    /**
     * Issue #317 fix: sum AR_PAYMENT amounts for a SO so SO.paidAmount/payment_status
     * can be derived. amounts on AR_PAYMENT rows are stored NEGATIVE (冲减应收) by
     * ArApServiceImpl.recordArPayment.
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): 改 {@code SUM(ABS(t.amount))} 而非 {@code SUM(-t.amount)}。
     * 若任一 AR_PAYMENT 行以 POSITIVE 存储 (mixed-sign prod 数据), 旧 {@code -t.amount} 会算成负数 →
     * SO.paidAmount 变负 → payment_status 误判。AR_PAYMENT 语义上恒是收款 (磁盘上 SIGN 不论),
     * {@code ABS} 取金额绝对值累计得正确的 累计收款, 对两种存储 SIGN 都正确。
     */
    @Query("SELECT COALESCE(SUM(ABS(t.amount)), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.salesOrderId = :salesOrderId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT " +
            "AND t.deletedAt IS NULL")
    BigDecimal sumArPaymentsBySalesOrderId(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId);

    /** 按交易类型统计 */
    @Query("SELECT t.transactionType, COUNT(t), COALESCE(SUM(t.amount), 0) " +
            "FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "GROUP BY t.transactionType")
    List<Object[]> statisticsByType(@Param("factoryId") String factoryId);
}

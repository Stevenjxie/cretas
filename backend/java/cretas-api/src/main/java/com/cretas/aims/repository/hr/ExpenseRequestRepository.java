package com.cretas.aims.repository.hr;

import com.cretas.aims.entity.hr.ExpenseRequest;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseRequestRepository extends JpaRepository<ExpenseRequest, String> {

    Optional<ExpenseRequest> findByIdAndFactoryId(String id, String factoryId);

    Page<ExpenseRequest> findByFactoryIdAndUserId(String factoryId, Long userId, Pageable pageable);

    Page<ExpenseRequest> findByFactoryIdAndUserIdAndStatus(
            String factoryId, Long userId, HrRequestStatus status, Pageable pageable);

    Page<ExpenseRequest> findByFactoryIdAndStatus(
            String factoryId, HrRequestStatus status, Pageable pageable);

    Page<ExpenseRequest> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 「全部」列表页 类目/状态 筛选 (2026-07-14, web-admin filter audit) — 两个参数均可空。
     * CAST(:param AS string) IS NULL 类型 hint (PostgreSQL 严格类型推断, 见 database-entity-sync.md)。
     */
    @Query("SELECT r FROM ExpenseRequest r WHERE r.factoryId = :factoryId " +
           "AND (CAST(:category AS string) IS NULL OR r.category = :category) " +
           "AND (CAST(:status AS string) IS NULL OR r.status = :status)")
    Page<ExpenseRequest> findByFiltersAll(@Param("factoryId") String factoryId,
                                          @Param("category") ExpenseCategory category,
                                          @Param("status") HrRequestStatus status,
                                          Pageable pageable);

    Optional<ExpenseRequest> findByFactoryIdAndPaymentRecordId(
            String factoryId, String paymentRecordId);

    /**
     * R4 防呆: 5min 窗口内同 user/category/amount/date 已有 DRAFT/SUBMITTED 报销
     * (防 double-click 重复创建).
     */
    @Query("""
            SELECT r FROM ExpenseRequest r
            WHERE r.factoryId = :factoryId
              AND r.userId = :userId
              AND r.category = :category
              AND r.amount = :amount
              AND r.expenseDate = :expenseDate
              AND r.status IN (
                com.cretas.aims.entity.hr.enums.HrRequestStatus.DRAFT,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.SUBMITTED,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED)
              AND r.createdAt >= :since
            """)
    List<ExpenseRequest> findRecentDuplicates(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("category") ExpenseCategory category,
            @Param("amount") BigDecimal amount,
            @Param("expenseDate") LocalDate expenseDate,
            @Param("since") java.time.LocalDateTime since);

    /** 月度类目汇总. */
    @Query("""
            SELECT r.category, SUM(r.amount)
            FROM ExpenseRequest r
            WHERE r.factoryId = :factoryId
              AND r.status IN (
                com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.PAID)
              AND r.expenseDate >= :rangeStart
              AND r.expenseDate < :rangeEnd
            GROUP BY r.category
            """)
    List<Object[]> sumApprovedAmountByCategoryInRange(
            @Param("factoryId") String factoryId,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd);

    /** 用户已批准报销总额 (月度). */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
            FROM ExpenseRequest r
            WHERE r.factoryId = :factoryId
              AND r.userId = :userId
              AND r.status IN (
                com.cretas.aims.entity.hr.enums.HrRequestStatus.APPROVED,
                com.cretas.aims.entity.hr.enums.HrRequestStatus.PAID)
              AND r.expenseDate >= :rangeStart
              AND r.expenseDate < :rangeEnd
            """)
    BigDecimal sumUserApprovedAmountInRange(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("rangeStart") LocalDate rangeStart,
            @Param("rangeEnd") LocalDate rangeEnd);
}

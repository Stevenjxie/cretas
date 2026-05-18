package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrder, String> {

    Page<SalesOrder> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<SalesOrder> findByFactoryIdAndStatusOrderByCreatedAtDesc(String factoryId, SalesOrderStatus status, Pageable pageable);

    /**
     * R23 audit I6: JPQL push-down replacement for ProductionPlanController.findAll().stream().filter(...)
     * which loaded ALL factories' SOs into JVM heap before filtering — full table scan + multi-tenant
     * data leak risk. Now WHERE-clause filtered at DB level.
     */
    List<SalesOrder> findByFactoryIdAndStatusInOrderByCreatedAtDesc(
            String factoryId, Collection<SalesOrderStatus> statuses, Pageable pageable);

    Optional<SalesOrder> findByFactoryIdAndOrderNumber(String factoryId, String orderNumber);

    List<SalesOrder> findByFactoryIdAndCustomerId(String factoryId, String customerId);

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 7): paginated by customer, newest first. */
    Page<SalesOrder> findByFactoryIdAndCustomerIdOrderByCreatedAtDesc(
            String factoryId, String customerId, Pageable pageable);

    @Query("SELECT so FROM SalesOrder so WHERE so.factoryId = :factoryId " +
            "AND so.orderDate BETWEEN :startDate AND :endDate ORDER BY so.orderDate DESC")
    List<SalesOrder> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    Page<SalesOrder> findByFactoryIdInOrderByCreatedAtDesc(List<String> factoryIds, Pageable pageable);

    @Query("SELECT COUNT(so) FROM SalesOrder so WHERE so.factoryId = :factoryId AND so.orderDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    /**
     * Find max orderNumber by prefix (e.g. 'SO-20260411-') so order number generation
     * is based on actual DB state, not by business orderDate which may be past/future.
     * Mirrors PurchaseOrderRepository pattern to avoid duplicate-key collisions.
     */
    @Query("SELECT MAX(so.orderNumber) FROM SalesOrder so WHERE so.factoryId = :factoryId AND so.orderNumber LIKE :prefix")
    String findMaxOrderNumberByPrefix(@Param("factoryId") String factoryId, @Param("prefix") String prefix);

    /**
     * Bug G fix: keyword search (qa-prompt v2.3 Rule 12.1).
     * Searches orderNumber + salesperson + remark (display fields visible in list).
     * customerName is @Formula and not directly queryable, so we JOIN customer entity.
     * Audit H1: ESCAPE '\' so user-typed % and _ are literal (service layer pre-escapes).
     */
    @Query("SELECT so FROM SalesOrder so LEFT JOIN Customer c ON c.id = so.customerId " +
            "WHERE so.factoryId = :factoryId AND (" +
            "LOWER(so.orderNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
            "LOWER(COALESCE(so.salesperson, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
            "LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
            "LOWER(COALESCE(so.remark, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\'" +
            ") ORDER BY so.createdAt DESC")
    Page<SalesOrder> searchByFactoryAndKeyword(@Param("factoryId") String factoryId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * Sprint 4 W2 S-REMIND-1 — Reminder scanner 用. 查找未结清且 requiredDeliveryDate
     * 在 [today - 365d, today + thresholdDays] 区间的订单. 用 paidAmount &lt; totalAmount
     * (NULL paidAmount 视为 0) 判定未结清, 排除草稿 / 已取消状态.
     */
    @Query("SELECT so FROM SalesOrder so WHERE so.factoryId = :factoryId " +
            "AND so.status NOT IN (com.cretas.aims.entity.enums.SalesOrderStatus.DRAFT, " +
            "                       com.cretas.aims.entity.enums.SalesOrderStatus.CANCELLED) " +
            "AND so.requiredDeliveryDate IS NOT NULL " +
            "AND so.requiredDeliveryDate BETWEEN :startDate AND :endDate " +
            "AND so.totalAmount IS NOT NULL " +
            "AND COALESCE(so.paidAmount, 0) < so.totalAmount " +
            "AND so.salespersonId IS NOT NULL")
    List<SalesOrder> findUnpaidForReminderScan(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Scanner: 列出所有工厂 (distinct), 供 per-factory 扫描. */
    @Query("SELECT DISTINCT so.factoryId FROM SalesOrder so WHERE so.status NOT IN " +
            "(com.cretas.aims.entity.enums.SalesOrderStatus.DRAFT, " +
            " com.cretas.aims.entity.enums.SalesOrderStatus.CANCELLED)")
    List<String> findDistinctFactoryIds();
}

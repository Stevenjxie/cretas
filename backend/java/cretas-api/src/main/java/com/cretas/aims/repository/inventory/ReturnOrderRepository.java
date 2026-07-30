package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, String> {

    Page<ReturnOrder> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndReturnTypeOrderByCreatedAtDesc(String factoryId, ReturnType returnType, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndStatusOrderByCreatedAtDesc(String factoryId, ReturnOrderStatus status, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndReturnTypeAndStatusOrderByCreatedAtDesc(
            String factoryId, ReturnType returnType, ReturnOrderStatus status, Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope) — composite filter with createdBy.
     * Used when {@link com.cretas.aims.security.DataScopeContext} scope is
     * SELF / DEPT_AND_BELOW / SELF_AND_BELOW. createdByList already resolved by caller
     * (single-element list for SELF; chain for DEPT/SELF_AND_BELOW).
     *
     * <p>:returnType / :status NULL → 不过滤. createdByList 非 null 必须非空.
     */
    @Query("SELECT r FROM ReturnOrder r WHERE r.factoryId = :factoryId " +
           "AND (CAST(:returnType AS string) IS NULL OR r.returnType = :returnType) " +
           "AND (CAST(:status AS string) IS NULL OR r.status = :status) " +
           "AND r.createdBy IN :createdByList " +
           "ORDER BY r.createdAt DESC")
    Page<ReturnOrder> findByFactoryIdAndFiltersAndCreatedByIn(
            @Param("factoryId") String factoryId,
            @Param("returnType") ReturnType returnType,
            @Param("status") ReturnOrderStatus status,
            @Param("createdByList") Collection<Long> createdByList,
            Pageable pageable);

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 17): paginated by customer counterparty + ReturnType filter,
     * newest first. ReturnOrder.counterpartyId is supplier_id for SUPPLIER_RETURN
     * and customer_id for SALES_RETURN — caller must pass ReturnType.SALES_RETURN.
     */
    Page<ReturnOrder> findByFactoryIdAndCounterpartyIdAndReturnTypeOrderByCreatedAtDesc(
            String factoryId, String counterpartyId, ReturnType returnType, Pageable pageable);

    @Query("SELECT COUNT(r) FROM ReturnOrder r WHERE r.factoryId = :factoryId AND r.returnDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    /**
     * 单据追踪 — 按源单反查退货单。
     *
     * <p>{@code sourceOrderId} 的指向由 {@code returnType} 决定 (见
     * {@code ReturnOrderServiceImpl}: SALES_RETURN → SalesOrder, PURCHASE_RETURN → PurchaseOrder),
     * 所以两个条件必须同时给, 只按 sourceOrderId 查会把两种源单的 ID 空间混在一起。
     * factoryId 前置保证租户隔离。
     */
    java.util.List<ReturnOrder> findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
            String factoryId, ReturnType returnType, String sourceOrderId);
}

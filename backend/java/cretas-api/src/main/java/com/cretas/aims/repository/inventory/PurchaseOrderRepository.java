package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {

    Page<PurchaseOrder> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope SELF) — 仅查当前用户创建的采购单.
     * Used by {@link com.cretas.aims.service.inventory.PurchaseService#getPurchaseOrders} when
     * {@link com.cretas.aims.security.DataScopeContext#current()} scope is SELF.
     */
    Page<PurchaseOrder> findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
            String factoryId, Long createdBy, Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope DEPT_AND_BELOW / SELF_AND_BELOW) — IN list 过滤.
     */
    Page<PurchaseOrder> findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
            String factoryId, List<Long> createdByList, Pageable pageable);

    Page<PurchaseOrder> findByFactoryIdAndStatusOrderByCreatedAtDesc(String factoryId, PurchaseOrderStatus status, Pageable pageable);

    /** W-12 fix: SO detail "关联采购" tab filter. */
    Page<PurchaseOrder> findByFactoryIdAndSalesOrderId(String factoryId, String salesOrderId, Pageable pageable);

    Optional<PurchaseOrder> findByFactoryIdAndOrderNumber(String factoryId, String orderNumber);

    /**
     * 按 ID + factoryId 查 PO（租户隔离安全查法）.
     *
     * <p>防止跨工厂 ID 猜测：先查 ID，再断言 factoryId 匹配。
     * 等价于 {@code findById} + {@code !factoryId.equals(po.getFactoryId())} 检查，
     * 但以单个 SQL 完成，消除 audit 扫描的 "findById without factoryId guard" 误报。
     */
    Optional<PurchaseOrder> findByIdAndFactoryId(String id, String factoryId);

    List<PurchaseOrder> findByFactoryIdAndSupplierId(String factoryId, String supplierId);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.factoryId = :factoryId " +
            "AND po.orderDate BETWEEN :startDate AND :endDate ORDER BY po.orderDate DESC")
    List<PurchaseOrder> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 多组织聚合查询（总部视图） */
    Page<PurchaseOrder> findByFactoryIdInOrderByCreatedAtDesc(List<String> factoryIds, Pageable pageable);

    /** 生成订单号：统计当天该工厂的采购单数量 */
    @Query("SELECT COUNT(po) FROM PurchaseOrder po WHERE po.factoryId = :factoryId AND po.orderDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    /** 生成订单号：查找当天最大订单号（避免并发冲突） */
    @Query("SELECT MAX(po.orderNumber) FROM PurchaseOrder po WHERE po.factoryId = :factoryId AND po.orderNumber LIKE :prefix")
    Optional<String> findMaxOrderNumberByPrefix(@Param("factoryId") String factoryId, @Param("prefix") String prefix);
}

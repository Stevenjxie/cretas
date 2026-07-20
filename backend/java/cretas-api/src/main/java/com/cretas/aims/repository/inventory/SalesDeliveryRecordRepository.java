package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface SalesDeliveryRecordRepository extends JpaRepository<SalesDeliveryRecord, String> {

    /**
     * Mutation lock for shipment state transitions. The factory predicate is part of the query so
     * a caller cannot lock, and subsequently mutate, another tenant's delivery row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM SalesDeliveryRecord d WHERE d.id = :id AND d.factoryId = :factoryId")
    Optional<SalesDeliveryRecord> findByIdAndFactoryIdForUpdate(
            @Param("id") String id,
            @Param("factoryId") String factoryId);

    Page<SalesDeliveryRecord> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<SalesDeliveryRecord> findBySalesOrderId(String salesOrderId);

    Optional<SalesDeliveryRecord> findByFactoryIdAndDeliveryNumber(String factoryId, String deliveryNumber);

    /**
     * Issue #739 idempotency: find existing in-progress (DRAFT / PENDING_WAREHOUSE_CONFIRM / PICKED)
     * delivery for a sales order. Used to prevent duplicate drafts when user double-clicks "快速出库".
     *
     * SHIPPED / DELIVERED / RETURNED are terminal — should NOT block new draft (e.g. multi-shipment SO).
     */
    @Query("SELECT d FROM SalesDeliveryRecord d "
            + "WHERE d.factoryId = :factoryId AND d.salesOrderId = :salesOrderId "
            + "AND d.status IN :statuses "
            + "ORDER BY d.createdAt DESC")
    List<SalesDeliveryRecord> findInProgressBySalesOrderId(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId,
            @Param("statuses") List<SalesDeliveryStatus> statuses);

    /**
     * #740 warehouse view: list deliveries awaiting warehouse confirmation
     * (DRAFT + PENDING_WAREHOUSE_CONFIRM + PICKED). Sales-created drafts surface here for
     * warehouse staff to confirm and ship.
     */
    @Query("SELECT d FROM SalesDeliveryRecord d "
            + "WHERE d.factoryId = :factoryId AND d.status IN :statuses "
            + "ORDER BY d.createdAt DESC")
    Page<SalesDeliveryRecord> findByFactoryIdAndStatusIn(
            @Param("factoryId") String factoryId,
            @Param("statuses") List<SalesDeliveryStatus> statuses,
            Pageable pageable);
}

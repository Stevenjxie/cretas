package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SalesOrderSuppliedMaterialRequirementRepository
        extends JpaRepository<SalesOrderSuppliedMaterialRequirement, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT requirement FROM SalesOrderSuppliedMaterialRequirement requirement " +
            "WHERE requirement.id = :id AND requirement.factoryId = :factoryId")
    java.util.Optional<SalesOrderSuppliedMaterialRequirement> findByIdAndFactoryIdForUpdate(
            @Param("id") String id, @Param("factoryId") String factoryId);

    List<SalesOrderSuppliedMaterialRequirement> findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(
            String salesOrderId);

    @Query("""
            SELECT requirement
              FROM SalesOrderSuppliedMaterialRequirement requirement
              JOIN FETCH requirement.salesOrder salesOrder
              JOIN FETCH requirement.materialType materialType
              JOIN FETCH requirement.targetWarehouse targetWarehouse
              LEFT JOIN FETCH requirement.salesOrderItem salesOrderItem
             WHERE requirement.factoryId = :factoryId
               AND salesOrder.factoryId = :factoryId
               AND salesOrder.status IN :approvedStatuses
               AND requirement.status IN :openStatuses
               AND requirement.receivedQuantity < requirement.expectedQuantity
             ORDER BY requirement.expectedArrivalAt ASC,
                      requirement.createdAt ASC,
                      requirement.id ASC
            """)
    List<SalesOrderSuppliedMaterialRequirement> findPendingReceivingTasks(
            @Param("factoryId") String factoryId,
            @Param("approvedStatuses") Collection<SalesOrderStatus> approvedStatuses,
            @Param("openStatuses")
            Collection<SalesOrderSuppliedMaterialRequirementStatus> openStatuses);
}

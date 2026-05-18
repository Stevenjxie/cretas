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

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, String> {

    Page<ReturnOrder> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndReturnTypeOrderByCreatedAtDesc(String factoryId, ReturnType returnType, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndStatusOrderByCreatedAtDesc(String factoryId, ReturnOrderStatus status, Pageable pageable);

    Page<ReturnOrder> findByFactoryIdAndReturnTypeAndStatusOrderByCreatedAtDesc(
            String factoryId, ReturnType returnType, ReturnOrderStatus status, Pageable pageable);

    /**
     * Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 17): paginated by customer counterparty + ReturnType filter,
     * newest first. ReturnOrder.counterpartyId is supplier_id for SUPPLIER_RETURN
     * and customer_id for SALES_RETURN — caller must pass ReturnType.SALES_RETURN.
     */
    Page<ReturnOrder> findByFactoryIdAndCounterpartyIdAndReturnTypeOrderByCreatedAtDesc(
            String factoryId, String counterpartyId, ReturnType returnType, Pageable pageable);

    @Query("SELECT COUNT(r) FROM ReturnOrder r WHERE r.factoryId = :factoryId AND r.returnDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);
}

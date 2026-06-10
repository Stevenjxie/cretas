package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseOrderItem> findByMaterialTypeId(String materialTypeId);

    /**
     * D-9 G1 批量加载：一次查询多个 PO 的所有行（避免 N+1）。
     * 用于出纳视图 listApprovedForPaymentWithDetails() 批量 hydrate items。
     */
    @Query("SELECT i FROM PurchaseOrderItem i WHERE i.purchaseOrderId IN :purchaseOrderIds AND i.deletedAt IS NULL")
    List<PurchaseOrderItem> findByPurchaseOrderIdIn(@Param("purchaseOrderIds") List<String> purchaseOrderIds);
}

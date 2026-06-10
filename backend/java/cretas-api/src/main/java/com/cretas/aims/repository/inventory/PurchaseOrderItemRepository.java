package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseOrderItem> findByMaterialTypeId(String materialTypeId);

    /**
     * 按 ID + PO 隶属关系查 item（行项目范围验证安全查法）.
     *
     * <p>确保 lineId 真属于 poId（防止跨 PO 访问）。结合上游对 PO 的
     * factoryId 校验，形成完整的租户 → 订单 → 行项目三层隔离。
     */
    Optional<PurchaseOrderItem> findByIdAndPurchaseOrderId(Long id, String purchaseOrderId);
}

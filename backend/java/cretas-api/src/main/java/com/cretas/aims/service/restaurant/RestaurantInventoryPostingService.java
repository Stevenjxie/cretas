package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.MaterialRequisition;
import com.cretas.aims.entity.restaurant.StocktakingRecord;
import com.cretas.aims.entity.restaurant.WastageRecord;

/**
 * 餐饮单据到库存/采购底座的过账服务.
 */
public interface RestaurantInventoryPostingService {

    /**
     * 餐饮供应商送货单验收入库：创建采购收货记录并确认，生成 MaterialBatch.
     */
    SupplierDeliveryNote postSupplierDeliveryToInventory(String factoryId, String noteId, Long userId);

    String postMaterialRequisitionIssue(String factoryId, MaterialRequisition requisition, Long userId);

    String postWastageDeduction(String factoryId, WastageRecord record, Long userId);

    String postStocktakingAdjustment(String factoryId, StocktakingRecord record, Long userId);

    /**
     * 过账失败状态独立事务持久化，避免主事务回滚后丢失失败原因.
     */
    void markSupplierDeliveryPostingFailed(String factoryId, String noteId, String errorMessage);

    void markMaterialRequisitionPostingFailed(String factoryId, String requisitionId, String errorMessage);

    void markWastagePostingFailed(String factoryId, String wastageId, String errorMessage);

    void markStocktakingPostingFailed(String factoryId, String recordId, String errorMessage);
}

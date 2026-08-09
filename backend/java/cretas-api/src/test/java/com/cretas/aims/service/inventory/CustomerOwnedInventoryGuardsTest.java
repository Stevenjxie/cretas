package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.sales.SalesFinishedGoodsOwnershipGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("客户专属库存生产与销售归属门禁")
class CustomerOwnedInventoryGuardsTest {

    @Test
    @DisplayName("库存生产只领用同客户且未绑定销售订单的原料")
    void inventoryProductionRequiresSameCustomerUnassignedRawMaterial() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId(null);

        MaterialBatch allowed = new MaterialBatch();
        allowed.setFactoryId("F006");
        allowed.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        allowed.setOwnerCustomerId("CUSTOMER-1");
        allowed.setSourceSalesOrderId(null);

        assertDoesNotThrow(() -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, allowed, "materialBatchId"));

        MaterialBatch alreadyBound = new MaterialBatch();
        alreadyBound.setFactoryId("F006");
        alreadyBound.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        alreadyBound.setOwnerCustomerId("CUSTOMER-1");
        alreadyBound.setSourceSalesOrderId("SO-OTHER");

        assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, alreadyBound, "materialBatchId"));
    }

    @Test
    @DisplayName("正式销售订单只能发出本订单已预留的同客户预生产成品")
    void prestockedSalesRequiresActiveReservationWithoutLineageRewrite() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-1");
        order.setCustomerId("CUSTOMER-1");
        order.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
        order.setCustomerStockFulfillmentMode(CustomerStockFulfillmentMode.PRESTOCKED);

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId("CUSTOMER-1");
        batch.setSourceSalesOrderId(null);

        assertDoesNotThrow(() -> SalesFinishedGoodsOwnershipGuard
                .assertBatchAllowed(order, batch, true, "finishedGoodsBatchId"));
        assertThrows(BusinessException.class, () -> SalesFinishedGoodsOwnershipGuard
                .assertBatchAllowed(order, batch, false, "finishedGoodsBatchId"));
    }
}

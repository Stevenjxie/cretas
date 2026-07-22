package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionPlanMaterialOwnershipGuardTest {

    @Test
    void customerSuppliedPlanAcceptsOnlySameCustomerAndSalesOrderBatch() {
        ProductionPlan plan = customerSuppliedPlan();
        MaterialBatch batch = customerBatch("CUSTOMER-1", "SO-1", "101");

        assertDoesNotThrow(() -> ProductionPlanServiceImpl
                .assertMaterialBatchOwnershipMatchesPlan(plan, batch, "原料领用"));

        batch.setOwnerCustomerId("CUSTOMER-2");
        BusinessException wrongCustomer = assertThrows(BusinessException.class, () ->
                ProductionPlanServiceImpl.assertMaterialBatchOwnershipMatchesPlan(plan, batch, "原料领用"));
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", wrongCustomer.getErrorCode());

        batch.setOwnerCustomerId("CUSTOMER-1");
        batch.setSourceSalesOrderId("SO-2");
        BusinessException wrongOrder = assertThrows(BusinessException.class, () ->
                ProductionPlanServiceImpl.assertMaterialBatchOwnershipMatchesPlan(plan, batch, "原料领用"));
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", wrongOrder.getErrorCode());
    }

    @Test
    void factorySuppliedAndLegacyPlansRejectCustomerOwnedInventory() {
        MaterialBatch batch = customerBatch("CUSTOMER-1", "SO-1", "101");
        ProductionPlan factoryPlan = plan(MaterialSupplyMode.FACTORY_SUPPLIED);
        ProductionPlan legacyPlan = plan(null);

        assertEquals("CUSTOMER_OWNED_MATERIAL_FORBIDDEN",
                assertThrows(BusinessException.class, () -> ProductionPlanServiceImpl
                        .assertMaterialBatchOwnershipMatchesPlan(factoryPlan, batch, "原料领用"))
                        .getErrorCode());
        assertEquals("CUSTOMER_OWNED_MATERIAL_FORBIDDEN",
                assertThrows(BusinessException.class, () -> ProductionPlanServiceImpl
                        .assertMaterialBatchOwnershipMatchesPlan(legacyPlan, batch, "原料领用"))
                        .getErrorCode());

        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        assertDoesNotThrow(() -> ProductionPlanServiceImpl
                .assertMaterialBatchOwnershipMatchesPlan(factoryPlan, batch, "原料领用"));
    }

    @Test
    void customerSuppliedPlanFailsClosedWithoutLineage() {
        ProductionPlan plan = customerSuppliedPlan();
        plan.setSourceOrderId(null);

        BusinessException error = assertThrows(BusinessException.class, () ->
                ProductionPlanServiceImpl.assertMaterialBatchOwnershipMatchesPlan(
                        plan, customerBatch("CUSTOMER-1", "SO-1", "101"), "原料领用"));

        assertEquals("CUSTOMER_SUPPLIED_PLAN_LINEAGE_INCOMPLETE", error.getErrorCode());
    }

    private ProductionPlan customerSuppliedPlan() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setCustomerId("CUSTOMER-1");
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("101");
        return plan;
    }

    private ProductionPlan plan(MaterialSupplyMode supplyMode) {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setMaterialSupplyMode(supplyMode);
        return plan;
    }

    private MaterialBatch customerBatch(String customerId, String orderId, String itemId) {
        MaterialBatch batch = new MaterialBatch();
        batch.setFactoryId("F006");
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId(customerId);
        batch.setSourceSalesOrderId(orderId);
        batch.setSourceSalesOrderItemId(itemId);
        return batch;
    }
}

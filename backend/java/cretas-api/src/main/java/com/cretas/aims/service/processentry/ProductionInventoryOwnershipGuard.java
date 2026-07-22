package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;

import java.util.Objects;

/**
 * Single fail-closed ownership contract shared by plan assignment, reporting
 * allocation, direct consumption and settlement posting.
 */
public final class ProductionInventoryOwnershipGuard {

    private ProductionInventoryOwnershipGuard() {
    }

    public static void assertMaterialBatchAllowed(ProductionPlan plan,
                                                  MaterialBatch batch,
                                                  String hintTarget) {
        if (plan == null || batch == null) {
            throw new BusinessException(409, "生产计划或原料批次归属信息缺失")
                    .withCode("PRODUCTION_MATERIAL_OWNERSHIP_CONTEXT_REQUIRED")
                    .withHintTarget(hintTarget);
        }
        if (!Objects.equals(plan.getFactoryId(), batch.getFactoryId())) {
            throw new BusinessException(403, "原料批次不属于当前生产计划的工厂")
                    .withCode("PRODUCTION_MATERIAL_CROSS_FACTORY_FORBIDDEN")
                    .withHintTarget(hintTarget);
        }

        if (plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            requireCustomerSuppliedPlanLineage(plan, hintTarget);
            boolean sameCustomer = Objects.equals(plan.getCustomerId(), batch.getOwnerCustomerId());
            boolean sameSalesOrder = Objects.equals(plan.getSourceOrderId(), batch.getSourceSalesOrderId());
            boolean sameSalesOrderItem = isBlank(plan.getSourceOrderItemId())
                    || isBlank(batch.getSourceSalesOrderItemId())
                    || Objects.equals(plan.getSourceOrderItemId(), batch.getSourceSalesOrderItemId());
            if (batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED
                    || !sameCustomer || !sameSalesOrder || !sameSalesOrderItem) {
                throw new BusinessException(409, "客供料只能用于同一客户、同一销售订单的生产计划")
                        .withCode("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH")
                        .withHint("请使用该销售订单对应的客户来料批次")
                        .withHintTarget(hintTarget);
            }
            return;
        }

        if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "工厂备料生产不能领用客户所有的库存")
                    .withCode("CUSTOMER_OWNED_MATERIAL_FORBIDDEN")
                    .withHint("请选择公司自有原料批次；客供料仅可用于其绑定的客户订单")
                    .withHintTarget(hintTarget);
        }
    }

    public static void requireCustomerSuppliedPlanLineage(ProductionPlan plan, String hintTarget) {
        if (plan == null || isBlank(plan.getCustomerId()) || isBlank(plan.getSourceOrderId())) {
            throw new BusinessException(409, "客供料生产计划缺少客户或销售订单归属快照")
                    .withCode("CUSTOMER_SUPPLIED_PLAN_LINEAGE_INCOMPLETE")
                    .withHint("请从已完成审批且已明确客供料的销售订单创建生产计划")
                    .withHintTarget(hintTarget);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

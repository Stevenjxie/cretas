package com.cretas.aims.service.sales;

import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;

import java.util.Objects;

/** 推荐、分配与发货共用的库存所有权 fail-closed 门禁。 */
public final class SalesFinishedGoodsOwnershipGuard {

    private SalesFinishedGoodsOwnershipGuard() {
    }

    public static boolean requiresCustomerOwnedStock(SalesOrder order) {
        return order != null && order.getProcessingMode() == SalesProcessingMode.TOLL_PROCESSING;
    }

    public static void assertBatchAllowed(SalesOrder order,
                                          FinishedGoodsBatch batch,
                                          String hintTarget) {
        if (batch == null) {
            throw new BusinessException(409, "成品批次缺少库存所有权信息")
                    .withCode("FINISHED_GOODS_OWNERSHIP_CONTEXT_REQUIRED")
                    .withHintTarget(hintTarget);
        }
        if (requiresCustomerOwnedStock(order)) {
            if (isBlank(order.getCustomerId()) || isBlank(order.getId())) {
                throw new BusinessException(409, "代加工销售订单缺少客户或来源订单信息")
                        .withCode("CUSTOMER_OWNED_SALES_LINEAGE_INCOMPLETE")
                        .withHintTarget(hintTarget);
            }
            if (batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED
                    || !Objects.equals(order.getCustomerId(), batch.getOwnerCustomerId())
                    || !Objects.equals(order.getId(), batch.getSourceSalesOrderId())) {
                throw new BusinessException(409, "代加工订单只能发出属于同一客户、同一销售订单的成品库存")
                        .withCode("CUSTOMER_OWNED_FINISHED_GOODS_SCOPE_MISMATCH")
                        .withHint("请选择该客户订单生产并入库的成品批次")
                        .withHintTarget(hintTarget);
            }
            return;
        }

        if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "普通销售订单不能发出客户所有的成品库存")
                    .withCode("CUSTOMER_OWNED_FINISHED_GOODS_FORBIDDEN")
                    .withHintTarget(hintTarget);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

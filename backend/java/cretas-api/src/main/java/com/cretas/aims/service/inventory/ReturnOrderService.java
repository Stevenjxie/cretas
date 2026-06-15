package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;

import java.util.Map;

public interface ReturnOrderService {

    ReturnOrder createReturnOrder(String factoryId, CreateReturnOrderRequest request, Long userId);

    ReturnOrder getReturnOrderById(String factoryId, String returnOrderId);

    PageResponse<ReturnOrder> getReturnOrders(String factoryId, ReturnType returnType,
                                               ReturnOrderStatus status, int page, int size);

    ReturnOrder submitReturnOrder(String factoryId, String returnOrderId);

    ReturnOrder approveReturnOrder(String factoryId, String returnOrderId, Long approverId);

    /**
     * 六扇门 Tier0 #16 (catalog 行2399-2416): 退货财务审批门。
     * 退货跟钱有关，业务审批 (APPROVED) 后必须先经财务审批 (FINANCE_APPROVED)
     * 才能交仓管完成/出货。只有 finance 角色 (finance:read_write) 可调用。
     *
     * @param financeUserId 财务审批人用户 ID
     */
    ReturnOrder financeApproveReturnOrder(String factoryId, String returnOrderId, Long financeUserId);

    ReturnOrder financeRejectReturnOrder(String factoryId, String returnOrderId, Long financeUserId);

    ReturnOrder rejectReturnOrder(String factoryId, String returnOrderId);

    ReturnOrder completeReturnOrder(String factoryId, String returnOrderId);

    Map<String, Object> getReturnOrderStatistics(String factoryId);
}

package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PurchaseApprovalRecoveryResponse {
    String orderId;
    String orderNumber;
    PurchaseOrderStatus orderStatus;
    String workflowInstanceId;
    InstanceStatus workflowStatus;
    List<String> currentNodeIds;
    boolean recovered;
}

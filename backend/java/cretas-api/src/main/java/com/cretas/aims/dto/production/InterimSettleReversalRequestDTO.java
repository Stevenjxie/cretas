package com.cretas.aims.dto.production;

import com.cretas.aims.entity.InterimSettleReversalRequest;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 撤销小结申请响应 DTO。
 */
@Data
public class InterimSettleReversalRequestDTO {

    private String id;
    private String factoryId;
    private String productionPlanId;
    private Integer sessionSeq;
    private LocalDateTime settlementPostedAt;
    private String reason;
    private String status;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private LocalDateTime executedAt;
    private String affectedBatchNumbers;
    /** #1214 缺口修复: 连带冲销的同厂调拨记录操作提示 (分号分隔); 无连带冲销 → null。 */
    private String transferReconcileHints;
    /** 统一审批中心 workflow 实例 ID (可空)。 */
    private String workflowInstanceId;

    public static InterimSettleReversalRequestDTO from(InterimSettleReversalRequest r) {
        InterimSettleReversalRequestDTO dto = new InterimSettleReversalRequestDTO();
        dto.setId(r.getId());
        dto.setFactoryId(r.getFactoryId());
        dto.setProductionPlanId(r.getProductionPlanId());
        dto.setSessionSeq(r.getSessionSeq());
        dto.setSettlementPostedAt(r.getSettlementPostedAt());
        dto.setReason(r.getReason());
        dto.setStatus(r.getStatus() != null ? r.getStatus().name() : null);
        dto.setRequestedBy(r.getRequestedBy());
        dto.setRequestedAt(r.getRequestedAt());
        dto.setApprovedBy(r.getApprovedBy());
        dto.setApprovedAt(r.getApprovedAt());
        dto.setRejectReason(r.getRejectReason());
        dto.setExecutedAt(r.getExecutedAt());
        dto.setAffectedBatchNumbers(r.getAffectedBatchNumbers());
        dto.setTransferReconcileHints(r.getTransferReconcileHints());
        dto.setWorkflowInstanceId(r.getWorkflowInstanceId());
        return dto;
    }
}

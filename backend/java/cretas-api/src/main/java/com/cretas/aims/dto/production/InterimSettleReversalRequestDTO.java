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
        return dto;
    }
}

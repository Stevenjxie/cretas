package com.cretas.aims.dto.inventory;

import com.cretas.aims.entity.inventory.WastageReport;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报损单响应 DTO (SP7 T1).
 */
@Data
public class WastageReportDTO {

    private String id;
    private String factoryId;
    private String reportNo;
    private String trackType;
    private String warehouseId;
    private String materialBatchId;
    private String rawMaterialTypeId;
    private BigDecimal wastageQty;
    private String wastageReason;
    private String reasonDetail;
    private String photoUrls;
    private String status;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private String approverRole;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private LocalDateTime appliedAt;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WastageReportDTO from(WastageReport report) {
        WastageReportDTO dto = new WastageReportDTO();
        dto.setId(report.getId());
        dto.setFactoryId(report.getFactoryId());
        dto.setReportNo(report.getReportNo());
        dto.setTrackType(report.getTrackType() != null ? report.getTrackType().name() : null);
        dto.setWarehouseId(report.getWarehouseId());
        dto.setMaterialBatchId(report.getMaterialBatchId());
        dto.setRawMaterialTypeId(report.getRawMaterialTypeId());
        dto.setWastageQty(report.getWastageQty());
        dto.setWastageReason(report.getWastageReason() != null ? report.getWastageReason().name() : null);
        dto.setReasonDetail(report.getReasonDetail());
        dto.setPhotoUrls(report.getPhotoUrls());
        dto.setStatus(report.getStatus() != null ? report.getStatus().name() : null);
        dto.setSubmittedBy(report.getSubmittedBy());
        dto.setSubmittedAt(report.getSubmittedAt());
        dto.setApproverRole(report.getApproverRole());
        dto.setApprovedBy(report.getApprovedBy());
        dto.setApprovedAt(report.getApprovedAt());
        dto.setRejectReason(report.getRejectReason());
        dto.setAppliedAt(report.getAppliedAt());
        dto.setNotes(report.getNotes());
        dto.setCreatedAt(report.getCreatedAt());
        dto.setUpdatedAt(report.getUpdatedAt());
        return dto;
    }
}

package com.cretas.aims.dto.factory;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 盘点任务响应 DTO (SP7 T1).
 */
@Data
public class StocktakeDTO {

    private String id;
    private String factoryId;
    private String stocktakeNo;
    private String warehouseId;
    private String periodMonth;
    private String status;
    private Long initiatedBy;
    private LocalDateTime initiatedAt;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private LocalDateTime appliedAt;
    private String notes;
    private LocalDateTime createdAt;
    private String workflowInstanceId;
    /** 批量导入模式: NORMAL / OPENING(期初建账) / null(逐项UI盘点)。用于列表区分「期初建账」与常规盘点。*/
    private String importMode;
    private List<StocktakeItemDTO> items;

    @Data
    public static class StocktakeItemDTO {
        private String id;
        private String stocktakeId;
        private String materialBatchId;
        private String rawMaterialTypeId;
        private BigDecimal systemQty;
        private BigDecimal actualQty;
        private BigDecimal differenceQty;
        private String differenceType;
        private String photoUrls;
        private String notes;

        public static StocktakeItemDTO from(FactoryStocktakeItem item) {
            StocktakeItemDTO dto = new StocktakeItemDTO();
            dto.setId(item.getId());
            dto.setStocktakeId(item.getStocktake() != null ? item.getStocktake().getId() : null);
            dto.setMaterialBatchId(item.getMaterialBatchId());
            dto.setRawMaterialTypeId(item.getRawMaterialTypeId());
            dto.setSystemQty(item.getSystemQty());
            dto.setActualQty(item.getActualQty());
            dto.setDifferenceQty(item.getDifferenceQty());
            dto.setDifferenceType(item.getDifferenceType() != null ? item.getDifferenceType().name() : null);
            dto.setPhotoUrls(item.getPhotoUrls());
            dto.setNotes(item.getNotes());
            return dto;
        }
    }

    public static StocktakeDTO from(FactoryStocktake stocktake) {
        StocktakeDTO dto = new StocktakeDTO();
        dto.setId(stocktake.getId());
        dto.setFactoryId(stocktake.getFactoryId());
        dto.setStocktakeNo(stocktake.getStocktakeNo());
        dto.setWarehouseId(stocktake.getWarehouseId());
        dto.setPeriodMonth(stocktake.getPeriodMonth());
        dto.setStatus(stocktake.getStatus() != null ? stocktake.getStatus().name() : null);
        dto.setInitiatedBy(stocktake.getInitiatedBy());
        dto.setInitiatedAt(stocktake.getInitiatedAt());
        dto.setSubmittedBy(stocktake.getSubmittedBy());
        dto.setSubmittedAt(stocktake.getSubmittedAt());
        dto.setApprovedBy(stocktake.getApprovedBy());
        dto.setApprovedAt(stocktake.getApprovedAt());
        dto.setRejectReason(stocktake.getRejectReason());
        dto.setAppliedAt(stocktake.getAppliedAt());
        dto.setNotes(stocktake.getNotes());
        dto.setCreatedAt(stocktake.getCreatedAt());
        dto.setWorkflowInstanceId(stocktake.getWorkflowInstanceId());
        dto.setImportMode(stocktake.getImportMode() != null ? stocktake.getImportMode().name() : null);
        if (stocktake.getItems() != null) {
            dto.setItems(stocktake.getItems().stream()
                    .map(StocktakeItemDTO::from)
                    .collect(Collectors.toList()));
        }
        return dto;
    }
}

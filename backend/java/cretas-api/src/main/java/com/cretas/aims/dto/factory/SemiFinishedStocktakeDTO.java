package com.cretas.aims.dto.factory;

import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 半成品盘点任务响应 DTO (镜像 SP7 {@link StocktakeDTO})。
 */
@Data
public class SemiFinishedStocktakeDTO {

    private String id;
    private String factoryId;
    private String stocktakeNo;
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
    private List<SemiFinishedStocktakeItemDTO> items;

    /**
     * 撤销小结告警点 (READ-ONLY, 由服务在盘点期间从撤销审计填充): 该期间内被撤销过小结的半成品/成品批次,
     * 提示盘点人核实实物 (撤销逆转了系统库存, 实物可能已动)。空 = 无撤销告警。
     */
    private List<ReversalWarning> warnings;

    @Data
    public static class ReversalWarning {
        private String batchNo;
        private String message;

        public ReversalWarning() {}

        public ReversalWarning(String batchNo, String message) {
            this.batchNo = batchNo;
            this.message = message;
        }
    }

    @Data
    public static class SemiFinishedStocktakeItemDTO {
        private String id;
        private String stocktakeId;
        private Long semiFinishedId;
        private String intermediateBatchNo;
        private String productTypeId;
        private BigDecimal systemQty;
        private BigDecimal actualQty;
        private BigDecimal differenceQty;
        private String differenceType;
        private String unit;
        private String photoUrls;
        private String notes;

        public static SemiFinishedStocktakeItemDTO from(SemiFinishedStocktakeItem item) {
            SemiFinishedStocktakeItemDTO dto = new SemiFinishedStocktakeItemDTO();
            dto.setId(item.getId());
            dto.setStocktakeId(item.getStocktake() != null ? item.getStocktake().getId() : null);
            dto.setSemiFinishedId(item.getSemiFinishedId());
            dto.setIntermediateBatchNo(item.getIntermediateBatchNo());
            dto.setProductTypeId(item.getProductTypeId());
            dto.setSystemQty(item.getSystemQty());
            dto.setActualQty(item.getActualQty());
            dto.setDifferenceQty(item.getDifferenceQty());
            dto.setDifferenceType(item.getDifferenceType() != null ? item.getDifferenceType().name() : null);
            dto.setUnit(item.getUnit());
            dto.setPhotoUrls(item.getPhotoUrls());
            dto.setNotes(item.getNotes());
            return dto;
        }
    }

    public static SemiFinishedStocktakeDTO from(SemiFinishedStocktake stocktake) {
        SemiFinishedStocktakeDTO dto = new SemiFinishedStocktakeDTO();
        dto.setId(stocktake.getId());
        dto.setFactoryId(stocktake.getFactoryId());
        dto.setStocktakeNo(stocktake.getStocktakeNo());
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
        if (stocktake.getItems() != null) {
            dto.setItems(stocktake.getItems().stream()
                    .map(SemiFinishedStocktakeItemDTO::from)
                    .collect(Collectors.toList()));
        }
        return dto;
    }
}

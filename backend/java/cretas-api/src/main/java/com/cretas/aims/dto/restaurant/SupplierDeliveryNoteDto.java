package com.cretas.aims.dto.restaurant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 供应商送货单 DTO — OCR 解析 / 列表 / 详情 响应 + 行项编辑请求 (G7 Tier A)。
 *
 * @since 2026-06-03 (G7)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDeliveryNoteDto {

    private String id;
    private String factoryId;
    private String sourceType;          // OCR | MANUAL
    private String photoOssUrl;
    private String supplierId;
    private String supplierName;
    private LocalDate deliveryDate;
    private String warehouseId;
    private String noteNumber;
    private BigDecimal totalAmount;
    private BigDecimal ocrConfidence;
    private String ocrErrorMessage;
    private String status;              // DRAFT | CONFIRMED | REJECTED
    private String postingStatus;       // UNPOSTED | POSTING | POSTED | FAILED
    private String receiveRecordId;
    private String postedAt;
    private String postingError;
    private String rejectReasonCode;
    private String rejectReasonNote;

    /** Rule 5: 置信度 < 阈值时前端显橙色重拍提示。 */
    private Boolean lowConfidenceWarning;

    private List<LineDto> lines;

    /** 行项 — OCR 提取或人工编辑。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDto {
        private Long id;
        private String ingredientName;
        private String rawMaterialTypeId;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;
        private BigDecimal lineAmount;
        private String qcResult;
        private String materialBatchId;
        private String remark;
        private BigDecimal ocrConfidence;
    }

    /** 拒绝请求体 (Rule 3)。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectRequest {
        private String rejectReasonCode;    // IMAGE_BLUR | LOW_LIGHT | WRONG_DOCUMENT | SUPPLIER_NOT_FOUND | OTHER
        private String rejectReasonNote;
    }

    /** 人工录入 (createManual, 决策 D2) / 行项批量编辑请求体。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualCreateRequest {
        private String supplierId;
        private String supplierName;
        private LocalDate deliveryDate;
        private String warehouseId;
        private String noteNumber;
        private List<LineDto> lines;
    }
}

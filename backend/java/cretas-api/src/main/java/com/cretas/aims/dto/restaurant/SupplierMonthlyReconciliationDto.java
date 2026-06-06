package com.cretas.aims.dto.restaurant;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierMonthlyReconciliationDto {

    private String id;
    private String factoryId;
    private String supplierId;
    private String supplierName;
    private String month;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String status;
    private Integer deliveryNoteCount;
    private Integer apTransactionCount;

    @PriceSensitive
    private BigDecimal deliveryTotal;

    @PriceSensitive
    private BigDecimal apInvoiceTotal;

    @PriceSensitive
    private BigDecimal apPaymentTotal;

    @PriceSensitive
    private BigDecimal apAdjustmentTotal;

    @PriceSensitive
    private BigDecimal differenceAmount;

    @PriceSensitive
    private BigDecimal netPayableAmount;

    private Long confirmedBy;
    private String confirmedAt;
    private String createdAt;
    private String updatedAt;
    private List<LineDto> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDto {
        private Long id;
        private String lineType;
        private String deliveryNoteId;
        private String deliveryNoteNumber;
        private LocalDate deliveryDate;

        @PriceSensitive
        private BigDecimal deliveryAmount;

        private String apTransactionId;
        private String apTransactionNumber;
        private LocalDate transactionDate;
        private String transactionType;

        @PriceSensitive
        private BigDecimal apAmount;

        @PriceSensitive
        private BigDecimal differenceAmount;

        private String lineStatus;
        private String remark;

        private String photoOssUrl;
        private String voiceAudioUrl;
        private String voiceTranscriptText;
        private String supplierContactNote;
        private String priceAnomalyApprovalStatus;
        private Long priceAnomalyApprovedBy;
        private String priceAnomalyApprovedAt;
        private String priceAnomalyApprovalComment;
        private String payableTransactionId;
        private String payablePostedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftRequest {
        private String supplierId;
        /** Format: yyyy-MM. */
        private String month;
    }
}

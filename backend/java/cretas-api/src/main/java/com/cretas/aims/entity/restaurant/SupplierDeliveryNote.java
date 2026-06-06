package com.cretas.aims.entity.restaurant;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteSourceType;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.DeliveryPostingStatus;
import com.cretas.aims.entity.restaurant.enums.PriceAnomalyApprovalStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 供应商送货单 / 进货单 (G7 取数自动化 Tier A)。
 *
 * <p>两种来源:</p>
 * <ul>
 *   <li><b>OCR</b> — DashScope Vision 识别照片自动解析行项 (置信度写 ocrConfidence)</li>
 *   <li><b>MANUAL</b> — OCR 失败/低置信时用户手工录入 (决策 D2)</li>
 * </ul>
 *
 * <p>幂等 (Rule 4): photoHash 唯一索引, 同照片二次上传返回已有草稿。</p>
 * <p>确认 (CONFIRMED) 时写 gold 表 agg_supplier_price (smartbi_db) 供 G5 成本卡 / G4 进价趋势。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "lines")
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "supplier_delivery_notes",
        indexes = {
                @Index(name = "idx_sdn_factory_date", columnList = "factory_id,delivery_date"),
                @Index(name = "idx_sdn_supplier", columnList = "factory_id,supplier_id"),
                @Index(name = "idx_sdn_status", columnList = "factory_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class SupplierDeliveryNote extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 100)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private DeliveryNoteSourceType sourceType = DeliveryNoteSourceType.OCR;

    /** SHA-256 of uploaded photo bytes (Rule 4 幂等键)。MANUAL 录入为 null。 */
    @Column(name = "photo_hash", length = 64, unique = true)
    private String photoHash;

    @Column(name = "photo_oss_url", length = 500)
    private String photoOssUrl;

    @Column(name = "supplier_id", length = 191)
    private String supplierId;

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "warehouse_id", length = 191)
    private String warehouseId;

    @Column(name = "note_number", length = 100)
    private String noteNumber;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /** OCR 整体置信度 0.000-1.000 (< 0.75 前端提示重拍)。 */
    @Column(name = "ocr_confidence", precision = 4, scale = 3)
    private BigDecimal ocrConfidence;

    @Column(name = "ocr_raw_json", columnDefinition = "TEXT")
    private String ocrRawJson;

    @Column(name = "ocr_error_message", columnDefinition = "TEXT")
    private String ocrErrorMessage;

    @Column(name = "ocr_parsed_at")
    private LocalDateTime ocrParsedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryNoteStatus status = DeliveryNoteStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_status", nullable = false, length = 20)
    private DeliveryPostingStatus postingStatus = DeliveryPostingStatus.UNPOSTED;

    @Column(name = "receive_record_id", length = 191)
    private String receiveRecordId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by")
    private Long postedBy;

    @Column(name = "posting_error", columnDefinition = "TEXT")
    private String postingError;

    @Column(name = "payable_transaction_id", length = 191)
    private String payableTransactionId;

    @Column(name = "payable_posted_at")
    private LocalDateTime payablePostedAt;

    @Column(name = "payable_posting_error", columnDefinition = "TEXT")
    private String payablePostingError;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** Rule 3: 拒绝标准原因码 (IMAGE_BLUR / LOW_LIGHT / WRONG_DOCUMENT / SUPPLIER_NOT_FOUND / OTHER)。 */
    @Column(name = "reject_reason_code", length = 50)
    private String rejectReasonCode;

    @Column(name = "reject_reason_note", columnDefinition = "TEXT")
    private String rejectReasonNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_anomaly_approval_status", nullable = false, length = 20)
    private PriceAnomalyApprovalStatus priceAnomalyApprovalStatus = PriceAnomalyApprovalStatus.NONE;

    @Column(name = "price_anomaly_submitted_by")
    private Long priceAnomalySubmittedBy;

    @Column(name = "price_anomaly_submitted_at")
    private LocalDateTime priceAnomalySubmittedAt;

    @Column(name = "price_anomaly_approved_by")
    private Long priceAnomalyApprovedBy;

    @Column(name = "price_anomaly_approved_at")
    private LocalDateTime priceAnomalyApprovedAt;

    @Column(name = "price_anomaly_rejected_by")
    private Long priceAnomalyRejectedBy;

    @Column(name = "price_anomaly_rejected_at")
    private LocalDateTime priceAnomalyRejectedAt;

    @Column(name = "price_anomaly_approval_comment", columnDefinition = "TEXT")
    private String priceAnomalyApprovalComment;

    @Column(name = "source_requisition_id", length = 191)
    private String sourceRequisitionId;

    @Column(name = "procurement_confirmed_by")
    private Long procurementConfirmedBy;

    @Column(name = "procurement_confirmed_at")
    private LocalDateTime procurementConfirmedAt;

    @Column(name = "supplier_contact_note", columnDefinition = "TEXT")
    private String supplierContactNote;

    @Column(name = "voice_audio_url", length = 500)
    private String voiceAudioUrl;

    @Column(name = "voice_transcript_text", columnDefinition = "TEXT")
    private String voiceTranscriptText;

    @Column(name = "supplier_quote_photo_urls", columnDefinition = "TEXT")
    private String supplierQuotePhotoUrls;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "created_by")
    private Long createdBy;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "note", fetch = FetchType.LAZY)
    private List<SupplierDeliveryNoteLine> lines = new ArrayList<>();

    /** 双向关联辅助: 加行项时维护 back-reference + factoryId。 */
    public void addLine(SupplierDeliveryNoteLine line) {
        line.setNote(this);
        line.setFactoryId(this.factoryId);
        this.lines.add(line);
    }
}

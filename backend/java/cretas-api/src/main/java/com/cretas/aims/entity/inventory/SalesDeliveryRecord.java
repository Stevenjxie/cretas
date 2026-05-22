package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 销售发货/出库单
 * 关联 SalesOrder，出库确认时扣减 FinishedGoodsBatch 库存
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "salesOrder", "customer", "shippedByUser", "items"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sales_delivery_records",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "delivery_number"})
        },
        indexes = {
                @Index(name = "idx_sdr_factory", columnList = "factory_id"),
                @Index(name = "idx_sdr_order", columnList = "sales_order_id"),
                @Index(name = "idx_sdr_customer", columnList = "customer_id"),
                @Index(name = "idx_sdr_status", columnList = "status"),
                @Index(name = "idx_sdr_delivery_date", columnList = "delivery_date")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SalesDeliveryRecord extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "delivery_number", nullable = false, length = 50)
    private String deliveryNumber;

    @Column(name = "sales_order_id", length = 191)
    private String salesOrderId;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "delivery_address")
    private String deliveryAddress;

    /** 物流公司 */
    @Column(name = "logistics_company", length = 100)
    private String logisticsCompany;

    /** 物流单号 */
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SalesDeliveryStatus status = SalesDeliveryStatus.DRAFT;

    @Column(name = "shipped_by", nullable = false)
    private Long shippedBy;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    // ==================== P0-NEW-1 签收凭证 (客户原话 2807s) ====================

    /** 签收照片 URL 列表 (JSON 数组字符串) */
    @Column(name = "signature_photo_urls", columnDefinition = "TEXT")
    private String signaturePhotoUrls;

    /** 签收人姓名 */
    @Column(name = "signed_by_name", length = 100)
    private String signedByName;

    /** 签收时间 */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** 签收备注 */
    @Column(name = "signature_remark", length = 500)
    private String signatureRemark;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private SalesOrder salesOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Customer customer;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipped_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User shippedByUser;

    @OneToMany(mappedBy = "deliveryRecord", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SalesDeliveryItem> items = new ArrayList<>();

    /**
     * Sprint 10 AI invocation source tag — set when Workdesk AI 1-click create.
     * Schema: {source: "sprint-10-loop-1", testRun: bool, createdAt: iso}.
     * NULL = manual UI create (传统菜单 path).
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ai_invocation_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiInvocationMetadata;
}

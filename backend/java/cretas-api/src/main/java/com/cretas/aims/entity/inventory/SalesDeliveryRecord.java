package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Formula;
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

    /** 母发货单 ID。NULL 表示母单/历史单；非 NULL 表示一次实际子发运。 */
    @Column(name = "parent_delivery_id", length = 191)
    private String parentDeliveryId;

    /** MASTER / SHIPMENT / LEGACY。历史数据默认 LEGACY，保持原有单层流程。 */
    @Column(name = "record_role", nullable = false, length = 16)
    private String recordRole = "LEGACY";

    /** 子发运顺序号，用于生成母单号-01/-02。 */
    @Column(name = "shipment_sequence")
    private Integer shipmentSequence;

    /** 客户端幂等键；同一业务主体和键只生成一次。 */
    @Column(name = "idempotency_key", length = 191)
    private String idempotencyKey;

    /**
     * headed-audit fix (2026-07-03): same rationale as {@link #customerName} — the
     * {@link #salesOrder} relation is {@code @JsonIgnore}d, so the 待确认发货单 list
     * only had the raw {@link #salesOrderId} UUID to show in the "销售订单" column
     * (human-unreadable, worker can't cross-check against the paper/system order number).
     * {@code sales_order_id} can be NULL (delivery not linked to an SO) — subquery
     * then naturally returns NULL, no special-case needed.
     */
    @Formula("(SELECT so.order_number FROM sales_orders so WHERE so.id = sales_order_id)")
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    /**
     * headed-audit fix (2026-07-03): 仓储→出货管理→"销售发货单待确认" 列表
     * ({@code GET /warehouse/deliveries/pending}) 原样序列化本 entity, 而 {@link #customer}
     * 关系是 {@code @JsonIgnore} (避免懒加载序列化炸), 结果前端只能拿到 {@link #customerId}
     * 裸 UUID (如 "56e40470-…") — 仓管员无法判断该发哪个客户的货, 也无法确认装车对象。
     * 用 {@code @Formula} 只读派生列 (镜像 {@link SalesOrder#getCustomerName()} 同一 pattern),
     * DB 端 subquery 拿客户名, 无需改任何 controller/DTO/service 调用点, 且自动覆盖本 entity
     * 被序列化的所有位置 (待确认列表 + confirmDelivery 响应 + 确认弹窗)。
     */
    @Formula("(SELECT c.name FROM customers c WHERE c.id = customer_id)")
    private String customerName;

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

    /** LOGISTICS / SELF_PICKUP / SELF_DELIVERY. Null means legacy data. */
    @Column(name = "delivery_method", length = 30)
    private String deliveryMethod;

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

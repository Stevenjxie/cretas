package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.enums.ReceiveDecisionStatus;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Where;

/**
 * 采购入库单
 * 对应一次到货验收入库操作，入库确认后创建 MaterialBatch
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "purchaseOrder", "supplier", "receivedByUser", "items"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_receive_records",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "receive_number"})
        },
        indexes = {
                @Index(name = "idx_prr_factory", columnList = "factory_id"),
                @Index(name = "idx_prr_po", columnList = "purchase_order_id"),
                @Index(name = "idx_prr_supplier", columnList = "supplier_id"),
                @Index(name = "idx_prr_date", columnList = "receive_date"),
                @Index(name = "idx_prr_status", columnList = "status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseReceiveRecord extends BaseEntity {

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

    @Column(name = "receive_number", nullable = false, length = 50)
    private String receiveNumber;

    @Column(name = "purchase_order_id", length = 191)
    private String purchaseOrderId;

    @Column(name = "supplier_id", nullable = false, length = 191)
    private String supplierId;

    // Issue #567: receives LIST response previously returned only the FK UUIDs;
    // L3 入库记录 列表的 "采购订单" 和 "供应商" 列因此显示 raw UUID. Mirror the
    // PurchaseOrder.supplierName pattern (PurchaseOrder.java:75-76) — Hibernate
    // resolves these subqueries when the entity is loaded, so the list response
    // now carries human-readable identifiers without service-layer hydration.
    @Formula("(SELECT po.order_number FROM purchase_orders po WHERE po.id = purchase_order_id)")
    private String purchaseOrderNumber;

    @Formula("(SELECT s.name FROM suppliers s WHERE s.id = supplier_id)")
    private String supplierName;

    @Column(name = "receive_date", nullable = false)
    private LocalDate receiveDate;

    /** 预留：仓库ID（P3 启用） */
    @Column(name = "warehouse_id", length = 191)
    private String warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseReceiveStatus status = PurchaseReceiveStatus.DRAFT;

    @Column(name = "received_by", nullable = false)
    private Long receivedBy;

    @PriceSensitive
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    // ==================== SP6: 收货异常 ====================

    /**
     * SP6 — 收货异常类型（超收 / 少收）。null = 正常收货。
     * 系统在 confirmReceive 时根据 PO 数量 vs 实收数量自动推算并写入。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", length = 32)
    private ReceiveExceptionType exceptionType;

    /**
     * SP6 — 异常数量（超收为正，少收为正绝对值）。
     */
    @Column(name = "exception_qty", precision = 15, scale = 4)
    private BigDecimal exceptionQty;

    /**
     * SP6 — 异常决策状态（待决策 / 已接受 / 已退回）。
     * null = 无异常；有异常单 PurchaseException 决策后同步更新至此。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_status", length = 32)
    private ReceiveDecisionStatus decisionStatus;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Supplier supplier;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User receivedByUser;

    @OneToMany(mappedBy = "receiveRecord", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PurchaseReceiveItem> items = new ArrayList<>();

    /**
     * Sprint 10 AI invocation source tag — set when Workdesk AI 1-click create.
     * Schema: {source: "sprint-10-loop-2", testRun: bool, createdAt: iso}.
     * NULL = manual UI create.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ai_invocation_metadata", columnDefinition = "jsonb")
    private Map<String, Object> aiInvocationMetadata;
}

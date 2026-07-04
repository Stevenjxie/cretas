package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 内部调拨单
 * 总部↔分店/分厂 之间的物资调拨
 * 状态机: DRAFT → REQUESTED → APPROVED → SHIPPED → RECEIVED → CONFIRMED
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"sourceFactory", "targetFactory", "requestedByUser", "approvedByUser", "items"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "internal_transfers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"source_factory_id", "transfer_number"})
        },
        indexes = {
                @Index(name = "idx_it_source", columnList = "source_factory_id"),
                @Index(name = "idx_it_target", columnList = "target_factory_id"),
                @Index(name = "idx_it_status", columnList = "status"),
                @Index(name = "idx_it_type", columnList = "transfer_type"),
                @Index(name = "idx_it_transfer_date", columnList = "transfer_date"),
                @Index(name = "idx_it_plan", columnList = "production_plan_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class InternalTransfer extends BaseEntity {

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "transfer_number", nullable = false, length = 50)
    private String transferNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type", nullable = false, length = 32)
    private TransferType transferType;

    /** 调出方工厂/门店ID */
    @NotBlank
    @Column(name = "source_factory_id", nullable = false, length = 191)
    private String sourceFactoryId;

    /** 调入方工厂/门店ID */
    @NotBlank
    @Column(name = "target_factory_id", nullable = false, length = 191)
    private String targetFactoryId;

    /** 工厂内跨仓调拨: 调出仓库ID (null = 跨工厂调拨). V3 P0-5 */
    @Column(name = "source_warehouse_id", length = 64)
    private String sourceWarehouseId;

    /** 工厂内跨仓调拨: 调入仓库ID (null = 跨工厂调拨). V3 P0-5 */
    @Column(name = "target_warehouse_id", length = 64)
    private String targetWarehouseId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    /** 期望到货日期 */
    @Column(name = "expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @PriceSensitive
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransferStatus status = TransferStatus.DRAFT;

    /** 申请人 */
    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    /** 审批人 */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** 发货时间 */
    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /** 签收时间 */
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** 确认时间 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 驳回/取消原因 */
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /** 关联生产计划ID（排产→调拨可追溯） */
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory sourceFactory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory targetFactory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User requestedByUser;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User approvedByUser;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InternalTransferItem> items = new ArrayList<>();

    // fool-proof-design Rule 2 (2026-07-04): 申请人/审批人 userId → 姓名. requestedByUser/approvedByUser
    // 关联本身是 @JsonIgnore (避免暴露 User 全字段含 passwordHash), 由 TransferServiceImpl#getTransferById
    // 在事务内取 fullName 填充这两个非-JsonIgnore transient 字段供前端展示, 同 ReportReversalLog 范式。
    @Transient
    private String requestedByName;

    @Transient
    private String approvedByName;
}

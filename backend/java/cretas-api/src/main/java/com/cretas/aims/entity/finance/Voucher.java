package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.exception.UnbalancedVoucherException;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 财务凭证. 由业务单 (SalesOrder / PurchaseOrder / ...) 通过对应 VoucherGenerator 自动生成,
 * 财务员审核后过账 (POSTED).
 *
 * <p>借贷必平不变式: sum(entries.debit) == sum(entries.credit) == totalDebit == totalCredit.
 * {@link #validateBalanced()} 在 persist 前必须调用.
 *
 * <p>idempotent: VoucherService.createFromBusiness 通过 (sourceBusinessType, sourceBusinessId)
 * 唯一约束防 event 重发导致重复生成 — 已存在直接返回 existing.
 */
@Data
@EqualsAndHashCode(callSuper = true, exclude = {"entries"})
@ToString(exclude = {"entries"})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "vouchers",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "voucher_number"}),
                @UniqueConstraint(name = "uk_voucher_source_business",
                        columnNames = {"source_business_type", "source_business_id"})
        },
        indexes = {
                @Index(name = "idx_v_factory", columnList = "factory_id"),
                @Index(name = "idx_v_type", columnList = "voucher_type"),
                @Index(name = "idx_v_status", columnList = "status"),
                @Index(name = "idx_v_voucher_date", columnList = "voucher_date"),
                @Index(name = "idx_v_source_business",
                        columnList = "source_business_type, source_business_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class Voucher extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** 凭证号, 格式 "V-YYYY-NNNN" — factory 内唯一 */
    @Column(name = "voucher_number", nullable = false, length = 50)
    private String voucherNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 32)
    private VoucherType voucherType;

    /** 凭证日期 (业务发生日, 不是 createdAt) */
    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    /** 来源业务类型: SALES_ORDER / PURCHASE_ORDER / PRODUCTION_PLAN / RETURN_ORDER /
     * INTERNAL_TRANSFER / WASTAGE_RECORD / PAYROLL_RECORD / DEPRECATION_SCHEDULE */
    @Column(name = "source_business_type", nullable = false, length = 50)
    private String sourceBusinessType;

    /** 来源业务单 ID (UUID String or Long-as-String). */
    @Column(name = "source_business_id", nullable = false, length = 191)
    private String sourceBusinessId;

    @PriceSensitive
    @Column(name = "total_debit", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDebit;

    @PriceSensitive
    @Column(name = "total_credit", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCredit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private VoucherStatus status = VoucherStatus.DRAFT;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * 红字冲销 (Feature #7): 原凭证 → 指向其红字冲销凭证 id (一对一).
     * 仅当原凭证被红字冲销 (status=REVERSED) 时非 null。
     */
    @Column(name = "reversal_voucher_id", length = 191)
    private String reversalVoucherId;

    /**
     * 红字冲销 (Feature #7): 红字冲销凭证 → 指回原凭证 id.
     * 仅红字冲销凭证 (反向分录) 非 null; 普通凭证为 null。
     */
    @Column(name = "original_voucher_id", length = 191)
    private String originalVoucherId;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VoucherEntry> entries = new ArrayList<>();

    /**
     * 借贷必平校验. 双重检查:
     * 1. totalDebit == totalCredit (整体)
     * 2. sum(entries.debit) == sum(entries.credit) (明细)
     *
     * @throws UnbalancedVoucherException 任一不平
     */
    public void validateBalanced() {
        BigDecimal debit = Objects.requireNonNullElse(totalDebit, BigDecimal.ZERO);
        BigDecimal credit = Objects.requireNonNullElse(totalCredit, BigDecimal.ZERO);
        if (debit.compareTo(credit) != 0) {
            throw new UnbalancedVoucherException(debit, credit);
        }
        if (entries != null && !entries.isEmpty()) {
            BigDecimal entrySumDebit = entries.stream()
                    .map(e -> Objects.requireNonNullElse(e.getDebit(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal entrySumCredit = entries.stream()
                    .map(e -> Objects.requireNonNullElse(e.getCredit(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (entrySumDebit.compareTo(entrySumCredit) != 0) {
                throw new UnbalancedVoucherException(entrySumDebit, entrySumCredit);
            }
            // entries 必须跟 totals 一致
            if (entrySumDebit.compareTo(debit) != 0) {
                throw new UnbalancedVoucherException(entrySumDebit, debit);
            }
        }
    }
}

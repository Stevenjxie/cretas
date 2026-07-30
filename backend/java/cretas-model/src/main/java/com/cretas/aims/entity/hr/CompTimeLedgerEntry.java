package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.CompTimeSourceType;
import com.cretas.aims.entity.hr.enums.CompTimeTransactionType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 调休账户明细 (CompTime Ledger Entry).
 *
 * <p>每笔调休 credit/debit 一行, 用于审计追溯.
 *
 * <p>防呆 R2 (上下文+身份): 每条 ledger 含 {@code sourceType + sourceRefId},
 * 可下钻到原 OT / Leave 单据查看时间 / 时长 / 理由 / 审批人.
 *
 * <p>防呆 R4 (幂等): {@code (sourceType, sourceRefId)} UNIQUE — listener 重放 / event 重复时
 * 第二次入账会被 DB 阻止抛 DataIntegrityViolationException, service 层 catch 后 log warn 跳过.
 *
 * @author Cretas Team — #835 follow-up CompTime Balance
 * @since 2026-05-18
 */
@Entity
@Table(name = "comp_time_ledger_entries",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_comptime_ledger_source",
           columnNames = {"source_type", "source_ref_id"}),
       indexes = {
           @Index(name = "idx_comptime_ledger_factory_user_created",
                  columnList = "factory_id,user_id,created_at DESC"),
           @Index(name = "idx_comptime_ledger_factory_month",
                  columnList = "factory_id,year_month"),
           @Index(name = "idx_comptime_ledger_source_ref",
                  columnList = "source_type,source_ref_id")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompTimeLedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "transaction_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private CompTimeTransactionType transactionType;

    /** 始终正数; {@link #transactionType} 决定加减语义 */
    @Column(name = "hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal hours;

    @Column(name = "source_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CompTimeSourceType sourceType;

    /** 关联原单据 ID — overtime_requests.id / leave_requests.id; MANUAL_ADJUST 可为 null */
    @Column(name = "source_ref_id", length = 36)
    private String sourceRefId;

    /** 'YYYY-MM' 格式 — 用于查询聚合 */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    /** 入账后的余额快照 (让 ledger 可独立读取, 不用每次重算) */
    @Column(name = "balance_after_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal balanceAfterHours;

    /** 备注: 自动入账时填业务描述 (e.g. "周六加班 4h 转调休") */
    @Column(name = "note", length = 500)
    private String note;

    /** 操作员: 自动入账 = approver_id; MANUAL_ADJUST = 调整人 ID */
    @Column(name = "operator_id")
    private Long operatorId;
}
